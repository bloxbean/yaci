package com.bloxbean.cardano.yaci.node.runtime.chain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for era start slot tracking in DirectRocksDBChainState.
 */
class DirectRocksDBChainStateEraTest {

    @TempDir
    Path tempDir;

    private DirectRocksDBChainState chainState;

    @BeforeEach
    void setUp() {
        chainState = new DirectRocksDBChainState(tempDir.resolve("testdb").toString());
    }

    @AfterEach
    void tearDown() {
        if (chainState != null) {
            chainState.close();
        }
    }

    @Test
    void setAndGetEraStartSlot() {
        chainState.setEraStartSlot(2, 4492800L);
        OptionalLong result = chainState.getEraStartSlot(2);
        assertThat(result).isPresent();
        assertThat(result.getAsLong()).isEqualTo(4492800L);
    }

    @Test
    void getEraStartSlot_nonExistent_returnsEmpty() {
        OptionalLong result = chainState.getEraStartSlot(99);
        assertThat(result).isEmpty();
    }

    @Test
    void setEraStartSlot_idempotent() {
        chainState.setEraStartSlot(2, 4492800L);
        // Setting again with different value should be a no-op (keeps original)
        chainState.setEraStartSlot(2, 9999L);
        OptionalLong result = chainState.getEraStartSlot(2);
        assertThat(result).isPresent();
        assertThat(result.getAsLong()).isEqualTo(4492800L);
    }

    @Test
    void getFirstNonByronEraStartSlot_singleEra() {
        chainState.setEraStartSlot(2, 4492800L);
        OptionalLong result = chainState.getFirstNonByronEraStartSlot();
        assertThat(result).isPresent();
        assertThat(result.getAsLong()).isEqualTo(4492800L);
    }

    @Test
    void getFirstNonByronEraStartSlot_multipleEras_returnsSmallest() {
        // Mainnet era start slots
        chainState.setEraStartSlot(2, 4492800L);   // Shelley
        chainState.setEraStartSlot(3, 16588800L);   // Allegra
        chainState.setEraStartSlot(4, 23068800L);   // Mary
        chainState.setEraStartSlot(5, 39916975L);   // Alonzo
        chainState.setEraStartSlot(6, 72316896L);   // Babbage
        chainState.setEraStartSlot(7, 133660855L);  // Conway

        OptionalLong result = chainState.getFirstNonByronEraStartSlot();
        assertThat(result).isPresent();
        assertThat(result.getAsLong()).isEqualTo(4492800L);
    }

    @Test
    void getFirstNonByronEraStartSlot_noEras_returnsEmpty() {
        OptionalLong result = chainState.getFirstNonByronEraStartSlot();
        assertThat(result).isEmpty();
    }

    @Test
    void getFirstNonByronEraStartSlot_devnet_slotZero() {
        // Devnet: Conway starts at slot 0
        chainState.setEraStartSlot(7, 0L);
        OptionalLong result = chainState.getFirstNonByronEraStartSlot();
        assertThat(result).isPresent();
        assertThat(result.getAsLong()).isEqualTo(0L);
    }
}
