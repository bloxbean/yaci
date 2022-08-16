package com.bloxbean.cardano.yaci.core.protocol.chainsync;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshkeState;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;

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

    private BufferedWriter br;
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

        try {
            br = new BufferedWriter(new FileWriter(new File("log-" + agentNo)));
            startTime = System.currentTimeMillis();
            br.write("\nStart slot : " + knownPoints[0].getSlot());
            br.write("\nShould stop at: " + stopSlotNo);
            br.write("\nStart Time : " + new Date());
            br.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

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
                return new FindIntersect(knownPoints);
            } else {
                return new FindIntersect(new Point[]{currentPoint});
            }
        } else if (intersact != null) {
            return new RequestNext();
        } else
            return null;
    }

    @Override
    public Message deserializeResponse(byte[] bytes) {
        Message message = this.currenState.handleInbound(bytes);
        if (message instanceof IntersectFound) {
            intersact = ((IntersectFound) message).getPoint();
            tip = ((IntersectFound) message).getTip();
            log.debug("IntersectFound - " + message);
            onIntersactFound((IntersectFound) message);
        } else if (message instanceof IntersectNotFound) {
            log.debug("IntersectNotFound - " + message);
        } else if (message instanceof RollForward) {
            RollForward rollForward = (RollForward) message;
            onRollForward(rollForward);
        } else if (message instanceof Rollbackward) {
            log.debug("RollBackward - " + message);
            Rollbackward rollBackward = (Rollbackward) message;
            onRollBackward(rollBackward);
        }

        return message;
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
        this.currentPoint = new Point(rollBackward.getPoint().getSlot(), rollBackward.getPoint().getHash());

        getAgentListeners().stream().forEach(
                chainSyncAgentListener -> {
                    chainSyncAgentListener.rollbackward(rollBackward.getTip(), rollBackward.getPoint());
                }
        );
    }

    private void onRollForward(RollForward rollForward) {
        this.currentPoint = new Point(rollForward.getBlockHeader().getHeaderBody().getSlot(), rollForward.getBlockHeader().getHeaderBody().getBlockBodyHash());
        if (counter++ % 100 == 0 || (tip.getPoint().getSlot() - currentPoint.getSlot()) < 10) {
            log.debug("**********************************************************");
            log.debug(String.valueOf(currentPoint));
            log.debug("[Agent No: " + agentNo + "] : " + rollForward);
            log.debug("**********************************************************");

            if (stopAt != 0 && rollForward.getBlockHeader().getHeaderBody().getSlot() >= stopAt) {
                this.currenState = HandshkeState.Done;

                //Stop here
                try {
                    br.write("\nStopping at slot no: " + rollForward.getBlockHeader().getHeaderBody().getSlot());
                    br.write("\nStopping at blockno: " + rollForward.getBlockHeader().getHeaderBody().getBlockNumber());
                    br.write("\nTime taken: " + (System.currentTimeMillis() - startTime)/1000 + " sec");
                    br.write("\nCurrent Time : " + new Date());
                    br.write("\nTotal block processed: " + counter);
                    br.flush();
                    br.close();
                } catch (IOException e) {
//                  throw new RuntimeException(e);
                }
            }
        }

        getAgentListeners().stream().forEach(
                chainSyncAgentListener -> {
                    chainSyncAgentListener.rollforward(rollForward.getTip(), rollForward.getBlockHeader());
                }
        );
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
