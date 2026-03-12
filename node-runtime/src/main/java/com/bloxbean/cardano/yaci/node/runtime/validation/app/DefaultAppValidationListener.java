package com.bloxbean.cardano.yaci.node.runtime.validation.app;

import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yaci.node.api.events.AppDataValidateEvent;
import com.bloxbean.cardano.yaci.node.api.validation.app.AppDataValidator;
import com.bloxbean.cardano.yaci.node.api.validation.app.AppValidationContext;
import com.bloxbean.cardano.yaci.node.api.validation.app.AppValidationResult;
import lombok.extern.slf4j.Slf4j;

/**
 * Default listener for AppDataValidateEvent.
 * Delegates to the configured AppDataValidator implementation.
 */
@Slf4j
public class DefaultAppValidationListener {

    private final AppDataValidator validator;

    public DefaultAppValidationListener(AppDataValidator validator) {
        this.validator = validator;
    }

    @DomainEventListener(order = 100)
    public void onAppDataValidate(AppDataValidateEvent event) {
        if (event.isRejected()) {
            log.debug("App data validation already rejected, skipping");
            return;
        }

        AppValidationContext ctx = AppValidationContext.builder()
                .topicId(event.topicId())
                .currentBlockNumber(event.currentBlockNumber())
                .timestamp(System.currentTimeMillis())
                .build();

        AppValidationResult result = validator.validate(event.message(), ctx);
        if (!result.valid()) {
            event.reject("app-data-validator", result.reason());
            log.debug("App message {} rejected: {}", event.message().getMessageIdHex(), result.reason());
        }
    }
}
