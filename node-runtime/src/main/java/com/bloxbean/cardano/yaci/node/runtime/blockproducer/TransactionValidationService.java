package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yaci.node.ledgerrules.TransactionValidator;
import com.bloxbean.cardano.yaci.node.ledgerrules.ValidationError;
import com.bloxbean.cardano.yaci.node.ledgerrules.ValidationResult;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Wraps a {@link TransactionValidator} with UTXO resolution from {@link UtxoState}.
 * Validates transactions against Cardano ledger rules (Phase 1 structural + Phase 2 script execution).
 */
@Slf4j
public class TransactionValidationService {

    private final TransactionValidator validator;
    private final UtxoState utxoState;

    public TransactionValidationService(TransactionValidator validator, UtxoState utxoState) {
        this.validator = validator;
        this.utxoState = utxoState;
    }

    /**
     * Mempool admission: resolves UTXOs from persistent UtxoState.
     */
    public ValidationResult validate(byte[] txCbor) {
        return validate(txCbor, this::resolveFromUtxoState);
    }

    /**
     * Block production: custom resolver with spent-tracking overlay.
     */
    public ValidationResult validate(byte[] txCbor, Function<Outpoint, Utxo> resolver) {
        // Deserialize to extract input references for UTXO resolution
        Transaction transaction;
        try {
            transaction = Transaction.deserialize(txCbor);
        } catch (Exception e) {
            log.debug("Failed to deserialize transaction CBOR: {}", e.getMessage());
            return ValidationResult.failure(new ValidationError(
                    "CborDeserialization",
                    "Failed to deserialize transaction: " + e.getMessage(),
                    ValidationError.Phase.PHASE_1));
        }

        // Collect all inputs that need resolution: regular + reference + collateral
        Set<Utxo> inputUtxos = new HashSet<>();
        List<TransactionInput> allInputs = new java.util.ArrayList<>();

        if (transaction.getBody().getInputs() != null) {
            allInputs.addAll(transaction.getBody().getInputs());
        }
        if (transaction.getBody().getReferenceInputs() != null) {
            allInputs.addAll(transaction.getBody().getReferenceInputs());
        }
        if (transaction.getBody().getCollateral() != null) {
            allInputs.addAll(transaction.getBody().getCollateral());
        }

        for (TransactionInput input : allInputs) {
            Outpoint op = new Outpoint(input.getTransactionId(), input.getIndex());
            Utxo resolved = resolver.apply(op);
            if (resolved == null) {
                return ValidationResult.failure(new ValidationError(
                        "UtxoNotFound",
                        "UTXO not found: " + op.txHash() + "#" + op.index(),
                        ValidationError.Phase.PHASE_1));
            }
            inputUtxos.add(resolved);
        }

        try {
            return validator.validate(txCbor, inputUtxos);
        } catch (Exception e) {
            log.debug("Validation threw exception: {}", e.getMessage());
            return ValidationResult.failure(new ValidationError(
                    "ValidationException",
                    "Validation error: " + e.getMessage(),
                    ValidationError.Phase.PHASE_1));
        }
    }

    private Utxo resolveFromUtxoState(Outpoint outpoint) {
        return utxoState.getUtxo(outpoint)
                .map(UtxoMapper::toCclUtxo)
                .orElse(null);
    }
}
