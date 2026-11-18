package com.bloxbean.cardano.yaci.core.model;

import com.bloxbean.cardano.yaci.core.types.NonNegativeInterval;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.core.util.Tuple;
import com.bloxbean.cardano.client.transaction.spec.NilOrValueJsonDeserializer;
import com.bloxbean.cardano.client.transaction.spec.NilOrValueJsonSerializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;

@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
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

    //Dijkstra era fields: null when absent
    /**
     * The maximum size of reference scripts in a block, in bytes (key 34).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long maxRefScriptSizePerBlock; //34
    /**
     * The maximum size of reference scripts in a transaction, in bytes (key 35).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long maxRefScriptSizePerTx; //35
    /**
     * The size of each step of the reference script fee, in bytes (key 36).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long refScriptCostStride; //36
    /**
     * The multiplier of the reference script fee for each step (key 37).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private NonNegativeInterval refScriptCostMultiplier; //37
    /**
     * The maximum pledge leverage (key 38): null when absent, empty when set to nil (no maximum), as the ledger's
     * {@code StrictMaybe NonNegativeInterval} inside an update.
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonSerialize(using = NilOrValueJsonSerializer.class)
    @JsonDeserialize(using = NilOrValueJsonDeserializer.class)
    private Optional<NonNegativeInterval> maxPledgeLeverage; //38
    /**
     * The minimum pool margin (key 39).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private UnitInterval minPoolMargin; //39
    /**
     * The Leios announcement period, in milliseconds (key 40).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long leiosAnnouncementPeriodLength; //40
    /**
     * The Leios vote period, in milliseconds (key 41).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long leiosVotePeriodLength; //41
    /**
     * The Leios diffusion period, in milliseconds (key 42).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long leiosDiffusionPeriodLength; //42
    /**
     * The Leios committee size (key 43).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer leiosCommitteeSize; //43
    /**
     * The Leios quorum stake threshold (key 44).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private UnitInterval leiosQuorumStakeThreshold; //44
    /**
     * The maximum size of an endorser block's references, in bytes (key 45).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long maxEndorserBlockReferencesSize; //45
    /**
     * The maximum size of an endorser block's transactions, in bytes (key 46).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long maxEndorserBlockTxsSize; //46
    /**
     * The memory of the maximum execution units of an endorser block (key 47).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigInteger maxEndorserBlockExMem; //47
    /**
     * The steps of the maximum execution units of an endorser block (key 47).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigInteger maxEndorserBlockExSteps; //47
    /**
     * The maximum size of reference scripts in an endorser block, in bytes (key 48).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long maxRefScriptSizePerEndorserBlock; //48
    /**
     * The minimum age of a Peras candidate block, in slots (key 49).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long perasMinCandidateBlockAge; //49
    /**
     * The Peras healing factor (key 50).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private NonNegativeInterval perasHealingFactor; //50
    /**
     * The Peras certificate boost (key 51).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer perasCertBoost; //51
    /**
     * The Peras target committee size (key 52).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer perasTargetCommitteeSize; //52
    /**
     * The Peras bootstrap round (key 53): null when absent, empty when set to nil, as the ledger's
     * {@code StrictMaybe Word32} inside an update.
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonSerialize(using = NilOrValueJsonSerializer.class)
    @JsonDeserialize(using = NilOrValueJsonDeserializer.class)
    private Optional<Long> perasBootstrapRound; //53
    /**
     * The Peras quorum threshold safety margin (key 54).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private UnitInterval perasQuorumThresholdSafetyMargin; //54
    /**
     * The cost of each multi-asset policy of reference inputs (key 55).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigInteger refInputsCostPerMultiAssetPolicy; //55
    /**
     * The cost of each datum byte of reference inputs (key 56).
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigInteger refInputsCostPerDatumByte; //56

    /** Kept for source and binary compatibility: the Dijkstra fields are null. Use the builder for them. */
    public ProtocolParamUpdate(Integer minFeeA, Integer minFeeB, Integer maxBlockSize, Integer maxTxSize,
                               Integer maxBlockHeaderSize, BigInteger keyDeposit, BigInteger poolDeposit,
                               Integer maxEpoch, Integer nOpt, NonNegativeInterval poolPledgeInfluence,
                               UnitInterval expansionRate, UnitInterval treasuryGrowthRate,
                               UnitInterval decentralisationParam, Tuple<Integer, String> extraEntropy,
                               Integer protocolMajorVer, Integer protocolMinorVer, BigInteger minUtxo,
                               BigInteger minPoolCost, BigInteger adaPerUtxoByte, Map<Integer, String> costModels,
                               String costModelsHash, NonNegativeInterval priceMem, NonNegativeInterval priceStep,
                               BigInteger maxTxExMem, BigInteger maxTxExSteps, BigInteger maxBlockExMem,
                               BigInteger maxBlockExSteps, Long maxValSize, Integer collateralPercent,
                               Integer maxCollateralInputs, PoolVotingThresholds poolVotingThresholds,
                               DrepVoteThresholds drepVotingThresholds, Integer committeeMinSize,
                               Integer committeeMaxTermLength, Integer govActionLifetime, BigInteger govActionDeposit,
                               BigInteger drepDeposit, Integer drepActivity,
                               NonNegativeInterval minFeeRefScriptCostPerByte) {
        this(minFeeA, minFeeB, maxBlockSize, maxTxSize, maxBlockHeaderSize, keyDeposit, poolDeposit, maxEpoch, nOpt,
                poolPledgeInfluence, expansionRate, treasuryGrowthRate, decentralisationParam, extraEntropy,
                protocolMajorVer, protocolMinorVer, minUtxo, minPoolCost, adaPerUtxoByte, costModels, costModelsHash,
                priceMem, priceStep, maxTxExMem, maxTxExSteps, maxBlockExMem, maxBlockExSteps, maxValSize,
                collateralPercent, maxCollateralInputs, poolVotingThresholds, drepVotingThresholds, committeeMinSize,
                committeeMaxTermLength, govActionLifetime, govActionDeposit, drepDeposit, drepActivity,
                minFeeRefScriptCostPerByte, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
