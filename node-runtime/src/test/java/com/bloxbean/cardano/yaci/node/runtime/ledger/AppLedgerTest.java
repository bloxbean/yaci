package com.bloxbean.cardano.yaci.node.runtime.ledger;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.node.api.consensus.ConsensusMode;
import com.bloxbean.cardano.yaci.node.api.consensus.ConsensusProof;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import com.bloxbean.cardano.yaci.node.api.ledger.AppLedger;
import com.bloxbean.cardano.yaci.node.api.ledger.AppLedgerTip;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AppLedgerTest {

    private static Path tempDir;

    @BeforeAll
    static void setupDir() throws IOException {
        tempDir = Files.createTempDirectory("app-ledger-test");
    }

    @AfterAll
    static void cleanupDir() throws IOException {
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Nested
    class InMemoryTests {
        @Test void storeAndRetrieve() { runStoreAndRetrieve(new InMemoryAppLedger()); }
        @Test void tipUpdates() { runTipUpdates(new InMemoryAppLedger()); }
        @Test void getLatestBlock() { runGetLatestBlock(new InMemoryAppLedger()); }
        @Test void getBlockRange() { runGetBlockRange(new InMemoryAppLedger()); }
        @Test void missingBlock() { runMissingBlock(new InMemoryAppLedger()); }
        @Test void consensusProof() { runConsensusProof(new InMemoryAppLedger()); }
        @Test void topicIsolation() { runTopicIsolation(new InMemoryAppLedger()); }
    }

    @Nested
    class RocksDBTests {
        private RocksDBAppLedger createLedger() {
            return new RocksDBAppLedger(tempDir.resolve("db-" + System.nanoTime()).toString());
        }
        @Test void storeAndRetrieve() { runStoreAndRetrieve(createLedger()); }
        @Test void tipUpdates() { runTipUpdates(createLedger()); }
        @Test void getLatestBlock() { runGetLatestBlock(createLedger()); }
        @Test void getBlockRange() { runGetBlockRange(createLedger()); }
        @Test void missingBlock() { runMissingBlock(createLedger()); }
        @Test void consensusProof() { runConsensusProof(createLedger()); }
        @Test void topicIsolation() { runTopicIsolation(createLedger()); }
    }

    // --- Shared test logic ---

    void runStoreAndRetrieve(AppLedger ledger) {
        try {
            AppBlock block = createTestBlock("test-topic", 0, null);
            ledger.storeBlock(block);

            Optional<AppBlock> retrieved = ledger.getBlock("test-topic", 0);
            assertThat(retrieved).isPresent();
            assertThat(retrieved.get().getBlockNumber()).isEqualTo(0);
            assertThat(retrieved.get().getTopicId()).isEqualTo("test-topic");
            assertThat(retrieved.get().messageCount()).isEqualTo(2);
            assertThat(new String(retrieved.get().getMessages().get(0).getMessageBody()))
                    .startsWith("message-0");
        } finally {
            ledger.close();
        }
    }

    void runTipUpdates(AppLedger ledger) {
        try {
            AppBlock block0 = createTestBlock("tip-topic", 0, null);
            ledger.storeBlock(block0);
            AppBlock block1 = createTestBlock("tip-topic", 1, block0.getBlockHash());
            ledger.storeBlock(block1);

            Optional<AppLedgerTip> tip = ledger.getTip("tip-topic");
            assertThat(tip).isPresent();
            assertThat(tip.get().getBlockNumber()).isEqualTo(1);
            assertThat(tip.get().getTopicId()).isEqualTo("tip-topic");
        } finally {
            ledger.close();
        }
    }

    void runGetLatestBlock(AppLedger ledger) {
        try {
            AppBlock block0 = createTestBlock("latest-topic", 0, null);
            ledger.storeBlock(block0);
            AppBlock block1 = createTestBlock("latest-topic", 1, block0.getBlockHash());
            ledger.storeBlock(block1);

            Optional<AppBlock> latest = ledger.getLatestBlock("latest-topic");
            assertThat(latest).isPresent();
            assertThat(latest.get().getBlockNumber()).isEqualTo(1);
        } finally {
            ledger.close();
        }
    }

    void runGetBlockRange(AppLedger ledger) {
        try {
            String topic = "range-topic";
            byte[] prevHash = null;
            for (int i = 0; i < 5; i++) {
                AppBlock block = createTestBlock(topic, i, prevHash);
                ledger.storeBlock(block);
                prevHash = block.getBlockHash();
            }

            List<AppBlock> range = ledger.getBlocks(topic, 1, 3);
            assertThat(range).hasSize(3);
            assertThat(range.get(0).getBlockNumber()).isEqualTo(1);
            assertThat(range.get(2).getBlockNumber()).isEqualTo(3);
        } finally {
            ledger.close();
        }
    }

    void runMissingBlock(AppLedger ledger) {
        try {
            assertThat(ledger.getBlock("nonexistent", 0)).isEmpty();
            assertThat(ledger.getTip("nonexistent")).isEmpty();
            assertThat(ledger.getLatestBlock("nonexistent")).isEmpty();
        } finally {
            ledger.close();
        }
    }

    void runConsensusProof(AppLedger ledger) {
        try {
            ConsensusProof proof = ConsensusProof.singleSigner(
                    new byte[]{0x01, 0x02}, new byte[]{0x03, 0x04});
            ledger.storeConsensusProof("proof-topic", 5, proof);

            Optional<ConsensusProof> retrieved = ledger.getConsensusProof("proof-topic", 5);
            assertThat(retrieved).isPresent();
            assertThat(retrieved.get().getMode()).isEqualTo(ConsensusMode.SINGLE_SIGNER);
            assertThat(retrieved.get().getThreshold()).isEqualTo(1);
            assertThat(retrieved.get().signatureCount()).isEqualTo(1);
            assertThat(retrieved.get().meetsThreshold()).isTrue();
        } finally {
            ledger.close();
        }
    }

    void runTopicIsolation(AppLedger ledger) {
        try {
            AppBlock blockA = createTestBlock("topic-a", 0, null);
            AppBlock blockB = createTestBlock("topic-b", 0, null);
            ledger.storeBlock(blockA);
            ledger.storeBlock(blockB);

            assertThat(ledger.getTip("topic-a")).isPresent();
            assertThat(ledger.getTip("topic-b")).isPresent();
            assertThat(ledger.getBlock("topic-a", 0).get().getTopicId()).isEqualTo("topic-a");
            assertThat(ledger.getBlock("topic-b", 0).get().getTopicId()).isEqualTo("topic-b");
        } finally {
            ledger.close();
        }
    }

    // --- Helper ---

    private AppBlock createTestBlock(String topicId, long blockNumber, byte[] prevBlockHash) {
        List<AppMessage> messages = List.of(
                AppMessage.builder()
                        .messageId(("id-" + topicId + "-" + blockNumber + "-0").getBytes())
                        .messageBody(("message-" + blockNumber).getBytes())
                        .authMethod(0)
                        .authProof(new byte[0])
                        .topicId(topicId)
                        .expiresAt(0)
                        .build(),
                AppMessage.builder()
                        .messageId(("id-" + topicId + "-" + blockNumber + "-1").getBytes())
                        .messageBody(("message-" + blockNumber + "-b").getBytes())
                        .authMethod(0)
                        .authProof(new byte[0])
                        .topicId(topicId)
                        .expiresAt(0)
                        .build()
        );

        byte[] stateHash = AppBlock.computeStateHash(messages);
        long timestamp = System.currentTimeMillis();
        byte[] blockHash = AppBlock.computeBlockHash(blockNumber, topicId, stateHash, prevBlockHash, timestamp);

        return AppBlock.builder()
                .blockNumber(blockNumber)
                .topicId(topicId)
                .messages(messages)
                .stateHash(stateHash)
                .timestamp(timestamp)
                .prevBlockHash(prevBlockHash)
                .blockHash(blockHash)
                .consensusProof(ConsensusProof.singleSigner(new byte[]{0x01}, new byte[]{0x02}))
                .build();
    }
}
