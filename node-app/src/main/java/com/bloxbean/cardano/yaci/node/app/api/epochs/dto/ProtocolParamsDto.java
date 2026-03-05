package com.bloxbean.cardano.yaci.node.app.api.epochs.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

/**
 * Protocol parameters DTO matching Yaci Store's response format.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProtocolParamsDto(
        int epoch,
        @JsonProperty("min_fee_a") int minFeeA,
        @JsonProperty("min_fee_b") int minFeeB,
        @JsonProperty("max_block_size") int maxBlockSize,
        @JsonProperty("max_tx_size") int maxTxSize,
        @JsonProperty("max_block_header_size") int maxBlockHeaderSize,
        @JsonProperty("key_deposit") String keyDeposit,
        @JsonProperty("pool_deposit") String poolDeposit,
        BigDecimal a0,
        BigDecimal rho,
        BigDecimal tau,
        @JsonProperty("decentralisation_param") BigDecimal decentralisationParam,
        @JsonProperty("protocol_major_ver") int protocolMajorVer,
        @JsonProperty("protocol_minor_ver") int protocolMinorVer,
        @JsonProperty("min_utxo") String minUtxo,
        @JsonProperty("min_pool_cost") String minPoolCost,
        String nonce,
        @JsonProperty("cost_models") Map<String, Map<String, Long>> costModels,
        @JsonProperty("price_mem") BigDecimal priceMem,
        @JsonProperty("price_step") BigDecimal priceStep,
        @JsonProperty("max_tx_ex_mem") String maxTxExMem,
        @JsonProperty("max_tx_ex_steps") String maxTxExSteps,
        @JsonProperty("max_block_ex_mem") String maxBlockExMem,
        @JsonProperty("max_block_ex_steps") String maxBlockExSteps,
        @JsonProperty("max_val_size") String maxValSize,
        @JsonProperty("collateral_percent") int collateralPercent,
        @JsonProperty("max_collateral_inputs") int maxCollateralInputs,
        @JsonProperty("coins_per_utxo_size") String coinsPerUtxoSize,
        // Conway governance parameters
        @JsonProperty("pvt_motion_no_confidence") BigDecimal pvtMotionNoConfidence,
        @JsonProperty("pvt_committee_normal") BigDecimal pvtCommitteeNormal,
        @JsonProperty("pvt_committee_no_confidence") BigDecimal pvtCommitteeNoConfidence,
        @JsonProperty("pvt_hard_fork_initiation") BigDecimal pvtHardForkInitiation,
        @JsonProperty("dvt_motion_no_confidence") BigDecimal dvtMotionNoConfidence,
        @JsonProperty("dvt_committee_normal") BigDecimal dvtCommitteeNormal,
        @JsonProperty("dvt_committee_no_confidence") BigDecimal dvtCommitteeNoConfidence,
        @JsonProperty("dvt_update_to_constitution") BigDecimal dvtUpdateToConstitution,
        @JsonProperty("dvt_hard_fork_initiation") BigDecimal dvtHardForkInitiation,
        @JsonProperty("dvt_treasury_withdrawal") BigDecimal dvtTreasuryWithdrawal,
        @JsonProperty("committee_min_size") int committeeMinSize,
        @JsonProperty("committee_max_term_length") int committeeMaxTermLength,
        @JsonProperty("gov_action_lifetime") int govActionLifetime,
        @JsonProperty("gov_action_deposit") BigInteger govActionDeposit,
        @JsonProperty("drep_deposit") BigInteger drepDeposit,
        @JsonProperty("drep_activity") int drepActivity,
        @JsonProperty("min_fee_ref_script_cost_per_byte") BigDecimal minFeeRefScriptCostPerByte,
        @JsonProperty("e_max") int eMax,
        @JsonProperty("n_opt") int nOpt,
        @JsonProperty("pvt_p_p_security_group") BigDecimal pvtPPSecurityGroup,
        @JsonProperty("dvt_p_p_network_group") BigDecimal dvtPPNetworkGroup,
        @JsonProperty("dvt_p_p_economic_group") BigDecimal dvtPPEconomicGroup,
        @JsonProperty("dvt_p_p_technical_group") BigDecimal dvtPPTechnicalGroup,
        @JsonProperty("dvt_p_p_gov_group") BigDecimal dvtPPGovGroup
) {}
