package com.bloxbean.cardano.yaci.node.api.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class NodeStatusTest {

    @Test
    void calculateSyncProgress_shouldReturnNullWhenNoTipData() {
        NodeStatus status = NodeStatus.builder()
                .running(true)
                .syncing(true)
                .build();
        
        assertThat(status.calculateSyncProgress()).isNull();
    }

    @Test
    void calculateSyncProgress_shouldReturn100WhenLocalTipIsAheadOrEqual() {
        NodeStatus status = NodeStatus.builder()
                .running(true)
                .syncing(true)
                .localTipSlot(1000L)
                .remoteTipSlot(1000L)
                .build();
        
        assertThat(status.calculateSyncProgress()).isEqualTo(100.0);
        
        status = NodeStatus.builder()
                .running(true)
                .syncing(true)
                .localTipSlot(1001L)
                .remoteTipSlot(1000L)
                .build();
        
        assertThat(status.calculateSyncProgress()).isEqualTo(100.0);
    }

    @Test
    void calculateSyncProgress_shouldCalculateCorrectPercentage() {
        NodeStatus status = NodeStatus.builder()
                .running(true)
                .syncing(true)
                .localTipSlot(500L)
                .remoteTipSlot(1000L)
                .build();
        
        assertThat(status.calculateSyncProgress()).isEqualTo(50.0);
        
        status = NodeStatus.builder()
                .running(true)
                .syncing(true)
                .localTipSlot(250L)
                .remoteTipSlot(1000L)
                .build();
        
        assertThat(status.calculateSyncProgress()).isEqualTo(25.0);
    }

    @Test
    void calculateSyncProgress_shouldReturnExistingSyncProgressWhenNoTips() {
        NodeStatus status = NodeStatus.builder()
                .running(true)
                .syncing(true)
                .syncProgress(75.5)
                .build();
        
        assertThat(status.calculateSyncProgress()).isEqualTo(75.5);
    }

    @Test
    void isInSync_shouldReturnTrueWhenWithin10Slots() {
        NodeStatus status = NodeStatus.builder()
                .running(true)
                .syncing(true)
                .localTipSlot(1000L)
                .remoteTipSlot(1005L)
                .build();
        
        assertThat(status.isInSync()).isTrue();
        
        status = NodeStatus.builder()
                .running(true)
                .syncing(true)
                .localTipSlot(1000L)
                .remoteTipSlot(995L)
                .build();
        
        assertThat(status.isInSync()).isTrue();
        
        status = NodeStatus.builder()
                .running(true)
                .syncing(true)
                .localTipSlot(1000L)
                .remoteTipSlot(1000L)
                .build();
        
        assertThat(status.isInSync()).isTrue();
    }

    @Test
    void isInSync_shouldReturnFalseWhenMoreThan10SlotsApart() {
        NodeStatus status = NodeStatus.builder()
                .running(true)
                .syncing(true)
                .localTipSlot(1000L)
                .remoteTipSlot(1020L)
                .build();
        
        assertThat(status.isInSync()).isFalse();
        
        status = NodeStatus.builder()
                .running(true)
                .syncing(true)
                .localTipSlot(1000L)
                .remoteTipSlot(980L)
                .build();
        
        assertThat(status.isInSync()).isFalse();
    }

    @Test
    void isInSync_shouldReturnFalseWhenNoTipData() {
        NodeStatus status = NodeStatus.builder()
                .running(true)
                .syncing(true)
                .build();
        
        assertThat(status.isInSync()).isFalse();
    }

    @Test
    void getStatusSummary_shouldReturnStoppedWhenNotRunning() {
        NodeStatus status = NodeStatus.builder()
                .running(false)
                .syncing(false)
                .build();
        
        assertThat(status.getStatusSummary()).isEqualTo("Stopped");
    }

    @Test
    void getStatusSummary_shouldReturnServerOnlyWhenNotSyncing() {
        NodeStatus status = NodeStatus.builder()
                .running(true)
                .syncing(false)
                .serverRunning(true)
                .build();
        
        assertThat(status.getStatusSummary()).isEqualTo("Running (Server Only)");
        
        status = NodeStatus.builder()
                .running(true)
                .syncing(false)
                .serverRunning(false)
                .build();
        
        assertThat(status.getStatusSummary()).isEqualTo("Running (Idle)");
    }

    @Test
    void getStatusSummary_shouldReturnSyncingWithProgress() {
        NodeStatus status = NodeStatus.builder()
                .running(true)
                .syncing(true)
                .localTipSlot(500L)
                .remoteTipSlot(1000L)
                .build();
        
        assertThat(status.getStatusSummary()).isEqualTo("Syncing (50.0%)");
    }

    @Test
    void getStatusSummary_shouldReturnSyncingWhenNoProgress() {
        NodeStatus status = NodeStatus.builder()
                .running(true)
                .syncing(true)
                .build();
        
        assertThat(status.getStatusSummary()).isEqualTo("Syncing");
    }

    @Test
    void builder_shouldCreateStatusWithAllFields() {
        long now = System.currentTimeMillis();
        
        NodeStatus status = NodeStatus.builder()
                .running(true)
                .syncing(true)
                .serverRunning(true)
                .blocksProcessed(12345L)
                .lastProcessedSlot(98765L)
                .localTipSlot(1000L)
                .localTipBlockNumber(500L)
                .remoteTipSlot(1100L)
                .remoteTipBlockNumber(550L)
                .syncProgress(90.9)
                .initialSyncComplete(false)
                .syncMode("pipelined")
                .statusMessage("All good")
                .timestamp(now)
                .build();
        
        assertThat(status.isRunning()).isTrue();
        assertThat(status.isSyncing()).isTrue();
        assertThat(status.isServerRunning()).isTrue();
        assertThat(status.getBlocksProcessed()).isEqualTo(12345L);
        assertThat(status.getLastProcessedSlot()).isEqualTo(98765L);
        assertThat(status.getLocalTipSlot()).isEqualTo(1000L);
        assertThat(status.getLocalTipBlockNumber()).isEqualTo(500L);
        assertThat(status.getRemoteTipSlot()).isEqualTo(1100L);
        assertThat(status.getRemoteTipBlockNumber()).isEqualTo(550L);
        assertThat(status.getSyncProgress()).isEqualTo(90.9);
        assertThat(status.isInitialSyncComplete()).isFalse();
        assertThat(status.getSyncMode()).isEqualTo("pipelined");
        assertThat(status.getStatusMessage()).isEqualTo("All good");
        assertThat(status.getTimestamp()).isEqualTo(now);
    }
}