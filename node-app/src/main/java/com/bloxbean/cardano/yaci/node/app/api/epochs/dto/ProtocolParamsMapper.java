package com.bloxbean.cardano.yaci.node.app.api.epochs.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps protocol parameters from the Cardano node JSON format (protocol-param.json)
 * to the Yaci Store-compatible {@link ProtocolParamsDto} format.
 */
public final class ProtocolParamsMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProtocolParamsMapper() {}

    /**
     * Parse a raw protocol parameters JSON string and map to ProtocolParamsDto.
     *
     * @param json  raw protocol parameters JSON (Cardano node format)
     * @param epoch current epoch number
     * @return mapped DTO
     */
    public static ProtocolParamsDto fromJson(String json, int epoch) {
        try {
            JsonNode root = MAPPER.readTree(json);
            return map(root, epoch);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse protocol parameters JSON", e);
        }
    }

    private static ProtocolParamsDto map(JsonNode root, int epoch) {
        return new ProtocolParamsDto(
                epoch,
                intVal(root, "txFeePerByte"),
                intVal(root, "txFeeFixed"),
                intVal(root, "maxBlockBodySize"),
                intVal(root, "maxTxSize"),
                intVal(root, "maxBlockHeaderSize"),
                strVal(root, "stakeAddressDeposit"),
                strVal(root, "stakePoolDeposit"),
                decimalVal(root, "poolPledgeInfluence"),
                decimalVal(root, "monetaryExpansion"),
                decimalVal(root, "treasuryCut"),
                BigDecimal.ZERO, // decentralisation_param (deprecated, always 0 in Conway)
                null, // extra_entropy (deprecated post-Alonzo)
                nestedInt(root, "protocolVersion", "major"),
                nestedInt(root, "protocolVersion", "minor"),
                strVal(root, "utxoCostPerByte"), // min_utxo (same as coins_per_utxo_size)
                strVal(root, "minPoolCost"),
                null, // nonce
                mapCostModels(root.get("costModels")),
                nestedDecimal(root, "executionUnitPrices", "priceMemory"),
                nestedDecimal(root, "executionUnitPrices", "priceSteps"),
                nestedStr(root, "maxTxExecutionUnits", "memory"),
                nestedStr(root, "maxTxExecutionUnits", "steps"),
                nestedStr(root, "maxBlockExecutionUnits", "memory"),
                nestedStr(root, "maxBlockExecutionUnits", "steps"),
                strVal(root, "maxValueSize"),
                intVal(root, "collateralPercentage"),
                intVal(root, "maxCollateralInputs"),
                strVal(root, "utxoCostPerByte"),
                strVal(root, "utxoCostPerByte"), // coins_per_utxo_word (legacy alias)
                // Conway governance — pool voting thresholds
                nestedDecimal(root, "poolVotingThresholds", "motionNoConfidence"),
                nestedDecimal(root, "poolVotingThresholds", "committeeNormal"),
                nestedDecimal(root, "poolVotingThresholds", "committeeNoConfidence"),
                nestedDecimal(root, "poolVotingThresholds", "hardForkInitiation"),
                // Conway governance — drep voting thresholds
                nestedDecimal(root, "dRepVotingThresholds", "motionNoConfidence"),
                nestedDecimal(root, "dRepVotingThresholds", "committeeNormal"),
                nestedDecimal(root, "dRepVotingThresholds", "committeeNoConfidence"),
                nestedDecimal(root, "dRepVotingThresholds", "updateToConstitution"),
                nestedDecimal(root, "dRepVotingThresholds", "hardForkInitiation"),
                nestedDecimal(root, "dRepVotingThresholds", "treasuryWithdrawal"),
                intVal(root, "committeeMinSize"),
                intVal(root, "committeeMaxTermLength"),
                intVal(root, "govActionLifetime"),
                bigIntVal(root, "govActionDeposit"),
                bigIntVal(root, "dRepDeposit"),
                intVal(root, "dRepActivity"),
                decimalVal(root, "minFeeRefScriptCostPerByte"),
                intVal(root, "poolRetireMaxEpoch"),
                intVal(root, "stakePoolTargetNum"),
                // pvt_p_p_security_group
                nestedDecimal(root, "poolVotingThresholds", "ppSecurityGroup"),
                // dvt_p_p_*_group
                nestedDecimal(root, "dRepVotingThresholds", "ppNetworkGroup"),
                nestedDecimal(root, "dRepVotingThresholds", "ppEconomicGroup"),
                nestedDecimal(root, "dRepVotingThresholds", "ppTechnicalGroup"),
                nestedDecimal(root, "dRepVotingThresholds", "ppGovGroup")
        );
    }

    /**
     * Convert cost models from array format to Map with numbered string keys.
     */
    private static Map<String, Map<String, Long>> mapCostModels(JsonNode costModelsNode) {
        if (costModelsNode == null || costModelsNode.isMissingNode()) {
            return Map.of();
        }
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = costModelsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String version = entry.getKey();
            JsonNode values = entry.getValue();
            if (values.isArray()) {
                Map<String, Long> numbered = new LinkedHashMap<>();
                for (int i = 0; i < values.size(); i++) {
                    numbered.put(String.format("%03d", i), values.get(i).asLong());
                }
                result.put(version, numbered);
            } else if (values.isObject()) {
                // Already a named map
                Map<String, Long> named = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> vFields = values.fields();
                while (vFields.hasNext()) {
                    Map.Entry<String, JsonNode> vEntry = vFields.next();
                    named.put(vEntry.getKey(), vEntry.getValue().asLong());
                }
                result.put(version, named);
            }
        }
        return result;
    }

    private static int intVal(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n != null ? n.asInt() : 0;
    }

    private static String strVal(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n != null ? n.asText() : null;
    }

    private static BigDecimal decimalVal(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n != null ? n.decimalValue() : null;
    }

    private static BigInteger bigIntVal(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n != null ? n.bigIntegerValue() : null;
    }

    private static int nestedInt(JsonNode root, String parent, String child) {
        JsonNode p = root.get(parent);
        if (p == null) return 0;
        JsonNode c = p.get(child);
        return c != null ? c.asInt() : 0;
    }

    private static String nestedStr(JsonNode root, String parent, String child) {
        JsonNode p = root.get(parent);
        if (p == null) return null;
        JsonNode c = p.get(child);
        return c != null ? c.asText() : null;
    }

    private static BigDecimal nestedDecimal(JsonNode root, String parent, String child) {
        JsonNode p = root.get(parent);
        if (p == null) return null;
        JsonNode c = p.get(child);
        return c != null ? c.decimalValue() : null;
    }
}
