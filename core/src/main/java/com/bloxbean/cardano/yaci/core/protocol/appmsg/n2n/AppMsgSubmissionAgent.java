package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessageId;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.*;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Client-side agent for Protocol 100 (App Message Submission).
 * Maintains a queue of pending messages and responds to server requests
 * for message IDs and bodies. Only messages for chains negotiated via
 * MsgInit/MsgInitAck are offered; expired messages are dropped on offer.
 */
@Slf4j
public class AppMsgSubmissionAgent extends Agent<AppMsgSubmissionListener> {

    private final ConcurrentLinkedQueue<AppMessage> pendingMessages;
    private final ConcurrentLinkedQueue<byte[]> pendingIds;
    private final ConcurrentLinkedQueue<byte[]> requestedIds;
    private final AppMsgSubmissionConfig config;
    private final int maxQueueSize;
    private volatile int requestedMessageIdWindow;
    /** Chains both sides share; null until MsgInitAck received (then never null). */
    private volatile Set<String> negotiatedChainIds;

    public AppMsgSubmissionAgent() {
        this(AppMsgSubmissionConfig.createDefault(), 1000);
    }

    public AppMsgSubmissionAgent(AppMsgSubmissionConfig config) {
        this(config, 1000);
    }

    public AppMsgSubmissionAgent(AppMsgSubmissionConfig config, int maxQueueSize) {
        super(true); // Client agent
        this.currentState = AppMsgSubmissionState.Init;
        this.config = config;
        this.pendingMessages = new ConcurrentLinkedQueue<>();
        this.pendingIds = new ConcurrentLinkedQueue<>();
        this.requestedIds = new ConcurrentLinkedQueue<>();
        this.maxQueueSize = maxQueueSize;
    }

    @Override
    public int getProtocolId() {
        return 100;
    }

    @Override
    public Message buildNextMessage() {
        switch ((AppMsgSubmissionState) currentState) {
            case Init:
                return new MsgInit(new ArrayList<>(config.getChainIds()));
            case MessageIdsBlocking:
                // In blocking mode, hold agency until we have actual message IDs to report.
                // Don't rely solely on pendingIds — it may have stale entries (IDs whose
                // messages were already delivered and removed from pendingMessages).
                // enqueueMessage() will trigger sendNextMessage() when new messages arrive.
                sweepExpired();
                if (pendingMessages.isEmpty()) {
                    pendingIds.clear(); // Clean up stale IDs
                    return null;
                }
                return buildReplyMessageIds();
            case MessageIdsNonBlocking:
                sweepExpired();
                return buildReplyMessageIds();
            case Messages:
                return buildReplyMessages();
            default:
                return null;
        }
    }

    private MsgReplyMessageIds buildReplyMessageIds() {
        MsgReplyMessageIds reply = new MsgReplyMessageIds();
        if (!pendingIds.isEmpty()) {
            pendingIds.stream()
                    .limit(requestedMessageIdWindow)
                    .flatMap(id -> findMessage(id).stream())
                    .forEach(msg -> reply.addMessageId(
                            new AppMessageId(msg.getMessageId(), msg.getSize())));
            log.info("Sending {} message ID(s) to peer", reply.getMessageIds().size());
        }
        return reply;
    }

    private MsgReplyMessages buildReplyMessages() {
        MsgReplyMessages reply = new MsgReplyMessages();
        if (!requestedIds.isEmpty()) {
            requestedIds.forEach(id ->
                    findMessage(id).ifPresent(reply::addMessage));
            // Remove delivered messages
            requestedIds.forEach(this::removeMessage);
            requestedIds.clear();
            log.info("Sending {} message(s) to peer", reply.getMessages().size());
        }
        return reply;
    }

    @Override
    public void processResponse(Message message) {
        if (message == null) return;

        if (message instanceof MsgInitAck) {
            handleInitAck((MsgInitAck) message);
        } else if (message instanceof MsgRequestMessageIds) {
            handleRequestMessageIds((MsgRequestMessageIds) message);
        } else if (message instanceof MsgRequestMessages) {
            handleRequestMessages((MsgRequestMessages) message);
        }
    }

    private void handleInitAck(MsgInitAck ack) {
        Set<String> shared = new HashSet<>(config.getChainIds());
        shared.retainAll(new HashSet<>(ack.getChainIds()));
        this.negotiatedChainIds = Collections.unmodifiableSet(shared);
        if (shared.isEmpty()) {
            log.warn("No shared app chains with peer (local: {}, peer: {}) — nothing will be offered",
                    config.getChainIds(), ack.getChainIds());
        } else {
            log.info("App chains negotiated with peer: {}", shared);
        }
        // Drop queued messages for chains the peer doesn't serve
        pendingMessages.removeIf(m -> !shared.contains(m.getChainId()));
        pendingIds.removeIf(id -> findMessage(id).isEmpty());
        getAgentListeners().forEach(l -> l.handleInitAck(ack));
    }

    private void handleRequestMessageIds(MsgRequestMessageIds request) {
        if (log.isDebugEnabled())
            log.debug("RequestMessageIds - blocking: {}, ack: {}, req: {}",
                    request.isBlocking(), request.getAckCount(), request.getReqCount());

        // Process acknowledgments
        removeAcknowledged(request.getAckCount());

        requestedMessageIdWindow = Math.max(0, request.getReqCount());

        // Fill pending IDs up to requested count
        fillPendingIdsUpToWindow();

        getAgentListeners().forEach(l -> l.handleRequestMessageIds(request));
    }

    private void handleRequestMessages(MsgRequestMessages request) {
        requestedIds.clear();
        requestedIds.addAll(request.getMessageIds());
        getAgentListeners().forEach(l -> l.handleRequestMessages(request));
    }

    private void removeAcknowledged(int count) {
        if (count <= 0) return;
        int removed = Math.min(count, pendingIds.size());
        for (int i = 0; i < removed; i++) {
            byte[] id = pendingIds.poll();
            if (id != null) removeMessage(id);
        }
        if (count > removed)
            log.warn("Ack count {} exceeded pendingIds size {}", count, removed);
        log.info("Peer acknowledged {} message(s), remaining in queue: {}", removed, pendingMessages.size());
    }

    /**
     * Enqueue a message for submission to peers. Rejects duplicates, full queue,
     * oversized bodies, expired messages, and (post-negotiation) unshared chains.
     *
     * @return true if enqueued, false if rejected
     */
    public boolean enqueueMessage(AppMessage message) {
        if (message.getSize() > config.getMaxMessageSize()) {
            log.warn("Rejecting oversized message {} ({} > {} bytes)",
                    message.getMessageIdHex(), message.getSize(), config.getMaxMessageSize());
            return false;
        }
        long now = System.currentTimeMillis() / 1000;
        if (message.isExpired(now)) {
            log.warn("Rejecting expired message {}", message.getMessageIdHex());
            return false;
        }
        Set<String> negotiated = negotiatedChainIds;
        if (negotiated != null && !negotiated.contains(message.getChainId())) {
            log.debug("Not enqueuing message {} — chain {} not shared with peer",
                    message.getMessageIdHex(), message.getChainId());
            return false;
        }
        if (!config.getChainIds().isEmpty() && !config.getChainIds().contains(message.getChainId())) {
            log.warn("Rejecting message {} for unknown chain {}", message.getMessageIdHex(), message.getChainId());
            return false;
        }

        boolean shouldWake;
        synchronized (this) {
            if (pendingMessages.size() >= maxQueueSize) {
                log.warn("Message queue full ({}/{}), rejecting: {}",
                        pendingMessages.size(), maxQueueSize, message.getMessageIdHex());
                return false;
            }
            if (pendingMessages.stream().anyMatch(m -> Arrays.equals(m.getMessageId(), message.getMessageId()))) {
                return false; // duplicate
            }
            pendingMessages.add(message);
            shouldWake = AppMsgSubmissionState.MessageIdsBlocking.equals(currentState);
            if (shouldWake && hasPendingIdCapacity()) {
                pendingIds.add(message.getMessageId());
            } else {
                shouldWake = false;
            }
        }
        log.info("Message enqueued: {}, total in queue: {}", message.getMessageIdHex(), pendingMessages.size());
        if (shouldWake) {
            Channel ch = getChannel();
            if (ch != null && ch.isActive()) {
                ch.eventLoop().execute(this::sendNextMessage);
            }
        }
        return true;
    }

    public int getQueueSize() {
        return pendingMessages.size();
    }

    /** Chains shared with the peer, or null if MsgInitAck has not arrived yet. */
    public Set<String> getNegotiatedChainIds() {
        return negotiatedChainIds;
    }

    @Override
    public boolean isDone() {
        return currentState == AppMsgSubmissionState.Done;
    }

    @Override
    public void reset() {
        pendingMessages.clear();
        pendingIds.clear();
        requestedIds.clear();
        requestedMessageIdWindow = 0;
        negotiatedChainIds = null;
        this.currentState = AppMsgSubmissionState.Init;
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis() / 1000;
        boolean removed = pendingMessages.removeIf(m -> m.isExpired(now));
        if (removed) {
            pendingIds.removeIf(id -> findMessage(id).isEmpty());
        }
    }

    private Optional<AppMessage> findMessage(byte[] messageId) {
        return pendingMessages.stream()
                .filter(m -> Arrays.equals(m.getMessageId(), messageId))
                .findFirst();
    }

    private void removeMessage(byte[] messageId) {
        pendingMessages.removeIf(m -> Arrays.equals(m.getMessageId(), messageId));
    }

    private boolean containsId(ConcurrentLinkedQueue<byte[]> queue, byte[] id) {
        return queue.stream().anyMatch(existing -> Arrays.equals(existing, id));
    }

    private void fillPendingIdsUpToWindow() {
        int toAdd = requestedMessageIdWindow - pendingIds.size();
        if (toAdd <= 0)
            return;

        pendingMessages.stream()
                .map(AppMessage::getMessageId)
                .filter(id -> !containsId(pendingIds, id))
                .limit(toAdd)
                .forEach(pendingIds::add);
    }

    private boolean hasPendingIdCapacity() {
        return pendingIds.size() < requestedMessageIdWindow;
    }
}
