package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts protocol-param.json (Ogmios/cardano-node camelCase format) to CCL {@link ProtocolParams}.
 */
public class ProtocolParamsMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parse an Ogmios-format protocol parameters JSON string into a CCL {@link ProtocolParams}.
     */
    public static ProtocolParams fromNodeProtocolParam(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        ProtocolParams pp = new ProtocolParams();

        // Fee structure
        pp.setMinFeeA(intOrNull(root, "txFeePerByte"));
        pp.setMinFeeB(intOrNull(root, "txFeeFixed"));
        pp.setMaxBlockSize(intOrNull(root, "maxBlockBodySize"));
        pp.setMaxTxSize(intOrNull(root, "maxTxSize"));
        pp.setMaxBlockHeaderSize(intOrNull(root, "maxBlockHeaderSize"));

        // Deposits (as String)
        pp.setKeyDeposit(textOrNull(root, "stakeAddressDeposit"));
        pp.setPoolDeposit(textOrNull(root, "stakePoolDeposit"));

        // Pool parameters
        pp.setEMax(intOrNull(root, "poolRetireMaxEpoch"));
        pp.setNOpt(intOrNull(root, "stakePoolTargetNum"));
        pp.setA0(decimalOrNull(root, "poolPledgeInfluence"));
        pp.setRho(decimalOrNull(root, "monetaryExpansion"));
        pp.setTau(decimalOrNull(root, "treasuryCut"));
        pp.setMinPoolCost(textOrNull(root, "minPoolCost"));

        // Protocol version
        JsonNode pv = root.get("protocolVersion");
        if (pv != null) {
            pp.setProtocolMajorVer(intOrNull(pv, "major"));
            pp.setProtocolMinorVer(intOrNull(pv, "minor"));
        }

        // Execution unit prices
        JsonNode eup = root.get("executionUnitPrices");
        if (eup != null) {
            pp.setPriceMem(decimalOrNull(eup, "priceMemory"));
            pp.setPriceStep(decimalOrNull(eup, "priceSteps"));
        }

        // Max tx execution units
        JsonNode mteu = root.get("maxTxExecutionUnits");
        if (mteu != null) {
            pp.setMaxTxExMem(textOrNull(mteu, "memory"));
            pp.setMaxTxExSteps(textOrNull(mteu, "steps"));
        }

        // Max block execution units
        JsonNode mbeu = root.get("maxBlockExecutionUnits");
        if (mbeu != null) {
            pp.setMaxBlockExMem(textOrNull(mbeu, "memory"));
            pp.setMaxBlockExSteps(textOrNull(mbeu, "steps"));
        }

        // UTXO cost
        pp.setCoinsPerUtxoSize(textOrNull(root, "utxoCostPerByte"));

        // Max value size and collateral
        pp.setMaxValSize(textOrNull(root, "maxValueSize"));
        pp.setCollateralPercent(decimalOrNull(root, "collateralPercentage"));
        pp.setMaxCollateralInputs(intOrNull(root, "maxCollateralInputs"));

        // Ref script cost
        pp.setMinFeeRefScriptCostPerByte(decimalOrNull(root, "minFeeRefScriptCostPerByte"));

        // Cost models
        JsonNode cm = root.get("costModels");
        if (cm != null) {
            LinkedHashMap<String, LinkedHashMap<String, Long>> costModels = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = cm.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String language = entry.getKey();
                JsonNode costs = entry.getValue();
                if (costs.isArray()) {
                    LinkedHashMap<String, Long> costMap = new LinkedHashMap<>();
                    for (int i = 0; i < costs.size(); i++) {
                        costMap.put(String.valueOf(i), costs.get(i).asLong());
                    }
                    costModels.put(language, costMap);
                }
            }
            pp.setCostModels(costModels);
        }

        // Conway governance — DRep voting thresholds
        JsonNode dvt = root.get("dRepVotingThresholds");
        if (dvt != null) {
            pp.setDvtMotionNoConfidence(decimalOrNull(dvt, "motionNoConfidence"));
            pp.setDvtCommitteeNormal(decimalOrNull(dvt, "committeeNormal"));
            pp.setDvtCommitteeNoConfidence(decimalOrNull(dvt, "committeeNoConfidence"));
            pp.setDvtUpdateToConstitution(decimalOrNull(dvt, "updateToConstitution"));
            pp.setDvtHardForkInitiation(decimalOrNull(dvt, "hardForkInitiation"));
            pp.setDvtPPNetworkGroup(decimalOrNull(dvt, "ppNetworkGroup"));
            pp.setDvtPPEconomicGroup(decimalOrNull(dvt, "ppEconomicGroup"));
            pp.setDvtPPTechnicalGroup(decimalOrNull(dvt, "ppTechnicalGroup"));
            pp.setDvtPPGovGroup(decimalOrNull(dvt, "ppGovGroup"));
            pp.setDvtTreasuryWithdrawal(decimalOrNull(dvt, "treasuryWithdrawal"));
        }

        // Conway governance — Pool voting thresholds
        JsonNode pvt = root.get("poolVotingThresholds");
        if (pvt != null) {
            pp.setPvtMotionNoConfidence(decimalOrNull(pvt, "motionNoConfidence"));
            pp.setPvtCommitteeNormal(decimalOrNull(pvt, "committeeNormal"));
            pp.setPvtCommitteeNoConfidence(decimalOrNull(pvt, "committeeNoConfidence"));
            pp.setPvtHardForkInitiation(decimalOrNull(pvt, "hardForkInitiation"));
            pp.setPvtPPSecurityGroup(decimalOrNull(pvt, "ppSecurityGroup"));
        }

        // Conway governance — other fields
        pp.setCommitteeMinSize(intOrNull(root, "committeeMinSize"));
        pp.setCommitteeMaxTermLength(intOrNull(root, "committeeMaxTermLength"));
        pp.setGovActionLifetime(intOrNull(root, "govActionLifetime"));

        JsonNode gadNode = root.get("govActionDeposit");
        if (gadNode != null && !gadNode.isNull()) {
            pp.setGovActionDeposit(gadNode.bigIntegerValue());
        }

        JsonNode drepDepNode = root.get("dRepDeposit");
        if (drepDepNode != null && !drepDepNode.isNull()) {
            pp.setDrepDeposit(drepDepNode.bigIntegerValue());
        }

        pp.setDrepActivity(intOrNull(root, "dRepActivity"));

        return pp;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asInt() : null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.decimalValue() : null;
    }
}
