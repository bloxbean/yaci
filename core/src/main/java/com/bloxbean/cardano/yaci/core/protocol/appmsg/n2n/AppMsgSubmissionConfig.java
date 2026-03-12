package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Builder
@Slf4j
public class AppMsgSubmissionConfig {

    @Builder.Default
    private final int batchSize = 10;

    @Builder.Default
    private final boolean useBlockingMode = true;

    @Builder.Default
    private final int maxMessageSize = 65536; // 64KB max per message

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
    }

    @Override
    public String toString() {
        return String.format("AppMsgSubmissionConfig{batchSize=%d, blockingMode=%s, maxMessageSize=%d}",
                batchSize, useBlockingMode, maxMessageSize);
    }
}
