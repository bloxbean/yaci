package com.bloxbean.cardano.yaci.node.runtime.validation.app;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.node.api.validation.app.AppDataValidator;
import com.bloxbean.cardano.yaci.node.api.validation.app.AppValidationContext;
import com.bloxbean.cardano.yaci.node.api.validation.app.AppValidationResult;

/**
 * Default validator that accepts all messages.
 * Applications should provide their own implementation.
 */
public class DefaultAppDataValidator implements AppDataValidator {

    @Override
    public AppValidationResult validate(AppMessage message, AppValidationContext context) {
        return AppValidationResult.ACCEPTED;
    }
}
