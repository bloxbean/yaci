package com.bloxbean.cardano.yaci.node.scalusbridge;

import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.yaci.node.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yaci.node.ledgerrules.TransactionEvaluator;
import com.bloxbean.cardano.yaci.node.ledgerrules.TransactionValidator;

/**
 * Pure Java factory that hides Scala-compiled types from consumers.
 * Accepts only Java types (CCL {@link SlotConfig}) and returns {@link TransactionValidator}.
 */
public class ScalusTransactionFactory {

    public static TransactionValidator createValidator(ProtocolParams pp, ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId) {
        return new ScalusBasedTransactionValidator(pp, scriptSupplier, slotConfig, networkId);
    }

    public static TransactionValidator createValidator(ProtocolParams pp, ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId,
                                                       LedgerStateProvider ledgerStateProvider) {
        return new ScalusBasedTransactionValidator(pp, scriptSupplier, slotConfig, networkId, ledgerStateProvider);
    }

    public static TransactionEvaluator createEvaluator(ProtocolParams pp, ScriptSupplier scriptSupplier,
                                                       SlotConfig slotConfig, int networkId) {
        return new ScalusBasedTransactionEvaluator(pp, scriptSupplier, slotConfig, networkId);
    }
}
