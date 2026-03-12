package com.bloxbean.cardano.yaci.node.runtime.appmsg;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgReplyMessages;
import com.bloxbean.cardano.yaci.node.api.appmsg.MessageAuthenticator;
import com.bloxbean.cardano.yaci.node.runtime.appmsg.auth.OpenAuthenticator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YaciAppMessageHandlerTest {

    private DefaultAppMessageMemPool memPool;
    private YaciAppMessageHandler handler;

    @BeforeEach
    void setUp() {
        memPool = new DefaultAppMessageMemPool(100);
        MessageAuthenticator auth = new OpenAuthenticator();
        handler = new YaciAppMessageHandler(memPool, auth, null);
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
    void handleReplyMessages_addsToMempool() {
        AppMessage msg1 = createMessage(new byte[]{0x01}, "t", 0);
        AppMessage msg2 = createMessage(new byte[]{0x02}, "t", 0);
        MsgReplyMessages reply = new MsgReplyMessages(List.of(msg1, msg2));

        handler.handleReplyMessages(reply);

        assertThat(memPool.size()).isEqualTo(2);
        assertThat(handler.getMessagesReceived()).isEqualTo(2);
        assertThat(handler.getMessagesAccepted()).isEqualTo(2);
        assertThat(handler.getMessagesRejected()).isEqualTo(0);
    }

    @Test
    void handleReplyMessages_rejectsExpiredMessages() {
        long now = System.currentTimeMillis() / 1000;
        AppMessage expired = createMessage(new byte[]{0x01}, "t", now - 10);
        AppMessage valid = createMessage(new byte[]{0x02}, "t", now + 600);
        MsgReplyMessages reply = new MsgReplyMessages(List.of(expired, valid));

        handler.handleReplyMessages(reply);

        assertThat(memPool.size()).isEqualTo(1);
        assertThat(handler.getMessagesAccepted()).isEqualTo(1);
        assertThat(handler.getMessagesRejected()).isEqualTo(1);
    }

    @Test
    void handleReplyMessages_deduplicates() {
        AppMessage msg = createMessage(new byte[]{0x01}, "t", 0);
        MsgReplyMessages reply = new MsgReplyMessages(List.of(msg, msg));

        handler.handleReplyMessages(reply);

        assertThat(memPool.size()).isEqualTo(1);
        assertThat(handler.getMessagesAccepted()).isEqualTo(1);
    }

    @Test
    void handleLocalSubmission_accepted() {
        AppMessage msg = createMessage(new byte[]{0x01}, "t", 0);
        boolean result = handler.handleLocalSubmission(msg);

        assertThat(result).isTrue();
        assertThat(memPool.size()).isEqualTo(1);
        assertThat(handler.getMessagesAccepted()).isEqualTo(1);
    }

    @Test
    void handleLocalSubmission_rejectsExpired() {
        long now = System.currentTimeMillis() / 1000;
        AppMessage expired = createMessage(new byte[]{0x01}, "t", now - 10);
        boolean result = handler.handleLocalSubmission(expired);

        assertThat(result).isFalse();
        assertThat(memPool.size()).isEqualTo(0);
        assertThat(handler.getMessagesRejected()).isEqualTo(1);
    }

    @Test
    void handleLocalSubmission_rejectsDuplicate() {
        AppMessage msg = createMessage(new byte[]{0x01}, "t", 0);
        assertThat(handler.handleLocalSubmission(msg)).isTrue();
        assertThat(handler.handleLocalSubmission(msg)).isFalse();
        assertThat(memPool.size()).isEqualTo(1);
    }

    @Test
    void getMempoolSize_matchesPool() {
        handler.handleLocalSubmission(createMessage(new byte[]{0x01}, "t", 0));
        handler.handleLocalSubmission(createMessage(new byte[]{0x02}, "t", 0));
        assertThat(handler.getMempoolSize()).isEqualTo(2);
    }
}
