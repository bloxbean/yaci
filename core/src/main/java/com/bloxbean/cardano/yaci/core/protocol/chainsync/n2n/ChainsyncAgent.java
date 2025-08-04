package com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n;

import com.bloxbean.cardano.yaci.core.common.GenesisConfig;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshkeState;
import lombok.extern.slf4j.Slf4j;

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

    public ChainsyncAgent(Point[] knownPoints) {
        this(knownPoints, true);
    }
    public ChainsyncAgent(Point[] knownPoints, boolean isClient) {
        super(isClient);
        this.currenState = Idle;
        this.knownPoints = knownPoints;

        if (knownPoints != null && knownPoints.length > 0)
            log.info("Trying to find the point " + knownPoints[0]);
    }

    public ChainsyncAgent(Point[] knownPoints, long stopSlotNo, int agentNo) {
        this(knownPoints, stopSlotNo, agentNo, true);
    }
    public ChainsyncAgent(Point[] knownPoints, long stopSlotNo, int agentNo, boolean isClient) {
        super(isClient);
        this.currenState = Idle;
        this.knownPoints = knownPoints;
        this.stopAt = stopSlotNo;
        this.agentNo = agentNo;

        log.debug("Starting at slot > " + knownPoints[0].getSlot() +" --- To >> " + stopSlotNo +"  -- agent >> " + agentNo);
    }

    @Override
    public int getProtocolId() {
        return 2;
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
            onRollForward(rollForward);
        } else if (message instanceof Rollbackward) {
            if (log.isDebugEnabled())
                log.debug("RollBackward - {}", message);
            Rollbackward rollBackward = (Rollbackward) message;
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
        log.info("Intersect found at slot: {} - hash: {}",
                intersectFound.getPoint().getSlot(), intersectFound.getPoint().getHash());
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
            this.intersact = null;
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

        if (counter++ % 100 == 0 || (tip.getPoint().getSlot() - currentPoint.getSlot()) < 10) {

            if (log.isDebugEnabled()) {
                log.debug("**********************************************************");
                log.debug(String.valueOf(currentPoint));
                log.debug("[Agent No: " + agentNo + "] : " + rollForward);
                log.debug("**********************************************************");
            }

            if (stopAt != 0 && rollForward.getBlockHeader().getHeaderBody().getSlot() >= stopAt) {
                this.currenState = HandshkeState.Done;
            }
        }
    }

    @Override
    public boolean isDone() {
        return currenState == Done;
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
        if (requestedPoint != null && requestedPoint.equals(confirmedPoint)) {
            this.currentPoint = confirmedPoint;
            this.requestedPoint = null;
        }
    }

    public void reset() {
        this.currenState = Idle;
        this.counter = 0;
        this.requestedPoint = null;
    }

    public void reset(Point point) {
        this.currentPoint = null;
        this.intersact = null;
        this.knownPoints = new Point[] {point};
        this.requestedPoint = null;
    }
}
