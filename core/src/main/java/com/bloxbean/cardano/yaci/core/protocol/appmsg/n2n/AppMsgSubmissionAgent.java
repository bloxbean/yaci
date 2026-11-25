package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessageId;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.*;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Client-side agent for Protocol 100 (App Message Submission).
 * Maintains a queue of pending messages and responds to server requests
 * for message IDs and bodies. Only messages for chains negotiated via
 * MsgInit/MsgInitAck are offered; expired messages are dropped on offer.
 * <p>
 * Pending messages live in insertion-ordered maps keyed by message id, so enqueue, duplicate detection, lookup and
 * removal are constant time (ADR 0019 D2). Messages selected by {@link AppMsgSubmissionConfig#getPriority()} have
 * their own capacity and are offered before ordinary messages (D3). All bookkeeping is guarded by this agent's
 * monitor, the same one {@link Agent#receiveResponse} holds. {@link #enqueueMessage} never writes: its wake runs on
 * the event loop.
 */
@Slf4j
public class AppMsgSubmissionAgent extends Agent<AppMsgSubmissionListener> {

    private static final long QUEUE_FULL_WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    /** Undelivered messages, by id, in arrival order. */
    private final LinkedHashMap<MessageKey, AppMessage> pendingPriority = new LinkedHashMap<>();
    private final LinkedHashMap<MessageKey, AppMessage> pendingOrdinary = new LinkedHashMap<>();
    /** Ids offered to the server and not yet acknowledged, in offer order. */
    private final ArrayDeque<MessageKey> offeredIds = new ArrayDeque<>();
    private final Set<MessageKey> offeredIdSet = new HashSet<>();
    /** Ids whose bodies the server asked for, answered by the next reply. */
    private final List<MessageKey> requestedIds = new ArrayList<>();

    private final AppMsgSubmissionConfig config;
    private final Predicate<AppMessage> priority;
    private final int maxQueueSize;
    private final int maxPriorityQueueSize;
    private final long maxPriorityBytes;
    private long priorityBytes;
    private int requestedMessageIdWindow;
    /**
     * A reply to the current server request has been built. The state changes only once the write completes, so
     * without this flag a second wake could send the same ids again and the server would over-acknowledge.
     */
    private boolean replyBuilt;
    /** Chains both sides share; null until MsgInitAck received (then never null). */
    private volatile Set<String> negotiatedChainIds;

    private final AtomicLong rejectedQueueFull = new AtomicLong();
    private long rejectedSinceWarn;
    private long lastQueueFullWarnNanos = System.nanoTime() - QUEUE_FULL_WARN_INTERVAL_NANOS;

    public AppMsgSubmissionAgent() {
        this(AppMsgSubmissionConfig.createDefault());
    }

    public AppMsgSubmissionAgent(AppMsgSubmissionConfig config) {
        this(config, config.getMaxPendingMessages());
    }

    /** @param maxQueueSize capacity for ordinary messages; overrides {@code config.maxPendingMessages} */
    public AppMsgSubmissionAgent(AppMsgSubmissionConfig config, int maxQueueSize) {
        super(true); // Client agent
        this.currentState = AppMsgSubmissionState.Init;
        this.config = config;
        this.priority = config.getPriority();
        this.maxQueueSize = maxQueueSize;
        this.maxPriorityQueueSize = config.getMaxPendingPriorityMessages();
        this.maxPriorityBytes = config.getMaxPendingPriorityBytes();
    }

    @Override
    public int getProtocolId() {
        return 100;
    }

    @Override
    public Message buildNextMessage() {
        synchronized (this) {
            switch ((AppMsgSubmissionState) currentState) {
                case Init:
                    return new MsgInit(new ArrayList<>(config.getChainIds()));
                case MessageIdsBlocking:
                    if (replyBuilt) {
                        return null;
                    }
                    // In blocking mode, hold agency until there is something to offer; enqueueMessage() wakes the
                    // agent when a message arrives. Offered ids whose messages were already delivered are stale.
                    sweepExpired();
                    if (pendingCount() == 0) {
                        clearOffered();
                        return null;
                    }
                    return buildReplyMessageIds();
                case MessageIdsNonBlocking:
                    if (replyBuilt) {
                        return null;
                    }
                    sweepExpired();
                    return buildReplyMessageIds();
                case Messages:
                    if (replyBuilt) {
                        return null;
                    }
                    return buildReplyMessages();
                default:
                    return null;
            }
        }
    }

    /** Caller holds the monitor. */
    private MsgReplyMessageIds buildReplyMessageIds() {
        MsgReplyMessageIds reply = new MsgReplyMessageIds();
        int offered = 0;
        for (MessageKey key : offeredIds) {
            if (offered >= requestedMessageIdWindow) {
                break;
            }
            AppMessage message = pending(key);
            if (message != null) {
                reply.addMessageId(new AppMessageId(message.getMessageId(), message.getSize()));
            }
            offered++;
        }
        replyBuilt = true;
        if (log.isDebugEnabled()) {
            log.debug("Sending {} message ID(s) to peer", reply.getMessageIds().size());
        }
        return reply;
    }

    /** Caller holds the monitor. */
    private MsgReplyMessages buildReplyMessages() {
        MsgReplyMessages reply = new MsgReplyMessages();
        for (MessageKey key : requestedIds) {
            AppMessage message = removePending(key);
            if (message != null) {
                reply.addMessage(message);
            }
        }
        requestedIds.clear();
        replyBuilt = true;
        if (log.isDebugEnabled()) {
            log.debug("Sending {} message(s) to peer", reply.getMessages().size());
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
        synchronized (this) {
            this.negotiatedChainIds = Collections.unmodifiableSet(shared);
            // Drop queued messages for chains the peer doesn't serve
            pendingPriority.values().removeIf(m -> !shared.contains(m.getChainId()));
            priorityBytes = pendingPriority.values().stream().mapToLong(AppMessage::getSize).sum();
            pendingOrdinary.values().removeIf(m -> !shared.contains(m.getChainId()));
            removeOfferedIf(key -> pending(key) == null);
        }
        if (shared.isEmpty()) {
            log.warn("No shared app chains with peer (local: {}, peer: {}) — nothing will be offered",
                    config.getChainIds(), ack.getChainIds());
        } else {
            log.info("App chains negotiated with peer: {}", shared);
        }
        getAgentListeners().forEach(l -> l.handleInitAck(ack));
    }

    private void handleRequestMessageIds(MsgRequestMessageIds request) {
        if (log.isDebugEnabled())
            log.debug("RequestMessageIds - blocking: {}, ack: {}, req: {}",
                    request.isBlocking(), request.getAckCount(), request.getReqCount());

        synchronized (this) {
            replyBuilt = false;
            removeAcknowledged(request.getAckCount());
            requestedMessageIdWindow = Math.max(0, request.getReqCount());
            fillOfferedUpToWindow();
        }

        getAgentListeners().forEach(l -> l.handleRequestMessageIds(request));
    }

    private void handleRequestMessages(MsgRequestMessages request) {
        synchronized (this) {
            replyBuilt = false;
            requestedIds.clear();
            for (byte[] id : request.getMessageIds()) {
                requestedIds.add(new MessageKey(id));
            }
        }
        getAgentListeners().forEach(l -> l.handleRequestMessages(request));
    }

    /** Caller holds the monitor. */
    private void removeAcknowledged(int count) {
        if (count <= 0) return;
        int removed = 0;
        while (removed < count && !offeredIds.isEmpty()) {
            MessageKey key = offeredIds.poll();
            offeredIdSet.remove(key);
            removePending(key);
            removed++;
        }
        if (count > removed)
            log.warn("Ack count {} exceeded offered id count {}", count, removed);
        if (log.isDebugEnabled())
            log.debug("Peer acknowledged {} message(s), remaining in queue: {}", removed, pendingCount());
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
            log.debug("Rejecting expired message {}", message.getMessageIdHex());
            return false;
        }
        if (!config.getChainIds().isEmpty() && !config.getChainIds().contains(message.getChainId())) {
            log.warn("Rejecting message {} for unknown chain {}", message.getMessageIdHex(), message.getChainId());
            return false;
        }

        boolean urgent = priority != null && priority.test(message);
        MessageKey key = new MessageKey(message.getMessageId());
        boolean shouldWake;
        synchronized (this) {
            // Checked under the monitor, so a message cannot slip in after MsgInitAck pruned its chain.
            Set<String> negotiated = negotiatedChainIds;
            if (negotiated != null && !negotiated.contains(message.getChainId())) {
                log.debug("Not enqueuing message {} — chain {} not shared with peer",
                        message.getMessageIdHex(), message.getChainId());
                return false;
            }
            if (pendingPriority.containsKey(key) || pendingOrdinary.containsKey(key)) {
                return false; // duplicate
            }
            LinkedHashMap<MessageKey, AppMessage> lane = urgent ? pendingPriority : pendingOrdinary;
            if (lane.size() >= (urgent ? maxPriorityQueueSize : maxQueueSize)
                    || urgent && priorityBytes + message.getSize() > maxPriorityBytes) {
                recordQueueFull(urgent, lane.size());
                return false;
            }
            lane.put(key, message);
            if (urgent) {
                priorityBytes += message.getSize();
            }
            shouldWake = AppMsgSubmissionState.MessageIdsBlocking.equals(currentState) && !replyBuilt
                    && hasOfferCapacity();
            if (shouldWake) {
                offer(key);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Message enqueued: {}, priority: {}", message.getMessageIdHex(), urgent);
        }
        if (shouldWake) {
            Channel ch = getChannel();
            if (ch != null && ch.isActive()) {
                ch.eventLoop().execute(this::sendNextMessage);
            }
        }
        return true;
    }

    /** Caller holds the monitor. */
    private void recordQueueFull(boolean urgent, int size) {
        rejectedQueueFull.incrementAndGet();
        rejectedSinceWarn++;
        long now = System.nanoTime();
        if (now - lastQueueFullWarnNanos >= QUEUE_FULL_WARN_INTERVAL_NANOS) {
            log.warn("App message queue full ({} {}/{}): rejected {} message(s) since the last warning",
                    urgent ? "priority" : "ordinary", size, urgent ? maxPriorityQueueSize : maxQueueSize,
                    rejectedSinceWarn);
            rejectedSinceWarn = 0;
            lastQueueFullWarnNanos = now;
        }
    }

    /** Undelivered messages (both lanes), including ones offered but not yet requested. */
    public synchronized int getQueueSize() {
        return pendingCount();
    }

    public synchronized int getPriorityQueueSize() {
        return pendingPriority.size();
    }

    /** Messages rejected because their lane was full, since this agent was created. */
    public long getRejectedQueueFullCount() {
        return rejectedQueueFull.get();
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
    public synchronized void reset() {
        pendingPriority.clear();
        priorityBytes = 0;
        pendingOrdinary.clear();
        clearOffered();
        requestedIds.clear();
        requestedMessageIdWindow = 0;
        replyBuilt = false;
        negotiatedChainIds = null;
        this.currentState = AppMsgSubmissionState.Init;
    }

    /**
     * Drops expired messages that have not been offered. An offered message leaves only by delivery or
     * acknowledgement — its body is sent even if it expired meanwhile and the server rejects it — so the server's
     * count-based acknowledgements stay aligned with the offer order. Messages mostly arrive with one TTL policy, so
     * each lane is swept from its head up to the first unexpired message; an expired message behind it is removed
     * when it reaches the head or would be offered. Caller holds the monitor.
     */
    private void sweepExpired() {
        long now = System.currentTimeMillis() / 1000;
        sweepHead(pendingPriority, now);
        sweepHead(pendingOrdinary, now);
    }

    private void sweepHead(LinkedHashMap<MessageKey, AppMessage> lane, long now) {
        Iterator<Map.Entry<MessageKey, AppMessage>> it = lane.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<MessageKey, AppMessage> entry = it.next();
            if (offeredIdSet.contains(entry.getKey())) {
                continue;
            }
            if (!entry.getValue().isExpired(now)) {
                return;
            }
            it.remove();
            released(lane, entry.getValue());
        }
    }

    /**
     * Caller holds the monitor. Offers new ids until the requested window is full: priority ids first but at most
     * half the window, so a flood of priority messages cannot starve ordinary ones (with a window of 1 the ordinary
     * message goes first), then ordinary ids, then more priority ids if room remains.
     */
    private void fillOfferedUpToWindow() {
        if (!hasOfferCapacity()) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        fillFrom(pendingPriority, now, requestedMessageIdWindow / 2);
        fillFrom(pendingOrdinary, now, Integer.MAX_VALUE);
        fillFrom(pendingPriority, now, Integer.MAX_VALUE);
    }

    private void released(LinkedHashMap<MessageKey, AppMessage> lane, AppMessage message) {
        if (lane == pendingPriority) {
            priorityBytes -= message.getSize();
        }
    }

    private void fillFrom(LinkedHashMap<MessageKey, AppMessage> lane, long now, int limit) {
        Iterator<Map.Entry<MessageKey, AppMessage>> it = lane.entrySet().iterator();
        while (it.hasNext() && hasOfferCapacity() && limit > 0) {
            Map.Entry<MessageKey, AppMessage> entry = it.next();
            if (offeredIdSet.contains(entry.getKey())) {
                continue;
            }
            if (entry.getValue().isExpired(now)) {
                it.remove();
                released(lane, entry.getValue());
                continue;
            }
            offer(entry.getKey());
            limit--;
        }
    }

    private void offer(MessageKey key) {
        if (offeredIdSet.add(key)) {
            offeredIds.add(key);
        }
    }

    private void removeOfferedIf(Predicate<MessageKey> condition) {
        offeredIds.removeIf(key -> {
            if (condition.test(key)) {
                offeredIdSet.remove(key);
                return true;
            }
            return false;
        });
    }

    private void clearOffered() {
        offeredIds.clear();
        offeredIdSet.clear();
    }

    private boolean hasOfferCapacity() {
        return offeredIds.size() < requestedMessageIdWindow;
    }

    private int pendingCount() {
        return pendingPriority.size() + pendingOrdinary.size();
    }

    private AppMessage pending(MessageKey key) {
        AppMessage message = pendingPriority.get(key);
        return message != null ? message : pendingOrdinary.get(key);
    }

    private AppMessage removePending(MessageKey key) {
        AppMessage message = pendingPriority.remove(key);
        if (message != null) {
            priorityBytes -= message.getSize();
            return message;
        }
        return pendingOrdinary.remove(key);
    }

    /** Message id with value equality. */
    private static final class MessageKey {
        private final byte[] id;
        private final int hash;

        MessageKey(byte[] id) {
            this.id = id;
            this.hash = Arrays.hashCode(id);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MessageKey && Arrays.equals(id, ((MessageKey) other).id);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
