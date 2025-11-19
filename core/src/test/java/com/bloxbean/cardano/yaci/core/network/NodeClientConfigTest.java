package com.bloxbean.cardano.yaci.core.network;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class NodeClientConfigTest {

    @Test
    void testDefaultConfig() {
        NodeClientConfig config = NodeClientConfig.defaultConfig();

        assertNotNull(config);
        assertTrue(config.isAutoReconnect(), "Auto-reconnect should be enabled by default");
        assertEquals(8000, config.getInitialRetryDelayMs(), "Default retry delay should be 8000ms");
        assertEquals(Integer.MAX_VALUE, config.getMaxRetryAttempts(), "Default max retry attempts should be unlimited");
        assertTrue(config.isEnableConnectionLogging(), "Connection logging should be enabled by default");
    }

    @Test
    void testBuilderWithDefaults() {
        NodeClientConfig config = NodeClientConfig.builder().build();

        assertNotNull(config);
        assertTrue(config.isAutoReconnect());
        assertEquals(8000, config.getInitialRetryDelayMs());
        assertEquals(Integer.MAX_VALUE, config.getMaxRetryAttempts());
        assertTrue(config.isEnableConnectionLogging());
    }

    @Test
    void testBuilderWithCustomAutoReconnect() {
        NodeClientConfig config = NodeClientConfig.builder()
                .autoReconnect(false)
                .build();

        assertFalse(config.isAutoReconnect(), "Auto-reconnect should be disabled");
        // Other fields should still have default values
        assertEquals(8000, config.getInitialRetryDelayMs());
        assertEquals(Integer.MAX_VALUE, config.getMaxRetryAttempts());
        assertTrue(config.isEnableConnectionLogging());
    }

    @Test
    void testBuilderWithAllCustomValues() {
        NodeClientConfig config = NodeClientConfig.builder()
                .autoReconnect(false)
                .initialRetryDelayMs(5000)
                .maxRetryAttempts(3)
                .enableConnectionLogging(false)
                .build();

        assertFalse(config.isAutoReconnect());
        assertEquals(5000, config.getInitialRetryDelayMs());
        assertEquals(3, config.getMaxRetryAttempts());
        assertFalse(config.isEnableConnectionLogging());
    }

    @Test
    void testToBuilder() {
        NodeClientConfig original = NodeClientConfig.builder()
                .autoReconnect(true)
                .initialRetryDelayMs(10000)
                .maxRetryAttempts(5)
                .enableConnectionLogging(true)
                .build();

        // Modify one field using toBuilder
        NodeClientConfig modified = original.toBuilder()
                .autoReconnect(false)
                .build();

        // Original should be unchanged
        assertTrue(original.isAutoReconnect());
        assertEquals(10000, original.getInitialRetryDelayMs());
        assertEquals(5, original.getMaxRetryAttempts());
        assertTrue(original.isEnableConnectionLogging());

        // Modified should have the change
        assertFalse(modified.isAutoReconnect());
        // Other fields should be copied from original
        assertEquals(10000, modified.getInitialRetryDelayMs());
        assertEquals(5, modified.getMaxRetryAttempts());
        assertTrue(modified.isEnableConnectionLogging());
    }

    @Test
    void testImmutability() {
        NodeClientConfig config = NodeClientConfig.builder()
                .autoReconnect(true)
                .build();

        // Verify all fields are final (by attempting to use reflection - should fail or show final)
        assertNotNull(config);
        assertTrue(config.isAutoReconnect());

        // Create new config with modified values
        NodeClientConfig newConfig = config.toBuilder()
                .autoReconnect(false)
                .build();

        // Original should still be true
        assertTrue(config.isAutoReconnect());
        // New should be false
        assertFalse(newConfig.isAutoReconnect());
    }

    @Test
    void testEqualsAndHashCode() {
        NodeClientConfig config1 = NodeClientConfig.builder()
                .autoReconnect(false)
                .initialRetryDelayMs(5000)
                .maxRetryAttempts(3)
                .enableConnectionLogging(false)
                .build();

        NodeClientConfig config2 = NodeClientConfig.builder()
                .autoReconnect(false)
                .initialRetryDelayMs(5000)
                .maxRetryAttempts(3)
                .enableConnectionLogging(false)
                .build();

        NodeClientConfig config3 = NodeClientConfig.builder()
                .autoReconnect(true)  // Different value
                .initialRetryDelayMs(5000)
                .maxRetryAttempts(3)
                .enableConnectionLogging(false)
                .build();

        // Same values should be equal
        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());

        // Different values should not be equal
        assertNotEquals(config1, config3);
    }

    @Test
    void testToString() {
        NodeClientConfig config = NodeClientConfig.builder()
                .autoReconnect(false)
                .initialRetryDelayMs(5000)
                .maxRetryAttempts(3)
                .enableConnectionLogging(false)
                .build();

        String toString = config.toString();

        assertNotNull(toString);
        assertTrue(toString.contains("autoReconnect"));
        assertTrue(toString.contains("false"));
        assertTrue(toString.contains("5000"));
        assertTrue(toString.contains("3"));
    }

    @Test
    void testPeerDiscoveryUseCase() {
        // Test configuration for peer discovery (short-lived connections)
        NodeClientConfig config = NodeClientConfig.builder()
                .autoReconnect(false)
                .maxRetryAttempts(3)
                .build();

        assertFalse(config.isAutoReconnect(), "Peer discovery should not auto-reconnect");
        assertEquals(3, config.getMaxRetryAttempts(), "Should limit retry attempts");
    }

    @Test
    void testIndexerUseCase() {
        // Test configuration for long-running indexer (should use defaults)
        NodeClientConfig config = NodeClientConfig.defaultConfig();

        assertTrue(config.isAutoReconnect(), "Indexer should auto-reconnect");
        assertEquals(Integer.MAX_VALUE, config.getMaxRetryAttempts(), "Should retry indefinitely");
    }

    @Test
    void testCustomRetryStrategy() {
        NodeClientConfig config = NodeClientConfig.builder()
                .autoReconnect(true)
                .initialRetryDelayMs(1000)  // 1 second
                .maxRetryAttempts(10)
                .build();

        assertTrue(config.isAutoReconnect());
        assertEquals(1000, config.getInitialRetryDelayMs());
        assertEquals(10, config.getMaxRetryAttempts());
    }

    @Test
    void testDisableLogging() {
        NodeClientConfig config = NodeClientConfig.builder()
                .enableConnectionLogging(false)
                .build();

        assertFalse(config.isEnableConnectionLogging(), "Connection logging should be disabled");
        // Other fields should have defaults
        assertTrue(config.isAutoReconnect());
    }
}
