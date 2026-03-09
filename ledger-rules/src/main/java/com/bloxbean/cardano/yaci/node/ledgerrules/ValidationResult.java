package com.bloxbean.cardano.yaci.node.ledgerrules;

import java.util.List;

/**
 * Result of transaction validation against Cardano ledger rules.
 *
 * @param valid  true if the transaction passed all validation rules
 * @param errors list of validation errors (empty if valid)
 */
public record ValidationResult(boolean valid, List<ValidationError> errors) {

    public static ValidationResult success() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult failure(ValidationError error) {
        return new ValidationResult(false, List.of(error));
    }

    public static ValidationResult failure(List<ValidationError> errors) {
        return new ValidationResult(false, errors);
    }

    /**
     * Returns the message from the first error, or the given default if no errors are present.
     */
    public String firstErrorMessage(String defaultMessage) {
        return (errors != null && !errors.isEmpty()) ? errors.get(0).message() : defaultMessage;
    }
}
