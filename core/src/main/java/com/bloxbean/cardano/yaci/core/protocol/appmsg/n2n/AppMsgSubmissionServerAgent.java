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

    private static final int MAX_UNACKNOWLEDGED = 10;
    private static final int DEFAULT_BATCH_SIZE = 10;

    private final Queue<AppMessageId> outstandingMessageIds = new ConcurrentLinkedQueue<>();
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
        log.info("Received ReplyMessages with {} messages", reply.getMessages().size());

        Map<String, AppMessage> replyByMessageId = new HashMap<>();
        for (AppMessage msg : reply.getMessages()) {
            String messageId = HexUtil.encodeHexString(msg.getMessageId());
            if (outstandingMessageIdSet.contains(messageId)) {
                replyByMessageId.put(messageId, msg);
            } else {
                log.debug("Ignoring unsolicited or duplicate message body: {}", messageId);
            }
        }

        List<AppMessage> acceptedMessages = new ArrayList<>();
        while (!outstandingMessageIds.isEmpty()) {
            AppMessageId outstandingId = outstandingMessageIds.peek();
            String messageId = HexUtil.encodeHexString(outstandingId.getMessageId());
            AppMessage msg = replyByMessageId.get(messageId);
            if (msg == null)
                break;

            if (removeOutstandingMessageId(messageId)) {
                processedMessageIds.add(messageId);
                pendingAcknowledgments.incrementAndGet();
                String rejection = checkMessage(msg);
                if (rejection == null) {
                    acceptedMessages.add(msg);
                    log.debug("Processed message {}, pending acks: {}", messageId, pendingAcknowledgments.get());
                } else {
                    rejectedMessageCount.incrementAndGet();
                    log.warn("Rejected inbound app message {}: {}", messageId, rejection);
                }
            }
        }

        if (!outstandingMessageIds.isEmpty()) {
            log.warn("Peer replied with missing or out-of-order app messages; dropping {} unresolved message ID(s)",
                    outstandingMessageIds.size());
            outstandingMessageIds.clear();
            outstandingMessageIdSet.clear();
        }

        MsgReplyMessages acceptedReply = new MsgReplyMessages(acceptedMessages);
        getAgentListeners().forEach(l -> l.handleReplyMessages(acceptedReply));

        log.info("Message reply processed, sending next blocking request");
        sendNextBlockingRequest();
    }

    /**
     * Structural + pluggable validation of one inbound message.
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

        AppMsgValidator.Result result = config.getValidator().validate(msg);
        if (!result.isAccepted())
            return result.getReason() != null ? result.getReason() : "rejected by validator";

        return null;
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
        outstandingMessageIdSet.clear();
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

        outstandingMessageIds.removeIf(mid ->
                messageId.equals(HexUtil.encodeHexString(mid.getMessageId())));
        return true;
    }
}
