package com.bloxbean.cardano.yaci.core.network.server;

import com.bloxbean.cardano.yaci.core.protocol.Agent;

/**
 * Factory for creating additional protocol agents per server session.
 * Used to inject app-layer agents alongside the existing L1 agents.
 * The channel is set automatically after creation.
 */
@FunctionalInterface
public interface AgentFactory {
    Agent<?> createAgent();
}
