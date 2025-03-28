package com.bloxbean.cardano.yaci.core.protocol.chainsync.n2c;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshkeState;
import lombok.extern.slf4j.Slf4j;

import static com.bloxbean.cardano.yaci.core.protocol.chainsync.n2c.LocalChainSyncState.Done;
import static com.bloxbean.cardano.yaci.core.protocol.chainsync.n2c.LocalChainSyncState.Idle;

@Slf4j
public class LocalChainSyncAgent extends Agent<LocalChainSyncAgentListener> {
    private Point intersact;
    private Tip tip;
    private Point[] knownPoints;

    private Point currentPoint;
    private long stopAt;
    private int agentNo;
    private int counter = 0;

    public LocalChainSyncAgent(Point[] knownPoints) {
        this(knownPoints, true);
    }
    public LocalChainSyncAgent(Point[] knownPoints, boolean isClient) {
        super(isClient);
        this.currenState = Idle;
        this.knownPoints = knownPoints;

        if (knownPoints != null && knownPoints.length > 0)
            log.info("Trying to find the point " + knownPoints[0]);
    }

    public LocalChainSyncAgent(Point[] knownPoints, long stopSlotNo, int agentNo) {
        this(knownPoints,stopSlotNo, agentNo, true);
    }
    public LocalChainSyncAgent(Point[] knownPoints, long stopSlotNo, int agentNo, boolean isClient) {
        super(isClient);
        this.currenState = Idle;
        this.knownPoints = knownPoints;
        this.stopAt = stopSlotNo;
        this.agentNo = agentNo;

        log.debug("Starting at slot > " + knownPoints[0].getSlot() +" --- To >> " + stopSlotNo +"  -- agent >> " + agentNo);
    }

    @Override
    public int getProtocolId() {
        return 5;
    }

    @Override
    public Message buildNextMessage() {
        if (intersact == null) { //Find intersacts
            if (currentPoint == null) {
                if (log.isDebugEnabled())
                    log.debug("FindIntersect for point: {}", knownPoints);
                return new FindIntersect(knownPoints);
            } else {
                if (log.isDebugEnabled())
                    log.debug("FindIntersect for point: {}", currentPoint);
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
        } else if (message instanceof LocalRollForward) {
            if (log.isDebugEnabled()) {
                log.debug("LocalRollForward !!!");
                log.trace("LocalRollForward - {}", message);
            }
            LocalRollForward rollForward = (LocalRollForward) message;
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
                }
        );
    }

    private void onRollForward(LocalRollForward rollForward) {
        if (rollForward.getBlock() != null) { //For Byron era, this value is null. Will be fixed later
            this.currentPoint = new Point(rollForward.getBlock().getHeader().getHeaderBody().getSlot(), rollForward.getBlock().getHeader().getHeaderBody().getBlockHash());
        } else if (rollForward.getByronBlock() != null) {
            this.currentPoint = new Point(rollForward.getByronBlock().getHeader().getConsensusData().getSlotId().getSlot(), rollForward.getByronBlock().getHeader().getBlockHash());
        } else if (rollForward.getByronEbBlock() != null) {
            this.currentPoint = new Point(0, rollForward.getByronEbBlock().getHeader().getBlockHash()); //TODO -- SlotId == 0 for ByronEbBlock ??
        }

        if (counter++ % 100 == 0 || (tip.getPoint().getSlot() - currentPoint.getSlot()) < 10) {

            if (log.isDebugEnabled()) {
                log.debug("**********************************************************");
                log.debug(String.valueOf(currentPoint));
                log.debug("[Agent No: " + agentNo + "] : " + rollForward);
                log.debug("**********************************************************");
            }

            if (stopAt != 0 && rollForward.getBlock().getHeader().getHeaderBody().getSlot() >= stopAt) {
                this.currenState = HandshkeState.Done;
            }
        }

        if (rollForward.getBlock() != null) { //For shelley and later era
            getAgentListeners().stream().forEach(
                    chainSyncAgentListener -> {
                        chainSyncAgentListener.rollforward(rollForward.getTip(), rollForward.getBlock());
                    }
            );
        } else if(rollForward.getByronBlock() != null) { //For Byron
            if (log.isTraceEnabled())
                log.trace("Byron Block: " + rollForward.getByronBlock().getHeader().getConsensusData());
            getAgentListeners().stream().forEach(
                    chainSyncAgentListener -> {
                        chainSyncAgentListener.rollforwardByronEra(rollForward.getTip(), rollForward.getByronBlock());
                    }
            );
        } else if(rollForward.getByronEbBlock() != null) { //For Byron EbBlock
            if (log.isTraceEnabled())
                log.trace("Byron Eb Block: " + rollForward.getByronEbBlock().getHeader().getConsensusData());
            getAgentListeners().stream().forEach(
                    chainSyncAgentListener -> {
                        chainSyncAgentListener.rollforwardByronEra(rollForward.getTip(), rollForward.getByronEbBlock());
                    }
            );
        }
    }

    @Override
    public boolean isDone() {
        return currenState == Done;
    }

    public void reset() {
        this.currenState = Idle;
        this.counter = 0;
    }

    public void reset(Point point) {
        this.currentPoint = null;
        this.intersact = null;
        this.knownPoints = new Point[] {point};
    }
}
