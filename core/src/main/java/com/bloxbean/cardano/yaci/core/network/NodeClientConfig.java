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
     * Maximum number of retry cycles before giving up.
     * In DNS_ROTATING mode, one cycle tries every resolved socket address candidate once.
     * In STANDARD mode, one cycle has a single JVM-selected socket address candidate.
     * This is used only when autoReconnect is enabled. If autoReconnect is false,
     * startup fails after the first failed cycle.
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
     * Whether startup failures should be propagated to the caller.
     * Default: false to preserve historical behavior where start() logs startup errors.
     * Set to true for supervised applications that need to fail over to another peer.
     */
    @Builder.Default
    private final boolean propagateStartupFailure = false;

    /**
     * Controls how TCP socket addresses are resolved for connection attempts.
     * Default: STANDARD, which creates a fresh InetSocketAddress for each attempt
     * and lets the JVM choose the address.
     *
     * DNS_ROTATING uses the JVM InetAddress resolver and is subject to the JVM DNS cache TTL.
     * Operators that need faster DNS refresh should set networkaddress.cache.ttl or sun.net.inetaddr.ttl.
     */
    @Builder.Default
    private final SocketAddressResolutionMode socketAddressResolutionMode = SocketAddressResolutionMode.STANDARD;

    /**
     * Controls address-family filtering and preference for DNS_ROTATING mode.
     * Default: ANY, which keeps all resolved addresses.
     */
    @Builder.Default
    private final SocketAddressFamily socketAddressFamily = SocketAddressFamily.ANY;

    /**
     * Optional local host/interface used when binding outbound TCP connections before connect().
     * Blank or null means wildcard address. This is ignored when localBindPort is not positive.
     */
    private final String localBindHost;

    /**
     * Optional local source port used when binding outbound TCP connections before connect().
     * Default: 0, which lets the operating system choose an ephemeral source port.
     */
    @Builder.Default
    private final int localBindPort = 0;

    /**
     * When true, a local-bind failure falls back to a normal connect using an
     * OS-assigned source port. This is useful for relay source-port reuse,
     * where TIME_WAIT or platform socket rules must not prevent availability.
     */
    @Builder.Default
    private final boolean localBindFallbackToEphemeral = false;

    /**
     * @return true when outbound connects should bind to a local source port.
     */
    public boolean hasLocalBindAddress() {
        return localBindPort > 0;
    }

    /**
     * Creates a default configuration.
     * This is equivalent to calling {@code NodeClientConfig.builder().build()}
     *
     * @return a new NodeClientConfig with default values
     */
    public static NodeClientConfig defaultConfig() {
        return NodeClientConfig.builder().build();
    }
}
