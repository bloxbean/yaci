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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-side agent for Protocol 100 (App Message Submission).
 * Pulls messages from connected clients using a blocking request pattern.
 * Maintains FIFO queue, bounded processed-id cache, and ack/req window tracking.
 * <p>
 * Enforces on every inbound message body: content-derived message-id recompute,
 * max body size, TTL bounds, chain membership (negotiated via MsgInit/MsgInitAck)
 * and the pluggable {@link AppMsgValidator} (auth/membership/admission). Rejected
 * messages are still acknowledged (the window advances) but never reach listeners.
 */
@Slf4j
public class AppMsgSubmissionServerAgent extends Agent<AppMsgSubmissionListener> {

    private final Queue<AppMessageId> outstandingMessageIds = new ConcurrentLinkedQueue<>();
    /** The ids of the body request in flight, in request order (ADR 0019 D1 chunking). */
    private final List<String> requestedBodyIds = new ArrayList<>();
    /** Announced body size per requested id; a body of another size is a protocol fault. */
    private final Map<String, Integer> requestedBodySizes = new HashMap<>();
    private final Set<String> outstandingMessageIdSet = new HashSet<>();
    private final AtomicInteger pendingAcknowledgments = new AtomicInteger(0);
    private final Set<String> processedMessageIds;
    private final AtomicLong rejectedMessageCount = new AtomicLong(0);
    private volatile Message pendingRequest;
    private final AppMsgSubmissionConfig config;
    private volatile int requestedMessageIdCount;
    /** Chains announced by the connected client in MsgInit. */
    private volatile Set<String> clientChainIds = Set.of();

    public AppMsgSubmissionServerAgent() {
        this(AppMsgSubmissionConfig.createDefault());
    }

    public AppMsgSubmissionServerAgent(AppMsgSubmissionConfig config) {
        super(false); // Server agent
        this.config = config;
        this.processedMessageIds = createBoundedMessageIdCache(config.getProcessedMessageIdCacheSize());
        this.currentState = AppMsgSubmissionState.Init;
    }

    @Override
    public int getProtocolId() {
        return 100;
    }

    @Override
    public void sendRequest(Message message) {
        if (message instanceof MsgRequestMessageIds) {
            requestedMessageIdCount = Math.max(0, ((MsgRequestMessageIds) message).getReqCount());
        }
        super.sendRequest(message);
        if (message instanceof MsgInitAck) {
            // InitAck delivered, state is now Idle — kick off the pull loop
            sendInitialBlockingRequest();
        }
    }

    @Override
    public Message buildNextMessage() {
        log.debug("buildNextMessage() - state: {}, hasAgency: {}, pendingRequest: {}",
                currentState, hasAgency(),
                pendingRequest != null ? pendingRequest.getClass().getSimpleName() : "null");

        if (currentState == AppMsgSubmissionState.InitAck) {
            return new MsgInitAck(new ArrayList<>(config.getChainIds()));
        }

        if (currentState == AppMsgSubmissionState.Idle && pendingRequest != null) {
            if (getChannel() == null) {
                // Test mode
                Message toSend = pendingRequest;
                pendingRequest = null;
                return toSend;
            } else if (getChannel().isActive()) {
                Message toSend = pendingRequest;
                pendingRequest = null;
                if (log.isDebugEnabled())
                    log.debug("Sending {}", toSend.getClass().getSimpleName());
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
            handleInit((MsgInit) message);
        } else if (message instanceof MsgReplyMessageIds) {
            handleReplyMessageIds((MsgReplyMessageIds) message);
        } else if (message instanceof MsgReplyMessages) {
            handleReplyMessages((MsgReplyMessages) message);
        } else {
            log.warn("Unexpected message type: {}", message.getClass().getSimpleName());
        }
    }

    private void handleInit(MsgInit init) {
        this.clientChainIds = Set.copyOf(init.getChainIds());
        Set<String> shared = new HashSet<>(config.getChainIds());
        shared.retainAll(clientChainIds);
        log.info("Received MsgInit from client (chains: {}, shared: {})", clientChainIds, shared);
        if (shared.isEmpty() && !config.getChainIds().isEmpty()) {
            log.warn("No shared app chains with client — session will carry no messages");
        }
        // Send MsgInitAck; the pull loop starts from sendRequest() once it is delivered
        sendNextMessage();
    }

    private void handleReplyMessageIds(MsgReplyMessageIds reply) {
        if (log.isDebugEnabled())
            log.debug("Received ReplyMessageIds with {} IDs", reply.getMessageIds().size());

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

        int processedReplyIds = 0;
        for (AppMessageId mid : reply.getMessageIds()) {
            if (processedReplyIds >= requestedMessageIdCount) {
                log.warn("Peer replied with more app message IDs than requested; ignoring extra ID(s)");
                break;
            }

            String idStr = HexUtil.encodeHexString(mid.getMessageId());
            if (!processedMessageIds.contains(idStr) && !outstandingMessageIdSet.contains(idStr)) {
                outstandingMessageIds.offer(mid);
                outstandingMessageIdSet.add(idStr);
                log.debug("Added message ID to queue: {} (size: {} bytes)", idStr, mid.getSize());
            } else {
                pendingAcknowledgments.incrementAndGet();
                log.debug("Skipping already processed or in-flight message ID: {}", idStr);
            }
            processedReplyIds++;
        }

        getAgentListeners().forEach(l -> l.handleReplyMessageIds(reply));

        if (!outstandingMessageIds.isEmpty()) {
            requestMessageBodies();
        } else {
            log.debug("All {} message IDs were already processed or in-flight; sending next blocking request",
                    reply.getMessageIds().size());
            sendNextBlockingRequest();
        }
    }

    private void handleReplyMessages(MsgReplyMessages reply) {
        if (log.isDebugEnabled())
            log.debug("Received ReplyMessages with {} messages", reply.getMessages().size());

        Set<String> requested = new HashSet<>(requestedBodyIds);
        Map<String, AppMessage> replyByMessageId = new HashMap<>();
        for (AppMessage msg : reply.getMessages()) {
            String messageId = HexUtil.encodeHexString(msg.getMessageId());
            if (requested.contains(messageId)) {
                replyByMessageId.put(messageId, msg);
            } else {
                log.debug("Ignoring unsolicited or duplicate message body: {}", messageId);
            }
        }

        // Every requested id is acknowledged, answered or not: an acknowledgement retires the client's announcement,
        // and acknowledging exactly the ids taken from its reply keeps the client's count-based FIFO aligned. A
        // missing body is skipped (not marked received, so a later offer of it is pulled again).
        List<AppMessage> structurallyValid = new ArrayList<>();
        int missing = 0;
        for (String messageId : requestedBodyIds) {
            if (!removeOutstandingMessageId(messageId)) {
                continue;
            }
            pendingAcknowledgments.incrementAndGet();
            AppMessage msg = replyByMessageId.get(messageId);
            if (msg == null) {
                missing++;
                continue;
            }
            processedMessageIds.add(messageId);
            Integer announced = requestedBodySizes.get(messageId);
            String rejection = announced != null && announced != msg.getSize()
                    ? "body size " + msg.getSize() + " differs from the announced " + announced
                    : checkMessage(msg);
            if (rejection == null) {
                structurallyValid.add(msg);
            } else {
                rejectMessage(messageId, rejection);
            }
        }
        requestedBodyIds.clear();
        requestedBodySizes.clear();
        if (missing > 0) {
            log.warn("Peer omitted {} requested app message bod(ies); acknowledged and skipped", missing);
        }
        MsgReplyMessages acceptedReply = new MsgReplyMessages(validate(structurallyValid));
        getAgentListeners().forEach(l -> l.handleReplyMessages(acceptedReply));

        if (!outstandingMessageIds.isEmpty()) {
            requestMessageBodies();
        } else {
            sendNextBlockingRequest();
        }
    }

    /** Runs the pluggable validator once over a reply's structurally valid messages (ADR 0019 D5). */
    private List<AppMessage> validate(List<AppMessage> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        List<AppMsgValidator.Result> results;
        try {
            results = config.getValidator().validateAll(messages);
        } catch (RuntimeException e) {
            log.warn("App message validator failed; rejecting {} message(s): {}", messages.size(), e.toString());
            results = null;
        }
        if (results == null || results.size() != messages.size()) {
            if (results != null) {
                log.warn("App message validator returned {} result(s) for {} message(s); rejecting the reply",
                        results.size(), messages.size());
            }
            rejectedMessageCount.addAndGet(messages.size());
            return List.of();
        }
        List<AppMessage> accepted = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            AppMsgValidator.Result result = results.get(i);
            AppMessage msg = messages.get(i);
            if (result != null && result.isAccepted()) {
                accepted.add(msg);
            } else {
                rejectMessage(msg.getMessageIdHex(), result == null || result.getReason() == null
                        ? "rejected by validator" : result.getReason());
            }
        }
        return accepted;
    }

    private void rejectMessage(String messageId, String reason) {
        rejectedMessageCount.incrementAndGet();
        if (log.isDebugEnabled())
            log.debug("Rejected inbound app message {}: {}", messageId, reason);
    }

    /**
     * Structural validation of one inbound message; the pluggable validator runs afterwards over the reply.
     * @return null if accepted, otherwise the rejection reason
     */
    private String checkMessage(AppMessage msg) {
        if (msg.getVersion() != AppMessage.ENVELOPE_VERSION)
            return "unsupported envelope version: " + msg.getVersion();
        if (!msg.hasValidMessageId())
            return "message-id mismatch (content-derived id verification failed)";
        if (msg.getSize() > config.getMaxMessageSize())
            return "body exceeds max size (" + msg.getSize() + " > " + config.getMaxMessageSize() + ")";

        long now = System.currentTimeMillis() / 1000;
        if (msg.isExpired(now))
            return "expired (expiresAt=" + msg.getExpiresAt() + ")";
        if (config.getMaxTtlSeconds() > 0 && msg.getExpiresAt() > now + config.getMaxTtlSeconds())
            return "expiresAt too far in the future (max TTL " + config.getMaxTtlSeconds() + "s)";

        if (!config.getChainIds().isEmpty() && !config.getChainIds().contains(msg.getChainId()))
            return "chain not served: " + msg.getChainId();

        return null;
    }

    private void sendInitialBlockingRequest() {
        short ack = 0;
        short req = requestWindow();
        log.debug("Sending initial blocking request: ack={}, req={}", ack, req);
        requestMessageIds(ack, req, true);
    }

    private void sendNextBlockingRequest() {
        short ack = (short) Math.min(pendingAcknowledgments.getAndSet(0), Short.MAX_VALUE);
        short req = requestWindow();
        if (log.isDebugEnabled())
            log.debug("Sending next blocking request: ack={}, req={}", ack, req);
        requestMessageIds(ack, req, true);
    }

    /** Ids requested per round: {@code min(batchSize, maxUnacknowledged)}, at least 1, at most a uint16 short. */
    private short requestWindow() {
        int window = Math.min(config.getBatchSize(), config.getMaxUnacknowledged());
        return (short) Math.max(1, Math.min(window, Short.MAX_VALUE));
    }

    private void requestMessageIds(short ack, short req, boolean blocking) {
        if (currentState != AppMsgSubmissionState.Idle) {
            log.warn("Cannot request message IDs in state: {}", currentState);
            return;
        }

        MsgRequestMessageIds request = new MsgRequestMessageIds(blocking, ack, req);
        requestedMessageIdCount = Math.max(0, req);
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

        // The longest prefix of outstanding ids whose announced sizes fit the byte budget, never empty.
        MsgRequestMessages request = new MsgRequestMessages();
        requestedBodyIds.clear();
        requestedBodySizes.clear();
        long budget = Math.max(1, config.getMaxBodiesBytesPerRequest());
        long bytes = 0;
        for (AppMessageId mid : outstandingMessageIds) {
            long size = Math.max(0, mid.getSize());
            if (!requestedBodyIds.isEmpty() && bytes + size > budget) {
                break;
            }
            bytes += size;
            request.addMessageId(mid.getMessageId());
            String hex = HexUtil.encodeHexString(mid.getMessageId());
            requestedBodyIds.add(hex);
            requestedBodySizes.put(hex, mid.getSize());
        }
        if (log.isDebugEnabled())
            log.debug("Requesting {} message bodies ({} bytes announced, {} outstanding)",
                    request.getMessageIds().size(), bytes, outstandingMessageIds.size());

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
        outstandingMessageIdSet.clear();
        requestedBodyIds.clear();
        requestedBodySizes.clear();
        processedMessageIds.clear();
        requestedMessageIdCount = 0;
        pendingAcknowledgments.set(0);
        pendingRequest = null;
        rejectedMessageCount.set(0);
        clientChainIds = Set.of();
        this.currentState = AppMsgSubmissionState.Init;
    }

    @Override
    public void shutdown() {
        log.info("Shutting down AppMsgSubmissionServerAgent");
        reset();
    }

    public int getReceivedMessageIdCount() {
        return processedMessageIds.size();
    }

    public boolean hasReceivedMessageId(String hexId) {
        return processedMessageIds.contains(hexId);
    }

    public int getOutstandingMessageCount() {
        return outstandingMessageIds.size();
    }

    public int getPendingAcknowledgments() {
        return pendingAcknowledgments.get();
    }

    public long getRejectedMessageCount() {
        return rejectedMessageCount.get();
    }

    public Set<String> getClientChainIds() {
        return clientChainIds;
    }

    public AppMsgSubmissionConfig getConfig() {
        return config;
    }

    private Set<String> createBoundedMessageIdCache(int maxSize) {
        final int boundedMaxSize = Math.max(1, maxSize);
        return Collections.synchronizedSet(Collections.newSetFromMap(
                new LinkedHashMap<String, Boolean>(boundedMaxSize, 0.75f, false) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                        return size() > boundedMaxSize;
                    }
                }));
    }

    private boolean removeOutstandingMessageId(String messageId) {
        if (!outstandingMessageIdSet.remove(messageId))
            return false;

        // Bodies are requested as a prefix of the outstanding queue, so the id is normally at its head.
        AppMessageId head = outstandingMessageIds.peek();
        if (head != null && messageId.equals(HexUtil.encodeHexString(head.getMessageId()))) {
            outstandingMessageIds.poll();
            return true;
        }
        outstandingMessageIds.removeIf(mid ->
                messageId.equals(HexUtil.encodeHexString(mid.getMessageId())));
        return true;
    }
}
