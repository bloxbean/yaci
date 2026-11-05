package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.raw.EraSpec;
import com.bloxbean.cardano.client.transaction.raw.RawCostModels;
import com.bloxbean.cardano.client.transaction.raw.RawExUnitPrices;
import com.bloxbean.cardano.client.transaction.raw.RawExUnits;
import com.bloxbean.cardano.client.transaction.raw.RawProtocolParamUpdate;
import com.bloxbean.cardano.client.transaction.raw.RawProtocolVersion;
import com.bloxbean.cardano.client.transaction.raw.RawUpdate;
import com.bloxbean.cardano.client.transaction.raw.RawVotingThresholds;
import com.bloxbean.cardano.client.transaction.raw.RawVotingThresholds.DRep;
import com.bloxbean.cardano.client.transaction.raw.RawVotingThresholds.Pool;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds;
import com.bloxbean.cardano.yaci.core.model.PoolVotingThresholds;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.Update;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.types.NonNegativeInterval;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.Tuple;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.*;

/**
 * Maps updates and parameter updates read by {@link RawUpdate} and {@link RawProtocolParamUpdate} to Yaci's models (ADR
 * 0003 D8). Key 17 is kept in {@code adaPerUtxoByte} whatever the era. The cost models and their hash are the bytes
 * received (I8).
 */
public enum UpdateSerializer implements Serializer<Update> {
    INSTANCE;

    /**
     * @param bytes an update, exactly one CBOR item
     * @return the update, read in the newest era; null for empty input
     */
    @Override
    public Update deserialize(byte[] bytes) {
        if (bytes.length == 0)
            return null;
        return read(RawUpdate.lenient(CborSerializationUtil.span(bytes), EraSpec.newest()));
    }

    @Override
    public Update deserializeDI(DataItem di) {
        return read(RawUpdate.lenient(RawModels.span(di), EraSpec.newest()));
    }

    /**
     * @param update an update as received
     * @return the update, each genesis key's proposal under its hash in hex. The epoch keeps only its low 32 bits, as
     * it always has
     */
    public Update read(RawUpdate update) {
        java.util.Map<String, ProtocolParamUpdate> updates = new HashMap<>();
        for (java.util.Map.Entry<byte[], RawProtocolParamUpdate> proposal : update.proposals())
            updates.put(HexUtil.encodeHexString(proposal.getKey()), read(proposal.getValue()));
        return new Update(updates, (int) update.epoch());
    }

    /**
     * @param genesisProtocolParamsMap a parameter update, decoded
     * @return the parameter update, read in the newest era
     */
    public ProtocolParamUpdate getProtocolParams(Map genesisProtocolParamsMap) {
        return read(RawProtocolParamUpdate.lenient(RawModels.span(genesisProtocolParamsMap), EraSpec.newest()));
    }

    /**
     * @param update a parameter update as received
     * @return the parameter update. A field of 32 bits, such as the maximum block size, keeps only the low 32 bits of
     * its value in an {@code Integer}, as it always has
     */
    public ProtocolParamUpdate read(RawProtocolParamUpdate update) {
        ProtocolParamUpdate.ProtocolParamUpdateBuilder builder = ProtocolParamUpdate.builder()
                .minFeeA(update.minFeeA().map(BigInteger::intValue).orElse(null))
                .minFeeB(update.minFeeB().map(BigInteger::intValue).orElse(null))
                .maxBlockSize(low32(update.maxBlockBodySize()))
                .maxTxSize(low32(update.maxTxSize()))
                .maxBlockHeaderSize(orNull(update.maxBlockHeaderSize()))
                .keyDeposit(update.keyDeposit().orElse(null))
                .poolDeposit(update.poolDeposit().orElse(null))
                .maxEpoch(low32(update.maxEpoch()))
                .nOpt(orNull(update.nOpt()))
                .poolPledgeInfluence(update.poolPledgeInfluence().map(UpdateSerializer::nonNegative).orElse(null))
                .expansionRate(update.expansionRate().map(UpdateSerializer::unit).orElse(null))
                .treasuryGrowthRate(update.treasuryGrowthRate().map(UpdateSerializer::unit).orElse(null))
                .decentralisationParam(update.decentralization().map(UpdateSerializer::unit).orElse(null))
                .extraEntropy(update.extraEntropy().map(nonce -> nonce.hash()
                        .map(hash -> new Tuple<>(1, HexUtil.encodeHexString(hash)))
                        .orElse(new Tuple<>(0, ""))).orElse(null))
                .minUtxo(update.minUtxoValue().orElse(null))
                .minPoolCost(update.minPoolCost().orElse(null))
                .adaPerUtxoByte(update.coinsPerUtxoByte().orElse(null))
                .maxValSize(orNull(update.maxValueSize()))
                .collateralPercent(orNull(update.collateralPercentage()))
                .maxCollateralInputs(orNull(update.maxCollateralInputs()))
                .poolVotingThresholds(update.poolVotingThresholds().map(UpdateSerializer::pool).orElse(null))
                .drepVotingThresholds(update.drepVotingThresholds().map(UpdateSerializer::drep).orElse(null))
                .committeeMinSize(orNull(update.minCommitteeSize()))
                .committeeMaxTermLength(low32(update.committeeTermLimit()))
                .govActionLifetime(low32(update.govActionLifetime()))
                .govActionDeposit(update.govActionDeposit().orElse(null))
                .drepDeposit(update.drepDeposit().orElse(null))
                .drepActivity(low32(update.drepInactivity()))
                .minFeeRefScriptCostPerByte(update.minFeeRefScriptCostPerByte().map(UpdateSerializer::nonNegative)
                        .orElse(null));

        Optional<RawProtocolVersion> version = update.protocolVersion();
        if (version.isPresent()) {
            BigInteger minor = version.get().minor();
            builder.protocolMajorVer(version.get().major()).protocolMinorVer(minor == null ? null : minor.intValue());
        }
        Optional<RawCostModels> costModels = update.costModels();
        if (costModels.isPresent()) {
            java.util.Map<Integer, String> models = new LinkedHashMap<>();
            for (RawCostModels.Model model : costModels.get().models())
                models.put(model.language(), HexUtil.encodeHexString(model.span().bytes()));
            builder.costModels(models).costModelsHash(
                    HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(costModels.get().span().bytes())));
        }
        Optional<RawExUnitPrices> prices = update.executionCosts();
        if (prices.isPresent())
            builder.priceMem(nonNegative(prices.get().memory())).priceStep(nonNegative(prices.get().steps()));
        Optional<RawExUnits> maxTx = update.maxTxExUnits();
        if (maxTx.isPresent())
            builder.maxTxExMem(maxTx.get().mem()).maxTxExSteps(maxTx.get().steps());
        Optional<RawExUnits> maxBlock = update.maxBlockExUnits();
        if (maxBlock.isPresent())
            builder.maxBlockExMem(maxBlock.get().mem()).maxBlockExSteps(maxBlock.get().steps());
        return builder.build();
    }

    private static Integer low32(OptionalLong value) {
        return value.isPresent() ? (int) value.getAsLong() : null;
    }

    private static Integer orNull(OptionalInt value) {
        return value.isPresent() ? value.getAsInt() : null;
    }

    private static Long orNull(OptionalLong value) {
        return value.isPresent() ? value.getAsLong() : null;
    }

    private static UnitInterval unit(com.bloxbean.cardano.client.spec.UnitInterval interval) {
        return new UnitInterval(interval.getNumerator(), interval.getDenominator());
    }

    private static NonNegativeInterval nonNegative(com.bloxbean.cardano.client.spec.UnitInterval interval) {
        return new NonNegativeInterval(interval.getNumerator(), interval.getDenominator());
    }

    private static PoolVotingThresholds pool(RawVotingThresholds thresholds) {
        return PoolVotingThresholds.builder()
                .pvtMotionNoConfidence(unit(thresholds.get(Pool.MOTION_NO_CONFIDENCE)))
                .pvtCommitteeNormal(unit(thresholds.get(Pool.COMMITTEE_NORMAL)))
                .pvtCommitteeNoConfidence(unit(thresholds.get(Pool.COMMITTEE_NO_CONFIDENCE)))
                .pvtHardForkInitiation(unit(thresholds.get(Pool.HARD_FORK_INITIATION)))
                .pvtPPSecurityGroup(unit(thresholds.get(Pool.SECURITY_GROUP)))
                .build();
    }

    private static DrepVoteThresholds drep(RawVotingThresholds thresholds) {
        return DrepVoteThresholds.builder()
                .dvtMotionNoConfidence(unit(thresholds.get(DRep.MOTION_NO_CONFIDENCE)))
                .dvtCommitteeNormal(unit(thresholds.get(DRep.COMMITTEE_NORMAL)))
                .dvtCommitteeNoConfidence(unit(thresholds.get(DRep.COMMITTEE_NO_CONFIDENCE)))
                .dvtUpdateToConstitution(unit(thresholds.get(DRep.UPDATE_CONSTITUTION)))
                .dvtHardForkInitiation(unit(thresholds.get(DRep.HARD_FORK_INITIATION)))
                .dvtPPNetworkGroup(unit(thresholds.get(DRep.NETWORK_GROUP)))
                .dvtPPEconomicGroup(unit(thresholds.get(DRep.ECONOMIC_GROUP)))
                .dvtPPTechnicalGroup(unit(thresholds.get(DRep.TECHNICAL_GROUP)))
                .dvtPPGovGroup(unit(thresholds.get(DRep.GOVERNANCE_GROUP)))
                .dvtTreasuryWithdrawal(unit(thresholds.get(DRep.TREASURY_WITHDRAWAL)))
                .build();
    }

    // =====================================================================
    // Serialization: ProtocolParamUpdate → CBOR Map
    // Inverse of read(RawProtocolParamUpdate). Sparse map — only non-null fields.
    // =====================================================================

    /**
     * Serialize a ProtocolParamUpdate to a CBOR Map.
     * Field indices match the CDDL protocol_param_update spec and {@link #read(RawProtocolParamUpdate)}.
     */
    public static DataItem serializePPUpdate(ProtocolParamUpdate ppu) {
        if (ppu == null) return SimpleValue.NULL;
        Map map = new Map();

        // 0-6: basic params
        if (ppu.getMinFeeA() != null) cborMapPutUInt(map, 0, ppu.getMinFeeA());
        if (ppu.getMinFeeB() != null) cborMapPutUInt(map, 1, ppu.getMinFeeB());
        if (ppu.getMaxBlockSize() != null) cborMapPutUInt(map, 2, ppu.getMaxBlockSize());
        if (ppu.getMaxTxSize() != null) cborMapPutUInt(map, 3, ppu.getMaxTxSize());
        if (ppu.getMaxBlockHeaderSize() != null) cborMapPutUInt(map, 4, ppu.getMaxBlockHeaderSize());
        if (ppu.getKeyDeposit() != null) cborMapPutBigUInt(map, 5, ppu.getKeyDeposit());
        if (ppu.getPoolDeposit() != null) cborMapPutBigUInt(map, 6, ppu.getPoolDeposit());

        // 7: maxEpoch, 8: nOpt
        if (ppu.getMaxEpoch() != null) cborMapPutUInt(map, 7, ppu.getMaxEpoch());
        if (ppu.getNOpt() != null) cborMapPutUInt(map, 8, ppu.getNOpt());

        // 9-12: rational intervals
        if (ppu.getPoolPledgeInfluence() != null) map.put(cborUInt(9), serializeRational(ppu.getPoolPledgeInfluence()));
        if (ppu.getExpansionRate() != null) map.put(cborUInt(10), serializeRational(ppu.getExpansionRate()));
        if (ppu.getTreasuryGrowthRate() != null) map.put(cborUInt(11), serializeRational(ppu.getTreasuryGrowthRate()));
        if (ppu.getDecentralisationParam() != null) map.put(cborUInt(12), serializeRational(ppu.getDecentralisationParam()));

        // 13: extraEntropy [type, hash?]
        if (ppu.getExtraEntropy() != null) {
            Array entropyArr = new Array();
            entropyArr.add(cborUInt(ppu.getExtraEntropy()._1));
            if (ppu.getExtraEntropy()._2 != null && !ppu.getExtraEntropy()._2.isEmpty()) {
                entropyArr.add(new ByteString(HexUtil.decodeHexString(ppu.getExtraEntropy()._2)));
            }
            map.put(cborUInt(13), entropyArr);
        }

        // 14: protocol version [major, minor]
        if (ppu.getProtocolMajorVer() != null) {
            Array pvArr = new Array();
            pvArr.add(cborUInt(ppu.getProtocolMajorVer()));
            pvArr.add(cborUInt(ppu.getProtocolMinorVer() != null ? ppu.getProtocolMinorVer() : 0));
            map.put(cborUInt(14), pvArr);
        }

        // 15: minUtxo (deprecated), 16: minPoolCost, 17: adaPerUtxoByte
        if (ppu.getMinUtxo() != null) cborMapPutBigUInt(map, 15, ppu.getMinUtxo());
        if (ppu.getMinPoolCost() != null) cborMapPutBigUInt(map, 16, ppu.getMinPoolCost());
        if (ppu.getAdaPerUtxoByte() != null) cborMapPutBigUInt(map, 17, ppu.getAdaPerUtxoByte());

        // 18: costModels {version => cbor_encoded_cost_model}
        if (ppu.getCostModels() != null && !ppu.getCostModels().isEmpty()) {
            Map cmMap = new Map();
            for (var entry : ppu.getCostModels().entrySet()) {
                try {
                    byte[] costModelBytes = HexUtil.decodeHexString(entry.getValue());
                    cmMap.put(cborUInt(entry.getKey()), CborSerializationUtil.deserializeOne(costModelBytes));
                } catch (CborRuntimeException e) {
                    throw new RuntimeException("Failed to decode cost model CBOR for version " + entry.getKey(), e);
                }
            }
            map.put(cborUInt(18), cmMap);
        }

        // 19: ex_unit_prices [priceMem, priceSteps]
        if (ppu.getPriceMem() != null || ppu.getPriceStep() != null) {
            Array prices = new Array();
            prices.add(ppu.getPriceMem() != null ? serializeRational(ppu.getPriceMem()) : SimpleValue.NULL);
            prices.add(ppu.getPriceStep() != null ? serializeRational(ppu.getPriceStep()) : SimpleValue.NULL);
            map.put(cborUInt(19), prices);
        }

        // 20: max_tx_ex_units [mem, steps] — BigInteger values
        if (ppu.getMaxTxExMem() != null || ppu.getMaxTxExSteps() != null) {
            Array exUnits = new Array();
            exUnits.add(ppu.getMaxTxExMem() != null ? cborUInt(ppu.getMaxTxExMem()) : cborUInt(0));
            exUnits.add(ppu.getMaxTxExSteps() != null ? cborUInt(ppu.getMaxTxExSteps()) : cborUInt(0));
            map.put(cborUInt(20), exUnits);
        }

        // 21: max_block_ex_units [mem, steps] — BigInteger values
        if (ppu.getMaxBlockExMem() != null || ppu.getMaxBlockExSteps() != null) {
            Array exUnits = new Array();
            exUnits.add(ppu.getMaxBlockExMem() != null ? cborUInt(ppu.getMaxBlockExMem()) : cborUInt(0));
            exUnits.add(ppu.getMaxBlockExSteps() != null ? cborUInt(ppu.getMaxBlockExSteps()) : cborUInt(0));
            map.put(cborUInt(21), exUnits);
        }

        // 22-24: maxValSize, collateralPercent, maxCollateralInputs
        if (ppu.getMaxValSize() != null) map.put(cborUInt(22), cborUInt(ppu.getMaxValSize()));
        if (ppu.getCollateralPercent() != null) cborMapPutUInt(map, 23, ppu.getCollateralPercent());
        if (ppu.getMaxCollateralInputs() != null) cborMapPutUInt(map, 24, ppu.getMaxCollateralInputs());

        // 25-26: voting thresholds
        if (ppu.getPoolVotingThresholds() != null) map.put(cborUInt(25), serializePoolVotingThresholds(ppu.getPoolVotingThresholds()));
        if (ppu.getDrepVotingThresholds() != null) map.put(cborUInt(26), serializeDrepVotingThresholds(ppu.getDrepVotingThresholds()));

        // 27-32: Conway governance params
        if (ppu.getCommitteeMinSize() != null) cborMapPutUInt(map, 27, ppu.getCommitteeMinSize());
        if (ppu.getCommitteeMaxTermLength() != null) cborMapPutUInt(map, 28, ppu.getCommitteeMaxTermLength());
        if (ppu.getGovActionLifetime() != null) cborMapPutUInt(map, 29, ppu.getGovActionLifetime());
        if (ppu.getGovActionDeposit() != null) cborMapPutBigUInt(map, 30, ppu.getGovActionDeposit());
        if (ppu.getDrepDeposit() != null) cborMapPutBigUInt(map, 31, ppu.getDrepDeposit());
        if (ppu.getDrepActivity() != null) cborMapPutUInt(map, 32, ppu.getDrepActivity());

        // 33: minFeeRefScriptCostPerByte
        if (ppu.getMinFeeRefScriptCostPerByte() != null) map.put(cborUInt(33), serializeRational(ppu.getMinFeeRefScriptCostPerByte()));

        return map;
    }

    private static DataItem serializePoolVotingThresholds(PoolVotingThresholds pvt) {
        Array arr = new Array();
        arr.add(pvt.getPvtMotionNoConfidence() != null ? serializeRational(pvt.getPvtMotionNoConfidence()) : SimpleValue.NULL);
        arr.add(pvt.getPvtCommitteeNormal() != null ? serializeRational(pvt.getPvtCommitteeNormal()) : SimpleValue.NULL);
        arr.add(pvt.getPvtCommitteeNoConfidence() != null ? serializeRational(pvt.getPvtCommitteeNoConfidence()) : SimpleValue.NULL);
        arr.add(pvt.getPvtHardForkInitiation() != null ? serializeRational(pvt.getPvtHardForkInitiation()) : SimpleValue.NULL);
        arr.add(pvt.getPvtPPSecurityGroup() != null ? serializeRational(pvt.getPvtPPSecurityGroup()) : SimpleValue.NULL);
        return arr;
    }

    private static DataItem serializeDrepVotingThresholds(DrepVoteThresholds dvt) {
        Array arr = new Array();
        arr.add(dvt.getDvtMotionNoConfidence() != null ? serializeRational(dvt.getDvtMotionNoConfidence()) : SimpleValue.NULL);
        arr.add(dvt.getDvtCommitteeNormal() != null ? serializeRational(dvt.getDvtCommitteeNormal()) : SimpleValue.NULL);
        arr.add(dvt.getDvtCommitteeNoConfidence() != null ? serializeRational(dvt.getDvtCommitteeNoConfidence()) : SimpleValue.NULL);
        arr.add(dvt.getDvtUpdateToConstitution() != null ? serializeRational(dvt.getDvtUpdateToConstitution()) : SimpleValue.NULL);
        arr.add(dvt.getDvtHardForkInitiation() != null ? serializeRational(dvt.getDvtHardForkInitiation()) : SimpleValue.NULL);
        arr.add(dvt.getDvtPPNetworkGroup() != null ? serializeRational(dvt.getDvtPPNetworkGroup()) : SimpleValue.NULL);
        arr.add(dvt.getDvtPPEconomicGroup() != null ? serializeRational(dvt.getDvtPPEconomicGroup()) : SimpleValue.NULL);
        arr.add(dvt.getDvtPPTechnicalGroup() != null ? serializeRational(dvt.getDvtPPTechnicalGroup()) : SimpleValue.NULL);
        arr.add(dvt.getDvtPPGovGroup() != null ? serializeRational(dvt.getDvtPPGovGroup()) : SimpleValue.NULL);
        arr.add(dvt.getDvtTreasuryWithdrawal() != null ? serializeRational(dvt.getDvtTreasuryWithdrawal()) : SimpleValue.NULL);
        return arr;
    }
}
