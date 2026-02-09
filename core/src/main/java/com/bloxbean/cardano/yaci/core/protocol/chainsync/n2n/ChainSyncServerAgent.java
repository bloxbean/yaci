package com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n;

import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.TipSerializer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedList;
import java.util.Queue;

/**
 * ChainSync Server Agent - Handles client requests for chain synchronization
 * This agent responds to client FindIntersect and RequestNext messages
 */
@Slf4j
public class ChainSyncServerAgent extends Agent<ChainSyncAgentListener> {

    private final ChainState chainState;
    private Point intersectedPoint;
    private Queue<Message> pendingResponses = new LinkedList<>(); // Queue for pipelined responses
    private Point lastSentPoint; // Track the last point sent to client
    private boolean clientAtTip; // Flag to track if client is at tip

    public ChainSyncServerAgent(ChainState chainState) {
        super(false); // This is a server agent
        this.chainState = chainState;
        this.currentState = ChainSyncState.Idle;
    }

    @Override
    public int getProtocolId() {
        return 2; // ChainSync protocol ID
    }

    @Override
    public Message buildNextMessage() {
        // Return next pending response when server has agency
        if (!pendingResponses.isEmpty() && hasAgency()) {
            Message response = pendingResponses.poll();
            // Don't update state here - it will be updated in Agent.sendRequest
            if (log.isDebugEnabled()) {
                log.debug("ChainSyncServerAgent.buildNextMessage() - Sending: {} in state: {} (hasAgency: {})",
                         response.getClass().getSimpleName(), currentState, hasAgency());
            }
            return response;
        } else if (!pendingResponses.isEmpty()) {
            // We have a pending response but don't have agency - this is a protocol violation
            log.warn("ChainSyncServerAgent: Attempted to send {} but server doesn't have agency in state {}. Client has agency: {}",
                     pendingResponses.peek().getClass().getSimpleName(), currentState, !hasAgency());
            // Don't send the message - wait for proper state
        }
        return null;
    }

    @Override
    public void processResponse(Message message) {
        if (message == null) return;

        // Don't update state here - it's already updated in Agent.receiveResponse
        if (log.isDebugEnabled()) {
            log.debug("ChainSyncServerAgent.processResponse() - Received: {} in state: {} (hasAgency: {})",
                     message.getClass().getSimpleName(), currentState, hasAgency());
        }

        if (message instanceof FindIntersect) {
            handleFindIntersect((FindIntersect) message);
        } else if (message instanceof RequestNext) {
            handleRequestNext((RequestNext) message);
        } else {
            log.warn("Unexpected message type received: {}", message.getClass().getSimpleName());
        }

        // Log state after handling message
        if (log.isDebugEnabled()) {
            log.debug("ChainSyncServerAgent.processResponse() - After handling: state={}, hasAgency={}, pendingResponse={}",
                     currentState, hasAgency(), pendingResponses.isEmpty() ? "null" : pendingResponses.peek().getClass().getSimpleName());
        }
    }

    private void handleFindIntersect(FindIntersect findIntersect) {
        try {
            if (findIntersect.getPoints() == null || findIntersect.getPoints().length == 0) {
                log.warn("FindIntersect received with no points");
                sendNoIntersectionResponse();
                return;
            }

            log.info("Handling FindIntersect with {} points", findIntersect.getPoints().length);

            Point[] clientPoints = findIntersect.getPoints();
            Point intersectionPoint = null;

            // Find intersection point by checking client's points against our chain
            for (Point clientPoint : clientPoints) {
                if (clientPoint == null) {
                    log.warn("Skipping null client point");
                    continue;
                }

                log.info("Checking client point: slot={}, hash={}", clientPoint.getSlot(), clientPoint.getHash());

                try {
                    // Handle Point.ORIGIN specially - it's always accepted if we have any blocks
                    if (clientPoint.getSlot() == 0 && clientPoint.getHash() == null) {
                        // This is Point.ORIGIN - always accept if we have blocks
                        ChainTip tip = chainState.getTip();
                        if (tip != null) {
                            intersectionPoint = clientPoint;
                            log.info("Found intersection at Point.ORIGIN (genesis point)");
                            break;
                        } else {
                            log.warn("Client requested Point.ORIGIN but server has no blocks");
                        }
                    } else if (pointExistsInChain(clientPoint)) {
                        intersectionPoint = clientPoint;
                        log.info("Found intersection point: slot={}, hash={}", clientPoint.getSlot(), clientPoint.getHash());
                        break;
                    }
                } catch (Exception e) {
                    log.warn("Error checking point existence for point: {}", clientPoint, e);
                    continue;
                }
            }

            ChainTip currentTip = chainState.getTip();
            if (currentTip == null) {
                log.error("Chain state returned null tip");
                sendNoIntersectionResponse();
                return;
            }

            if (intersectionPoint != null) {
                // Found intersection
                this.intersectedPoint = intersectionPoint;

                Tip tip = createTipFromChainTip(currentTip);
                IntersectFound intersectFound = new IntersectFound(intersectionPoint, tip);

                log.info("Intersection found at point: {}, tip: {}", intersectionPoint, tip);
                log.info("About to serialize IntersectFound with point: {} and tip: {}", intersectionPoint, tip);

                // Enqueue pending response for server to send
                this.pendingResponses.add(intersectFound);

                // Notify listeners
                final Point finalIntersectionPoint = intersectionPoint;
                getAgentListeners().forEach(listener -> {
                    try {
                        listener.intersactFound(tip, finalIntersectionPoint);
                    } catch (Exception e) {
                        log.error("Error notifying listener about intersection found", e);
                    }
                });

            } else {
                sendNoIntersectionResponse();
            }
        } catch (Exception e) {
            log.error("Error handling FindIntersect", e);
            sendNoIntersectionResponse();
        }
    }

    private void sendNoIntersectionResponse() {
        try {
            ChainTip currentTip = chainState.getTip();
            if (currentTip == null) {
                // Create a default tip if chain state is unavailable
                currentTip = new ChainTip(0, new byte[32], 0);
            }

            Tip tip = createTipFromChainTip(currentTip);
            IntersectNotFound intersectNotFound = new IntersectNotFound(tip);

            log.debug("No intersection found, sending tip: {}", tip);

            // Enqueue pending response for server to send
            this.pendingResponses.add(intersectNotFound);

            // Notify listeners
            getAgentListeners().forEach(listener -> {
                try {
                    listener.intersactNotFound(tip);
                } catch (Exception e) {
                    log.error("Error notifying listener about intersection not found", e);
                }
            });
        } catch (Exception e) {
            log.error("Error sending no intersection response", e);
        }
    }

    private void handleRequestNext(RequestNext requestNext) {
        try {
            log.debug("Handling RequestNext from client");

            if (intersectedPoint == null) {
                log.warn("Client requested next without finding intersection first");
                sendAwaitReplyResponse();
                return;
            }

            //If the intersected point is not null, but if it's the first request next, send rollback to intersected point
            if (lastSentPoint == null) {
                handleRollback();
                return;
            }

            // Check if we need to handle rollback scenario
            if (shouldRollback()) {
                handleRollback();
                return;
            }

            // Find the next block after intersection point
            Point nextPoint = null;
            try {
                // Special handling for Point.ORIGIN - get first block directly
                if (intersectedPoint.getSlot() == 0 && intersectedPoint.getHash() == null) {
                    log.info("Client requesting next block after Point.ORIGIN - returning first block");
                    nextPoint = getFirstBlockInChain();
                } else {
                    nextPoint = findNextBlockAfterPoint(intersectedPoint);
                }
            } catch (Exception e) {
                log.error("Error finding next block after point: {}", intersectedPoint, e);
                sendAwaitReplyResponse();
                return;
            }

            ChainTip currentTip = chainState.getTip();
            if (currentTip == null) {
                log.error("Chain state returned null tip during RequestNext");
                sendAwaitReplyResponse();
                return;
            }

            if (nextPoint != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Found next block: slot={}, hash={}, previous intersection: slot={}",
                            nextPoint.getSlot(), nextPoint.getHash(), intersectedPoint.getSlot());
                }

                // Check if we have block header data
                byte[] blockHeaderBytes = null;
                try {
                    blockHeaderBytes = chainState.getBlockHeader(HexUtil.decodeHexString(nextPoint.getHash()));
                } catch (Exception e) {
                    log.error("Error getting block header for point: {}", nextPoint, e);
                    sendAwaitReplyResponse();
                    return;
                }

                if (blockHeaderBytes != null && blockHeaderBytes.length > 0) {
                    try {
                        // Create RollForward message directly from stored wrapped header bytes
                        Tip tip = createTipFromChainTip(currentTip);

                        log.debug("Creating RollForward from stored wrapped header for point: {}, header bytes length: {}", nextPoint, blockHeaderBytes.length);

                        //TODO : THis deserialization is not needed anymore, we can directly use the bytes. But it's here for debugging purposes
//                        byte[] reConstructRollForwardBytes = createRollForwardMessage(blockHeaderBytes, tip);
//                        RollForward reConstructRollForward = RollForwardSerializer.INSTANCE.deserialize(reConstructRollForwardBytes);

//                        RollForward rollForward = new RollForward(reConstructRollForward.getByronEbHead(), reConstructRollForward.getByronBlockHead(), reConstructRollForward.getBlockHeader(), tip, blockHeaderBytes);
                        RollForward rollForward = new RollForward(null, null, null, tip, blockHeaderBytes);

                        if (log.isDebugEnabled()) {
                            log.debug("Sending RollForward for point: slot={}, hash={}, tip: slot={}, block={}/{}",
                                    nextPoint.getSlot(), nextPoint.getHash(),
                                    tip.getPoint().getSlot(), tip.getBlock(), currentTip.getBlockNumber());
                        }

                        this.pendingResponses.add(rollForward);

                        // Update tracking variables
                        this.lastSentPoint = nextPoint;
                        this.intersectedPoint = nextPoint;
                        this.clientAtTip = false;

                        // Notify listeners with proper block header information
                        notifyListenersRollForward(rollForward, tip);

                    } catch (Exception e) {
                        log.error("Error creating RollForward message from block header bytes", e);
                        sendAwaitReplyResponse();
                    }
                } else {
                    log.warn("No block header bytes found for point: {}", nextPoint);
                    sendAwaitReplyResponse();
                }
            } else {
                // No next block available, client is at tip
                log.info("Client is at tip, sending AwaitReply");
                sendAwaitReplyResponse();
            }
        } catch (Exception e) {
            log.error("Error handling RequestNext", e);
            sendAwaitReplyResponse();
        }
    }

    private void sendAwaitReplyResponse() {
        try {
            this.pendingResponses.add(new AwaitReply());
            this.clientAtTip = true;
        } catch (Exception e) {
            log.error("Error sending AwaitReply response", e);
        }
    }

    private boolean pointExistsInChain(Point point) {
        return chainState.hasPoint(point);
    }

    private Point findNextBlockAfterPoint(Point currentPoint) {
        return chainState.findNextBlock(currentPoint);
    }

    private Point getFirstBlockInChain() {
        // Try to get the first block from chain state
        try {
            // Use the findNextBlock method with Point.ORIGIN to get the first block
            return chainState.findNextBlock(Point.ORIGIN);
        } catch (Exception e) {
            log.error("Error getting first block from chain", e);
            return null;
        }
    }

    private Tip createTipFromChainTip(ChainTip chainTip) {
        try {
            if (chainTip == null) {
                log.warn("Creating tip from null ChainTip, using origin point");
                return new Tip(Point.ORIGIN, 0);
            }

            if (chainTip.getBlockHash() == null) {
                log.warn("ChainTip has null block hash, using origin point");
                return new Tip(Point.ORIGIN, 0);
            }

            Point tipPoint = new Point(chainTip.getSlot(), HexUtil.encodeHexString(chainTip.getBlockHash()));
            return new Tip(tipPoint, chainTip.getBlockNumber());
        } catch (Exception e) {
            log.error("Error creating tip from chain tip", e);
            return new Tip(Point.ORIGIN, 0);
        }
    }


    @Override
    public boolean isDone() {
        return this.currentState == ChainSyncState.Done;
    }

    /**
     * Check if we need to rollback due to chain reorganization
     */
    private boolean shouldRollback() {
        if (lastSentPoint == null) {
            return false;
        }

        // Check if the last sent point is still in our chain
        // If not, we need to rollback to a common point
        return !chainState.hasPoint(lastSentPoint);
    }

    /**
     * Handle rollback scenario
     */
    private void handleRollback() {
        try {
            log.info("Handling rollback scenario - last sent point no longer in chain");

            // Find the rollback point (last common point)
            Point rollbackPoint = null;
            try {
                rollbackPoint = findRollbackPoint();
            } catch (Exception e) {
                log.error("Error finding rollback point", e);
                sendAwaitReplyResponse();
                return;
            }

            if (rollbackPoint != null) {
                log.info("ROLLBACK_TO_CLIENT: client={}, rollbackPoint={}, clientAtTip={}, hasAgency={}",
                        getChannel() != null ? getChannel().remoteAddress() : "unknown",
                        rollbackPoint, clientAtTip, hasAgency());

                log.info("Rolling back to point: slot={}, hash={}", rollbackPoint.getSlot(), rollbackPoint.getHash());

                ChainTip currentTip = chainState.getTip();
                if (currentTip == null) {
                    log.error("Chain state returned null tip during rollback");
                    sendAwaitReplyResponse();
                    return;
                }

                Tip tip = createTipFromChainTip(currentTip);

                Rollbackward rollbackMessage = new Rollbackward(rollbackPoint, tip);
                this.pendingResponses.add(rollbackMessage);

                // Update our tracking
                this.intersectedPoint = rollbackPoint;
                this.lastSentPoint = rollbackPoint;
                this.clientAtTip = false;

                log.info("ChainSyncServerAgent: Enqueued Rollbackward message to point: slot={}, new tip: slot={}",
                        rollbackPoint.getSlot(), tip.getPoint().getSlot());

                // Notify listeners
                final Point finalRollbackPoint = rollbackPoint;
                getAgentListeners().forEach(listener -> {
                    try {
                        listener.rollbackward(tip, finalRollbackPoint);
                    } catch (Exception e) {
                        log.error("Error notifying listener about rollback", e);
                    }
                });
            } else {
                log.warn("Could not find rollback point - sending AwaitReply");
                sendAwaitReplyResponse();
            }
        } catch (Exception e) {
            log.error("Error handling rollback", e);
            sendAwaitReplyResponse();
        }
    }

    /**
     * Find the rollback point (common ancestor) by traversing backwards from the current position
     * to find a point that still exists in the chain. This handles deep reorganizations.
     */
    private Point findRollbackPoint() {
        if (intersectedPoint == null) {
            log.warn("Cannot find rollback point - no intersection point set");
            return null;
        }

        // Start from the last sent point and work backwards to find a common ancestor
        Point candidatePoint = lastSentPoint;

        // First, try the last sent point
        if (candidatePoint != null && chainState.hasPoint(candidatePoint)) {
            log.debug("Last sent point is still valid in chain, using it as rollback point");
            return candidatePoint;
        }

        // Next, try the current intersection point
        if (chainState.hasPoint(intersectedPoint)) {
            log.debug("Using current intersection point as rollback point");
            return intersectedPoint;
        }

        // If both are invalid, we have a deep reorganization
        log.warn("Deep chain reorganization detected - both last sent point and intersection point are invalid");

        // Try to find a valid point by checking previous slots
        // Start from the intersection point and work backwards
        long currentSlot = intersectedPoint.getSlot();

        // Strategy: Try to go back in steps, checking if blocks exist at those slots
        // Start with small steps, then increase if needed
        long[] stepSizes = {10, 50, 100, 500}; // Progressive step sizes

        for (long stepSize : stepSizes) {
            long trySlot = currentSlot;

            // Try up to 10 steps with current step size
            for (int i = 0; i < 10 && trySlot > 0; i++) {
                trySlot = Math.max(0, trySlot - stepSize);

                if (trySlot == 0) {
                    // Reached genesis
                    log.info("Rollback search reached genesis, returning Point.ORIGIN");
                    return Point.ORIGIN;
                }

                // Check if a block exists at this slot
                Long blockNumber = chainState.getBlockNumberBySlot(trySlot);
                if (blockNumber != null) {
                    // Found a block at this slot, get its hash
                    byte[] blockHeader = chainState.getBlockHeaderByNumber(blockNumber);
                    if (blockHeader != null) {
                        // Extract the hash from the header to create a proper Point
                        // For now, we'll create a Point with the slot
                        // In a real implementation, we'd parse the header to get the hash
                        log.info("Found valid block at slot {} (block number {}) for rollback", trySlot, blockNumber);

                        // Try to get the block to extract its hash
                        byte[] block = chainState.getBlockByNumber(blockNumber);
                        if (block != null) {
                            var blockObj = BlockSerializer.INSTANCE.deserialize(block);
                            String blockHash = blockObj.getHeader().getHeaderBody().getBlockHash();
                            // Create a Point with slot but without hash
                            // The client will need to verify this
                            Point rollbackPoint = new Point(trySlot, blockHash);
                            log.warn("Creating rollback point at slot {} without hash - client will need to verify", trySlot);
                            return rollbackPoint;
                        }
                    }
                }
            }
        }

        // If we've searched back far and found nothing, try genesis
        log.warn("No valid rollback point found after extensive search, trying Point.ORIGIN");
        Long genesisBlock = chainState.getBlockNumberBySlot(0L);
        if (genesisBlock != null) {
            return Point.ORIGIN;
        }

        // If we can't find any valid rollback point, return null
        // This will force the client to send AwaitReply and potentially reconnect
        log.error("Could not find any valid rollback point after deep reorganization - chain state may be corrupted");
        return null;
    }

    /**
     * Notify listeners about rollforward with proper type handling
     */
    private void notifyListenersRollForward(RollForward rollForward, Tip tip) {
        getAgentListeners().forEach(listener -> {
            try {
                if (rollForward.getBlockHeader() != null) {
                    // Shelley+ era block - pass originalHeaderBytes to preserve complete wrapped header
                    if (rollForward.getOriginalHeaderBytes() != null) {
                        listener.rollforward(tip, rollForward.getBlockHeader(), rollForward.getOriginalHeaderBytes());
                    } else {
                        listener.rollforward(tip, rollForward.getBlockHeader());
                    }
                } else if (rollForward.getByronBlockHead() != null) {
                    // Byron main block - pass originalHeaderBytes to preserve complete wrapped header
                    if (rollForward.getOriginalHeaderBytes() != null) {
                        listener.rollforwardByronEra(tip, rollForward.getByronBlockHead(), rollForward.getOriginalHeaderBytes());
                    } else {
                        listener.rollforwardByronEra(tip, rollForward.getByronBlockHead());
                    }
                } else if (rollForward.getByronEbHead() != null) {
                    // Byron epoch boundary block - pass originalHeaderBytes to preserve complete wrapped header
                    if (rollForward.getOriginalHeaderBytes() != null) {
                        listener.rollforwardByronEra(tip, rollForward.getByronEbHead(), rollForward.getOriginalHeaderBytes());
                    } else {
                        listener.rollforwardByronEra(tip, rollForward.getByronEbHead());
                    }
                }
            } catch (Exception e) {
                log.error("Error notifying listener about rollforward", e);
            }
        });
    }

    /**
     * Check if client is currently at tip
     */
    public boolean isClientAtTip() {
        return clientAtTip;
    }

    /**
     * Get current state for debugging
     */
    public State getCurrentState() {
        return currentState;
    }

    /**
     * Get the last point sent to client
     */
    public Point getLastSentPoint() {
        return lastSentPoint;
    }

    /**
     * Push new blocks to client if they're waiting at tip
     * This method can be called externally when new blocks arrive
     */
    public void notifyNewBlock(Point newBlockPoint) {
        if (clientAtTip && hasAgency()) {
            log.info("Notifying client about new block: {}", newBlockPoint);
            // Client is waiting at tip and we have agency - send the new block
            try {
                ChainTip currentTip = chainState.getTip();
                byte[] blockHeaderBytes = chainState.getBlockHeader(HexUtil.decodeHexString(newBlockPoint.getHash()));

                if (blockHeaderBytes != null) {
                    Tip tip = createTipFromChainTip(currentTip);

                    // Create RollForward message directly from stored wrapped header bytes
//                    byte[] rollForwardBytes = createRollForwardMessage(blockHeaderBytes, tip);
//                    RollForward rollForward = RollForwardSerializer.INSTANCE.deserialize(rollForwardBytes);

                    RollForward rollForward = new RollForward(null, null, null, tip, blockHeaderBytes);

                    this.pendingResponses.add(rollForward);
                    this.lastSentPoint = newBlockPoint;
                    this.intersectedPoint = newBlockPoint;
                    this.clientAtTip = false;

                    // Send the message
                    sendNextMessage();

                    // Notify listeners
                    notifyListenersRollForward(rollForward, tip);
                }
            } catch (Exception e) {
                log.error("Error notifying client about new block", e);
            }
        }
    }

    /**
     * Create a complete RollForward message from stored wrapped header bytes and tip
     * @param wrappedHeaderBytes Complete wrapped header bytes (includes era variant)
     * @param tip Chain tip
     * @return RollForward message bytes
     */
    private byte[] createRollForwardMessage(byte[] wrappedHeaderBytes, Tip tip) {
        try {
            Array rollForwardArray = new Array();

            // Add rollForwardType (2 for RollForward)
            rollForwardArray.add(new UnsignedInteger(2));

            // Add the stored wrapped header directly (already includes era variant)
            rollForwardArray.add(CborSerializationUtil.deserializeOne(wrappedHeaderBytes));

            // Add Tip
            rollForwardArray.add(TipSerializer.INSTANCE.serializeDI(tip));

            return CborSerializationUtil.serialize(rollForwardArray);
        } catch (Exception e) {
            log.error("Error creating RollForward message", e);
            throw new RuntimeException("Failed to create RollForward message", e);
        }
    }

    @Override
    public void reset() {
        this.currentState = ChainSyncState.Idle;
        this.intersectedPoint = null;
        this.pendingResponses.clear();
        this.lastSentPoint = null;
        this.clientAtTip = false;
    }

    /**
     * Called when new blockchain data becomes available.
     * If client is waiting at tip, we check for new blocks and notify them.
     * Also checks for rollback scenarios when the chain has reorganized.
     */
    @Override
    public void onNewDataAvailable() {
        try {
            // Check for rollback scenarios first
            if (lastSentPoint != null && !clientAtTip) {
                // Check if our last sent point is still valid in chain
                if (!chainState.hasPoint(lastSentPoint)) {
                    log.info("ChainSyncServerAgent: Detected chain rollback - last sent point no longer valid");

                    if (hasAgency()) {
                        // Use existing rollback handling
                        if (shouldRollback()) {
                            handleRollback();
                            return;
                        }
                    } else {
                        log.debug("ChainSyncServerAgent: Rollback detected but don't have agency to notify client");
                    }
                }
            }

            // Only check for new data if client is at tip and we have agency
            if (!clientAtTip) {
                log.debug("ChainSyncServerAgent: Client not at tip, ignoring new data notification");
                return;
            }

            if (!hasAgency()) {
                log.debug("ChainSyncServerAgent: Don't have agency, cannot send new block");
                return;
            }

            log.debug("ChainSyncServerAgent: Client is at tip and we have agency, checking for new blocks or rollbacks");

            // Get current tip from chain state
            ChainTip currentTip = chainState.getTip();
            if (currentTip == null) {
                log.debug("ChainSyncServerAgent: No tip available, cannot notify about new blocks");
                return;
            }

            // Create point from current tip
            Point currentTipPoint = new Point(currentTip.getSlot(),
                                             HexUtil.encodeHexString(currentTip.getBlockHash()));

            // Check if this is actually a new block (not the same as last sent)
            if (lastSentPoint != null &&
                lastSentPoint.getSlot() == currentTipPoint.getSlot() &&
                lastSentPoint.getHash().equals(currentTipPoint.getHash())) {
                log.debug("ChainSyncServerAgent: Tip hasn't changed, no new block to send");
                return;
            }

            // Check if we need to rollback (tip moved backwards)
            if (lastSentPoint != null && currentTipPoint.getSlot() < lastSentPoint.getSlot()) {
                log.info("ChainSyncServerAgent: Chain reorganization detected - current tip slot {} is before last sent slot {}",
                        currentTipPoint.getSlot(), lastSentPoint.getSlot());
                handleRollback();
                return;
            }

            log.info("ChainSyncServerAgent: New block available at tip: slot={}, hash={}",
                    currentTipPoint.getSlot(), currentTipPoint.getHash());

            // Use existing notifyNewBlock method
            notifyNewBlock(currentTipPoint);

        } catch (Exception e) {
            log.error("ChainSyncServerAgent: Error handling new data notification", e);
        }
    }
}
