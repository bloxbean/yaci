package com.bloxbean.cardano.yaci.core.protocol.chainsync;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshkeState;
import lombok.extern.slf4j.Slf4j;

import static com.bloxbean.cardano.yaci.core.protocol.chainsync.ChainSyncState.Done;
import static com.bloxbean.cardano.yaci.core.protocol.chainsync.ChainSyncState.Idle;

@Slf4j
public class ChainsyncAgent extends Agent<ChainSyncAgentListener> {
    private Point intersact;
    private Tip tip;
    private Point[] knownPoints;

    private Point currentPoint;
    private long stopAt;
    private int agentNo;
    private int counter = 0;

    private long startTime;

    public ChainsyncAgent(Point[] knownPoints) {
        this.currenState = Idle;
        this.knownPoints = knownPoints;

        log.info("Starting at slot >> " + knownPoints[0].getSlot());
    }

    public ChainsyncAgent(Point[] knownPoints, long stopSlotNo, int agentNo) {
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
                    log.info("FindIntersect for point: {}", knownPoints);
                return new FindIntersect(knownPoints);
            } else {
                if (log.isDebugEnabled())
                    log.info("FindIntersect for point: {}", currentPoint);
                return new FindIntersect(new Point[]{currentPoint});
            }
        } else if (intersact != null) {
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
        } else if (message instanceof RollForward) {
            if (log.isDebugEnabled())
                log.debug("RollForward - {}", message);
            RollForward rollForward = (RollForward) message;
            onRollForward(rollForward);
        } else if (message instanceof Rollbackward) {
            if (log.isDebugEnabled())
                log.debug("RollBackward - ", message);
            Rollbackward rollBackward = (Rollbackward) message;
            onRollBackward(rollBackward);
        }
    }

    private void onIntersactFound(IntersectFound intersectFound) {
        getAgentListeners().stream().forEach(
                chainSyncAgentListener -> {
                    chainSyncAgentListener.intersactFound(intersectFound.getTip(), intersectFound.getPoint());
                }
        );
    }

    private void onRollBackward(Rollbackward rollBackward) {
        if (rollBackward.getPoint().equals(currentPoint)) //Rollback on same point. So don't rollback
            return;

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

    private void onRollForward(RollForward rollForward) {
        if (rollForward.getBlockHeader() != null) //For Byron era, this value is null. Will be fixed later
            this.currentPoint = new Point(rollForward.getBlockHeader().getHeaderBody().getSlot(), rollForward.getBlockHeader().getHeaderBody().getBlockHash());

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

        if(rollForward.getByronBlockHead() != null) { //For Byron
            log.warn("Byron Block: " + rollForward.getByronBlockHead().getConsensusData().getSlotId());
            getAgentListeners().stream().forEach(
                    chainSyncAgentListener -> {
                        chainSyncAgentListener.rollforwardByronEra(rollForward.getTip(), rollForward.getByronBlockHead());
                    }
            );
        } else { //For Shelley and later eras
            getAgentListeners().stream().forEach(
                    chainSyncAgentListener -> {
                        chainSyncAgentListener.rollforward(rollForward.getTip(), rollForward.getBlockHeader());
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
