package com.bloxbean.cardano.yaci.node.runtime.validation;

import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yaci.node.api.events.TransactionValidateEvent;
import com.bloxbean.cardano.yaci.node.ledgerrules.ValidationError;
import com.bloxbean.cardano.yaci.node.ledgerrules.ValidationResult;
import com.bloxbean.cardano.yaci.node.runtime.blockproducer.TransactionValidationService;
import lombok.extern.slf4j.Slf4j;

/**
 * Default transaction validator that wraps the existing {@link TransactionValidationService}.
 * <p>
 * Runs at {@code order = 100}, leaving room for pre-validation (order &lt; 100) and
 * post-validation (order &gt; 100) plugins.
 * <p>
 * Can be disabled via config flag {@code yaci.node.validation.default-validator-enabled=false},
 * in which case this listener is simply not registered with the event bus.
 */
@Slf4j
public class DefaultTransactionValidatorListener {

    private final TransactionValidationService validationService;

    public DefaultTransactionValidatorListener(TransactionValidationService validationService) {
        this.validationService = validationService;
    }

    @DomainEventListener(order = 100)
    public void onTransactionValidate(TransactionValidateEvent event) {
        // Short-circuit: if already rejected by an earlier listener, skip expensive validation
        if (event.isRejected()) {
            log.debug("Tx {} already rejected, skipping default validation", event.txHash());
            return;
        }

        ValidationResult result = validationService.validate(event.txCbor());
        if (!result.valid()) {
            for (ValidationError err : result.errors()) {
                event.reject(err.rule(), err.message());
            }
            log.debug("Tx {} rejected by default validator: {}", event.txHash(),
                    result.firstErrorMessage("unknown validation error"));
        }
    }
}
