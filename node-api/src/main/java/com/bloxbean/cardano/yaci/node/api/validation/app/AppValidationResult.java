package com.bloxbean.cardano.yaci.node.api.validation.app;

/**
 * Result of validating an app message.
 */
public record AppValidationResult(boolean valid, String reason) {

    public static final AppValidationResult ACCEPTED = new AppValidationResult(true, null);

    public static AppValidationResult rejected(String reason) {
        return new AppValidationResult(false, reason);
    }
}
