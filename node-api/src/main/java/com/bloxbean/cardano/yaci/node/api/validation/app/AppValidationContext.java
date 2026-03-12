package com.bloxbean.cardano.yaci.node.api.validation.app;

import lombok.Builder;
import lombok.Getter;

/**
 * Context provided to validators during app message validation.
 */
@Getter
@Builder
public class AppValidationContext {
    private final String topicId;
    private final long currentBlockNumber;
    private final long timestamp;
}
