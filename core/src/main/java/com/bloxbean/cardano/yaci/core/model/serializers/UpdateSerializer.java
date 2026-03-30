package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.*;

public enum UpdateSerializer implements Serializer<Update> {
    INSTANCE;

    //    update = [ proposed_protocol_parameter_updates
//         , epoch
//         ]
    @Override
    public Update deserializeDI(DataItem di) {
        Array array = (Array) di;
        List<DataItem> dataItemList = array.getDataItems();

        long epoch = toInt(dataItemList.get(1));

        Map protoParamUpdateMap = (Map) dataItemList.get(0);

        java.util.Map<String, ProtocolParamUpdate> deProtocolParamUpdateMap = new HashMap<>();
        Collection<DataItem> genesisHashDIKeys = protoParamUpdateMap.getKeys();

        if (genesisHashDIKeys != null && genesisHashDIKeys.size() > 0) {
            for (DataItem gensisHashDI : genesisHashDIKeys) {
                Map genesisProtocolParamsMap = (Map) protoParamUpdateMap.get(gensisHashDI);

                ProtocolParamUpdate protocolParamUpdate = getProtocolParams(genesisProtocolParamsMap);

                String genHashKey = HexUtil.encodeHexString(toBytes(gensisHashDI));
                deProtocolParamUpdateMap.put(genHashKey, protocolParamUpdate);
            }
        }

        return new Update(deProtocolParamUpdateMap, epoch);
    }

    public ProtocolParamUpdate getProtocolParams(Map genesisProtocolParamsMap) {
        DataItem itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(0));
        Integer minFeeA = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(1));
        Integer minFeeB = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(2));
        Integer maxBlockSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(3));
        Integer maxTxSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(4));
        Integer maxBlockHeaderSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(5));
        BigInteger keyDeposit = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(6));
        BigInteger poolDeposit = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(7));
        Integer maxEpoch = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(8));
        Integer nOpt = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(9));
        NonNegativeInterval poolPledgeInfluence = itemDI != null ? toNonNegativeInterval(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(10));
        UnitInterval expansionRate = itemDI != null ? toUnitInterval(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(11));
        UnitInterval treasuryGrowthRate = itemDI != null ? toUnitInterval(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(12));
        UnitInterval decentralizationParam = itemDI != null ? toUnitInterval(itemDI) : null;

        Tuple<Integer, String> extraEntropy = null;
//                $nonce /= [ 0 // 1, bytes .size 32 ]
        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(13)); //Removed
        if (itemDI != null) {
            List<DataItem> extraEntropyDIList = ((Array) itemDI).getDataItems();
            int extraEntropy_1 = toInt(extraEntropyDIList.get(0));
            String extraEntropy_2 = "";
            if (extraEntropyDIList.size() == 2) {
                extraEntropy_2 = HexUtil.encodeHexString(toBytes(extraEntropyDIList.get(1)));
            }
            extraEntropy = new Tuple<>(extraEntropy_1, extraEntropy_2);
        }

        Integer protocolMajorVersion = null;
        Integer protocolMinorVersion = null;
        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(14));
        if (itemDI != null) {
            List<DataItem> protocolVersionDIList = ((Array) itemDI).getDataItems();
            protocolMajorVersion = toInt(protocolVersionDIList.get(0));
            protocolMinorVersion = toInt(protocolVersionDIList.get(1));
        }

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(15)); //Removed
        BigInteger minUtxo = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(16));
        BigInteger minPoolCost = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(17));
        BigInteger adaPerUtxoBytes = itemDI != null ? toBigInteger(itemDI) : null;

        //CostModels
        java.util.Map<Integer, String> costModelMap = null;
        String costModelsHash = null;
        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(18));
        if (itemDI != null) {
            costModelMap = new LinkedHashMap<>();
            Map itemDIMap = (Map) itemDI;
            for (DataItem key : itemDIMap.getKeys()) {
                Integer version = toInt(key);
                String costModel = HexUtil.encodeHexString(CborSerializationUtil.serialize(itemDIMap.get(key)));
                costModelMap.put(version, costModel);
            }

            var cbor = CborSerializationUtil.serialize(itemDI);
            costModelsHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(cbor));
        }

        //exUnits prices
        NonNegativeInterval priceMem = null;
        NonNegativeInterval priceSteps = null;
        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(19));
        if (itemDI != null) {
            List<DataItem> exUnitPriceList = ((Array) itemDI).getDataItems();
            Tuple<NonNegativeInterval, NonNegativeInterval> tuple = getExUnitPrices(exUnitPriceList);
            priceMem = tuple._1;
            priceSteps = tuple._2;
        }

        //max tx exunits
        BigInteger maxTxExMem = null;
        BigInteger maxTxExSteps = null;
        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(20));
        if (itemDI != null) {
            List<DataItem> exUnits = ((Array) itemDI).getDataItems();
            Tuple<BigInteger, BigInteger> tuple = getExUnits(exUnits);
            maxTxExMem = tuple._1;
            maxTxExSteps = tuple._2;
        }

        //max block exunits
        BigInteger maxBlockExMem = null;
        BigInteger maxBlockExSteps = null;
        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(21));
        if (itemDI != null) {
            List<DataItem> exUnits = ((Array) itemDI).getDataItems();
            Tuple<BigInteger, BigInteger> tuple = getExUnits(exUnits);
            maxBlockExMem = tuple._1;
            maxBlockExSteps = tuple._2;
        }

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(22));
        Long maxValueSize = itemDI != null ? toLong(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(23));
        Integer collateralPercent = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(24));
        Integer maxCollateralPercent = itemDI != null ? toInt(itemDI) : null;

        //Conway era protocol parameters
        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(25));
        PoolVotingThresholds poolVotingThresholds = deserializePoolVotingThresholds(itemDI);

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(26));
        DrepVoteThresholds drepVoteThresholds = deserializeDrepVoteThresholds(itemDI);

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(27));
        Integer minCommitteeSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(28));
        Integer committeeTermLimit = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(29));
        Integer goveranceActionValidityPeriod = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(30));
        BigInteger governanceActionDeposit = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(31));
        BigInteger drepDeposit = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(32));
        Integer drepInactivityPeriod = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(33));
        NonNegativeInterval minFeeRefScriptCostPerByte = itemDI != null ? toNonNegativeInterval(itemDI) : null;

        ProtocolParamUpdate protocolParamUpdate = ProtocolParamUpdate.builder()
                .minFeeA(minFeeA)
                .minFeeB(minFeeB)
                .maxBlockSize(maxBlockSize)
                .maxTxSize(maxTxSize)
                .maxBlockHeaderSize(maxBlockHeaderSize)
                .keyDeposit(keyDeposit)
                .poolDeposit(poolDeposit)
                .maxEpoch(maxEpoch)
                .nOpt(nOpt)
                .poolPledgeInfluence(poolPledgeInfluence)
                .expansionRate(expansionRate)
                .treasuryGrowthRate(treasuryGrowthRate)
                .decentralisationParam(decentralizationParam)
                .extraEntropy(extraEntropy)
                .protocolMajorVer(protocolMajorVersion)
                .protocolMinorVer(protocolMinorVersion)
                .minUtxo(minUtxo)
                .minPoolCost(minPoolCost)
                .adaPerUtxoByte(adaPerUtxoBytes)
                .costModels(costModelMap)
                .costModelsHash(costModelsHash)
                .priceMem(priceMem)
                .priceStep(priceSteps)
                .maxTxExMem(maxTxExMem)
                .maxTxExSteps(maxTxExSteps)
                .maxBlockExMem(maxBlockExMem)
                .maxBlockExSteps(maxBlockExSteps)
                .maxValSize(maxValueSize)
                .collateralPercent(collateralPercent)
                .maxCollateralInputs(maxCollateralPercent)
                .poolVotingThresholds(poolVotingThresholds)
                .drepVotingThresholds(drepVoteThresholds)
                .committeeMinSize(minCommitteeSize)
                .committeeMaxTermLength(committeeTermLimit)
                .govActionLifetime(goveranceActionValidityPeriod)
                .govActionDeposit(governanceActionDeposit)
                .drepDeposit(drepDeposit)
                .drepActivity(drepInactivityPeriod)
                .minFeeRefScriptCostPerByte(minFeeRefScriptCostPerByte)
                .build();
        return protocolParamUpdate;
    }

    private Tuple<BigInteger, BigInteger> getExUnits(List<DataItem> exunits) {
        BigInteger mem = toBigInteger(exunits.get(0));

        BigInteger steps = null;
        if (exunits.size() > 1)
            steps = toBigInteger(exunits.get(1));

        return new Tuple<>(mem, steps);
    }

    private Tuple<NonNegativeInterval, NonNegativeInterval> getExUnitPrices(List<DataItem> exunits) {
        RationalNumber memPriceRN = (RationalNumber) exunits.get(0);
        RationalNumber stepPriceRN = (RationalNumber) exunits.get(1);

        var memPrice = toNonNegativeInterval(memPriceRN);
        var stepPrice = toNonNegativeInterval(stepPriceRN);

        return new Tuple<>(memPrice, stepPrice);
    }

    /**
     * pool_voting_thresholds =
     *   [ unit_interval ; motion no confidence
     *   , unit_interval ; committee normal
     *   , unit_interval ; committee no confidence
     *   , unit_interval ; hard fork initiation
     *   ]
     * @param itemDI
     * @return
     */
    private PoolVotingThresholds deserializePoolVotingThresholds(DataItem itemDI) {
        if (itemDI == null)
            return null;

        List<DataItem> poolVotingThresholds = ((Array) itemDI).getDataItems();
        UnitInterval motionNoConfidence = toUnitInterval(poolVotingThresholds.get(0));
        UnitInterval committeeNormal = toUnitInterval(poolVotingThresholds.get(1));
        UnitInterval committeeNoConfidence = toUnitInterval(poolVotingThresholds.get(2));
        UnitInterval hardForkInitiation = toUnitInterval(poolVotingThresholds.get(3));
        UnitInterval ppSecurityGroup = toUnitInterval(poolVotingThresholds.get(4));

        return PoolVotingThresholds.builder()
                .pvtMotionNoConfidence(motionNoConfidence)
                .pvtCommitteeNormal(committeeNormal)
                .pvtCommitteeNoConfidence(committeeNoConfidence)
                .pvtHardForkInitiation(hardForkInitiation)
                .pvtPPSecurityGroup(ppSecurityGroup)
                .build();

    }

    /**
     * drep_voting_thresholds =
     *   [ unit_interval ; motion no confidence
     *   , unit_interval ; committee normal
     *   , unit_interval ; committee no confidence
     *   , unit_interval ; update constitution
     *   , unit_interval ; hard fork initiation
     *   , unit_interval ; PP network group
     *   , unit_interval ; PP economic group
     *   , unit_interval ; PP technical group
     *   , unit_interval ; PP governance group
     *   , unit_interval ; treasury withdrawal
     *   ]
     * @param itemDI
     * @return
     */
    private DrepVoteThresholds deserializeDrepVoteThresholds(DataItem itemDI) {
        if (itemDI == null)
            return null;

        List<DataItem> drepVotingThresholds = ((Array) itemDI).getDataItems();
        UnitInterval motionNoConfidence = toUnitInterval(drepVotingThresholds.get(0));
        UnitInterval committeeNormal = toUnitInterval(drepVotingThresholds.get(1));
        UnitInterval committeeNoConfidence = toUnitInterval(drepVotingThresholds.get(2));
        UnitInterval updateConstitution = toUnitInterval(drepVotingThresholds.get(3));
        UnitInterval hardForkInitiation = toUnitInterval(drepVotingThresholds.get(4));
        UnitInterval ppNetworkGroup = toUnitInterval(drepVotingThresholds.get(5));
        UnitInterval ppEconomicGroup = toUnitInterval(drepVotingThresholds.get(6));
        UnitInterval ppTechnicalGroup = toUnitInterval(drepVotingThresholds.get(7));
        UnitInterval ppGovernanceGroup = toUnitInterval(drepVotingThresholds.get(8));
        UnitInterval treasuryWithdrawal = toUnitInterval(drepVotingThresholds.get(9));

        return DrepVoteThresholds.builder()
                .dvtMotionNoConfidence(motionNoConfidence)
                .dvtCommitteeNormal(committeeNormal)
                .dvtCommitteeNoConfidence(committeeNoConfidence)
                .dvtUpdateToConstitution(updateConstitution)
                .dvtHardForkInitiation(hardForkInitiation)
                .dvtPPNetworkGroup(ppNetworkGroup)
                .dvtPPEconomicGroup(ppEconomicGroup)
                .dvtPPTechnicalGroup(ppTechnicalGroup)
                .dvtPPGovGroup(ppGovernanceGroup)
                .dvtTreasuryWithdrawal(treasuryWithdrawal)
                .build();
    }

    // =====================================================================
    // Serialization: ProtocolParamUpdate → CBOR Map
    // Inverse of getProtocolParams(). Sparse map — only non-null fields.
    // =====================================================================

    /**
     * Serialize a ProtocolParamUpdate to a CBOR Map.
     * Field indices match the CDDL protocol_param_update spec and {@link #getProtocolParams(Map)} deserialization.
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
                    DataItem costModelDI = CborDecoder.decode(costModelBytes).get(0);
                    cmMap.put(cborUInt(entry.getKey()), costModelDI);
                } catch (CborException e) {
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
