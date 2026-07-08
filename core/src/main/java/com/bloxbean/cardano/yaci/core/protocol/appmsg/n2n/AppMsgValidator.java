package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;

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
