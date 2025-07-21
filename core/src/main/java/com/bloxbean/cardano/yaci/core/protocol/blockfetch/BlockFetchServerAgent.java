package com.bloxbean.cardano.yaci.core.protocol.blockfetch;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * BlockFetch Server Agent - Handles client requests for block ranges
 * This agent responds to client RequestRange messages with blocks
 */
@Slf4j
public class BlockFetchServerAgent extends Agent<BlockfetchAgentListener> {

    private final ChainState chainState;
    private Message pendingResponse;
    private List<Point> pointsToSend;
    private int currentBlockIndex;
    private Point requestedFrom;
    private Point requestedTo;

    public BlockFetchServerAgent(ChainState chainState) {
        super(false); // This is a server agent
        this.chainState = chainState;
        this.currenState = BlockfetchState.Idle;
        this.pointsToSend = new ArrayList<>();
        this.currentBlockIndex = 0;

        // Add listener to track state changes
        this.addListener(new BlockfetchAgentListener() {
            @Override
            public void onStateUpdate(com.bloxbean.cardano.yaci.core.protocol.State oldState, com.bloxbean.cardano.yaci.core.protocol.State newState) {
                log.info("BlockFetchServerAgent STATE TRANSITION: {} → {} (hasAgency: {})", oldState, newState, hasAgency());
            }
        });
    }

    @Override
    public int getProtocolId() {
        return 3; // BlockFetch protocol ID
    }

    @Override
    public Message buildNextMessage() {
        if (log.isDebugEnabled()) {
            log.debug("BlockFetchServerAgent.buildNextMessage() called - pendingResponse: {}, state: {}, hasAgency: {}, pointsToSend: {}, currentBlockIndex: {}",
                     pendingResponse != null ? pendingResponse.getClass().getSimpleName() : "null", currenState, hasAgency(), pointsToSend.size(), currentBlockIndex);

            // Add explicit state transition logging for BlockFetch
            log.debug("BlockFetchServerAgent current state details: state={}, hasAgency={}, isClient={}", currenState, hasAgency(), isClient());
        }

        // If we're in streaming state and have points to send, load and send blocks lazily
        if (currenState == BlockfetchState.Streaming && hasAgency() && !pointsToSend.isEmpty() && currentBlockIndex < pointsToSend.size()) {
            Point currentPoint = pointsToSend.get(currentBlockIndex);
            currentBlockIndex++;

            try {
                // LAZY LOADING: Load block data only when needed
                byte[] blockHash = HexUtil.decodeHexString(currentPoint.getHash());
                byte[] blockData = chainState.getBlock(blockHash);

                if (blockData != null) {
                    MsgBlock msgBlock = new MsgBlock(blockData);
                    if (log.isDebugEnabled()) {
                        log.debug("BlockFetchServerAgent STREAMING: returning MsgBlock for slot {} (block size: {} bytes), remaining blocks: {}",
                                 currentPoint.getSlot(), blockData.length, pointsToSend.size() - currentBlockIndex);
                    }

                    return msgBlock;
                } else {
                    log.warn("Block data not found for point {}, skipping", currentPoint);
                    // Skip this block and try the next one (recursive call)
                    return buildNextMessage();
                }
            } catch (Exception e) {
                log.error("Error loading block data for point {}, skipping", currentPoint, e);
                // Skip this block and try the next one (recursive call)
                return buildNextMessage();
            }
        }

        // If we're in streaming state and finished sending all blocks, send BatchDone to transition back to Idle
        if (currenState == BlockfetchState.Streaming && hasAgency() && !pointsToSend.isEmpty() && currentBlockIndex >= pointsToSend.size()) {
            if (log.isDebugEnabled()) {
                log.debug("BlockFetchServerAgent: All blocks sent (index={}, total={}), returning BatchDone to transition to Idle state", currentBlockIndex, pointsToSend.size());
            }
            pointsToSend.clear();
            currentBlockIndex = 0;
            return new BatchDone();
        }

        // Return pending response when server has agency (match ChainSync pattern)
        if (pendingResponse != null && hasAgency()) {
            Message response = pendingResponse;
            pendingResponse = null; // Clear after returning
            if (log.isDebugEnabled()) {
                log.debug("BlockFetchServerAgent returning pendingResponse: {} in state: {}", response.getClass().getSimpleName(), currenState);
            }
            return response;
        } else if (pendingResponse != null) {
            // We have a pending response but don't have agency - protocol violation
            log.warn("BlockFetchServerAgent: Attempted to send {} but server doesn't have agency in state {}. Client has agency: {}",
                     pendingResponse.getClass().getSimpleName(), currenState, !hasAgency());
            // Don't send the message - wait for proper state
        }

        if (log.isDebugEnabled()) {
            log.debug("BlockFetchServerAgent.buildNextMessage() returning null - no message to send");
        }
        return null;
    }

    @Override
    public void processResponse(Message message) {
        if (message == null) return;

        if (message instanceof RequestRange) {
            handleRequestRange((RequestRange) message);
        } else if (message instanceof ClientDone) {
            handleClientDone((ClientDone) message);
        } else {
            log.warn("Unexpected message type received: {}", message.getClass().getSimpleName());
        }
    }

    private void handleRequestRange(RequestRange requestRange) {
        try {
            if (requestRange == null) {
                log.warn("Received null RequestRange message");
                sendNoBlocksResponse();
                return;
            }


            this.requestedFrom = requestRange.getFrom();
            this.requestedTo = requestRange.getTo();

            if (requestedFrom == null || requestedTo == null) {
                log.warn("RequestRange has null from or to points");
                sendNoBlocksResponse();
                return;
            }

            log.info("Handling RequestRange from {} to {}", requestedFrom, requestedTo);

            // OPTIMIZATION: Only find the points first, not the full blocks
            List<Point> pointsInRange = null;
            try {
                pointsInRange = chainState.findBlocksInRange(requestedFrom, requestedTo);
                if (log.isDebugEnabled()) {
                    log.debug("Found {} points in range {} to {}",
                            pointsInRange != null ? pointsInRange.size() : "null", requestedFrom, requestedTo);
                }
            } catch (Exception e) {
                log.error("Error finding points in range {} to {}", requestedFrom, requestedTo, e);
                sendNoBlocksResponse();
                return;
            }

            if (pointsInRange == null || pointsInRange.isEmpty()) {
                log.info("No blocks found in range {} to {} - sending NoBlocks", requestedFrom, requestedTo);
                sendNoBlocksResponse();
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Found {} points in range {} to {} - sending StartBatch immediately",
                            pointsInRange.size(), requestedFrom, requestedTo);
                }

                // Store points, not blocks - we'll load blocks lazily during streaming
                this.pointsToSend = new ArrayList<>(pointsInRange);
                this.currentBlockIndex = 0;
                this.pendingResponse = new StartBatch();
                if (log.isDebugEnabled()) {
                    log.debug("Set pendingResponse to StartBatch, points to send: {}", pointsToSend.size());
                }

                // Notify listeners
                getAgentListeners().forEach(listener -> {
                    try {
                        listener.batchStarted();
                    } catch (Exception e) {
                        log.error("Error notifying listener about batch start", e);
                    }
                });
            }
        } catch (Exception e) {
            log.error("Error handling RequestRange", e);
            sendNoBlocksResponse();
        }
    }

    private void sendNoBlocksResponse() {
        try {
            log.info("sendNoBlocksResponse() called for range {} to {}", requestedFrom, requestedTo);
            this.pendingResponse = new NoBlocks();
            log.info("Set pendingResponse to NoBlocks");

            // After sending NoBlocks, agent should transition back to Idle state
            // This will be handled by the state machine when NoBlocks is sent

            // Notify listeners
            getAgentListeners().forEach(listener -> {
                try {
                    listener.noBlockFound(requestedFrom, requestedTo);
                } catch (Exception e) {
                    log.error("Error notifying listener about no blocks found", e);
                }
            });
        } catch (Exception e) {
            log.error("Error sending no blocks response", e);
        }
    }

    private void handleClientDone(ClientDone clientDone) {
        log.debug("Client is done with BlockFetch");

        // Clean up any pending state
        this.pointsToSend.clear();
        this.currentBlockIndex = 0;
        this.pendingResponse = null;

        // Notify listeners
        getAgentListeners().forEach(listener ->
            listener.batchDone());
    }


    @Override
    public boolean isDone() {
        return this.currenState == BlockfetchState.Done;
    }

    @Override
    public void reset() {
        this.currenState = BlockfetchState.Idle;
        this.pendingResponse = null;
        this.pointsToSend.clear();
        this.currentBlockIndex = 0;
        this.requestedFrom = null;
        this.requestedTo = null;
    }
}
