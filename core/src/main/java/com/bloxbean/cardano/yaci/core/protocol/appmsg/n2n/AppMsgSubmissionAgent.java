package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessageId;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.*;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Client-side agent for Protocol 100 (App Message Submission).
 * Maintains a queue of pending messages and responds to server requests
 * for message IDs and bodies.
 */
@Slf4j
public class AppMsgSubmissionAgent extends Agent<AppMsgSubmissionListener> {
    private static final int DEFAULT_MAX_QUEUE_SIZE = 1000;

    private final ConcurrentLinkedQueue<AppMessage> pendingMessages;
    private final ConcurrentLinkedQueue<byte[]> pendingIds;
    private final ConcurrentLinkedQueue<byte[]> requestedIds;
    private final int maxQueueSize;

    public AppMsgSubmissionAgent() {
        this(DEFAULT_MAX_QUEUE_SIZE);
    }

    public AppMsgSubmissionAgent(int maxQueueSize) {
        super(true); // Client agent
        this.currentState = AppMsgSubmissionState.Init;
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
                return new MsgInit();
            case MessageIdsBlocking:
                // In blocking mode, hold agency until we have actual message IDs to report.
                // Don't rely solely on pendingIds — it may have stale entries (IDs whose
                // messages were already delivered and removed from pendingMessages).
                // enqueueMessage() will trigger sendNextMessage() when new messages arrive.
                if (pendingMessages.isEmpty()) {
                    pendingIds.clear(); // Clean up stale IDs
                    return null;
                }
                return buildReplyMessageIds();
            case MessageIdsNonBlocking:
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

        if (message instanceof MsgInit) {
            log.debug("Received MsgInit");
        } else if (message instanceof MsgRequestMessageIds) {
            handleRequestMessageIds((MsgRequestMessageIds) message);
        } else if (message instanceof MsgRequestMessages) {
            handleRequestMessages((MsgRequestMessages) message);
        }
    }

    private void handleRequestMessageIds(MsgRequestMessageIds request) {
        if (log.isDebugEnabled())
            log.debug("RequestMessageIds - blocking: {}, ack: {}, req: {}",
                    request.isBlocking(), request.getAckCount(), request.getReqCount());

        // Process acknowledgments
        removeAcknowledged(request.getAckCount());

        // Fill pending IDs up to requested count
        int toAdd = request.getReqCount() - pendingIds.size();
        if (toAdd > 0) {
            pendingMessages.stream()
                    .map(AppMessage::getMessageId)
                    .filter(id -> !containsId(pendingIds, id))
                    .limit(toAdd)
                    .forEach(pendingIds::add);
        }

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
     * Enqueue a message for submission to peers.
     *
     * @return true if enqueued, false if rejected (queue full or duplicate)
     */
    public boolean enqueueMessage(AppMessage message) {
        boolean shouldWake;
        synchronized (this) {
            if (pendingMessages.size() >= maxQueueSize) {
                log.warn("Message queue full ({}/{}), rejecting: {}", pendingMessages.size(), maxQueueSize, message.getMessageIdHex());
                return false;
            }
            if (pendingMessages.stream().anyMatch(m -> Arrays.equals(m.getMessageId(), message.getMessageId()))) {
                return false; // duplicate
            }
            pendingMessages.add(message);
            shouldWake = AppMsgSubmissionState.MessageIdsBlocking.equals(currentState);
            if (shouldWake) {
                pendingIds.add(message.getMessageId());
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

    @Override
    public boolean isDone() {
        return currentState == AppMsgSubmissionState.Done;
    }

    @Override
    public void reset() {
        pendingMessages.clear();
        pendingIds.clear();
        requestedIds.clear();
        this.currentState = AppMsgSubmissionState.Init;
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
}
