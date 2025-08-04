package com.bloxbean.cardano.yaci.core.protocol.localstate;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Query;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.LocalTxMonitorState;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.bloxbean.cardano.yaci.core.protocol.localstate.LocalStateQueryState.Idle;

@Slf4j
public class LocalStateQueryAgent extends Agent<LocalStateQueryListener> {
    private Point point;
    private boolean shutDown;

    private Queue<Message> acquiredCommands;
    private Queue<Query> pendingQueryCommands;

    public LocalStateQueryAgent() {
        this(true);
    }
    public LocalStateQueryAgent(boolean isClient) {
        super(isClient);
        this.currenState = Idle;

        acquiredCommands = new ConcurrentLinkedQueue<>();
        pendingQueryCommands = new ConcurrentLinkedQueue<>();
    }

    @Override
    public int getProtocolId() {
        return 7;
    }

    @Override
    public boolean isDone() {
        return currenState == LocalStateQueryState.Done;
    }

    @Override
    protected Message buildNextMessage() {
        if (shutDown && currenState == LocalTxMonitorState.Idle) {
            if (log.isDebugEnabled())
                log.debug("Shutdown flag set. MsgDone()");
            return new MsgDone();
        }

        switch ((LocalStateQueryState) currenState) {
            case Idle:
            case Acquired:
                Message peekMsg = acquiredCommands.peek();
                if (peekMsg != null) {
                    if (log.isDebugEnabled())
                        log.debug("Found command in acquired commands queue : {}", peekMsg);

                    if (peekMsg instanceof MsgQuery)
                        pendingQueryCommands.add(((MsgQuery) peekMsg).getQuery());

                    return acquiredCommands.poll();
                } else {
                    if (log.isDebugEnabled())
                        log.debug("No query found in acquired commands queue.");
                    return null;
                }
            default:
                return null;
        }
    }

    @Override
    protected void processResponse(Message message) {
        if (message == null) {
            if (log.isDebugEnabled())
                log.debug("Message is null");
        }

        if (message instanceof MsgFailure) {
            if (log.isDebugEnabled())
                log.debug("MsgFailure: {}", message);
            onMessageFailure((MsgFailure) message);
        } else if (message instanceof MsgAcquired) {
            if (log.isDebugEnabled())
                log.debug("MsgAcquire : {}", message);
            onMessageAcquired();
        } else if (message instanceof MsgRelease) {
            if (log.isDebugEnabled())
                log.debug("MsgRelease : {}", message);
            onMessageReleased();
        } else if (message instanceof MsgResult) {
            if (log.isDebugEnabled())
                log.debug("MsgResult : {}", message);
            onMsgResult((MsgResult) message);
        }
    }

    private void onMsgResult(MsgResult msgResult) {
        if (log.isDebugEnabled())
            log.debug("MsgResult ");

        Query query;
        if (!pendingQueryCommands.isEmpty())
            query = pendingQueryCommands.poll();
        else
            query = null;

        byte[] result = msgResult.getResult();
        QueryResult queryResult = query.deserializeResult(getProtocolVersion(), CborSerializationUtil.deserialize(result));

        getAgentListeners().stream().forEach(
                listener -> listener.resultReceived(query, queryResult)
        );
    }

    private void onMessageReleased() {
        getAgentListeners().stream().forEach(
                listener -> listener.released()
        );
    }

    private void onMessageAcquired() {
        getAgentListeners().stream().forEach(
                listener -> listener.acquired(point)
        );
    }

    private void onMessageFailure(MsgFailure msgFailure) {
        getAgentListeners().stream().forEach(
                listener -> listener.acquireFailed(msgFailure.getReason())
        );
    }

    public MsgQuery query(Query query) {
        MsgQuery msgQuery = new MsgQuery(query, getProtocolVersion());
        this.currenState.verifyMessageType(msgQuery);

        acquiredCommands.add(msgQuery);
        return msgQuery;
    }

    public MsgReAcquire reAcquire(Point point) {
        MsgReAcquire msgReAcquire = new MsgReAcquire(point);
        this.currenState.verifyMessageType(msgReAcquire);

        this.point = point;
        acquiredCommands.add(msgReAcquire);
        return msgReAcquire;
    }

    public MsgAcquire acquire() {
        return acquire(null);
    }

    public MsgAcquire acquire(Point point) {
        MsgAcquire msgAcquire = new MsgAcquire(point);
        this.currenState.verifyMessageType(msgAcquire);

        this.point = point;
        acquiredCommands.add(msgAcquire);
        return msgAcquire;
    }

    public MsgRelease release() {
        MsgRelease msgRelease = new MsgRelease();
        this.currenState.verifyMessageType(msgRelease);

        acquiredCommands.add(msgRelease);
        return msgRelease;
    }

    @Override
    public void reset() {
        this.currenState = Idle;
        acquiredCommands.clear();
        pendingQueryCommands.clear();
    }

    public void shutdown() {
        this.shutDown = true;
    }

}
