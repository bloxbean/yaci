package com.bloxbean.cardano.yaci.node.api;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.node.api.config.NodeConfig;
import com.bloxbean.cardano.yaci.node.api.listener.NodeEventListener;
import com.bloxbean.cardano.yaci.node.api.model.NodeStatus;
import com.bloxbean.cardano.yaci.node.api.model.SnapshotInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Test for NodeAPI interface - validates interface structure and contracts
 */
class NodeAPITest {

    /**
     * Simple test implementation of NodeAPI for testing
     */
    private static class TestNodeAPI implements NodeAPI {
        private boolean running = false;
        private boolean syncing = false;
        private boolean serverRunning = false;

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
            syncing = false;
            serverRunning = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public boolean isSyncing() {
            return syncing;
        }

        @Override
        public boolean isServerRunning() {
            return serverRunning;
        }

        @Override
        public NodeStatus getStatus() {
            return NodeStatus.builder()
                    .running(running)
                    .syncing(syncing)
                    .serverRunning(serverRunning)
                    .timestamp(System.currentTimeMillis())
                    .build();
        }

        @Override
        public ChainTip getLocalTip() {
            return null; // Simple implementation
        }

        @Override
        public void addBlockChainDataListener(BlockChainDataListener listener) {
            // Simple implementation - no-op
        }

        @Override
        public void removeBlockChainDataListener(BlockChainDataListener listener) {
            // Simple implementation - no-op
        }

        @Override
        public void addNodeEventListener(NodeEventListener listener) {
            // Simple implementation - no-op
        }

        @Override
        public void removeNodeEventListener(NodeEventListener listener) {
            // Simple implementation - no-op
        }

        @Override
        public ChainState getChainState() {
            return null; // Simple implementation
        }

        @Override
        public NodeConfig getConfig() {
            return null; // Simple implementation
        }

        @Override
        public boolean recoverChainState() {
            // For test stub, assume no corruption detected
            return false;
        }

        @Override
        public String submitTransaction(byte[] txCbor) {
            return "dummy-hash";
        }

        @Override
        public void registerListeners(Object... listeners) {

        }

        @Override
        public void registerListener(Object listener, SubscriptionOptions sbOptions) {

        }

        @Override
        public com.bloxbean.cardano.yaci.node.api.utxo.UtxoState getUtxoState() {
            return null;
        }

        @Override
        public String getProtocolParameters() {
            return null;
        }

        @Override
        public com.bloxbean.cardano.yaci.node.api.model.GenesisParameters getGenesisParameters() {
            return null;
        }

        @Override
        public void rollbackTo(long targetSlot) {
            // no-op for test
        }

        @Override
        public SnapshotInfo createSnapshot(String name) {
            return new com.bloxbean.cardano.yaci.node.api.model.SnapshotInfo(name, 0, 0, System.currentTimeMillis());
        }

        @Override
        public void restoreSnapshot(String name) {
            // no-op for test
        }

        @Override
        public java.util.List<com.bloxbean.cardano.yaci.node.api.model.SnapshotInfo> listSnapshots() {
            return java.util.List.of();
        }

        @Override
        public void deleteSnapshot(String name) {
            // no-op for test
        }

        @Override
        public com.bloxbean.cardano.yaci.node.api.model.FundResult fundAddress(String address, long lovelace) {
            return new com.bloxbean.cardano.yaci.node.api.model.FundResult("dummy-hash", 0, lovelace);
        }

        @Override
        public com.bloxbean.cardano.yaci.node.api.model.TimeAdvanceResult advanceTimeBySlots(int slots) {
            return new com.bloxbean.cardano.yaci.node.api.model.TimeAdvanceResult(slots, slots, slots);
        }

        @Override
        public com.bloxbean.cardano.yaci.node.api.model.TimeAdvanceResult advanceTimeBySeconds(int seconds) {
            return new com.bloxbean.cardano.yaci.node.api.model.TimeAdvanceResult(seconds, seconds, seconds);
        }

        @Override
        public long slotToUnixTime(long slot) {
            return 0;
        }
    }

    @Test
    void nodeAPI_shouldImplementAllRequiredMethods() {
        NodeAPI nodeAPI = new TestNodeAPI();

        // Test initial state
        assertThat(nodeAPI.isRunning()).isFalse();
        assertThat(nodeAPI.isSyncing()).isFalse();
        assertThat(nodeAPI.isServerRunning()).isFalse();

        // Test lifecycle
        nodeAPI.start();
        assertThat(nodeAPI.isRunning()).isTrue();

        nodeAPI.stop();
        assertThat(nodeAPI.isRunning()).isFalse();
        assertThat(nodeAPI.isSyncing()).isFalse();
        assertThat(nodeAPI.isServerRunning()).isFalse();
    }

    @Test
    void nodeAPI_shouldProvideStatus() {
        NodeAPI nodeAPI = new TestNodeAPI();

        NodeStatus status = nodeAPI.getStatus();
        assertThat(status).isNotNull();
        assertThat(status.isRunning()).isFalse();
        assertThat(status.isSyncing()).isFalse();
        assertThat(status.isServerRunning()).isFalse();
        assertThat(status.getTimestamp()).isGreaterThan(0);

        nodeAPI.start();
        status = nodeAPI.getStatus();
        assertThat(status.isRunning()).isTrue();
    }

    @Test
    void nodeAPI_shouldSupportListenerManagement() {
        NodeAPI nodeAPI = new TestNodeAPI();

        // Create test listeners
        BlockChainDataListener blockchainListener = new BlockChainDataListener() {};
        NodeEventListener nodeListener = new NodeEventListener() {};

        // Test that listener methods can be called without errors
        assertThatCode(() -> {
            nodeAPI.addBlockChainDataListener(blockchainListener);
            nodeAPI.addNodeEventListener(nodeListener);
            nodeAPI.removeBlockChainDataListener(blockchainListener);
            nodeAPI.removeNodeEventListener(nodeListener);
        }).doesNotThrowAnyException();
    }

    @Test
    void nodeAPI_shouldProvideAccessToChainStateAndConfig() {
        NodeAPI nodeAPI = new TestNodeAPI();

        // Test that methods exist and can be called
        assertThatCode(() -> {
            ChainState chainState = nodeAPI.getChainState();
            ChainTip localTip = nodeAPI.getLocalTip();
            NodeConfig config = nodeAPI.getConfig();

            // In our test implementation, these return null, which is fine for interface testing
            assertThat(chainState).isNull();
            assertThat(localTip).isNull();
            assertThat(config).isNull();
        }).doesNotThrowAnyException();
    }
}
