package com.bloxbean.cardano.yaci.node.bootstrap.providers;

import com.bloxbean.cardano.yaci.node.api.bootstrap.BootstrapBlockInfo;
import com.bloxbean.cardano.yaci.node.api.bootstrap.BootstrapUtxo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link BlockfrostBootstrapProvider} against the live preprod API.
 *
 * <p>These tests require a Blockfrost project ID. Set the environment variable
 * {@code BF_PROJECT_ID} before running:
 * <pre>
 *   export BF_PROJECT_ID=preprodYourProjectIdHere
 *   ./gradlew :node-bootstrap-providers:integrationTest --tests "BlockfrostBootstrapProviderIT"
 * </pre>
 *
 * <p>If {@code BF_PROJECT_ID} is not set, all tests are automatically skipped.
 */
@EnabledIfEnvironmentVariable(named = "BF_PROJECT_ID", matches = ".+")
class BlockfrostBootstrapProviderIT {
    private static final Logger log = LoggerFactory.getLogger(BlockfrostBootstrapProviderIT.class);

    private static final String ADDRESS =
            "addr_test1qryvgass5dsrf2kxl3vgfz76uhp83kv5lagzcp29tcana68ca5aqa6swlq6llfamln09tal7n5kvt4275ckwedpt4v7q48uhex";
    private static final String STAKE_ADDRESS =
            "stake_test1up86nkhqymgqmf0536q82wm6x9g3x53qhjggch0jka3wdkq5v3fa0";

    private static BlockfrostBootstrapProvider provider;

    @BeforeAll
    static void setUp() {
        String projectId = System.getenv("BF_PROJECT_ID");
        provider = BlockfrostBootstrapProvider.forNetwork("preprod", projectId);
        log.info("Using Blockfrost preprod with project ID: {}...", projectId.substring(0, Math.min(8, projectId.length())));
    }

    @Test
    void testGetLatestBlock() {
        BootstrapBlockInfo latest = provider.getLatestBlock();
        log.info("Latest block: number={}, slot={}, hash={}", latest.blockNumber(), latest.slot(), latest.blockHash());

        assertThat(latest.blockNumber()).isGreaterThan(0);
        assertThat(latest.slot()).isGreaterThan(0);
        assertThat(latest.blockHash()).isNotNull().isNotEmpty();
    }

    @Test
    void testGetBlocks() {
        BootstrapBlockInfo latest = provider.getLatestBlock();
        long from = latest.blockNumber() - 5;
        long to = latest.blockNumber() - 3;

        List<BootstrapBlockInfo> blocks = provider.getBlocks(from, to);
        log.info("Fetched {} blocks from {} to {}", blocks.size(), from, to);
        blocks.forEach(b -> log.info("  block: number={}, slot={}, hash={}", b.blockNumber(), b.slot(), b.blockHash()));

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).blockNumber()).isEqualTo(from);
        assertThat(blocks.get(1).blockNumber()).isEqualTo(from + 1);
        assertThat(blocks.get(2).blockNumber()).isEqualTo(to);
        blocks.forEach(b -> assertThat(b.blockHash()).isNotNull().isNotEmpty());
    }

    @Test
    void testGetUtxosByAddress() {
        List<BootstrapUtxo> utxos = provider.getUtxosByAddress(ADDRESS);
        log.info("UTXOs by address: count={}", utxos.size());
        utxos.forEach(u -> log.info("  utxo: {}#{} lovelace={} assets={}", u.txHash(), u.outputIndex(), u.lovelace(), u.assets().size()));

        assertThat(utxos).isNotEmpty();
        utxos.forEach(u -> {
            assertThat(u.txHash()).isNotNull().isNotEmpty();
            assertThat(u.outputIndex()).isGreaterThanOrEqualTo(0);
            assertThat(u.address()).isEqualTo(ADDRESS);
            assertThat(u.lovelace()).isPositive();
        });
    }

    @Test
    void testGetUtxosByStakeAddress() {
        List<BootstrapUtxo> utxos = provider.getUtxosByStakeAddress(STAKE_ADDRESS);
        log.info("UTXOs by stake address: count={}", utxos.size());
        utxos.forEach(u -> log.info("  utxo: {}#{} addr={} lovelace={}", u.txHash(), u.outputIndex(), u.address(), u.lovelace()));

        assertThat(utxos).isNotEmpty();
        utxos.forEach(u -> {
            assertThat(u.txHash()).isNotNull().isNotEmpty();
            assertThat(u.outputIndex()).isGreaterThanOrEqualTo(0);
            assertThat(u.address()).isNotNull().isNotEmpty();
            assertThat(u.lovelace()).isPositive();
        });
    }

    @Test
    void testGetUtxo() {
        // First get a known UTXO from the address
        List<BootstrapUtxo> utxos = provider.getUtxosByAddress(ADDRESS);
        assertThat(utxos).isNotEmpty();

        BootstrapUtxo known = utxos.get(0);
        log.info("Looking up specific UTXO: {}#{}", known.txHash(), known.outputIndex());

        BootstrapUtxo fetched = provider.getUtxo(known.txHash(), known.outputIndex());

        assertThat(fetched).isNotNull();
        assertThat(fetched.txHash()).isEqualTo(known.txHash());
        assertThat(fetched.outputIndex()).isEqualTo(known.outputIndex());
        assertThat(fetched.address()).isNotNull().isNotEmpty();
        assertThat(fetched.lovelace()).isPositive();
        log.info("Fetched UTXO: addr={} lovelace={}", fetched.address(), fetched.lovelace());
    }
}
