package com.bloxbean.cardano.yaci.node.runtime.genesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight parser for standard Cardano shelley-genesis.json files.
 * Extracts only what yaci-node needs: initialFunds and network metadata.
 */
@Slf4j
public class ShelleyGenesisParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ShelleyGenesisData parse(File file) throws IOException {
        JsonNode root = MAPPER.readTree(file);
        return parseRoot(root);
    }

    public static ShelleyGenesisData parse(InputStream in) throws IOException {
        JsonNode root = MAPPER.readTree(in);
        return parseRoot(root);
    }

    private static ShelleyGenesisData parseRoot(JsonNode root) {
        Map<String, Long> initialFunds = parseInitialFunds(root.get("initialFunds"));
        long networkMagic = root.path("networkMagic").asLong(0);
        long epochLength = root.path("epochLength").asLong(0);
        double slotLength = root.path("slotLength").asDouble(1.0);
        String systemStart = root.path("systemStart").asText(null);
        long maxLovelaceSupply = root.path("maxLovelaceSupply").asLong(0);
        double activeSlotsCoeff = root.path("activeSlotsCoeff").asDouble(0.05);
        long securityParam = root.path("securityParam").asLong(0);
        long maxKESEvolutions = root.path("maxKESEvolutions").asLong(0);
        long slotsPerKESPeriod = root.path("slotsPerKESPeriod").asLong(0);
        long updateQuorum = root.path("updateQuorum").asLong(0);

        log.info("Parsed shelley genesis: networkMagic={}, initialFunds={} entries, epochLength={}, systemStart={}, activeSlotsCoeff={}",
                networkMagic, initialFunds.size(), epochLength, systemStart, activeSlotsCoeff);

        return new ShelleyGenesisData(initialFunds, networkMagic, epochLength, slotLength,
                systemStart, maxLovelaceSupply, activeSlotsCoeff,
                securityParam, maxKESEvolutions, slotsPerKESPeriod, updateQuorum);
    }

    /**
     * Update the systemStart field in a shelley-genesis.json file.
     *
     * @param file        the genesis file to update
     * @param systemStart ISO-8601 timestamp (e.g. "2026-03-05T14:30:00Z")
     */
    public static void updateSystemStart(File file, String systemStart) throws IOException {
        ObjectNode root = (ObjectNode) MAPPER.readTree(file);
        root.put("systemStart", systemStart);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, root);
        log.info("Updated systemStart in {}: {}", file.getPath(), systemStart);
    }

    private static Map<String, Long> parseInitialFunds(JsonNode fundsNode) {
        if (fundsNode == null || fundsNode.isNull() || !fundsNode.isObject()) {
            return Collections.emptyMap();
        }

        Map<String, Long> funds = new LinkedHashMap<>();
        var fields = fundsNode.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            funds.put(entry.getKey(), entry.getValue().asLong());
        }
        return Collections.unmodifiableMap(funds);
    }
}
