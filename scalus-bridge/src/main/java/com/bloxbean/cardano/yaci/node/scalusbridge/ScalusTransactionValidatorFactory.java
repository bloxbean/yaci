package com.bloxbean.cardano.yaci.node.scalusbridge;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.yaci.node.ledgerrules.TransactionValidator;

/**
 * Pure Java factory that hides Scala-compiled types from consumers.
 * Accepts only Java types (CCL {@link SlotConfig}) and returns {@link TransactionValidator}.
 */
public class ScalusTransactionValidatorFactory {

    public static TransactionValidator create(ProtocolParams pp, SlotConfig slotConfig, int networkId) {
        SlotConfigHandle handle = SlotConfigBridge.custom(
                slotConfig.getZeroTime(), slotConfig.getZeroSlot(), slotConfig.getSlotLength());
        return new ScalusBasedTransactionValidator(pp, handle, networkId);
    }
}
