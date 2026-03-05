package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.yaci.node.ledgerrules.ValidationError;
import com.bloxbean.cardano.yaci.node.ledgerrules.ValidationResult;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when a submitted transaction fails ledger rule validation.
 * Carries structured error details that can be consumed without CCL dependencies.
 */
public class TransactionValidationException extends RuntimeException {

    /**
     * Simple error record that carries validation error details without CCL type dependencies.
     */
    public record Error(String rule, String message, String phase) {}

    private final List<Error> errors;

    public TransactionValidationException(ValidationResult validationResult) {
        super(buildMessage(validationResult));
        this.errors = validationResult.errors() != null
                ? validationResult.errors().stream()
                    .map(e -> new Error(
                            e.rule(),
                            e.message(),
                            e.phase() != null ? e.phase().name() : null))
                    .collect(Collectors.toList())
                : List.of();
    }

    public List<Error> getErrors() {
        return errors;
    }

    private static String buildMessage(ValidationResult result) {
        return "Transaction validation failed: " + result.firstErrorMessage("unknown error");
    }
}
