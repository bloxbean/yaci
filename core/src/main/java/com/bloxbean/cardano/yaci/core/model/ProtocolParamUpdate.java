package com.bloxbean.cardano.yaci.core.model;

import com.bloxbean.cardano.yaci.core.types.NonNegativeInterval;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.core.util.Tuple;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class ProtocolParamUpdate {
    private Integer minFeeA; //0
    private Integer minFeeB; //1
    private Integer maxBlockSize; //2
    private Integer maxTxSize; //3
    private Integer maxBlockHeaderSize; //4
    private BigInteger keyDeposit; //5
    private BigInteger poolDeposit; //6
    private Integer maxEpoch; //7
    @JsonProperty("nopt")
    private Integer nOpt; //8
    private NonNegativeInterval poolPledgeInfluence; //rational //9
    private UnitInterval expansionRate; //unit interval //10
    private UnitInterval treasuryGrowthRate; //11
    private UnitInterval decentralisationParam; //12
    private Tuple<Integer, String> extraEntropy; //13
    private Integer protocolMajorVer; //14
    private Integer protocolMinorVer; //14
    private BigInteger minUtxo; //TODO //15

    private BigInteger minPoolCost; //16
    private BigInteger adaPerUtxoByte; //17
    //private String nonce;

    //Alonzo changes
    private Map<Integer, String> costModels; //18
    private String costModelsHash; //derived field

    //ex_unit_prices
    private NonNegativeInterval priceMem; //19
    private NonNegativeInterval priceStep; //19

    //max tx ex units
    private BigInteger maxTxExMem; //20
    private BigInteger maxTxExSteps; //20

    //max block ex units
    private BigInteger maxBlockExMem; //21
    private BigInteger maxBlockExSteps; //21

    private Long maxValSize; //22

    private Integer collateralPercent; //23
    private Integer maxCollateralInputs; //24

//    //Cost per UTxO word for Alonzo.
//    //Cost per UTxO byte for Babbage and later.
//    private String coinsPerUtxoSize;
//    @Deprecated
//    private String coinsPerUtxoWord;

    //Conway era fields
    private PoolVotingThresholds poolVotingThresholds; //25
    private DrepVoteThresholds drepVotingThresholds; //26
    private Integer committeeMinSize; //27
    private Integer committeeMaxTermLength; //28
    private Integer govActionLifetime; //29
    private BigInteger govActionDeposit; //30
    private BigInteger drepDeposit; //31
    private Integer drepActivity; //32
    private NonNegativeInterval minFeeRefScriptCostPerByte; //33

    //Dijkstra era fields
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long maxRefScriptSizePerBlock; //34
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long maxRefScriptSizePerTx; //35
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long refScriptCostStride; //36
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private NonNegativeInterval refScriptCostMultiplier; //37
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private NonNegativeInterval maxPledgeLeverage; //38, null when absent or nil
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean maxPledgeLeverageNoLimit; //38, true when the update sets nil (no limit)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private UnitInterval minPoolMargin; //39

    //Leios fields (Dijkstra era)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long leiosAnnouncementPeriodMs; //40
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long leiosVotePeriodMs; //41
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long leiosDiffusionPeriodMs; //42
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer leiosCommitteeSize; //43
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private UnitInterval leiosQuorumStakeThreshold; //44
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long maxEndorserBlockRefsSize; //45
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long maxEndorserBlockTxsSize; //46
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigInteger maxEndorserBlockExMem; //47
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigInteger maxEndorserBlockExSteps; //47
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long maxRefScriptSizePerEndorserBlock; //48

    /**
     * Pre-Dijkstra constructor, kept so existing callers compile and behave unchanged.
     */
    public ProtocolParamUpdate(Integer minFeeA,
                               Integer minFeeB,
                               Integer maxBlockSize,
                               Integer maxTxSize,
                               Integer maxBlockHeaderSize,
                               BigInteger keyDeposit,
                               BigInteger poolDeposit,
                               Integer maxEpoch,
                               Integer nOpt,
                               NonNegativeInterval poolPledgeInfluence,
                               UnitInterval expansionRate,
                               UnitInterval treasuryGrowthRate,
                               UnitInterval decentralisationParam,
                               Tuple<Integer, String> extraEntropy,
                               Integer protocolMajorVer,
                               Integer protocolMinorVer,
                               BigInteger minUtxo,
                               BigInteger minPoolCost,
                               BigInteger adaPerUtxoByte,
                               Map<Integer, String> costModels,
                               String costModelsHash,
                               NonNegativeInterval priceMem,
                               NonNegativeInterval priceStep,
                               BigInteger maxTxExMem,
                               BigInteger maxTxExSteps,
                               BigInteger maxBlockExMem,
                               BigInteger maxBlockExSteps,
                               Long maxValSize,
                               Integer collateralPercent,
                               Integer maxCollateralInputs,
                               PoolVotingThresholds poolVotingThresholds,
                               DrepVoteThresholds drepVotingThresholds,
                               Integer committeeMinSize,
                               Integer committeeMaxTermLength,
                               Integer govActionLifetime,
                               BigInteger govActionDeposit,
                               BigInteger drepDeposit,
                               Integer drepActivity,
                               NonNegativeInterval minFeeRefScriptCostPerByte) {
        this(minFeeA, minFeeB, maxBlockSize, maxTxSize, maxBlockHeaderSize, keyDeposit, poolDeposit, maxEpoch, nOpt, poolPledgeInfluence, expansionRate, treasuryGrowthRate, decentralisationParam, extraEntropy, protocolMajorVer, protocolMinorVer, minUtxo, minPoolCost, adaPerUtxoByte, costModels, costModelsHash, priceMem, priceStep, maxTxExMem, maxTxExSteps, maxBlockExMem, maxBlockExSteps, maxValSize, collateralPercent, maxCollateralInputs, poolVotingThresholds, drepVotingThresholds, committeeMinSize, committeeMaxTermLength, govActionLifetime, govActionDeposit, drepDeposit, drepActivity, minFeeRefScriptCostPerByte,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
