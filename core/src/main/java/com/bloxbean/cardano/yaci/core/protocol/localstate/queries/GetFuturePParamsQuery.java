package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.util.Tuple;
import com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds;
import com.bloxbean.cardano.yaci.core.model.PoolVotingThresholds;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import com.bloxbean.cardano.yaci.core.types.NonNegativeInterval;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.*;

@Getter
@AllArgsConstructor
@ToString
public class GetFuturePParamsQuery implements EraQuery<GetFuturePParamsQueryResult> {
    private Era era;

    public GetFuturePParamsQuery() {
        this.era = Era.Conway;
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array queryArray = new Array();
        queryArray.add(new UnsignedInteger(33));

        return wrapWithOuterArray(queryArray);
    }

    @Override
    public GetFuturePParamsQueryResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        var resultDI = extractResultArray(di[0]);
        var outerArray = (Array)resultDI.get(0);

        if (outerArray.getDataItems().isEmpty()) {
            return new GetFuturePParamsQueryResult(null);
        }

        Array futurePParamArray = (Array)outerArray.getDataItems().get(0);
        ProtocolParamUpdate futureProtocolParam = deserializePPResult(futurePParamArray.getDataItems());

        return new GetFuturePParamsQueryResult(futureProtocolParam);
    }

    public ProtocolParamUpdate deserializePPResult(List<DataItem> paramsDIList) {
        if (paramsDIList.isEmpty()) {
            return null;
        }

        DataItem itemDI = paramsDIList.get(0);
        Integer minFeeA = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(1);
        Integer minFeeB = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(2);
        Integer maxBlockSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(3);
        Integer maxTxSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(4);
        Integer maxBlockHeaderSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(5);
        BigInteger keyDeposit = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = paramsDIList.get(6);
        BigInteger poolDeposit = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = paramsDIList.get(7);
        Integer maxEpoch = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(8);
        Integer nOpt = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(9);
        NonNegativeInterval poolPledgeInfluence = itemDI != null ? toNonNegativeInterval(itemDI) : null;

        itemDI = paramsDIList.get(10);
        UnitInterval expansionRate = itemDI != null ? toUnitInterval(itemDI) : null;

        itemDI = paramsDIList.get(11);
        UnitInterval treasuryGrowthRate = itemDI != null ? toUnitInterval(itemDI) : null;

        Integer protocolMajorVersion = null;
        Integer protocolMinorVersion = null;
        itemDI = paramsDIList.get(12);
        if (itemDI != null) {
            List<DataItem> protocolVersions = ((Array) itemDI).getDataItems();
            protocolMajorVersion = toInt(protocolVersions.get(0));
            protocolMinorVersion = toInt(protocolVersions.get(1));
        }

        itemDI = paramsDIList.get(13);
        BigInteger minPoolCost = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = paramsDIList.get(14);
        BigInteger adaPerUtxoBytes = itemDI != null ? toBigInteger(itemDI) : null;

        //CostModels
        java.util.Map<Integer, String> costModelMap = null;
        itemDI = paramsDIList.get(15);
        if (itemDI != null) {
            costModelMap = new LinkedHashMap<>();
            Map itemDIMap = (Map) itemDI;
            for (DataItem key : itemDIMap.getKeys()) {
                Integer version = toInt(key);
                String costModel = HexUtil.encodeHexString(CborSerializationUtil.serialize(itemDIMap.get(key)));
                costModelMap.put(version, costModel);
            }
        }

        //exUnits prices
        NonNegativeInterval priceMem = null;
        NonNegativeInterval priceSteps = null;
        itemDI = paramsDIList.get(16);
        if (itemDI != null) {
            List<DataItem> exUnitPriceList = ((Array) itemDI).getDataItems();
            Tuple<NonNegativeInterval, NonNegativeInterval> tuple = getExUnitPrices(exUnitPriceList);
            priceMem = tuple._1;
            priceSteps = tuple._2;
        }

        //max tx exunits
        BigInteger maxTxExMem = null;
        BigInteger maxTxExSteps = null;
        itemDI = paramsDIList.get(17);
        if (itemDI != null) {
            List<DataItem> exUnits = ((Array) itemDI).getDataItems();
            Tuple<BigInteger, BigInteger> tuple = getExUnits(exUnits);
            maxTxExMem = tuple._1;
            maxTxExSteps = tuple._2;
        }

        //max block exunits
        BigInteger maxBlockExMem = null;
        BigInteger maxBlockExSteps = null;
        itemDI = paramsDIList.get(18);
        if (itemDI != null) {
            List<DataItem> exUnits = ((Array) itemDI).getDataItems();
            Tuple<BigInteger, BigInteger> tuple = getExUnits(exUnits);
            maxBlockExMem = tuple._1;
            maxBlockExSteps = tuple._2;
        }

        itemDI = paramsDIList.get(19);
        Long maxValueSize = itemDI != null ? toLong(itemDI) : null;

        itemDI = paramsDIList.get(20);
        Integer collateralPercent = itemDI != null ? toInt(itemDI) : null;

        Integer maxCollateralInputs = null;
        itemDI = paramsDIList.get(21);
        maxCollateralInputs = itemDI != null ? toInt(itemDI) : null;

        //Pool Voting Threshold
        itemDI = paramsDIList.get(22);
        UnitInterval motionNoConfidence = null;
        UnitInterval committeeNormal = null;
        UnitInterval committeeNoConfidence = null;
        UnitInterval hardForkInitiation = null;
        UnitInterval ppSecurityGroup = null;
        if (itemDI != null) {
            List<DataItem> poolVotingThresholdList = ((Array) itemDI).getDataItems();
            if (poolVotingThresholdList.size() != 5)
                throw new IllegalStateException("Invalid pool voting threshold list");

            var pvtMotionNoConfidenceDI = (RationalNumber) poolVotingThresholdList.get(0);
            var pvtCommitteeNormalDI = (RationalNumber) poolVotingThresholdList.get(1);
            var pvtCommitteeNoConfidenceDI = (RationalNumber) poolVotingThresholdList.get(2);
            var pvtHardForkInitiationDI = (RationalNumber) poolVotingThresholdList.get(3);
            var pvtPPSecurityGroupDI = (RationalNumber) poolVotingThresholdList.get(4);

            motionNoConfidence = toUnitInterval(pvtMotionNoConfidenceDI);
            committeeNormal = toUnitInterval(pvtCommitteeNormalDI);
            committeeNoConfidence = toUnitInterval(pvtCommitteeNoConfidenceDI);
            hardForkInitiation = toUnitInterval(pvtHardForkInitiationDI);
            ppSecurityGroup = toUnitInterval(pvtPPSecurityGroupDI);
        }

        //DRep voting thresholds
        itemDI = paramsDIList.get(23);
        UnitInterval dvtMotionNoConfidence = null;
        UnitInterval dvtCommitteeNormal = null;
        UnitInterval dvtCommitteeNoConfidence = null;
        UnitInterval dvtUpdateToConstitution = null;
        UnitInterval dvtHardForkInitiation = null;
        UnitInterval dvtPPNetworkGroup = null;
        UnitInterval dvtPPEconomicGroup = null;
        UnitInterval dvtPPTechnicalGroup = null;
        UnitInterval dvtPPGovGroup = null;
        UnitInterval dvtTreasuryWithdrawal = null;

        if (itemDI != null) {
            List<DataItem> dRepVotingThresholdList = ((Array) itemDI).getDataItems();
            if (dRepVotingThresholdList.size() != 10)
                throw new IllegalStateException("Invalid dRep voting threshold list");

            var dvtMotionNoConfidenceDI = (RationalNumber) dRepVotingThresholdList.get(0);
            var dvtCommitteeNormalDI = (RationalNumber) dRepVotingThresholdList.get(1);
            var dvtCommitteeNoConfidenceDI = (RationalNumber) dRepVotingThresholdList.get(2);
            var dvtUpdateToConstitutionDI = (RationalNumber) dRepVotingThresholdList.get(3);
            var dvtHardForkInitiationDI = (RationalNumber) dRepVotingThresholdList.get(4);
            var dvtPPNetworkGroupDI = (RationalNumber) dRepVotingThresholdList.get(5);
            var dvtPPEconomicGroupDI = (RationalNumber) dRepVotingThresholdList.get(6);
            var dvtPPTechnicalGroupDI = (RationalNumber) dRepVotingThresholdList.get(7);
            var dvtPPGovGroupDI = (RationalNumber) dRepVotingThresholdList.get(8);
            var dvtTreasuryWithdrawalDI = (RationalNumber) dRepVotingThresholdList.get(9);

            dvtMotionNoConfidence = toUnitInterval(dvtMotionNoConfidenceDI);
            dvtCommitteeNormal = toUnitInterval(dvtCommitteeNormalDI);
            dvtCommitteeNoConfidence = toUnitInterval(dvtCommitteeNoConfidenceDI);
            dvtUpdateToConstitution = toUnitInterval(dvtUpdateToConstitutionDI);
            dvtHardForkInitiation = toUnitInterval(dvtHardForkInitiationDI);
            dvtPPNetworkGroup = toUnitInterval(dvtPPNetworkGroupDI);
            dvtPPEconomicGroup = toUnitInterval(dvtPPEconomicGroupDI);
            dvtPPTechnicalGroup = toUnitInterval(dvtPPTechnicalGroupDI);
            dvtPPGovGroup = toUnitInterval(dvtPPGovGroupDI);
            dvtTreasuryWithdrawal = toUnitInterval(dvtTreasuryWithdrawalDI);
        }

        itemDI = paramsDIList.get(24);
        Integer minCommitteeSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(25);
        Integer committeeTermLimit = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(26);
        Integer governanceActionValidityPeriod = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(27);
        BigInteger governanceActionDeposit = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = paramsDIList.get(28);
        BigInteger drepDeposit = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = paramsDIList.get(29);
        Integer drepInactivityPeriod = itemDI != null ? toInt(itemDI) : null;

        NonNegativeInterval minFeeRefScriptCostPerByte = null;
        if (paramsDIList.size() > 30) {
            itemDI = paramsDIList.get(30);
            minFeeRefScriptCostPerByte = itemDI != null ? toNonNegativeInterval(itemDI) : null;
        }

        return ProtocolParamUpdate.builder()
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
                .protocolMajorVer(protocolMajorVersion)
                .protocolMinorVer(protocolMinorVersion)
                .minPoolCost(minPoolCost)
                .adaPerUtxoByte(adaPerUtxoBytes)
                .costModels(costModelMap)
                .priceMem(priceMem)
                .priceStep(priceSteps)
                .maxTxExMem(maxTxExMem)
                .maxTxExSteps(maxTxExSteps)
                .maxBlockExMem(maxBlockExMem)
                .maxBlockExSteps(maxBlockExSteps)
                .maxValSize(maxValueSize)
                .collateralPercent(collateralPercent)
                .maxCollateralInputs(maxCollateralInputs)
                .poolVotingThresholds(PoolVotingThresholds.builder()
                        .pvtMotionNoConfidence(motionNoConfidence)
                        .pvtCommitteeNormal(committeeNormal)
                        .pvtCommitteeNoConfidence(committeeNoConfidence)
                        .pvtHardForkInitiation(hardForkInitiation)
                        .pvtPPSecurityGroup(ppSecurityGroup)
                        .build())
                .drepVotingThresholds(DrepVoteThresholds.builder()
                        .dvtMotionNoConfidence(dvtMotionNoConfidence)
                        .dvtCommitteeNormal(dvtCommitteeNormal)
                        .dvtCommitteeNoConfidence(dvtCommitteeNoConfidence)
                        .dvtUpdateToConstitution(dvtUpdateToConstitution)
                        .dvtHardForkInitiation(dvtHardForkInitiation)
                        .dvtPPNetworkGroup(dvtPPNetworkGroup)
                        .dvtPPEconomicGroup(dvtPPEconomicGroup)
                        .dvtPPTechnicalGroup(dvtPPTechnicalGroup)
                        .dvtPPGovGroup(dvtPPGovGroup)
                        .dvtTreasuryWithdrawal(dvtTreasuryWithdrawal)
                        .build())
                .committeeMinSize(minCommitteeSize)
                .committeeMaxTermLength(committeeTermLimit)
                .govActionLifetime(governanceActionValidityPeriod)
                .govActionDeposit(governanceActionDeposit)
                .drepDeposit(drepDeposit)
                .drepActivity(drepInactivityPeriod)
                .minFeeRefScriptCostPerByte(minFeeRefScriptCostPerByte)
                .build();
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

        NonNegativeInterval memPrice = toNonNegativeInterval(memPriceRN);
        NonNegativeInterval stepPrice = toNonNegativeInterval(stepPriceRN);

        return new Tuple<>(memPrice, stepPrice);
    }

}
