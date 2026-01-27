package com.bloxbean.cardano.yaci.core.network;

import lombok.*;

/**
 * Configuration for NodeClient connection behavior.
 * This class controls reconnection policies, retry strategies, and logging preferences.
 *
 * <p>Use the builder pattern to create instances:</p>
 * <pre>{@code
 * NodeClientConfig config = NodeClientConfig.builder()
 *     .autoReconnect(false)
 *     .maxRetryAttempts(3)
 *     .build();
 * }</pre>
 *
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode
@ToString
@Builder(toBuilder = true)
public class NodeClientConfig {

    /**
     * Whether to automatically reconnect when connection is lost.
     * Default: true (maintains backward compatibility for long-running processes like indexers)
     * Set to false for short-lived connections (e.g., peer discovery)
     */
    @Builder.Default
    private final boolean autoReconnect = true;

    /**
     * Initial delay in milliseconds between retry attempts during connection establishment.
     * Default: 8000ms (8 seconds)
     */
    @Builder.Default
    private final int initialRetryDelayMs = 8000;

    /**
     * Maximum number of retry attempts before giving up.
     * Default: Integer.MAX_VALUE (unlimited retries for backward compatibility)
     */
    @Builder.Default
    private final int maxRetryAttempts = Integer.MAX_VALUE;

    /**
     * Whether to enable connection-related logging (connect, disconnect, reconnect messages).
     * Default: true
     */
    @Builder.Default
    private final boolean enableConnectionLogging = true;

    /**
     * Connection timeout in milliseconds for establishing TCP connections.
     * This configures Netty's CONNECT_TIMEOUT_MILLIS option.
     * Default: 30000ms (30 seconds)
     *
     * For short-lived connections like peer discovery, consider using a lower value (e.g., 10000ms).
     * For long-running connections, the default is appropriate.
     */
    @Builder.Default
    private final int connectionTimeoutMs = 30000;

    /**
     * Creates a default configuration with backward-compatible settings.
     * This is equivalent to calling {@code NodeClientConfig.builder().build()}
     *
     * @return a new NodeClientConfig with default values
     */
    public static NodeClientConfig defaultConfig() {
        return NodeClientConfig.builder().build();
    }
}
