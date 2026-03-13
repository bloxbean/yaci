package com.bloxbean.cardano.yaci.node.api.consensus;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlockVoteTest {

    @Test
    void serializeAndDeserialize_roundTrip() {
        BlockVote original = BlockVote.builder()
                .blockHash(new byte[]{1, 2, 3, 4, 5})
                .blockNumber(99)
                .topicId("my-topic")
                .signerKey(new byte[]{10, 20, 30})
                .signature(new byte[]{40, 50, 60, 70})
                .build();

        byte[] serialized = original.serialize();
        BlockVote restored = BlockVote.deserialize(serialized);

        assertThat(restored.getBlockHash()).isEqualTo(new byte[]{1, 2, 3, 4, 5});
        assertThat(restored.getBlockNumber()).isEqualTo(99);
        assertThat(restored.getTopicId()).isEqualTo("my-topic");
        assertThat(restored.getSignerKey()).isEqualTo(new byte[]{10, 20, 30});
        assertThat(restored.getSignature()).isEqualTo(new byte[]{40, 50, 60, 70});
    }

    @Test
    void toAppMessage_setsCorrectTopicSuffix() {
        BlockVote vote = BlockVote.builder()
                .blockHash(new byte[]{1})
                .blockNumber(0)
                .topicId("topic-x")
                .signerKey(new byte[]{2})
                .signature(new byte[]{3})
                .build();

        AppMessage msg = vote.toAppMessage(30);
        assertThat(msg.getTopicId()).isEqualTo("topic-x::vote");
        assertThat(msg.getMessageBody()).isNotEmpty();
        assertThat(msg.getMessageId()).isNotNull();
    }

    @Test
    void create_factoryMethod() {
        BlockVote vote = BlockVote.create(
                new byte[]{1, 2}, 5, "topic", new byte[]{3, 4}, new byte[]{5, 6});

        assertThat(vote.getBlockHash()).isEqualTo(new byte[]{1, 2});
        assertThat(vote.getBlockNumber()).isEqualTo(5);
        assertThat(vote.getTopicId()).isEqualTo("topic");
        assertThat(vote.getSignerKey()).isEqualTo(new byte[]{3, 4});
        assertThat(vote.getSignature()).isEqualTo(new byte[]{5, 6});
    }

    @Test
    void signerKeyHex_returnsHex() {
        BlockVote vote = BlockVote.builder()
                .blockHash(new byte[]{})
                .blockNumber(0)
                .topicId("t")
                .signerKey(new byte[]{(byte) 0xAB, (byte) 0xCD})
                .signature(new byte[]{})
                .build();

        assertThat(vote.signerKeyHex()).isEqualTo("abcd");
    }
}
