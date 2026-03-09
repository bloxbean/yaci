package com.bloxbean.cardano.yaci.node.app.api.genesis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Blockfrost-compatible genesis info DTO.
 */
public record GenesisDto(
        @JsonProperty("active_slots_coefficient") double activeSlotsCoefficient,
        @JsonProperty("update_quorum") long updateQuorum,
        @JsonProperty("max_lovelace_supply") String maxLovelaceSupply,
        @JsonProperty("network_magic") long networkMagic,
        @JsonProperty("epoch_length") long epochLength,
        @JsonProperty("system_start") String systemStart,
        @JsonProperty("slots_per_kes_period") long slotsPerKesPeriod,
        @JsonProperty("slot_length") int slotLength,
        @JsonProperty("max_kes_evolutions") long maxKesEvolutions,
        @JsonProperty("security_param") long securityParam
) {}
