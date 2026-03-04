package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * Loads optional genesis configuration from JSON files.
 * <p>
 * Genesis funds file format (same as shelley-genesis initialFunds):
 * <pre>
 * {
 *   "00c8c47610a36034aa...": 10000000000,
 *   "605276322ac788243...": 3000000000000000
 * }
 * </pre>
 * <p>
 * Protocol parameters file: any valid JSON object (passed through as-is).
 */
@Slf4j
@Getter
public class GenesisConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Long> initialFunds;
    private final String protocolParameters;

    private GenesisConfig(Map<String, Long> initialFunds, String protocolParameters) {
        this.initialFunds = initialFunds;
        this.protocolParameters = protocolParameters;
    }

    /**
     * Load genesis configuration from optional JSON files.
     *
     * @param genesisFundsFile       path to initial funds JSON (nullable)
     * @param protocolParametersFile path to protocol parameters JSON (nullable)
     * @return GenesisConfig (never null; fields may be empty/null)
     */
    public static GenesisConfig load(String genesisFundsFile, String protocolParametersFile) {
        Map<String, Long> funds = Collections.emptyMap();
        String protocolParams = null;

        if (genesisFundsFile != null && !genesisFundsFile.isBlank()) {
            funds = loadFunds(genesisFundsFile);
        }

        if (protocolParametersFile != null && !protocolParametersFile.isBlank()) {
            protocolParams = loadProtocolParameters(protocolParametersFile);
        }

        return new GenesisConfig(funds, protocolParams);
    }

    private static Map<String, Long> loadFunds(String path) {
        try {
            Map<String, Long> funds = MAPPER.readValue(new File(path), new TypeReference<>() {});
            log.info("Loaded {} genesis fund entries from {}", funds.size(), path);
            return funds;
        } catch (IOException e) {
            log.error("Failed to load genesis funds from {}: {}", path, e.getMessage());
            return Collections.emptyMap();
        }
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

    public boolean hasProtocolParameters() {
        return protocolParameters != null;
    }
}
