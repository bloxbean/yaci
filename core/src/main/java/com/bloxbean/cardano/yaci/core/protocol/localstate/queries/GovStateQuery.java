package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.util.Tuple;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds;
import com.bloxbean.cardano.yaci.core.model.PoolVotingThresholds;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.certs.StakePoolId;
import com.bloxbean.cardano.yaci.core.model.governance.*;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.AnchorSerializer;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.EnactState;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.Proposal;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.ProposalType;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.RatifyState;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.*;

@Getter
@AllArgsConstructor
@ToString
public class GovStateQuery implements EraQuery<GovStateQueryResult> {
    private Era era;

    public GovStateQuery() {
        this.era = Era.Conway;
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array queryArray = new Array();
        queryArray.add(new UnsignedInteger(24));

        return wrapWithOuterArray(queryArray);
    }

    @Override
    public GovStateQueryResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        GovStateQueryResult govStateQueryResult = new GovStateQueryResult();
        Array array = (Array) di[0];
        Array resultArray = (Array) ((Array) array.getDataItems().get(1)).getDataItems().get(0);

        // committee
        Array committeeResult =  (Array) resultArray.getDataItems().get(1);
        Array committeeDI =  (Array) committeeResult.getDataItems().get(0);
        Committee committee = deserializeCommitteeResult(committeeDI.getDataItems());
        govStateQueryResult.setCommittee(committee);

        // constitution
        Array constitutionArr = (Array) resultArray.getDataItems().get(2);
        var constitutionDI = constitutionArr.getDataItems().get(0);

        Constitution constitution = deserializeConstitutionResult(constitutionDI);
        govStateQueryResult.setConstitution(constitution);

        // current protocol params
        Array currentPParams = (Array) resultArray.getDataItems().get(3);
        List<DataItem> paramsDIList = currentPParams.getDataItems();

        ProtocolParamUpdate currentProtocolParam = deserializePPResult(paramsDIList);

        govStateQueryResult.setCurrentPParams(currentProtocolParam);

        Array futurePParams = (Array) resultArray.getDataItems().get(5);
        if (!futurePParams.getDataItems().isEmpty() && futurePParams.getDataItems().size() > 1) {
            List<DataItem> futureParamsDIList = ((Array)futurePParams.getDataItems().get(1)).getDataItems();
            ProtocolParamUpdate futureProtocolParam = deserializePPResult(futureParamsDIList);
            govStateQueryResult.setFuturePParams(futureProtocolParam);
        }

        // next ratify state
        Array nextRatifyStateDI =  (Array) ((Array)resultArray.getDataItems().get(6)).getDataItems().get(1);

        // next ratify state - enacted gov actions
        List<Proposal> enactedProposals = new ArrayList<>();
        Array enactedProposalArr = (Array) nextRatifyStateDI.getDataItems().get(1);
        for (var proposalDI : enactedProposalArr.getDataItems()) {
            Proposal enactedProposal = deserializeProposalResult(proposalDI);
            enactedProposals.add(enactedProposal);
        }

        // next ratify state - expired gov actions
        List<GovActionId> expiredGovActions = deserializeGovActionIdListResult(nextRatifyStateDI.getDataItems().get(2));

        // next ratify state - next enact state
        var nextEnactStateDI = (Array)nextRatifyStateDI.getDataItems().get(0);

            // next enact state - committee
        var nextEnactStateCommitteeArr = (Array) nextEnactStateDI.getDataItems().get(0);
        Committee nextEnactStateCommittee = deserializeCommitteeResult(
                ((Array)nextEnactStateCommitteeArr.getDataItems().get(0)).getDataItems());

            // next enact state - constitution
        var nextEnactStateConstitutionArr = (Array)nextEnactStateDI.getDataItems().get(1);
        Constitution nextEnactStateConstitution = deserializeConstitutionResult(nextEnactStateConstitutionArr.getDataItems().get(0));

            // next enact state - current protocol params
        ProtocolParamUpdate nextEnactStateCurrentPParams = deserializePPResult(
                ((Array)nextEnactStateDI.getDataItems().get(2)).getDataItems());
            // next enact state - prev protocol params
        ProtocolParamUpdate nextEnactStatePrevPParams = deserializePPResult(
                ((Array)nextEnactStateDI.getDataItems().get(3)).getDataItems());
            // next enact state - Prev govActionIds
        java.util.Map<ProposalType, GovActionId> prevGovActionIds = new HashMap<>();
        Array nextEnactStatePrevGovActionIds = (Array) nextEnactStateDI.getDataItems().get(6);

        prevGovActionIds.put(ProposalType.COMMITTEE,
                deserializeGovActionIdListResult(nextEnactStatePrevGovActionIds.getDataItems().get(0))
                        .stream()
                        .findFirst()
                        .orElse(null));

        prevGovActionIds.put(ProposalType.HARD_FORK,
                deserializeGovActionIdListResult(nextEnactStatePrevGovActionIds.getDataItems().get(1))
                        .stream()
                        .findFirst()
                        .orElse(null));

        prevGovActionIds.put(ProposalType.CONSTITUTION,
                deserializeGovActionIdListResult(nextEnactStatePrevGovActionIds.getDataItems().get(1))
                        .stream()
                        .findFirst()
                        .orElse(null));

        prevGovActionIds.put(ProposalType.P_PARAM_UPDATE,
                deserializeGovActionIdListResult(nextEnactStatePrevGovActionIds.getDataItems().get(1))
                        .stream()
                        .findFirst()
                        .orElse(null));

        // next ratify state - ratificationDelayed
        var ratificationDelayedDI = nextRatifyStateDI.getDataItems().get(3);
        Boolean ratificationDelayed = ratificationDelayedDI != null ?
                (((SimpleValue) ratificationDelayedDI).getValue() == SimpleValueType.FALSE.getValue() ? Boolean.FALSE : Boolean.TRUE)
                : null;

        RatifyState nextRatifyState = RatifyState.builder()
                .ratificationDelayed(ratificationDelayed)
                .nextEnactState(EnactState.builder()
                        .committee(nextEnactStateCommittee)
                        .constitution(nextEnactStateConstitution)
                        .currentPParams(nextEnactStateCurrentPParams)
                        .prevGovActionIds(prevGovActionIds)
                        .prevPParams(nextEnactStatePrevPParams)
                        .build())
                .enactedGovActions(enactedProposals)
                .expiredGovActions(expiredGovActions)
                .build();

        govStateQueryResult.setNextRatifyState(nextRatifyState);

        // previous protocol params
        Array prevPParams = (Array) resultArray.getDataItems().get(4);
        paramsDIList = prevPParams.getDataItems();
        ProtocolParamUpdate prevProtocolParam = deserializePPResult(paramsDIList);

        govStateQueryResult.setPreviousPParams(prevProtocolParam);

        // proposals
        Array proposalArr = (Array)((Array) resultArray.getDataItems().get(0)).getDataItems().get(1);

        List<Proposal> proposals = new ArrayList<>();
        for (DataItem item : proposalArr.getDataItems()) {
            if (item == SimpleValue.BREAK) {
                continue;
            }
            Proposal proposal = deserializeProposalResult(item);
            proposals.add(proposal);
        }

        govStateQueryResult.setProposals(proposals);

        return govStateQueryResult;
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
        BigDecimal poolPledgeInfluence = itemDI != null ? toRationalNumber(itemDI) : null;

        itemDI = paramsDIList.get(10);
        BigDecimal expansionRate = itemDI != null ? toRationalNumber(itemDI) : null;

        itemDI = paramsDIList.get(11);
        BigDecimal treasuryGrowthRate = itemDI != null ? toRationalNumber(itemDI) : null;

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
        BigDecimal priceMem = null;
        BigDecimal priceSteps = null;
        itemDI = paramsDIList.get(16);
        if (itemDI != null) {
            List<DataItem> exUnitPriceList = ((Array) itemDI).getDataItems();
            Tuple<BigDecimal, BigDecimal> tuple = getExUnitPrices(exUnitPriceList);
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
        BigDecimal motionNoConfidence = null;
        BigDecimal committeeNormal = null;
        BigDecimal committeeNoConfidence = null;
        BigDecimal hardForkInitiation = null;
        BigDecimal ppSecurityGroup = null;
        if (itemDI != null) {
            List<DataItem> poolVotingThresholdList = ((Array) itemDI).getDataItems();
            if (poolVotingThresholdList.size() != 5)
                throw new IllegalStateException("Invalid pool voting threshold list");

            var pvtMotionNoConfidenceDI = (RationalNumber) poolVotingThresholdList.get(0);
            var pvtCommitteeNormalDI = (RationalNumber) poolVotingThresholdList.get(1);
            var pvtCommitteeNoConfidenceDI = (RationalNumber) poolVotingThresholdList.get(2);
            var pvtHardForkInitiationDI = (RationalNumber) poolVotingThresholdList.get(3);
            var pvtPPSecurityGroupDI = (RationalNumber) poolVotingThresholdList.get(4);

            motionNoConfidence = toRationalNumber(pvtMotionNoConfidenceDI);
            committeeNormal = toRationalNumber(pvtCommitteeNormalDI);
            committeeNoConfidence = toRationalNumber(pvtCommitteeNoConfidenceDI);
            hardForkInitiation = toRationalNumber(pvtHardForkInitiationDI);
            ppSecurityGroup = toRationalNumber(pvtPPSecurityGroupDI);
        }

        //DRep voting thresholds
        itemDI = paramsDIList.get(23);
        BigDecimal dvtMotionNoConfidence = null;
        BigDecimal dvtCommitteeNormal = null;
        BigDecimal dvtCommitteeNoConfidence = null;
        BigDecimal dvtUpdateToConstitution = null;
        BigDecimal dvtHardForkInitiation = null;
        BigDecimal dvtPPNetworkGroup = null;
        BigDecimal dvtPPEconomicGroup = null;
        BigDecimal dvtPPTechnicalGroup = null;
        BigDecimal dvtPPGovGroup = null;
        BigDecimal dvtTreasuryWithdrawal = null;

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

            dvtMotionNoConfidence = toRationalNumber(dvtMotionNoConfidenceDI);
            dvtCommitteeNormal = toRationalNumber(dvtCommitteeNormalDI);
            dvtCommitteeNoConfidence = toRationalNumber(dvtCommitteeNoConfidenceDI);
            dvtUpdateToConstitution = toRationalNumber(dvtUpdateToConstitutionDI);
            dvtHardForkInitiation = toRationalNumber(dvtHardForkInitiationDI);
            dvtPPNetworkGroup = toRationalNumber(dvtPPNetworkGroupDI);
            dvtPPEconomicGroup = toRationalNumber(dvtPPEconomicGroupDI);
            dvtPPTechnicalGroup = toRationalNumber(dvtPPTechnicalGroupDI);
            dvtPPGovGroup = toRationalNumber(dvtPPGovGroupDI);
            dvtTreasuryWithdrawal = toRationalNumber(dvtTreasuryWithdrawalDI);
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

        BigDecimal minFeeRefScriptCostPerByte = null; //TODO -- Remove if condition once this is available in the node release
        if (paramsDIList.size() > 30) {
            itemDI = paramsDIList.get(30);
            minFeeRefScriptCostPerByte = itemDI != null ? toRationalNumber(itemDI) : null;
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

    private Tuple<BigDecimal, BigDecimal> getExUnitPrices(List<DataItem> exunits) {
        RationalNumber memPriceRN = (RationalNumber) exunits.get(0);
        RationalNumber stepPriceRN = (RationalNumber) exunits.get(1);

        BigDecimal memPrice = toRationalNumber(memPriceRN);
        BigDecimal stepPrice = toRationalNumber(stepPriceRN);

        return new Tuple<>(memPrice, stepPrice);
    }

    public Committee deserializeCommitteeResult(List<DataItem> committeeDIList) {
        var committeeMapDI = (Map) committeeDIList.get(0);
        java.util.Map<Credential, Long> committeeColdCredentialEpoch = new LinkedHashMap<>();

        for (DataItem di :  committeeMapDI.getKeys()) {
            var key = (Array) di;
            int credType = toInt(key.getDataItems().get(0));
            String credentialHash = HexUtil.encodeHexString(((ByteString)key.getDataItems().get(1)).getBytes());
            Credential credential = Credential.builder()
                    .type(credType == 1 ? StakeCredType.SCRIPTHASH: StakeCredType.ADDR_KEYHASH)
                    .hash(credentialHash)
                    .build();
            var expiredEpochDI = committeeMapDI.get(key);
            committeeColdCredentialEpoch.put(credential, toLong(expiredEpochDI));
        }

        var committeeThresholdDI = (RationalNumber) committeeDIList.get(1);
        BigDecimal committeeThreshold = toRationalNumber(committeeThresholdDI);

        return Committee.builder()
                .committeeColdCredentialEpoch(committeeColdCredentialEpoch)
                .threshold(committeeThreshold)
                .build();
    }

    public List<GovActionId> deserializeGovActionIdListResult(DataItem govActionsDI) {
        List<GovActionId> govActionIds = new ArrayList<>();

        Array govActionsArray = (Array) govActionsDI;
        for (DataItem item : govActionsArray.getDataItems()) {
            govActionIds.add(deserializeGovActionIdResult(item));
        }

        return govActionIds;
    }

    public GovActionId deserializeGovActionIdResult(DataItem govActionId) {
        Array govActionIdDI = (Array) govActionId;

        if (govActionIdDI.getDataItems().isEmpty()) {
            return null;
        }

        return GovActionId.builder()
                .transactionId(HexUtil.encodeHexString(((ByteString) govActionIdDI.getDataItems().get(0)).getBytes()))
                .gov_action_index(toInt(govActionIdDI.getDataItems().get(1)))
                .build();
    }

    public Constitution deserializeConstitutionResult(DataItem constitutionDI) {
        Anchor anchor = AnchorSerializer.INSTANCE.deserializeDI(constitutionDI);
        return Constitution.builder().anchor(anchor).build();
    }

    public Proposal deserializeProposalResult(DataItem proposalDI) {
        Array proposalArray = (Array) proposalDI;
        GovActionId govActionId = deserializeGovActionIdResult(proposalArray.getDataItems().get(0));

        var proposalProcedureDI = (Array) proposalArray.getDataItems().get(4);

        ProposalProcedure proposalProcedure = ProposalProcedure.builder()
                .anchor(AnchorSerializer.INSTANCE.deserializeDI(proposalProcedureDI.getDataItems().get(3)))
                .rewardAccount(HexUtil.encodeHexString(((ByteString) proposalProcedureDI.getDataItems().get(1)).getBytes()))
                // TODO: 'govAction' field
                .deposit(toBigInteger(proposalProcedureDI.getDataItems().get(0)))
                .build();

        // committee votes
        java.util.Map<Credential, Vote> committeeVotes = new HashMap<>();
        var committeeVotesDI = (Map) proposalArray.getDataItems().get(1);

        for (DataItem key : committeeVotesDI.getKeys()) {
            var credentialDI = (Array) key;
            int credType = toInt(credentialDI.getDataItems().get(0));
            String credHash = HexUtil.encodeHexString(((ByteString) credentialDI.getDataItems().get(1)).getBytes());
            var voteDI = committeeVotesDI.get(credentialDI);
            Vote vote = Vote.values()[toInt(voteDI)];
            committeeVotes.put(Credential.builder()
                    .type(credType == 0 ? StakeCredType.ADDR_KEYHASH : StakeCredType.SCRIPTHASH)
                    .hash(credHash)
                    .build(), vote);
        }

        // dRep votes
        java.util.Map<Drep, Vote> dRepVotes = new HashMap<>();
        var dRepVotesDI = (Map) proposalArray.getDataItems().get(2);

        for (DataItem key : dRepVotesDI.getKeys()) {
            var credentialDI = (Array) key;
            int credType = toInt(credentialDI.getDataItems().get(0));
            String credHash = HexUtil.encodeHexString(((ByteString)credentialDI.getDataItems().get(1)).getBytes());
            var voteDI = dRepVotesDI.get(credentialDI);
            Vote vote = Vote.values()[toInt(voteDI)];
            dRepVotes.put(credType == 0 ? Drep.addrKeyHash(credHash) : Drep.scriptHash(credHash), vote);
        }

        // stake pool votes
        java.util.Map<StakePoolId, Vote> stakePoolVotes = new HashMap<>();
        var stakePoolVotesDI = (Map) proposalArray.getDataItems().get(3);
        for (DataItem key : stakePoolVotesDI.getKeys()) {
            String poolHash = HexUtil.encodeHexString(((ByteString)key).getBytes());
            var voteDI = stakePoolVotesDI.get(key);
            Vote vote = Vote.values()[toInt(voteDI)];
            stakePoolVotes.put(StakePoolId.builder().poolKeyHash(poolHash).build(), vote);
        }

        // expiredAfter
        Integer expiredAfter = toInt(proposalArray.getDataItems().get(6));
        // proposedIn
        Integer proposedIn = toInt(proposalArray.getDataItems().get(5));

        return Proposal.builder()
                .govActionId(govActionId)
                .dRepVotes(dRepVotes)
                .stakePoolVotes(stakePoolVotes)
                .proposalProcedure(proposalProcedure)
                .expiredAfter(expiredAfter)
                .proposedIn(proposedIn)
                .build();
    }

}
