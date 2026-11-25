package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.function.Predicate;

@Data
@Builder(toBuilder = true)
@Slf4j
public class AppMsgSubmissionConfig {

    @Builder.Default
    private final int batchSize = 10;

    /**
     * Upper bound for the message ids the server requests per round (ADR 0019 D1). The wire field is a uint16 and a
     * client never replies with more ids than requested, so any value interoperates with older peers.
     */
    @Builder.Default
    private final int maxUnacknowledged = 10;

    /**
     * Byte budget for one body request: the server asks for the longest prefix of outstanding ids whose announced
     * sizes fit, always at least one id (ADR 0019 D1).
     */
    @Builder.Default
    private final int maxBodiesBytesPerRequest = 1024 * 1024;

    /** Client capacity for ordinary messages awaiting delivery to one peer. */
    @Builder.Default
    private final int maxPendingMessages = 1000;

    /** Client capacity for priority messages awaiting delivery to one peer (ADR 0019 D3). */
    @Builder.Default
    private final int maxPendingPriorityMessages = 1000;

    /** Byte budget (sum of body sizes) for pending priority messages; a message that would exceed it is rejected. */
    @Builder.Default
    private final long maxPendingPriorityBytes = Long.MAX_VALUE;

    /**
     * Selects messages that are offered before ordinary messages and have their own capacity (ADR 0019 D3). Null
     * means no priority lane.
     */
    @Builder.Default
    private final Predicate<AppMessage> priority = null;

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
        if (maxUnacknowledged <= 0 || maxUnacknowledged > Short.MAX_VALUE)
            log.warn("AppMsgSubmissionConfig: maxUnacknowledged must be in 1..{}, got: {}", Short.MAX_VALUE,
                    maxUnacknowledged);
        if (maxBodiesBytesPerRequest <= 0)
            log.warn("AppMsgSubmissionConfig: maxBodiesBytesPerRequest must be positive, got: {}",
                    maxBodiesBytesPerRequest);
        if (maxPendingMessages <= 0 || maxPendingPriorityMessages <= 0)
            log.warn("AppMsgSubmissionConfig: pending capacities must be positive, got: {} / {}",
                    maxPendingMessages, maxPendingPriorityMessages);
        if (processedMessageIdCacheSize <= 0)
            log.warn("AppMsgSubmissionConfig: processedMessageIdCacheSize must be positive, got: {}",
                    processedMessageIdCacheSize);
        if (maxMessageSize <= 0)
            log.warn("AppMsgSubmissionConfig: maxMessageSize must be positive, got: {}", maxMessageSize);
    }

    @Override
    public String toString() {
        return String.format("AppMsgSubmissionConfig{batchSize=%d, maxUnacknowledged=%d, maxBodiesBytesPerRequest=%d, "
                        + "maxPendingMessages=%d, maxPendingPriorityMessages=%d, priority=%s, blockingMode=%s, "
                        + "maxMessageSize=%d, processedMessageIdCacheSize=%d, chains=%s, maxTtlSeconds=%d}",
                batchSize, maxUnacknowledged, maxBodiesBytesPerRequest, maxPendingMessages,
                maxPendingPriorityMessages, priority != null, useBlockingMode, maxMessageSize,
                processedMessageIdCacheSize, chainIds, maxTtlSeconds);
    }
}
