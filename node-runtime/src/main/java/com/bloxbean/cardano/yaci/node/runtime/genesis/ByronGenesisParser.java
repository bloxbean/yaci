package com.bloxbean.cardano.yaci.node.runtime.genesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight parser for standard Cardano byron-genesis.json files.
 * Extracts nonAvvmBalances (the main initial ADA distributions for mainnet/preprod).
 * AVVM addresses are skipped for now (complex address conversion).
 */
@Slf4j
public class ByronGenesisParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ByronGenesisData parse(File file) throws IOException {
        JsonNode root = MAPPER.readTree(file);
        return parseRoot(root);
    }

    public static ByronGenesisData parse(InputStream in) throws IOException {
        JsonNode root = MAPPER.readTree(in);
        return parseRoot(root);
    }

    private static ByronGenesisData parseRoot(JsonNode root) {
        Map<String, Long> nonAvvmBalances = parseNonAvvmBalances(root.get("nonAvvmBalances"));
        long startTime = root.path("startTime").asLong(0);

        // Extract k from protocolConsts
        long k = root.path("protocolConsts").path("k").asLong(0);

        // Extract protocolMagic from protocolConsts
        long protocolMagic = 0;
        JsonNode protoConsts = root.get("protocolConsts");
        if (protoConsts != null && protoConsts.has("protocolMagic")) {
            protocolMagic = protoConsts.get("protocolMagic").asLong(0);
        }

        // Extract slot duration from blockVersionData.slotDuration (in milliseconds)
        long slotDurationMs = root.path("blockVersionData").path("slotDuration").asLong(0);
        long slotDuration = slotDurationMs > 0 ? slotDurationMs / 1000 : 0;

        log.info("Parsed byron genesis: nonAvvmBalances={} entries, startTime={}, protocolMagic={}, slotDuration={}s, k={}",
                nonAvvmBalances.size(), startTime, protocolMagic, slotDuration, k);

        return new ByronGenesisData(nonAvvmBalances, startTime, protocolMagic, slotDuration, k);
    }

    private static Map<String, Long> parseNonAvvmBalances(JsonNode balancesNode) {
        if (balancesNode == null || balancesNode.isNull() || !balancesNode.isObject()) {
            return Collections.emptyMap();
        }

        Map<String, Long> balances = new LinkedHashMap<>();
        var fields = balancesNode.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            // Byron genesis uses string values for amounts (e.g., "30000000000000000")
            long amount = Long.parseLong(entry.getValue().asText("0"));
            if (amount > 0) {
                balances.put(entry.getKey(), amount);
            }
        }
        return Collections.unmodifiableMap(balances);
    }
}
