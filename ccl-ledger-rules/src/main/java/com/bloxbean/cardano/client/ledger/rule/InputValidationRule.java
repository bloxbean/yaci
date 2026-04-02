package com.bloxbean.cardano.client.ledger.rule;

import com.bloxbean.cardano.client.api.model.ValidationError;
import com.bloxbean.cardano.client.ledger.LedgerContext;
import com.bloxbean.cardano.client.ledger.slice.UtxoSlice;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import java.util.*;
import java.util.stream.Collectors;

public class InputValidationRule implements LedgerRule {

    private static final String RULE_NAME = "InputValidation";

    @Override
    public List<ValidationError> validate(LedgerContext context, Transaction transaction) {
        List<ValidationError> errors = new ArrayList<>();
        TransactionBody body = transaction.getBody();
        UtxoSlice utxoSlice = context.getUtxoSlice();

        List<TransactionInput> inputs = body.getInputs();

        if (inputs == null || inputs.isEmpty()) {
            errors.add(error("Transaction has no inputs"));
            return errors;
        }

        Set<TransactionInput> inputSet = new HashSet<>(inputs);
        if (inputSet.size() < inputs.size()) {
            errors.add(error("Transaction has duplicate spending inputs"));
        }

        if (utxoSlice != null) {
            for (TransactionInput input : inputs) {
                if (utxoSlice.lookup(input).isEmpty()) {
                    errors.add(error("Spending input not found in UTxO set: " + input.getTransactionId() + "#" + input.getIndex()));
                }
            }
        }

        List<TransactionInput> refInputs = body.getReferenceInputs();
        if (refInputs != null && !refInputs.isEmpty()) {
            Set<TransactionInput> refInputSet = new HashSet<>(refInputs);
            if (refInputSet.size() < refInputs.size()) {
                errors.add(error("Transaction has duplicate reference inputs"));
            }

            Set<TransactionInput> overlap = refInputSet.stream()
                    .filter(inputSet::contains)
                    .collect(Collectors.toSet());
            if (!overlap.isEmpty()) {
                errors.add(error("Reference inputs overlap with spending inputs: "
                        + overlap.stream()
                            .map(i -> i.getTransactionId() + "#" + i.getIndex())
                            .collect(Collectors.joining(", "))));
            }

            if (utxoSlice != null) {
                for (TransactionInput refInput : refInputs) {
                    if (utxoSlice.lookup(refInput).isEmpty()) {
                        errors.add(error("Reference input not found in UTxO set: " + refInput.getTransactionId() + "#" + refInput.getIndex()));
                    }
                }
            }
        }

        return errors;
    }

    private ValidationError error(String message) {
        return ValidationError.builder()
                .rule(RULE_NAME)
                .message(message)
                .phase(ValidationError.Phase.PHASE_1)
                .build();
    }
}
