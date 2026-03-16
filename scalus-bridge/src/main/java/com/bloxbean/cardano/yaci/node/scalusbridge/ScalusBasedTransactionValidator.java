package com.bloxbean.cardano.yaci.node.scalusbridge;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.yaci.node.ledgerrules.TransactionValidator;
import com.bloxbean.cardano.yaci.node.ledgerrules.ValidationError;
import com.bloxbean.cardano.yaci.node.ledgerrules.ValidationResult;
import scalus.bloxbean.ScriptSupplier;
import scalus.cardano.ledger.SlotConfig;

import java.util.Set;

/**
 * {@link TransactionValidator} implementation using Scalus for full Cardano ledger rule validation.
 * Delegates to {@link LedgerBridge} which calls Scalus's CardanoMutator.transit().
 */
public class ScalusBasedTransactionValidator implements TransactionValidator {

    private final ProtocolParams protocolParams;
    private final ScriptSupplier scriptSupplier;
    private final SlotConfig scalusSlotConfig;
    private final int networkId;

    public ScalusBasedTransactionValidator(ProtocolParams protocolParams,
                                           com.bloxbean.cardano.client.api.ScriptSupplier scriptSupplier,
                                           com.bloxbean.cardano.client.common.model.SlotConfig slotConfig,
                                           int networkId) {
        this.protocolParams = protocolParams;
        if (scriptSupplier != null)
            this.scriptSupplier = new ScalusScriptSupplier(scriptSupplier);
        else
            this.scriptSupplier = null;
        this.networkId = networkId;

        this.scalusSlotConfig = new scalus.cardano.ledger.SlotConfig(
                slotConfig.getZeroTime(),
                slotConfig.getZeroSlot(),
                slotConfig.getSlotLength());
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
            
            TransitResult result = LedgerBridge.validate(
                    txCbor, protocolParams, inputUtxos, currentSlot, 
                    scalusSlotConfig, networkId, scriptSupplier);

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
