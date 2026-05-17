package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessageId;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.*;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import io.netty.channel.Channel;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-side agent for Protocol 100 (App Message Submission).
 * Pulls messages from connected clients using a blocking request pattern.
 * Maintains FIFO queue, dedup set, and ack/req window tracking.
 */
@Slf4j
public class AppMsgSubmissionServerAgent extends Agent<AppMsgSubmissionListener> {

    private static final int MAX_UNACKNOWLEDGED = 10;
    private static final int DEFAULT_BATCH_SIZE = 10;

    private final Queue<AppMessageId> outstandingMessageIds = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingAcknowledgments = new AtomicInteger(0);
    private final Set<String> seenMessageIds = new HashSet<>();
    private volatile Message pendingRequest;
    private final AppMsgSubmissionConfig config;

    public AppMsgSubmissionServerAgent() {
        this(AppMsgSubmissionConfig.createDefault());
    }

    public AppMsgSubmissionServerAgent(AppMsgSubmissionConfig config) {
        super(false); // Server agent
        this.config = config;
        this.currentState = AppMsgSubmissionState.Init;
    }

    @Override
    public int getProtocolId() {
        return 100;
    }

    @Override
    public Message buildNextMessage() {
        log.debug("buildNextMessage() - state: {}, hasAgency: {}, pendingRequest: {}",
                currentState, hasAgency(),
                pendingRequest != null ? pendingRequest.getClass().getSimpleName() : "null");

        if (currentState == AppMsgSubmissionState.Idle && pendingRequest != null) {
            if (getChannel() == null) {
                // Test mode
                Message toSend = pendingRequest;
                pendingRequest = null;
                return toSend;
            } else if (getChannel().isActive()) {
                Message toSend = pendingRequest;
                pendingRequest = null;
                log.info("Sending {}", toSend.getClass().getSimpleName());
                return toSend;
            } else {
                log.debug("Channel not active, keeping pendingRequest");
                return null;
            }
        }
        return null;
    }

    @Override
    public void processResponse(Message message) {
        if (message == null) return;

        log.debug("Processing: {} in state: {}", message.getClass().getSimpleName(), currentState);

        if (message instanceof MsgInit) {
            handleInit();
        } else if (message instanceof MsgReplyMessageIds) {
            handleReplyMessageIds((MsgReplyMessageIds) message);
        } else if (message instanceof MsgReplyMessages) {
            handleReplyMessages((MsgReplyMessages) message);
        } else {
            log.warn("Unexpected message type: {}", message.getClass().getSimpleName());
        }
    }

    private void handleInit() {
        log.info("Received MsgInit from client");
        sendInitialBlockingRequest();
    }

    private void handleReplyMessageIds(MsgReplyMessageIds reply) {
        log.info("Received ReplyMessageIds with {} IDs", reply.getMessageIds().size());

        if (reply.getMessageIds().isEmpty()) {
            log.debug("No messages available from peer, will retry after delay");
            Channel ch = getChannel();
            if (ch != null && ch.isActive()) {
                ch.eventLoop().schedule(() -> {
                    sendNextBlockingRequest();
                    if (hasAgency()) {
                        sendNextMessage();
                    }
                }, 1, TimeUnit.SECONDS);
            }
            return;
        }

        for (AppMessageId mid : reply.getMessageIds()) {
            String idStr = HexUtil.encodeHexString(mid.getMessageId());
            if (!seenMessageIds.contains(idStr)) {
                outstandingMessageIds.offer(mid);
                seenMessageIds.add(idStr);
                log.debug("Added message ID to queue: {} (size: {} bytes)", idStr, mid.getSize());
            }
        }

        getAgentListeners().forEach(l -> l.handleReplyMessageIds(reply));

        if (!outstandingMessageIds.isEmpty()) {
            requestMessageBodies();
        } else {
            // All IDs were already seen (deduped) — ack and continue polling
            log.debug("All {} message IDs already seen, sending next blocking request", reply.getMessageIds().size());
            pendingAcknowledgments.addAndGet(reply.getMessageIds().size());
            sendNextBlockingRequest();
        }
    }

    private void handleReplyMessages(MsgReplyMessages reply) {
        log.info("Received ReplyMessages with {} messages", reply.getMessages().size());

        for (AppMessage msg : reply.getMessages()) {
            AppMessageId processedId = outstandingMessageIds.poll();
            if (processedId != null) {
                pendingAcknowledgments.incrementAndGet();
                log.debug("Processed message, pending acks: {}", pendingAcknowledgments.get());
            }
        }

        getAgentListeners().forEach(l -> l.handleReplyMessages(reply));

        if (outstandingMessageIds.isEmpty()) {
            log.info("All messages processed, sending next blocking request");
            sendNextBlockingRequest();
        }
    }

    private void sendInitialBlockingRequest() {
        short ack = 0;
        short req = (short) Math.min(config.getBatchSize(), MAX_UNACKNOWLEDGED);
        log.info("Sending initial blocking request: ack={}, req={}", ack, req);
        requestMessageIds(ack, req, true);
    }

    private void sendNextBlockingRequest() {
        short ack = (short) Math.min(pendingAcknowledgments.getAndSet(0), Short.MAX_VALUE);
        short req = (short) Math.min(config.getBatchSize(), MAX_UNACKNOWLEDGED);
        log.info("Sending next blocking request: ack={}, req={}", ack, req);
        requestMessageIds(ack, req, true);
    }

    private void requestMessageIds(short ack, short req, boolean blocking) {
        if (currentState != AppMsgSubmissionState.Idle) {
            log.warn("Cannot request message IDs in state: {}", currentState);
            return;
        }

        MsgRequestMessageIds request = new MsgRequestMessageIds(blocking, ack, req);
        this.pendingRequest = request;

        if (hasAgency()) {
            if (getChannel() != null && getChannel().isActive()) {
                sendNextMessage();
            }
        }
    }

    private void requestMessageBodies() {
        if (currentState != AppMsgSubmissionState.Idle) {
            log.warn("Cannot request message bodies in state: {}", currentState);
            return;
        }

        MsgRequestMessages request = new MsgRequestMessages();
        for (AppMessageId mid : outstandingMessageIds) {
            request.addMessageId(mid.getMessageId());
        }
        log.info("Requesting {} message bodies", request.getMessageIds().size());

        this.pendingRequest = request;

        if (hasAgency() && getChannel() != null && getChannel().isActive()) {
            sendNextMessage();
        }
    }

    @Override
    public boolean isDone() {
        return currentState == AppMsgSubmissionState.Done;
    }

    @Override
    public void reset() {
        outstandingMessageIds.clear();
        seenMessageIds.clear();
        pendingAcknowledgments.set(0);
        pendingRequest = null;
        this.currentState = AppMsgSubmissionState.Init;
    }

    @Override
    public void shutdown() {
        log.info("Shutting down AppMsgSubmissionServerAgent");
        reset();
    }

    public int getReceivedMessageIdCount() {
        return seenMessageIds.size();
    }

    public boolean hasReceivedMessageId(String hexId) {
        return seenMessageIds.contains(hexId);
    }

    public int getOutstandingMessageCount() {
        return outstandingMessageIds.size();
    }

    public int getPendingAcknowledgments() {
        return pendingAcknowledgments.get();
    }

    public AppMsgSubmissionConfig getConfig() {
        return config;
    }
}
