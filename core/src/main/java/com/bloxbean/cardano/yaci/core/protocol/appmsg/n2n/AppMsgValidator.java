package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Validation hook invoked by {@link AppMsgSubmissionServerAgent} for every inbound
 * message body, after the structural checks (message-id recompute, size, TTL, chain)
 * have passed. The embedding node plugs authentication (signature verification,
 * membership checks) and admission policy in here — the transport itself is
 * crypto-agnostic beyond content-id derivation.
 */
@FunctionalInterface
public interface AppMsgValidator {

    AppMsgValidator ACCEPT_ALL = message -> Result.accept();

    Result validate(AppMessage message);

    /**
     * Validates the structurally valid messages of one reply, in one call (ADR 0019 D5). An implementation may
     * validate them in parallel; the result list must have the same length and order as {@code messages}, otherwise
     * the server rejects every message of the reply.
     */
    default List<Result> validateAll(List<AppMessage> messages) {
        List<Result> results = new ArrayList<>(messages.size());
        for (AppMessage message : messages) {
            Result result;
            try {
                result = validate(message);
            } catch (RuntimeException e) {
                result = Result.reject("validator failed: " + e);
            }
            results.add(result);
        }
        return results;
    }

    final class Result {
        private static final Result ACCEPTED = new Result(true, null);

        private final boolean accepted;
        private final String reason;

        private Result(boolean accepted, String reason) {
            this.accepted = accepted;
            this.reason = reason;
        }

        public static Result accept() {
            return ACCEPTED;
        }

        public static Result reject(String reason) {
            return new Result(false, reason);
        }

        public boolean isAccepted() {
            return accepted;
        }

        public String getReason() {
            return reason;
        }
    }
}
