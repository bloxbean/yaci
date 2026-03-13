package com.bloxbean.cardano.yaci.node.runtime.consensus;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.node.api.consensus.*;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsensusTest {

    @Test
    void singleSigner_alwaysCanPropose() {
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        assertThat(consensus.canPropose()).isTrue();
        assertThat(consensus.consensusMode()).isEqualTo(ConsensusMode.SINGLE_SIGNER);
    }

    @Test
    void singleSigner_createAndVerifyProof() {
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        AppBlock block = createTestBlock();

        ConsensusProof proof = consensus.createProof(block);
        assertThat(proof).isNotNull();
        assertThat(proof.getMode()).isEqualTo(ConsensusMode.SINGLE_SIGNER);
        assertThat(proof.signatureCount()).isEqualTo(1);
        assertThat(proof.meetsThreshold()).isTrue();

        assertThat(consensus.verifyProof(block, proof)).isTrue();
    }

    @Test
    void singleSigner_rejectsNullProof() {
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        assertThat(consensus.verifyProof(createTestBlock(), null)).isFalse();
    }

    @Test
    void multiSig_createProof() {
        MultiSigConsensus consensus = new MultiSigConsensus(
                List.of(),
                ConsensusParams.builder().threshold(2).totalSigners(3).build());
        AppBlock block = createTestBlock();

        ConsensusProof proof = consensus.createProof(block);
        assertThat(proof.getMode()).isEqualTo(ConsensusMode.MULTI_SIG);
        assertThat(proof.signatureCount()).isEqualTo(1); // Only local signature
        assertThat(proof.getThreshold()).isEqualTo(2);
    }

    @Test
    void multiSig_verifyWithEnoughSignatures() {
        // Create 3 signers
        MultiSigConsensus signer1 = new MultiSigConsensus(List.of(), ConsensusParams.builder().threshold(2).totalSigners(3).build());
        MultiSigConsensus signer2 = new MultiSigConsensus(List.of(), ConsensusParams.builder().threshold(2).totalSigners(3).build());

        // Allow both keys
        List<byte[]> allowedKeys = List.of(signer1.getLocalPublicKey(), signer2.getLocalPublicKey());
        MultiSigConsensus verifier = new MultiSigConsensus(allowedKeys, ConsensusParams.builder().threshold(2).totalSigners(3).build());

        AppBlock block = createTestBlock();

        // Each signer creates their proof
        ConsensusProof proof1 = signer1.createProof(block);
        ConsensusProof proof2 = signer2.createProof(block);

        // Aggregate signatures
        List<byte[]> allSigs = new ArrayList<>();
        allSigs.addAll(proof1.getSignatures());
        allSigs.addAll(proof2.getSignatures());

        List<byte[]> allKeys = new ArrayList<>();
        allKeys.addAll(proof1.getSignerKeys());
        allKeys.addAll(proof2.getSignerKeys());

        ConsensusProof aggregated = ConsensusProof.builder()
                .mode(ConsensusMode.MULTI_SIG)
                .signatures(allSigs)
                .signerKeys(allKeys)
                .threshold(2)
                .build();

        assertThat(verifier.verifyProof(block, aggregated)).isTrue();
    }

    @Test
    void multiSig_rejectInsufficientSignatures() {
        MultiSigConsensus signer = new MultiSigConsensus(List.of(), ConsensusParams.builder().threshold(2).totalSigners(3).build());

        // Allow the signer's key
        List<byte[]> allowedKeys = List.of(signer.getLocalPublicKey());
        MultiSigConsensus verifier = new MultiSigConsensus(allowedKeys, ConsensusParams.builder().threshold(2).totalSigners(3).build());

        AppBlock block = createTestBlock();
        ConsensusProof proof = signer.createProof(block);
        // Only 1 signature, but threshold is 2
        assertThat(verifier.verifyProof(block, proof)).isFalse();
    }

    @Test
    void multiSig_rejectUnknownSignerKey() {
        MultiSigConsensus signer = new MultiSigConsensus(List.of(), ConsensusParams.builder().threshold(1).totalSigners(1).build());
        // Empty allowed keys list — only unknown keys will be present
        MultiSigConsensus verifier = new MultiSigConsensus(
                List.of(new byte[]{0x01}), // Some random key, not matching signer
                ConsensusParams.builder().threshold(1).totalSigners(1).build());

        AppBlock block = createTestBlock();
        ConsensusProof proof = signer.createProof(block);
        assertThat(verifier.verifyProof(block, proof)).isFalse();
    }

    @Test
    void consensusProof_meetsThreshold() {
        ConsensusProof proof = ConsensusProof.builder()
                .mode(ConsensusMode.MULTI_SIG)
                .signatures(List.of(new byte[]{1}, new byte[]{2}, new byte[]{3}))
                .signerKeys(List.of(new byte[]{1}, new byte[]{2}, new byte[]{3}))
                .threshold(2)
                .build();
        assertThat(proof.signatureCount()).isEqualTo(3);
        assertThat(proof.meetsThreshold()).isTrue();
    }

    @Test
    void consensusProof_doesNotMeetThreshold() {
        ConsensusProof proof = ConsensusProof.builder()
                .mode(ConsensusMode.MULTI_SIG)
                .signatures(List.of(new byte[]{1}))
                .signerKeys(List.of(new byte[]{1}))
                .threshold(3)
                .build();
        assertThat(proof.meetsThreshold()).isFalse();
    }

    @Test
    void consensusMode_fromString() {
        assertThat(ConsensusMode.fromString("single-signer")).isEqualTo(ConsensusMode.SINGLE_SIGNER);
        assertThat(ConsensusMode.fromString("multisig")).isEqualTo(ConsensusMode.MULTI_SIG);
        assertThat(ConsensusMode.fromString("round_robin")).isEqualTo(ConsensusMode.ROUND_ROBIN);
        assertThat(ConsensusMode.fromString("bft")).isEqualTo(ConsensusMode.BFT);
    }

    @Test
    void multiSig_sortsAllowedKeysLexicographically() {
        // Create keys in a specific order: key3 > key2 > key1 (unsigned byte comparison)
        byte[] key1 = new byte[]{0x01, 0x02, 0x03};
        byte[] key2 = new byte[]{0x05, 0x06, 0x07};
        byte[] key3 = new byte[]{(byte) 0xFF, 0x00, 0x01};

        // Pass in reverse order
        List<byte[]> keysReversed = new ArrayList<>(List.of(key3, key2, key1));
        MultiSigConsensus consensus = new MultiSigConsensus(keysReversed,
                ConsensusParams.builder().threshold(1).totalSigners(3).build());

        // Pass in forward order
        List<byte[]> keysForward = new ArrayList<>(List.of(key1, key2, key3));
        MultiSigConsensus consensus2 = new MultiSigConsensus(keysForward,
                ConsensusParams.builder().threshold(1).totalSigners(3).build());

        // Both should agree on proposer for any block number
        for (long block = 0; block < 10; block++) {
            assertThat(consensus.isExpectedProposer(block, key1))
                    .isEqualTo(consensus2.isExpectedProposer(block, key1));
            assertThat(consensus.isExpectedProposer(block, key2))
                    .isEqualTo(consensus2.isExpectedProposer(block, key2));
            assertThat(consensus.isExpectedProposer(block, key3))
                    .isEqualTo(consensus2.isExpectedProposer(block, key3));
        }
    }

    @Test
    void multiSig_isExpectedProposer_roundRobin() {
        byte[] keyA = new byte[]{0x01};
        byte[] keyB = new byte[]{0x02};
        byte[] keyC = new byte[]{0x03};
        List<byte[]> keys = List.of(keyA, keyB, keyC); // already sorted

        MultiSigConsensus consensus = new MultiSigConsensus(keys,
                ConsensusParams.builder().threshold(2).totalSigners(3).build());

        // Block 0 → keyA, Block 1 → keyB, Block 2 → keyC, Block 3 → keyA again
        assertThat(consensus.isExpectedProposer(0, keyA)).isTrue();
        assertThat(consensus.isExpectedProposer(0, keyB)).isFalse();
        assertThat(consensus.isExpectedProposer(1, keyB)).isTrue();
        assertThat(consensus.isExpectedProposer(1, keyA)).isFalse();
        assertThat(consensus.isExpectedProposer(2, keyC)).isTrue();
        assertThat(consensus.isExpectedProposer(3, keyA)).isTrue();
    }

    @Test
    void multiSig_isExpectedProposer_emptyKeys_alwaysTrue() {
        MultiSigConsensus consensus = new MultiSigConsensus(List.of(),
                ConsensusParams.builder().threshold(1).totalSigners(1).build());

        assertThat(consensus.isExpectedProposer(0, new byte[]{(byte) 0x99})).isTrue();
        assertThat(consensus.isExpectedProposer(100, new byte[]{0x42})).isTrue();
    }

    @Test
    void singleSigner_isExpectedProposer_alwaysTrue() {
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        assertThat(consensus.isExpectedProposer(0, new byte[]{0x01})).isTrue();
        assertThat(consensus.isExpectedProposer(5, new byte[]{(byte) 0x99})).isTrue();
    }

    // --- Helper ---

    private AppBlock createTestBlock() {
        List<AppMessage> messages = List.of(
                AppMessage.builder()
                        .messageId(new byte[]{0x01, 0x02})
                        .messageBody("test-data".getBytes())
                        .authMethod(0)
                        .authProof(new byte[0])
                        .topicId("test")
                        .expiresAt(0)
                        .build()
        );

        byte[] stateHash = AppBlock.computeStateHash(messages);
        long timestamp = System.currentTimeMillis();
        byte[] blockHash = AppBlock.computeBlockHash(0, "test", stateHash, null, timestamp);

        return AppBlock.builder()
                .blockNumber(0)
                .topicId("test")
                .messages(messages)
                .stateHash(stateHash)
                .timestamp(timestamp)
                .blockHash(blockHash)
                .build();
    }
}
