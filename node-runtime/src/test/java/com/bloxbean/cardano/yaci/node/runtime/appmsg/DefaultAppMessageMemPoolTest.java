package com.bloxbean.cardano.yaci.node.runtime.appmsg;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAppMessageMemPoolTest {

    private DefaultAppMessageMemPool memPool;

    @BeforeEach
    void setUp() {
        memPool = new DefaultAppMessageMemPool(5);
    }

    private AppMessage createMessage(byte[] id, String topic, long expiresAt) {
        return AppMessage.builder()
                .messageId(id)
                .messageBody(new byte[]{1, 2, 3})
                .authMethod(0)
                .authProof(new byte[0])
                .topicId(topic)
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    void testAddAndRetrieve() {
        byte[] id = new byte[]{0x01, 0x02};
        AppMessage msg = createMessage(id, "test", 0);
        assertThat(memPool.addMessage(msg)).isTrue();
        assertThat(memPool.size()).isEqualTo(1);
        assertThat(memPool.getMessage(id)).isNotNull();
        assertThat(memPool.getMessage(id).getTopicId()).isEqualTo("test");
    }

    @Test
    void testDeduplicate() {
        byte[] id = new byte[]{0x01, 0x02};
        AppMessage msg1 = createMessage(id, "a", 0);
        AppMessage msg2 = createMessage(id, "b", 0);
        assertThat(memPool.addMessage(msg1)).isTrue();
        assertThat(memPool.addMessage(msg2)).isFalse();
        assertThat(memPool.size()).isEqualTo(1);
        assertThat(memPool.getMessage(id).getTopicId()).isEqualTo("a");
    }

    @Test
    void testContains() {
        byte[] id = new byte[]{0x0A};
        assertThat(memPool.contains(id)).isFalse();
        memPool.addMessage(createMessage(id, "t", 0));
        assertThat(memPool.contains(id)).isTrue();
    }

    @Test
    void testEvictionAtCapacity() {
        for (int i = 0; i < 6; i++) {
            memPool.addMessage(createMessage(new byte[]{(byte) i}, "t", 0));
        }
        assertThat(memPool.size()).isEqualTo(5);
        // First message (id=0x00) should have been evicted
        assertThat(memPool.contains(new byte[]{0x00})).isFalse();
        // Last message (id=0x05) should be present
        assertThat(memPool.contains(new byte[]{0x05})).isTrue();
    }

    @Test
    void testGetMessages() {
        for (int i = 0; i < 4; i++) {
            memPool.addMessage(createMessage(new byte[]{(byte) i}, "t", 0));
        }
        List<AppMessage> all = memPool.getMessages(10);
        assertThat(all).hasSize(4);

        List<AppMessage> limited = memPool.getMessages(2);
        assertThat(limited).hasSize(2);
    }

    @Test
    void testGetMessagesForTopic() {
        memPool.addMessage(createMessage(new byte[]{0x01}, "topicA", 0));
        memPool.addMessage(createMessage(new byte[]{0x02}, "topicB", 0));
        memPool.addMessage(createMessage(new byte[]{0x03}, "topicA", 0));

        List<AppMessage> topicA = memPool.getMessagesForTopic("topicA", 10);
        assertThat(topicA).hasSize(2);

        List<AppMessage> topicB = memPool.getMessagesForTopic("topicB", 10);
        assertThat(topicB).hasSize(1);
    }

    @Test
    void testRemoveExpired() {
        memPool.addMessage(createMessage(new byte[]{0x01}, "t", 100));  // expires at 100
        memPool.addMessage(createMessage(new byte[]{0x02}, "t", 200));  // expires at 200
        memPool.addMessage(createMessage(new byte[]{0x03}, "t", 0));    // no expiry

        int removed = memPool.removeExpired(150);
        assertThat(removed).isEqualTo(1);
        assertThat(memPool.size()).isEqualTo(2);
        assertThat(memPool.contains(new byte[]{0x01})).isFalse();
        assertThat(memPool.contains(new byte[]{0x02})).isTrue();
        assertThat(memPool.contains(new byte[]{0x03})).isTrue();
    }

    @Test
    void testClear() {
        memPool.addMessage(createMessage(new byte[]{0x01}, "t", 0));
        memPool.addMessage(createMessage(new byte[]{0x02}, "t", 0));
        assertThat(memPool.size()).isEqualTo(2);
        memPool.clear();
        assertThat(memPool.size()).isEqualTo(0);
    }
}
