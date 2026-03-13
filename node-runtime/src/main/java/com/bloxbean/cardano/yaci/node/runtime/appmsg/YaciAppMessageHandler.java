package com.bloxbean.cardano.yaci.node.runtime.appmsg;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgReplyMessageIds;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgReplyMessages;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.node.api.appmsg.MessageAuthenticator;
import com.bloxbean.cardano.yaci.node.api.consensus.BlockProposal;
import com.bloxbean.cardano.yaci.node.api.consensus.BlockVote;
import com.bloxbean.cardano.yaci.node.api.consensus.FinalizedAppBlock;
import com.bloxbean.cardano.yaci.node.api.events.AppMessageReceivedEvent;
import com.bloxbean.cardano.yaci.node.runtime.consensus.AppConsensusCoordinator;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Wires app-message protocol events to the authenticator, mempool, and event bus.
 * Routes consensus messages (::proposal, ::vote, ::finalized) to coordinators.
 * Registered as a listener on AppMsgSubmissionServerAgent.
 */
@Slf4j
public class YaciAppMessageHandler implements AppMsgSubmissionListener {

    private static final String CONSENSUS_SEPARATOR = "::";

    private final AppMessageMemPool memPool;
    private final MessageAuthenticator authenticator;
    private final EventBus eventBus;

    /** Coordinators keyed by base topic ID. */
    @Setter
    private Map<String, AppConsensusCoordinator> coordinators;

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

                // Consensus messages skip auth check (they use their own proof verification)
                if (!isConsensusMessage(msg.getTopicId())) {
                    if (!authenticator.verify(msg)) {
                        messagesRejected++;
                        log.warn("Rejecting unauthenticated message: {}", msg.getMessageIdHex());
                        continue;
                    }
                }

                // Add to mempool (for re-gossip to other peers)
                boolean added = memPool.addMessage(msg);
                if (added) {
                    messagesAccepted++;

                    // Route consensus messages to coordinator
                    if (isConsensusMessage(msg.getTopicId())) {
                        routeConsensusMessage(msg);
                        memPool.removeMessage(msg.getMessageId());
                    } else {
                        // Publish event for regular data messages
                        if (eventBus != null) {
                            eventBus.publish(new AppMessageReceivedEvent(msg, "n2n"),
                                    EventMetadata.builder().origin("appmsg").build(),
                                    PublishOptions.builder().build());
                        }
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

    /**
     * Check if a topicId is a consensus message (contains "::").
     */
    static boolean isConsensusMessage(String topicId) {
        return topicId != null && topicId.contains(CONSENSUS_SEPARATOR);
    }

    /**
     * Route a consensus message to the appropriate coordinator.
     */
    private void routeConsensusMessage(AppMessage msg) {
        if (coordinators == null || coordinators.isEmpty()) {
            log.info("No coordinators configured, skipping consensus message: {}", msg.getTopicId());
            return;
        }

        String topicId = msg.getTopicId();
        int sepIdx = topicId.lastIndexOf(CONSENSUS_SEPARATOR);
        if (sepIdx < 0) return;

        String baseTopic = topicId.substring(0, sepIdx);
        String suffix = topicId.substring(sepIdx + CONSENSUS_SEPARATOR.length());

        AppConsensusCoordinator coordinator = coordinators.get(baseTopic);
        if (coordinator == null) {
            log.info("No coordinator for consensus topic '{}' (available: {})", baseTopic, coordinators.keySet());
            return;
        }

        log.info("Routing consensus message: topic={}, suffix={}, running={}",
                baseTopic, suffix, coordinator.isRunning());

        try {
            switch (suffix) {
                case "proposal" -> coordinator.handleProposal(
                        BlockProposal.deserialize(msg.getMessageBody()));
                case "vote" -> coordinator.handleVote(
                        BlockVote.deserialize(msg.getMessageBody()));
                case "finalized" -> coordinator.handleFinalizedBlock(
                        FinalizedAppBlock.deserialize(msg.getMessageBody()));
                default -> log.warn("Unknown consensus message suffix: {}", suffix);
            }
        } catch (Exception e) {
            log.warn("Failed to route consensus message (topic={}): {}", topicId, e.getMessage());
        }
    }

    public long getMessagesReceived() { return messagesReceived; }
    public long getMessagesAccepted() { return messagesAccepted; }
    public long getMessagesRejected() { return messagesRejected; }
    public int getMempoolSize() { return memPool.size(); }
}
