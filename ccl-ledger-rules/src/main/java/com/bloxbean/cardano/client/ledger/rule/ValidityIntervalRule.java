package com.bloxbean.cardano.client.ledger.rule;

import com.bloxbean.cardano.client.api.model.ValidationError;
import com.bloxbean.cardano.client.ledger.LedgerContext;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;

import java.util.ArrayList;
import java.util.List;

public class ValidityIntervalRule implements LedgerRule {

    private static final String RULE_NAME = "ValidityInterval";

    @Override
    public List<ValidationError> validate(LedgerContext context, Transaction transaction) {
        List<ValidationError> errors = new ArrayList<>();
        TransactionBody body = transaction.getBody();
        long currentSlot = context.getCurrentSlot();

        long validityStart = body.getValidityStartInterval();
        if (validityStart > 0 && currentSlot < validityStart) {
            errors.add(error("Current slot " + currentSlot
                    + " is before validity start interval " + validityStart));
        }

        long ttl = body.getTtl();
        if (ttl > 0 && currentSlot >= ttl) {
            errors.add(error("Current slot " + currentSlot
                    + " is at or past TTL " + ttl));
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
