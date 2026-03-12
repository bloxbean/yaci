package com.bloxbean.cardano.yaci.node.api.validation.app;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;

/**
 * Pluggable validator for app-layer messages.
 * <p>
 * Implementations define application-specific business rules for validating data
 * before inclusion in an app block. Examples:
 * <ul>
 *   <li>Oracle: verify data against external API within tolerance</li>
 *   <li>DPP: verify document hash against issuer registry</li>
 *   <li>Audit log: verify sender authorization and schema compliance</li>
 * </ul>
 * <p>
 * The default implementation accepts all messages.
 */
public interface AppDataValidator {

    /**
     * Validate an app message for inclusion in an app block.
     *
     * @param message the message to validate
     * @param context validation context (topic, block number, etc.)
     * @return validation result (accepted or rejected with reason)
     */
    AppValidationResult validate(AppMessage message, AppValidationContext context);
}
