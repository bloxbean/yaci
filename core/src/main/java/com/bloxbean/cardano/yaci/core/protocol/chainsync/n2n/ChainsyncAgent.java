package com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n;

import com.bloxbean.cardano.yaci.core.common.GenesisConfig;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.*;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshkeState;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

import static com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncState.Done;
import static com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncState.Idle;

@Slf4j
public class ChainsyncAgent extends Agent<ChainSyncAgentListener> {
    private Point intersact;
    private Tip tip;
    private Point[] knownPoints;

    /**
     * The last confirmed block point. This point represents blocks that have been
     * successfully processed by the application. Used for FindIntersect during
     * reconnection to ensure no blocks are lost.
     */
    private Point currentPoint;

    /**
     * The point of the block currently being requested but not yet confirmed.
     * This implements a two-phase commit pattern where blocks are first requested
     * (requestedPoint set) then confirmed (moved to currentPoint) after successful processing.
     */
    private Point requestedPoint;
    private long stopAt;
    private int agentNo;
    private int counter = 0;

    private long startTime;

    private boolean isPipelining = false;
    private int batchSize = 100;
    private AtomicInteger outstandingRequests = new AtomicInteger(0);

    // Enhanced pipeline management
    private PipelineManager pipelineManager;
    private boolean enhancedPipeliningEnabled = false;

    // Simplified connection tracking
    private volatile boolean isReconnecting = false;

    public ChainsyncAgent(Point[] knownPoints) {
        this(knownPoints, true);
    }
    public ChainsyncAgent(Point[] knownPoints, boolean isClient) {
        super(isClient);
        this.currentState = Idle;
        this.knownPoints = knownPoints;

        // Initialize with adaptive strategy by default
        this.pipelineManager = new PipelineManager(PipelineStrategies.adaptive());

        if (knownPoints != null && knownPoints.length > 0)
            log.info("Trying to find the point " + knownPoints[0]);
    }

    public ChainsyncAgent(Point[] knownPoints, long stopSlotNo, int agentNo) {
        this(knownPoints, stopSlotNo, agentNo, true);
    }
    public ChainsyncAgent(Point[] knownPoints, long stopSlotNo, int agentNo, boolean isClient) {
        super(isClient);
        this.currentState = Idle;
        this.knownPoints = knownPoints;
        this.stopAt = stopSlotNo;
        this.agentNo = agentNo;

        // Initialize with adaptive strategy by default
        this.pipelineManager = new PipelineManager(PipelineStrategies.adaptive());

        log.debug("Starting at slot > " + knownPoints[0].getSlot() +" --- To >> " + stopSlotNo +"  -- agent >> " + agentNo);
    }

    @Override
    public int getProtocolId() {
        return 2;
    }

    public void enablePipelining(boolean isPipelining) {
        this.isPipelining = isPipelining;
        if (pipelineManager != null) {
            pipelineManager.setEnabled(isPipelining);
        }
        if (log.isDebugEnabled()) {
            log.debug("🔧 Pipelining {}: legacy={}, enhanced={}",
                     isPipelining ? "enabled" : "disabled", this.isPipelining, enhancedPipeliningEnabled);
        }
    }

    /**
     * Enable enhanced pipelining with strategy-based decisions.
     * This replaces the legacy batch-based pipelining.
     */
    public void enableEnhancedPipelining(boolean enabled) {
        this.enhancedPipeliningEnabled = enabled;
        if (enabled) {
            this.isPipelining = true; // Enable legacy flag for compatibility
        }
        if (pipelineManager != null) {
            pipelineManager.setEnabled(enabled);
        }
        if (log.isInfoEnabled()) {
            log.info("🚀 Enhanced pipelining {} with strategy: {}",
                    enabled ? "enabled" : "disabled",
                    pipelineManager != null ? pipelineManager.getStrategy().getStrategyName() : "none");
        }
    }

    /**
     * Set the pipeline strategy to use.
     */
    public void setPipelineStrategy(PipelineDecisionStrategy strategy) {
        this.pipelineManager = new PipelineManager(strategy);
        this.pipelineManager.setEnabled(isPipelining || enhancedPipeliningEnabled);
        if (log.isInfoEnabled()) {
            log.info("🎯 Pipeline strategy set to: {}", strategy.getStrategyName());
        }
    }

    @Override
    public Message buildNextMessage() {
        if (intersact == null) { //Find intersacts
            if (currentPoint == null) {
                if (log.isDebugEnabled())
                    log.debug("FindIntersect for point: {}", knownPoints);
                log.info("FindIntersect for point: {}", knownPoints);
                return new FindIntersect(knownPoints);
            } else {
                if (log.isDebugEnabled())
                    log.debug("FindIntersect for point: {}", currentPoint);
                log.info("FindIntersect for current point: {}", currentPoint);
                return new FindIntersect(new Point[]{currentPoint});
            }
        } else if (intersact != null) {
            if (log.isDebugEnabled())
                log.debug("RequestNext : Current point: {}", currentPoint);
            return new RequestNext();
        } else
            return null;
    }

    // Simplified approach: rely on aggressive Netty buffer clearing instead of complex filtering

    @Override
    public void processResponse(Message message) {
        if (message == null) return;
        if (message instanceof IntersectFound) {
            if (log.isDebugEnabled())
                log.debug("IntersectFound - {}", message);
            intersact = ((IntersectFound) message).getPoint();
            tip = ((IntersectFound) message).getTip();
            onIntersactFound((IntersectFound) message);
        } else if (message instanceof IntersectNotFound) {
            if (log.isDebugEnabled())
                log.debug("IntersectNotFound - {}", message);
            onIntersactNotFound((IntersectNotFound)message);
        } else if (message instanceof RollForward) {
            if (log.isDebugEnabled())
                log.debug("RollForward - {}", message);
            RollForward rollForward = (RollForward) message;

            // Update pipeline manager with response
            if (pipelineManager != null && enhancedPipeliningEnabled) {
                long blockSize = estimateBlockSize(rollForward);
                pipelineManager.recordResponseReceived(true, blockSize);
            }

            onRollForward(rollForward);
        } else if (message instanceof Rollbackward) {
            if (log.isDebugEnabled())
                log.debug("RollBackward - {}", message);
            Rollbackward rollBackward = (Rollbackward) message;

            // Update pipeline manager with response
            if (pipelineManager != null && enhancedPipeliningEnabled) {
                pipelineManager.recordResponseReceived(true, 0);
            }

            onRollBackward(rollBackward);
        }
    }

    private void onIntersactNotFound(IntersectNotFound intersectNotFound) {
        log.error("Itersect not found. Tip: {}", intersectNotFound.getTip());
        getAgentListeners().stream().forEach(
                chainSyncAgentListener -> {
                    chainSyncAgentListener.intersactNotFound(intersectNotFound.getTip());
                }
        );
    }

    private void onIntersactFound(IntersectFound intersectFound) {
        System.out.println("Intersect found at slot: " + intersectFound.getPoint().getSlot() +
                " - hash: " + intersectFound.getPoint().getHash() +
                " - tip: " + intersectFound.getTip().getPoint().getSlot() +
                " - tipHash: " + intersectFound.getTip().getPoint().getHash());
        log.info("Intersect found at slot: {} - hash: {}",
                intersectFound.getPoint().getSlot(), intersectFound.getPoint().getHash());

        // Update pipeline manager with server tip
        if (pipelineManager != null && intersectFound.getTip() != null) {
            pipelineManager.updateServerTip(intersectFound.getTip().getPoint());
            pipelineManager.updateClientTip(intersectFound.getPoint());
        }

        getAgentListeners().stream().forEach(
                chainSyncAgentListener -> {
                    chainSyncAgentListener.intersactFound(intersectFound.getTip(), intersectFound.getPoint());
                }
        );
    }

    private void onRollBackward(Rollbackward rollBackward) {
        if (rollBackward.getPoint().equals(currentPoint)) {//Rollback on same point. So don't rollback. But call listeners
            getAgentListeners().stream().forEach(
                    chainSyncAgentListener -> {
                        chainSyncAgentListener.rollbackward(rollBackward.getTip(), rollBackward.getPoint());
                    }
            );
            return;
        }

        if (currentPoint != null) { //so not first time
            // After rollback, set intersact to the rollback point to avoid another FindIntersect
            // This ensures we continue with RequestNext messages from the rollback point
            this.intersact = rollBackward.getPoint();
        }

        if (log.isDebugEnabled()) {
            log.debug("Current point : {}", currentPoint);
            log.debug("Rollback to:  {}", rollBackward.getPoint());
        }

        if (rollBackward.getPoint().getHash() != null)
            this.currentPoint = new Point(rollBackward.getPoint().getSlot(), rollBackward.getPoint().getHash());

        if (log.isDebugEnabled())
            log.debug("Current point after rollback: {}", this.currentPoint);

        getAgentListeners().stream().forEach(
                chainSyncAgentListener -> {
                    chainSyncAgentListener.rollbackward(rollBackward.getTip(), rollBackward.getPoint());
                });
    }

    private void onRollForward(RollForward rollForward) {
        if (rollForward.getBlockHeader() != null) { //For Shelley and later eras
            getAgentListeners().stream().forEach(
                    chainSyncAgentListener -> {
                        if (rollForward.getOriginalHeaderBytes() != null) {
                            chainSyncAgentListener.rollforward(rollForward.getTip(), rollForward.getBlockHeader(), rollForward.getOriginalHeaderBytes());
                        } else {
                            chainSyncAgentListener.rollforward(rollForward.getTip(), rollForward.getBlockHeader());
                        }
                    }
            );
        } else if(rollForward.getByronBlockHead() != null) { //For Byron main block
            if (log.isTraceEnabled())
                log.trace("Byron Block: " + rollForward.getByronBlockHead().getConsensusData().getSlotId());
            getAgentListeners().stream().forEach(
                    chainSyncAgentListener -> {
                        if (rollForward.getOriginalHeaderBytes() != null) {
                            chainSyncAgentListener.rollforwardByronEra(rollForward.getTip(), rollForward.getByronBlockHead(), rollForward.getOriginalHeaderBytes());
                        } else {
                            chainSyncAgentListener.rollforwardByronEra(rollForward.getTip(), rollForward.getByronBlockHead());
                        }
                    }
            );
        } else if (rollForward.getByronEbHead() != null) { //For Byron Eb block
           if (log.isTraceEnabled())
               log.trace("Byron Eb Block: " + rollForward.getByronEbHead().getConsensusData());
           getAgentListeners().stream().forEach(
                   chainSyncAgentListener -> {
                       if (rollForward.getOriginalHeaderBytes() != null) {
                           chainSyncAgentListener.rollforwardByronEra(rollForward.getTip(), rollForward.getByronEbHead(), rollForward.getOriginalHeaderBytes());
                       } else {
                           chainSyncAgentListener.rollforwardByronEra(rollForward.getTip(), rollForward.getByronEbHead());
                       }
                   }
           );
       }

        if (rollForward.getBlockHeader() != null) { //shelley and later
            this.requestedPoint = new Point(rollForward.getBlockHeader().getHeaderBody().getSlot(), rollForward.getBlockHeader().getHeaderBody().getBlockHash());
        } else if (rollForward.getByronBlockHead() != null) { //Byron Block
            long absoluteSlot = GenesisConfig.getInstance().absoluteSlot(Era.Byron,
                    rollForward.getByronBlockHead().getConsensusData().getSlotId().getEpoch(),
                    rollForward.getByronBlockHead().getConsensusData().getSlotId().getSlot());
            this.requestedPoint = new Point(absoluteSlot, rollForward.getByronBlockHead().getBlockHash());
        } else if (rollForward.getByronEbHead() != null) { //Byron Epoch block.
            long absoluteSlot = GenesisConfig.getInstance().absoluteSlot(
                    Era.Byron,
                    rollForward.getByronEbHead().getConsensusData().getEpoch(),
                    0);
            this.requestedPoint = new Point(absoluteSlot, rollForward.getByronEbHead().getBlockHash());
        }

        if (counter++ % 100 == 0 || (tip.getPoint().getSlot() - requestedPoint.getSlot()) < 10) {

            if (log.isDebugEnabled()) {
                log.debug("**********************************************************");
                log.debug(String.valueOf(requestedPoint));
                log.debug("[Agent No: " + agentNo + "] : " + rollForward);
                log.debug("**********************************************************");
            }

            if (stopAt != 0 && rollForward.getBlockHeader().getHeaderBody().getSlot() >= stopAt) {
                this.currentState = HandshkeState.Done;
            }
        }
    }

    @Override
    public boolean isDone() {
        return currentState == Done;
    }

    /**
     * Confirms that a block has been successfully processed by the application.
     * <p>
     * This method implements the second phase of a two-phase commit pattern:
     * <ol>
     *   <li>Phase 1: Block header received via RollForward → requestedPoint set</li>
     *   <li>Phase 2: Block successfully processed → confirmBlock() called → currentPoint updated</li>
     * </ol>
     *
     * <p><strong>IMPORTANT:</strong> When using ChainsyncAgent directly, you MUST call this method
     * after successfully processing each block and before calling sendNextMessage().
     * Failure to do so will result in duplicate block delivery on reconnection.
     *
     * <p>Use cases include:
     * <ul>
     *   <li>After successfully fetching and storing a full block body</li>
     *   <li>After processing block headers in header-only sync</li>
     *   <li>After any application-specific block processing is complete</li>
     * </ul>
     *
     * @param confirmedPoint the point of the block that has been successfully processed
     */
    public void confirmBlock(Point confirmedPoint) {
        // IMPORTANT: Always update currentPoint to support both sequential and pipeline modes
        // In pipeline mode, multiple headers may be requested ahead, so requestedPoint
        // will be ahead of confirmedPoint. We still need to update currentPoint for each
        // confirmed block to maintain proper cursor position for recovery after disconnection.
        this.currentPoint = confirmedPoint;

        // Clear requestedPoint only if it matches (for sequential mode compatibility)
        // In sequential mode: RequestNext → RollForward → Fetch → Confirm → RequestNext
        // In pipeline mode: This condition will rarely be true as requestedPoint advances ahead
        if (requestedPoint != null && requestedPoint.equals(confirmedPoint)) {
            this.requestedPoint = null;
        }

        int outstanding = outstandingRequests.decrementAndGet();
        if (outstanding < 0) {
            outstandingRequests.set(0);
        }

        if (log.isDebugEnabled())
            log.debug("Block confirmed: {}, outstanding requests: {}", confirmedPoint, outstandingRequests.get());
    }

    /**
     * Override sendNextMessage to support batch sending of RequestNext
     */
    @Override
    public void sendNextMessage() {
        if (!this.hasAgency()) {
            return;
        }

        // Handle intersection phase normally
        if (intersact == null) {
            super.sendNextMessage();
            return;
        }

        // Enhanced pipelining mode using strategy-based decisions
        if (enhancedPipeliningEnabled && pipelineManager != null && intersact != null) {
            PipelineManager.PipelineAction action = pipelineManager.getNextAction();

            if (log.isDebugEnabled()) {
                log.debug("🎯 Pipeline action: {}", action);
            }

            // First, handle collection if needed
            if (action.shouldCollect() && outstandingRequests.get() > 0) {
                // Just wait for responses, don't send new requests yet
                if (log.isDebugEnabled()) {
                    log.debug("📋 Collecting responses: {} outstanding", outstandingRequests.get());
                }
                return;
            }

            // Send requests based on action
            if (action.shouldSendRequests()) {
                int toSend = action.getRequestCount();
                boolean isPipelined = action.getDecision() == PipelineDecision.PIPELINE ||
                                    action.getDecision() == PipelineDecision.COLLECT_OR_PIPELINE;

                for (int i = 0; i < toSend; i++) {
                    RequestNext message = new RequestNext();
                    writeMessage(message, () -> {
                        sendRequest(message);
                        if (isPipelined) {
                            outstandingRequests.incrementAndGet();
                        }
                        pipelineManager.recordRequestSent(message, isPipelined);
                    });
                }

                if (log.isDebugEnabled() && toSend > 0) {
                    log.debug("📤 Sent {} {} requests (outstanding: {})",
                             toSend, isPipelined ? "pipelined" : "sequential", outstandingRequests.get());
                }
            }

            return;
        }

        // Legacy sequential batch mode: send multiple RequestNext at once
        if (isPipelining && intersact != null) {
            int currentOutstanding = outstandingRequests.get();
            int toSend = batchSize - currentOutstanding;

            if (log.isDebugEnabled())
                log.debug("Current outstanding requests: {}, batch size: {}", currentOutstanding, batchSize);

            if (toSend <= 0) {
                log.debug("Batch full: {} outstanding requests", currentOutstanding);
                return;
            }

            if (log.isDebugEnabled()) {
                log.debug("Sending {} batch RequestNext messages (outstanding: {})", toSend, currentOutstanding);
            }

            // Send multiple RequestNext messages
            for (int i = 0; i < toSend; i++) {
                Message message = new RequestNext();
                writeMessage(message, () -> {
                    sendRequest(message);
                    outstandingRequests.incrementAndGet();
                });
            }

            if (log.isDebugEnabled())
                log.info("Sent {} batch RequestNext messages, outstandingRequest: {}, batchSize: {}", toSend, outstandingRequests.get(), batchSize);
        } else {
            // Legacy sequential mode
            super.sendNextMessage();
        }
    }

    public void reset() {
        this.currentState = Idle;
        this.counter = 0;
        this.requestedPoint = null;
        this.intersact = null;
        this.outstandingRequests.set(0);

        // Reset pipeline manager
        if (pipelineManager != null) {
            pipelineManager.reset();
        }

        // Reset connection tracking
        this.isReconnecting = false;

        // IMPORTANT: Preserve currentPoint during reset to maintain sync position
        // after reconnection. This allows the agent to continue from where it left off.
        if (log.isDebugEnabled())
            log.debug("Reset completed. Current point preserved: {}", currentPoint);
    }

    /**
     * Simplified connection management - rely on aggressive buffer clearing.
     */
    public void onConnectionEstablished() {
        this.isReconnecting = false;
        if (log.isInfoEnabled()) {
            log.info("🔗 Connection established, currentPoint: {}", currentPoint);
        }
    }

    public void onConnectionLost() {
        this.isReconnecting = true;
        if (log.isInfoEnabled()) {
            log.info("💔 Connection lost, currentPoint: {}", currentPoint);
        }
    }

    public void reset(Point point) {
        this.currentPoint = null;
        this.intersact = null;
        this.knownPoints = new Point[] {point};
        this.requestedPoint = null;
        this.outstandingRequests.set(0);

        // Reset pipeline manager
        if (pipelineManager != null) {
            pipelineManager.reset();
        }

        // Reset connection tracking
        this.isReconnecting = false;
    }

    /**
     * Get current pipeline metrics for monitoring.
     */
    public PipelineMetrics getPipelineMetrics() {
        return pipelineManager != null ? pipelineManager.getMetrics() : null;
    }

    /**
     * Log current pipeline statistics.
     */
    public void logPipelineStatistics() {
        if (pipelineManager != null) {
            pipelineManager.logStatistics();
        }
    }

    /**
     * Estimate block size for metrics (rough approximation).
     */
    private long estimateBlockSize(RollForward rollForward) {
        if (rollForward.getOriginalHeaderBytes() != null) {
            return rollForward.getOriginalHeaderBytes().length;
        }
        // Rough estimate based on block type
        if (rollForward.getBlockHeader() != null) {
            return 1024; // Approximate header size
        } else if (rollForward.getByronBlockHead() != null) {
            return 512; // Byron block header estimate
        } else if (rollForward.getByronEbHead() != null) {
            return 256; // Byron epoch boundary estimate
        }
        return 100; // Fallback estimate
    }

    /**
     * Check if enhanced pipelining is enabled.
     */
    public boolean isEnhancedPipeliningEnabled() {
        return enhancedPipeliningEnabled;
    }

    /**
     * Get current pipeline strategy name.
     */
    public String getPipelineStrategyName() {
        return pipelineManager != null ? pipelineManager.getStrategy().getStrategyName() : "none";
    }

    /**
     * Get current outstanding requests from pipeline manager.
     */
    public int getPipelineOutstandingRequests() {
        return pipelineManager != null ? pipelineManager.getOutstandingRequestCount() : outstandingRequests.get();
    }

}
