package com.bloxbean.cardano.yaci.node.ledgerrules;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import java.util.Set;

/**
 * Validates a transaction against Cardano ledger rules.
 * Implementations provide concrete validation using libraries like Scalus.
 */
public interface TransactionValidator {

    /**
     * Validate a transaction against Cardano ledger rules.
     *
     * @param txCbor     raw CBOR bytes of the transaction
     * @param inputUtxos pre-resolved input UTxOs (regular + reference + collateral)
     * @return validation result indicating success or structured errors
     */
    ValidationResult validate(byte[] txCbor, Set<Utxo> inputUtxos);

    /**
     * Validate using a CCL Transaction object (convenience overload).
     *
     * @param transaction CCL Transaction object
     * @param inputUtxos  pre-resolved input UTxOs
     * @return validation result
     */
    default ValidationResult validate(Transaction transaction, Set<Utxo> inputUtxos) {
        try {
            return validate(transaction.serialize(), inputUtxos);
        } catch (Exception e) {
            return ValidationResult.failure(new ValidationError(
                    "Validator Error",
                    "Failed to validate transaction: " + e.getMessage(),
                    ValidationError.Phase.PHASE_1));
        }
    }
}
