package com.bloxbean.cardano.yaci.node.ledgerstate.governance.model;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.GovAction;

import java.math.BigInteger;

/**
 * Stored governance action proposal state in RocksDB (prefix 0x60).
 * <p>
 * The {@code govAction} field holds the fully parsed governance action object
 * (e.g., {@code TreasuryWithdrawalsAction}, {@code ParameterChangeAction}).
 * Core models are already parsed by yaci's CBOR deserializer — no additional parsing needed.
 *
 * @param deposit           Proposal deposit amount (lovelace)
 * @param returnAddress     Reward account hex for deposit refund
 * @param proposedInEpoch   Epoch when the proposal was submitted
 * @param expiresAfterEpoch Epoch after which the proposal expires (proposedInEpoch + govActionLifetime)
 * @param actionType        Type of governance action
 * @param prevActionTxHash  Previous action tx hash in the chain (null if none)
 * @param prevActionIndex   Previous action index (null if none)
 * @param govAction         The parsed governance action (concrete type per actionType)
 * @param proposalSlot      Slot when the proposal was submitted (for ordering)
 */
public record GovActionRecord(
        BigInteger deposit,
        String returnAddress,
        int proposedInEpoch,
        int expiresAfterEpoch,
        GovActionType actionType,
        String prevActionTxHash,
        Integer prevActionIndex,
        GovAction govAction,
        long proposalSlot
) {}
