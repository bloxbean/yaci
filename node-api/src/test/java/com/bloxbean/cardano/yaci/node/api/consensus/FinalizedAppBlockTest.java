package com.bloxbean.cardano.yaci.node.api.consensus;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinalizedAppBlockTest {

    @Test
    void serializeAndDeserialize_roundTrip() {
        List<AppMessage> messages = List.of(
                AppMessage.builder()
                        .messageId(new byte[]{1, 2})
                        .messageBody("data".getBytes())
                        .authMethod(0)
                        .authProof(new byte[0])
                        .topicId("test")
                        .expiresAt(500L)
                        .build()
        );

        ConsensusProof proof = ConsensusProof.builder()
                .mode(ConsensusMode.MULTI_SIG)
                .threshold(2)
                .proposerKey(new byte[]{10, 20})
                .signatures(List.of(new byte[]{30, 40}, new byte[]{50, 60}))
                .signerKeys(List.of(new byte[]{10, 20}, new byte[]{70, 80}))
                .build();

        AppBlock block = AppBlock.builder()
                .blockNumber(7)
                .topicId("test")
                .stateHash(new byte[]{11})
                .timestamp(12345L)
                .prevBlockHash(new byte[]{22})
                .blockHash(new byte[]{33})
                .messages(messages)
                .consensusProof(proof)
                .build();

        FinalizedAppBlock original = FinalizedAppBlock.builder()
                .block(block)
                .proof(proof)
                .build();

        byte[] serialized = original.serialize();
        FinalizedAppBlock restored = FinalizedAppBlock.deserialize(serialized);

        // Block fields
        assertThat(restored.getBlock().getBlockNumber()).isEqualTo(7);
        assertThat(restored.getBlock().getTopicId()).isEqualTo("test");
        assertThat(restored.getBlock().getTimestamp()).isEqualTo(12345L);
        assertThat(restored.getBlock().getBlockHash()).isEqualTo(new byte[]{33});
        assertThat(restored.getBlock().getMessages()).hasSize(1);
        assertThat(restored.getBlock().getMessages().get(0).getMessageId()).isEqualTo(new byte[]{1, 2});

        // Proof fields
        assertThat(restored.getProof().getMode()).isEqualTo(ConsensusMode.MULTI_SIG);
        assertThat(restored.getProof().getThreshold()).isEqualTo(2);
        assertThat(restored.getProof().getProposerKey()).isEqualTo(new byte[]{10, 20});
        assertThat(restored.getProof().getSignatures()).hasSize(2);
        assertThat(restored.getProof().getSignerKeys()).hasSize(2);
    }

    @Test
    void serializeAndDeserialize_singleSigner() {
        ConsensusProof proof = ConsensusProof.singleSigner(new byte[]{1}, new byte[]{2});

        AppBlock block = AppBlock.builder()
                .blockNumber(0)
                .topicId("t")
                .stateHash(new byte[]{3})
                .timestamp(100)
                .blockHash(new byte[]{4})
                .messages(List.of())
                .consensusProof(proof)
                .build();

        FinalizedAppBlock original = FinalizedAppBlock.builder()
                .block(block)
                .proof(proof)
                .build();

        FinalizedAppBlock restored = FinalizedAppBlock.deserialize(original.serialize());

        assertThat(restored.getProof().getMode()).isEqualTo(ConsensusMode.SINGLE_SIGNER);
        assertThat(restored.getProof().getThreshold()).isEqualTo(1);
        assertThat(restored.getProof().signatureCount()).isEqualTo(1);
    }

    @Test
    void toAppMessage_setsCorrectTopicSuffix() {
        ConsensusProof proof = ConsensusProof.singleSigner(new byte[]{1}, new byte[]{2});
        AppBlock block = AppBlock.builder()
                .blockNumber(0)
                .topicId("my-topic")
                .stateHash(new byte[]{3})
                .timestamp(100)
                .blockHash(new byte[]{4})
                .messages(List.of())
                .consensusProof(proof)
                .build();

        FinalizedAppBlock finalized = FinalizedAppBlock.builder()
                .block(block)
                .proof(proof)
                .build();

        AppMessage msg = finalized.toAppMessage(60);
        assertThat(msg.getTopicId()).isEqualTo("my-topic::finalized");
        assertThat(msg.getMessageBody()).isNotEmpty();
    }
}
