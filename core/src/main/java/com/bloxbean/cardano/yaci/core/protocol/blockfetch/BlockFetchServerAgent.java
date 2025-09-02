package com.bloxbean.cardano.yaci.core.protocol.blockfetch;

import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class BlockFetchServerAgent extends Agent<BlockfetchAgentListener> {

    private static final int MAX_QUEUE_SIZE = 100;
    private final ChainState chainState;
    private final BlockingQueue<RequestRange> requestQueue;
    private final ExecutorService executor;

    private final AtomicBoolean processing = new AtomicBoolean(false);

    private final Queue<Message> pendingMessages = new ConcurrentLinkedQueue<>();
    private final AtomicInteger counter = new AtomicInteger(0);

    public BlockFetchServerAgent(ChainState chainState) {
        super(false);
        this.chainState = chainState;
        this.currenState = BlockfetchState.Idle;
        this.requestQueue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
        this.executor = Executors.newSingleThreadExecutor();
    }


    @Override
    public int getProtocolId() {
        return 3;
    }

    @Override
    public void sendRequest(Message message) {
        //DON'T call super.sendRequest() here, as we want to manage state transitions manually in the agent
        log.debug("BlockFetch sending message: {} in state: {}", message.getClass().getSimpleName(), currenState);

        if (log.isDebugEnabled()) {
            log.debug("BlockFetch state after sending {}: {}", message.getClass().getSimpleName(), currenState);
        }
    }

    @Override
    public void receiveResponse(Message message) {
        // Override to prevent automatic state transitions from base Agent class
        // The base Agent.receiveResponse() automatically transitions state based on message type
        // But we need to manage state transitions manually for proper async loading

        if (log.isDebugEnabled()) {
            log.debug("BlockFetch received message: {} in state: {}", message.getClass().getSimpleName(), currenState);
        }

        // Process the message without automatic state transitions
        processResponse(message);

        // Notify listeners about the message (but don't change state)
        getAgentListeners().forEach(listener ->
                listener.onStateUpdate(currenState, currenState));
    }

    @Override
    public Message buildNextMessage() {
        return null;
    }

    private synchronized void maybeStartProcessing() {
        if (!processing.get()) {
            executor.submit(this::processNext);
        }
    }

    private synchronized void processNext() {
        if (!processing.compareAndSet(false, true)) return;

        // Check if channel is still active
        if (getChannel() == null || !getChannel().isActive()) {
            log.warn("Channel inactive during processing, stopping BlockFetch processing");
            processing.set(false);
            return;
        }

        if (!getChannel().isWritable()) {
            processing.set(false);
            return;
        }

        try {
            RequestRange request = requestQueue.poll();
            if (request == null) {
                processing.set(false);
                return;
            }

            Point from = request.getFrom();
            Point to = request.getTo();

            log.info("Processing BlockFetch RequestRange: {} → {}", from, to);

            List<Point> range = chainState.findBlocksInRange(request.getFrom(), request.getTo());

            if (range.isEmpty()) {
                log.warn("No blocks found in requested range {} → {}. Sending NoBlocks.", from, to);
                sendToClient(new NoBlocks());
                processNext();
                return;
            }

            if (YaciConfig.INSTANCE.isBlockFetchCheckRangeExists()) {
                // Preflight availability check (existence-only) to avoid mid-batch failures
                for (Point point : range) {
                    byte[] hash = HexUtil.decodeHexString(point.getHash());
                    boolean exists = false;
                    try {
                        exists = chainState.hasBlock(hash);
                    } catch (Exception ignored) {
                    }
                    if (!exists) {
                        log.warn("Requested range contains missing body at {}. Sending NoBlocks.", point);
                        sendToClient(new NoBlocks());
                        processNext();
                        return;
                    }
                }
            }

            // All bodies available: send batch
            sendToClient(new StartBatch());

            counter.incrementAndGet();
            log.info("BATCH STARTED: {} → {} -> batch NO: {} -> pending requests: {}", from, to, counter.get(), requestQueue.size());
            for (Point point : range) {
                byte[] blockHash = HexUtil.decodeHexString(point.getHash());
                byte[] blockBody = chainState.getBlock(blockHash);

                //TODO -- Checking again here. Verify if it breaks protocol
                if (blockBody == null) {
                    log.error("Block missing after availability check. Point: {}", point);
                    sendToClient(new NoBlocks());
                    return;
                }

                var blockMessage = new MsgBlock(blockBody);
                sendToClient(blockMessage);
            }

            log.info("BATCH COMPLETED: {} → {} -> batch NO: {} -> pending requests: {}", from, to, counter.get(), requestQueue.size());

            // Send BatchDone
            sendToClient(new BatchDone());

        } catch (Exception e) {
            log.error("Error processing BlockFetch request", e);
        } finally {
            processing.set(false);
            executor.submit(this::processNext);
        }
    }

    private void sendToClient(Message message) {
        if (getChannel().isWritable()) {
            if (log.isDebugEnabled()) {
                log.debug("Message size: {} bytes", message.serialize().length);
            }
            writeMessage(message, null);
            currenState = currenState.nextState(message);
        } else {
            log.warn("Channel not writable. Queuing: {}", message.getClass().getSimpleName());
            pendingMessages.add(message);
        }

    }

    @Override
    public void onChannelWritabilityChanged(Channel channel) {
        while (channel.isWritable() && !pendingMessages.isEmpty()) {
            sendToClient(pendingMessages.poll());
        }

        if (!processing.get()) {
            processNext();
        }
    }

    @Override
    protected void processResponse(Message message) {
        if (message instanceof RequestRange req) {
            handleRequestRange(req);
        } else if (message instanceof ClientDone) {
            handleClientDone();
        } else {
            log.warn("Unexpected message: {}", message);
        }
    }

    private void handleRequestRange(RequestRange requestRange) {
        log.info("Received RequestRange: from={}, to={}", requestRange.getFrom(), requestRange.getTo());
        if (requestQueue.size() >= MAX_QUEUE_SIZE) {
            log.warn("Too many pipelined requests. Dropping new request: {} → {}", requestRange.getFrom(), requestRange.getTo());
            // Do not send response — client will retry or timeout
            return;
        }
        if (!requestQueue.offer(requestRange)) {
            log.warn("BlockFetch request queue full. Dropping request.");
            return;
        }

        maybeStartProcessing();
    }

    private void handleClientDone() {
        log.info("Client sent ClientDone. Resetting state.");
        reset();
    }

    @Override
    public boolean isDone() {
        return currenState == BlockfetchState.Done;
    }

    @Override
    public void reset() {
        this.currenState = BlockfetchState.Idle;
        requestQueue.clear();
        pendingMessages.clear();

        processing.set(false);
    }

    @Override
    public void disconnected() {
        log.info("Client disconnected - stopping BlockFetch processing");
        processing.set(false);
        requestQueue.clear();
        pendingMessages.clear();
        super.disconnected();
    }

    public void shutdown() {
        executor.shutdown();
    }
}
