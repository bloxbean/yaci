package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.*;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class TxSubmissionAgent extends Agent {
    private final Map<String, byte[]> txs;
    private final List<String> reqTxIds;
    private final List<String> reqNonBlockingTxIds;

    public TxSubmissionAgent() {
        this.currentState = TxSubmissionState.Init;
        txs = new HashMap<>();
        reqTxIds = new ArrayList<>();
        reqNonBlockingTxIds = new ArrayList<>();
    }

    @Override
    public int getProtocolId() {
        return 4;
    }

    @Override
    public Message buildNextMessage() {
        switch ((TxSubmissionState) currentState) {
            case Init:
                return new Init();
            case TxIdsNonBlocking:
                return getReplyTxIds();
            case  TxIdsBlocking:
                return getReplyTxIds();
            case Txs:
                return getReplyTxs();
            default:
                return null;
        }
    }

    private ReplyTxIds getReplyTxIds() {
        if (txs != null && txs.size() > 0) {
            ReplyTxIds replyTxIds = new ReplyTxIds();
            txs.forEach((id, txBytes) -> {
                replyTxIds.addTxId(id, txBytes.length);
            });
            return replyTxIds;
        } else
            return new ReplyTxIds();
    }

    private ReplyTxs getReplyTxs() {
        if (reqTxIds.isEmpty())
            return new ReplyTxs();

        ReplyTxs replyTxs = new ReplyTxs();
        for (String txId: reqTxIds) {
            byte[] tx = txs.get(txId);
            replyTxs.addTx(tx);
        }

        return replyTxs;
    }

    @Override
    public void processResponse(Message message) {
        if (message == null) return;
        if (message instanceof RequestTxIds) {
            if (((RequestTxIds) message).isBlocking()) {
                handleRequestTxIdsBlocking((RequestTxIds) message);
            } else {
                handleRequestTxIdsNonBlocking((RequestTxIds) message);
            }
        } else if (message instanceof RequestTxs) {
            handleRequestTxs((RequestTxs) message);
        }
    }

    private void handleRequestTxs(RequestTxs requestTxs) {
        log.info("RequestTxs >>" + requestTxs);
    }

    private void handleRequestTxIdsNonBlocking(RequestTxIds requestTxIds) {
        log.info("RequestTxIdsNonBlocking >> " + requestTxIds);
    }

    private void handleRequestTxIdsBlocking(RequestTxIds requestTxIds) {
        log.info("RequestTxIdsBlocking >> " + requestTxIds);
    }

    @Override
    public boolean isDone() {
        return this.currentState == TxSubmissionState.Done;
    }

    @Override
    public void reset() {
        this.currentState = TxSubmissionState.Init;
    }
}
