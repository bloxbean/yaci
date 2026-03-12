package com.bloxbean.cardano.yaci.node.runtime.appmsg;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgReplyMessageIds;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgReplyMessages;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.node.api.appmsg.MessageAuthenticator;
import com.bloxbean.cardano.yaci.node.api.events.AppMessageReceivedEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Wires app-message protocol events to the authenticator, mempool, and event bus.
 * Registered as a listener on AppMsgSubmissionServerAgent.
 */
@Slf4j
public class YaciAppMessageHandler implements AppMsgSubmissionListener {

    private final AppMessageMemPool memPool;
    private final MessageAuthenticator authenticator;
    private final EventBus eventBus;

    private long messagesReceived = 0;
    private long messagesAccepted = 0;
    private long messagesRejected = 0;

    public YaciAppMessageHandler(AppMessageMemPool memPool, MessageAuthenticator authenticator, EventBus eventBus) {
        this.memPool = memPool;
        this.authenticator = authenticator;
        this.eventBus = eventBus;
    }

    @Override
    public void handleReplyMessageIds(MsgReplyMessageIds reply) {
        log.info("Received {} message IDs from peer", reply.getMessageIds().size());
    }

    @Override
    public void handleReplyMessages(MsgReplyMessages reply) {
        messagesReceived += reply.getMessages().size();
        log.info("Received {} messages from peer", reply.getMessages().size());

        for (AppMessage msg : reply.getMessages()) {
            try {
                // Check expiry
                if (msg.getExpiresAt() > 0 && msg.getExpiresAt() <= System.currentTimeMillis() / 1000) {
                    messagesRejected++;
                    log.debug("Rejecting expired message: {}", msg.getMessageIdHex());
                    continue;
                }

                // Authenticate
                if (!authenticator.verify(msg)) {
                    messagesRejected++;
                    log.warn("Rejecting unauthenticated message: {}", msg.getMessageIdHex());
                    continue;
                }

                // Add to mempool
                boolean added = memPool.addMessage(msg);
                if (added) {
                    messagesAccepted++;

                    // Publish event
                    if (eventBus != null) {
                        eventBus.publish(new AppMessageReceivedEvent(msg, "n2n"),
                                EventMetadata.builder().origin("appmsg").build(),
                                PublishOptions.builder().build());
                    }

                    log.info("App message accepted: {} topic={} size={}",
                            msg.getMessageIdHex(), msg.getTopicId(), msg.getSize());
                } else {
                    log.debug("Duplicate message: {}", msg.getMessageIdHex());
                }
            } catch (Exception e) {
                messagesRejected++;
                log.warn("Failed to process app message: {}", e.getMessage());
            }
        }
    }

    /**
     * Handle a locally-submitted message (from N2C Protocol 101 or REST API).
     *
     * @return true if the message was accepted
     */
    public boolean handleLocalSubmission(AppMessage message) {
        messagesReceived++;

        if (message.getExpiresAt() > 0 && message.getExpiresAt() <= System.currentTimeMillis() / 1000) {
            messagesRejected++;
            return false;
        }

        if (!authenticator.verify(message)) {
            messagesRejected++;
            return false;
        }

        boolean added = memPool.addMessage(message);
        if (added) {
            messagesAccepted++;
            if (eventBus != null) {
                eventBus.publish(new AppMessageReceivedEvent(message, "local"),
                        EventMetadata.builder().origin("appmsg").build(),
                        PublishOptions.builder().build());
            }
        }
        return added;
    }

    public long getMessagesReceived() { return messagesReceived; }
    public long getMessagesAccepted() { return messagesAccepted; }
    public long getMessagesRejected() { return messagesRejected; }
    public int getMempoolSize() { return memPool.size(); }
}
