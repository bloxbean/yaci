package com.bloxbean.cardano.yaci.node.scalusbridge;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.yaci.node.ledgerrules.TransactionValidator;
import com.bloxbean.cardano.yaci.node.ledgerrules.ValidationError;
import com.bloxbean.cardano.yaci.node.ledgerrules.ValidationResult;

import java.util.Set;

/**
 * {@link TransactionValidator} implementation using Scalus for full Cardano ledger rule validation.
 * Delegates to {@link LedgerBridge} which calls Scalus's CardanoMutator.transit().
 */
public class ScalusBasedTransactionValidator implements TransactionValidator {

    private final ProtocolParams protocolParams;
    private final SlotConfigHandle slotConfig;
    private final int networkId;

    public ScalusBasedTransactionValidator(ProtocolParams protocolParams,
                                           SlotConfigHandle slotConfig,
                                           int networkId) {
        this.protocolParams = protocolParams;
        this.slotConfig = slotConfig;
        this.networkId = networkId;
    }

    @Override
    public ValidationResult validate(byte[] txCbor, Set<Utxo> inputUtxos) {
        try {
            // Extract currentSlot from tx validity interval
            long currentSlot = 0;
            try {
                Transaction tx = Transaction.deserialize(txCbor);
                if (tx.getBody().getValidityStartInterval() > 0) {
                    currentSlot = tx.getBody().getValidityStartInterval();
                }
            } catch (Exception e) {
                // If we can't deserialize to extract slot, use 0 and let Scalus handle it
            }

            SlotConfigHandle sc = slotConfig != null ? slotConfig : SlotConfigBridge.preview();

            TransitResult result = LedgerBridge.validate(
                    txCbor, protocolParams, inputUtxos, currentSlot, sc, networkId);

            if (!result.isSuccess()) {
                ValidationError error = mapError(result);
                return ValidationResult.failure(error);
            }

            return ValidationResult.success();

        } catch (Exception e) {
            return ValidationResult.failure(new ValidationError(
                    "InternalError",
                    "Validation error: " + e.getMessage(),
                    ValidationError.Phase.PHASE_1));
        }
    }

    private ValidationError mapError(TransitResult result) {
        String className = result.errorClassName() != null ? result.errorClassName() : "Unknown";
        String message = result.errorMessage();

        ValidationError.Phase phase = className.contains("PlutusScript") || className.contains("Script")
                ? ValidationError.Phase.PHASE_2
                : ValidationError.Phase.PHASE_1;

        String rule = className.replace("Exception", "").replace("$", "");

        return new ValidationError(rule, message, phase);
    }
}
