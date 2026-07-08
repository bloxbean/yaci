package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

@Data
@Builder
@Slf4j
public class AppMsgSubmissionConfig {

    @Builder.Default
    private final int batchSize = 10;

    @Builder.Default
    private final boolean useBlockingMode = true;

    /** Max body size in bytes; enforced on enqueue (client) and receive (server). */
    @Builder.Default
    private final int maxMessageSize = 65536; // 64KB max per message

    @Builder.Default
    private final int processedMessageIdCacheSize = 10000;

    /** Chain-ids this node participates in; exchanged via MsgInit/MsgInitAck. */
    @Builder.Default
    private final Set<String> chainIds = Set.of();

    /** Max allowed time-to-live; a message whose expiresAt exceeds now + maxTtl is rejected. */
    @Builder.Default
    private final long maxTtlSeconds = 3600;

    /**
     * Validation hook for inbound messages (auth/membership/admission), applied by the
     * server agent after structural checks. Defaults to accept-all.
     */
    @Builder.Default
    private final AppMsgValidator validator = AppMsgValidator.ACCEPT_ALL;

    public static AppMsgSubmissionConfig createDefault() {
        return AppMsgSubmissionConfig.builder().build();
    }

    public static AppMsgSubmissionConfig withBatchSize(int batchSize) {
        return AppMsgSubmissionConfig.builder().batchSize(batchSize).build();
    }

    public static AppMsgSubmissionConfig createTestConfig() {
        return AppMsgSubmissionConfig.builder().batchSize(3).build();
    }

    public void validate() {
        if (batchSize <= 0)
            log.warn("AppMsgSubmissionConfig: batchSize must be positive, got: {}", batchSize);
        if (batchSize > 10)
            log.warn("AppMsgSubmissionConfig: batchSize ({}) exceeds recommended limit of 10", batchSize);
        if (processedMessageIdCacheSize <= 0)
            log.warn("AppMsgSubmissionConfig: processedMessageIdCacheSize must be positive, got: {}",
                    processedMessageIdCacheSize);
        if (maxMessageSize <= 0)
            log.warn("AppMsgSubmissionConfig: maxMessageSize must be positive, got: {}", maxMessageSize);
    }

    @Override
    public String toString() {
        return String.format("AppMsgSubmissionConfig{batchSize=%d, blockingMode=%s, maxMessageSize=%d, "
                        + "processedMessageIdCacheSize=%d, chains=%s, maxTtlSeconds=%d}",
                batchSize, useBlockingMode, maxMessageSize, processedMessageIdCacheSize, chainIds, maxTtlSeconds);
    }
}
