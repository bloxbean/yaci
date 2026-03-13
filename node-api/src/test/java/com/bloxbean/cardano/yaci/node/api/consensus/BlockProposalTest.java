package com.bloxbean.cardano.yaci.node.api.consensus;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlockProposalTest {

    @Test
    void serializeAndDeserialize_roundTrip() {
        List<AppMessage> messages = List.of(
                AppMessage.builder()
                        .messageId(new byte[]{1, 2, 3})
                        .messageBody("hello".getBytes())
                        .authMethod(0)
                        .authProof(new byte[0])
                        .topicId("test-topic")
                        .expiresAt(1000L)
                        .build(),
                AppMessage.builder()
                        .messageId(new byte[]{4, 5, 6})
                        .messageBody("world".getBytes())
                        .authMethod(1)
                        .authProof(new byte[]{9, 8, 7})
                        .topicId("test-topic")
                        .expiresAt(2000L)
                        .build()
        );

        BlockProposal original = BlockProposal.builder()
                .blockNumber(42)
                .topicId("test-topic")
                .timestamp(System.currentTimeMillis())
                .prevBlockHash(new byte[]{10, 20, 30})
                .stateHash(new byte[]{40, 50, 60})
                .blockHash(new byte[]{70, 80, 90})
                .proposerKey(new byte[]{1, 1, 1})
                .proposerSignature(new byte[]{2, 2, 2})
                .messages(messages)
                .build();

        byte[] serialized = original.serialize();
        BlockProposal restored = BlockProposal.deserialize(serialized);

        assertThat(restored.getBlockNumber()).isEqualTo(42);
        assertThat(restored.getTopicId()).isEqualTo("test-topic");
        assertThat(restored.getTimestamp()).isEqualTo(original.getTimestamp());
        assertThat(restored.getPrevBlockHash()).isEqualTo(new byte[]{10, 20, 30});
        assertThat(restored.getStateHash()).isEqualTo(new byte[]{40, 50, 60});
        assertThat(restored.getBlockHash()).isEqualTo(new byte[]{70, 80, 90});
        assertThat(restored.getProposerKey()).isEqualTo(new byte[]{1, 1, 1});
        assertThat(restored.getProposerSignature()).isEqualTo(new byte[]{2, 2, 2});
        assertThat(restored.getMessages()).hasSize(2);
        assertThat(restored.getMessages().get(0).getMessageId()).isEqualTo(new byte[]{1, 2, 3});
        assertThat(restored.getMessages().get(1).getTopicId()).isEqualTo("test-topic");
    }

    @Test
    void serializeAndDeserialize_nullPrevBlockHash() {
        BlockProposal original = BlockProposal.builder()
                .blockNumber(0)
                .topicId("t")
                .timestamp(100)
                .prevBlockHash(null)
                .stateHash(new byte[]{1})
                .blockHash(new byte[]{2})
                .proposerKey(new byte[]{3})
                .proposerSignature(new byte[]{4})
                .messages(List.of())
                .build();

        BlockProposal restored = BlockProposal.deserialize(original.serialize());
        assertThat(restored.getPrevBlockHash()).isNull();
        assertThat(restored.getMessages()).isEmpty();
    }

    @Test
    void toAppMessage_setsCorrectTopicSuffix() {
        BlockProposal proposal = BlockProposal.builder()
                .blockNumber(0)
                .topicId("my-topic")
                .timestamp(100)
                .stateHash(new byte[]{1})
                .blockHash(new byte[]{2})
                .proposerKey(new byte[]{3})
                .proposerSignature(new byte[]{4})
                .messages(List.of())
                .build();

        AppMessage msg = proposal.toAppMessage(60);
        assertThat(msg.getTopicId()).isEqualTo("my-topic::proposal");
        assertThat(msg.getMessageBody()).isNotEmpty();
        assertThat(msg.getMessageId()).isNotNull();
        assertThat(msg.getExpiresAt()).isGreaterThan(0);
    }

    @Test
    void fromAppBlock_preservesFields() {
        byte[] stateHash = new byte[]{1, 2};
        byte[] blockHash = new byte[]{3, 4};
        List<AppMessage> messages = List.of(
                AppMessage.builder()
                        .messageId(new byte[]{10})
                        .messageBody(new byte[]{20})
                        .authMethod(0)
                        .authProof(new byte[0])
                        .topicId("t")
                        .expiresAt(0)
                        .build()
        );

        AppBlock block = AppBlock.builder()
                .blockNumber(5)
                .topicId("t")
                .timestamp(500)
                .prevBlockHash(null)
                .stateHash(stateHash)
                .blockHash(blockHash)
                .messages(messages)
                .build();

        BlockProposal proposal = BlockProposal.fromAppBlock(block, new byte[]{99}, new byte[]{88});
        assertThat(proposal.getBlockNumber()).isEqualTo(5);
        assertThat(proposal.getTopicId()).isEqualTo("t");
        assertThat(proposal.getBlockHash()).isEqualTo(blockHash);
        assertThat(proposal.getProposerKey()).isEqualTo(new byte[]{99});
        assertThat(proposal.getMessages()).hasSize(1);
    }
}
