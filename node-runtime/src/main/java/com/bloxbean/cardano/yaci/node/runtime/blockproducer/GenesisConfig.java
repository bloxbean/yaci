package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.yaci.node.runtime.genesis.ByronGenesisData;
import com.bloxbean.cardano.yaci.node.runtime.genesis.ByronGenesisParser;
import com.bloxbean.cardano.yaci.node.runtime.genesis.ShelleyGenesisData;
import com.bloxbean.cardano.yaci.node.runtime.genesis.ShelleyGenesisParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;

/**
 * Loads genesis configuration from standard Cardano genesis files.
 * <p>
 * Shelley genesis provides initialFunds (hex address → lovelace).
 * Byron genesis provides nonAvvmBalances (Byron base58 address → lovelace).
 * Protocol parameters come from a separate static JSON file.
 */
@Slf4j
@Getter
public class GenesisConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Long> initialFunds;
    private final String protocolParameters;
    private final Map<String, Long> byronBalances;
    private final ShelleyGenesisData shelleyGenesisData;
    private final ByronGenesisData byronGenesisData;

    private GenesisConfig(Map<String, Long> initialFunds, String protocolParameters,
                          Map<String, Long> byronBalances, ShelleyGenesisData shelleyGenesisData,
                          ByronGenesisData byronGenesisData) {
        this.initialFunds = initialFunds;
        this.protocolParameters = protocolParameters;
        this.byronBalances = byronBalances;
        this.shelleyGenesisData = shelleyGenesisData;
        this.byronGenesisData = byronGenesisData;
    }

    /**
     * Load genesis configuration from standard Cardano genesis files.
     *
     * @param shelleyGenesisFile     path to shelley-genesis.json (nullable)
     * @param byronGenesisFile       path to byron-genesis.json (nullable)
     * @param protocolParametersFile path to protocol parameters JSON (nullable)
     * @return GenesisConfig (never null; fields may be empty/null)
     */
    public static GenesisConfig load(String shelleyGenesisFile, String byronGenesisFile,
                                     String protocolParametersFile) {
        Map<String, Long> funds = Collections.emptyMap();
        Map<String, Long> byronBalances = Collections.emptyMap();
        ShelleyGenesisData shelleyData = null;
        ByronGenesisData byronData = null;
        String protocolParams = null;

        if (shelleyGenesisFile != null && !shelleyGenesisFile.isBlank()) {
            try {
                shelleyData = ShelleyGenesisParser.parse(new File(shelleyGenesisFile));
                funds = shelleyData.initialFunds();
            } catch (IOException e) {
                log.error("Failed to parse shelley genesis from {}: {}", shelleyGenesisFile, e.getMessage());
            }
        }

        if (byronGenesisFile != null && !byronGenesisFile.isBlank()) {
            try {
                byronData = ByronGenesisParser.parse(new File(byronGenesisFile));
                byronBalances = byronData.nonAvvmBalances();
            } catch (IOException e) {
                log.error("Failed to parse byron genesis from {}: {}", byronGenesisFile, e.getMessage());
            }
        }

        if (protocolParametersFile != null && !protocolParametersFile.isBlank()) {
            protocolParams = loadProtocolParameters(protocolParametersFile);
        }

        return new GenesisConfig(funds, protocolParams, byronBalances, shelleyData, byronData);
    }

    private static String loadProtocolParameters(String path) {
        try {
            String json = Files.readString(Path.of(path));
            MAPPER.readTree(json); // validate JSON
            log.info("Loaded protocol parameters from {}", path);
            return json;
        } catch (IOException e) {
            log.error("Failed to load protocol parameters from {}: {}", path, e.getMessage());
            return null;
        }
    }

    public boolean hasInitialFunds() {
        return initialFunds != null && !initialFunds.isEmpty();
    }

    public boolean hasByronBalances() {
        return byronBalances != null && !byronBalances.isEmpty();
    }

    public boolean hasProtocolParameters() {
        return protocolParameters != null;
    }

    public long getNetworkMagic() {
        return shelleyGenesisData != null ? shelleyGenesisData.networkMagic() : 0;
    }

    public double getActiveSlotsCoeff() {
        return shelleyGenesisData != null ? shelleyGenesisData.activeSlotsCoeff() : 0.0;
    }

    public String getSystemStart() {
        return shelleyGenesisData != null ? shelleyGenesisData.systemStart() : null;
    }

    /**
     * Parse the ISO-8601 systemStart from shelley genesis data into epoch millis.
     *
     * @return epoch millis, or 0 if systemStart is null/unparseable
     */
    public long getSystemStartEpochMillis() {
        String systemStart = getSystemStart();
        if (systemStart == null || systemStart.isBlank()) {
            return 0;
        }
        try {
            return Instant.parse(systemStart).toEpochMilli();
        } catch (Exception e) {
            log.warn("Failed to parse systemStart '{}': {}", systemStart, e.getMessage());
            return 0;
        }
    }

    /**
     * Byron slot duration in seconds. Defaults to 20 if no Byron genesis is available.
     */
    public long getByronSlotDurationSeconds() {
        return byronGenesisData != null && byronGenesisData.slotDuration() > 0
                ? byronGenesisData.slotDuration() : 20;
    }

    /**
     * Network start time in Unix epoch seconds.
     * Uses Byron genesis startTime if available, otherwise parses Shelley systemStart.
     */
    public long getNetworkStartTimeSeconds() {
        if (byronGenesisData != null && byronGenesisData.startTime() > 0) {
            return byronGenesisData.startTime();
        }
        long millis = getSystemStartEpochMillis();
        return millis > 0 ? millis / 1000 : 0;
    }

    /**
     * Shelley slot length in seconds. Defaults to 1.0 if no Shelley genesis is available.
     */
    public double getShelleySlotLengthSeconds() {
        return shelleyGenesisData != null && shelleyGenesisData.slotLength() > 0
                ? shelleyGenesisData.slotLength() : 1.0;
    }

    /**
     * Resolve the genesis timestamp for the block producer, persisting to the shelley genesis
     * file on fresh start when using auto (configTimestamp == 0).
     *
     * @param configTimestamp   configured genesis-timestamp (0 = auto)
     * @param freshStart        true if no existing chain state (first boot)
     * @param shelleyGenesisFile path to shelley-genesis.json (nullable)
     * @return resolved genesis timestamp in epoch millis
     */
    public long resolveAndPersistGenesisTimestamp(long configTimestamp, boolean freshStart, String shelleyGenesisFile) {
        // Explicit override — use it directly, no file write
        if (configTimestamp > 0) {
            log.info("Using explicit genesis timestamp: {}", configTimestamp);
            return configTimestamp;
        }

        // Fresh start + auto → write current time to genesis file
        if (freshStart) {
            long now = System.currentTimeMillis();
            if (shelleyGenesisFile != null && !shelleyGenesisFile.isBlank()) {
                try {
                    String isoTimestamp = Instant.ofEpochMilli(now).truncatedTo(ChronoUnit.SECONDS).toString();
                    ShelleyGenesisParser.updateSystemStart(new File(shelleyGenesisFile), isoTimestamp);
                    log.info("Persisted genesis systemStart={} to {}", isoTimestamp, shelleyGenesisFile);
                } catch (IOException e) {
                    log.warn("Failed to persist genesis timestamp to {}: {}", shelleyGenesisFile, e.getMessage());
                }
            }
            return now;
        }

        // Restart + auto → read persisted value from genesis file
        long persisted = getSystemStartEpochMillis();
        if (persisted > 0) {
            log.info("Using persisted genesis timestamp from shelley-genesis.json: {}", persisted);
            return persisted;
        }

        // Fallback
        long fallback = System.currentTimeMillis();
        log.warn("No persisted genesis timestamp found, falling back to current time: {}", fallback);
        return fallback;
    }
}
