package com.bloxbean.cardano.yaci.core.protocol.appchainsync;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.messages.*;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client side of App Chain Sync (protocol 103): request a range of finalized
 * app blocks from a peer. One outstanding request at a time; results are
 * delivered to {@link AppChainSyncListener}s.
 */
@Slf4j
public class AppChainSyncClientAgent extends Agent<AppChainSyncListener> {

    private final AtomicReference<MsgRequestRange> pendingRequest = new AtomicReference<>();

    public AppChainSyncClientAgent() {
        super(true);
        this.currentState = AppChainSyncState.Idle;
    }

    @Override
    public int getProtocolId() {
        return 103;
    }

    /** Request blocks [from..to]; returns false when a request is already in flight. */
    public boolean requestRange(String chainId, long fromHeight, long toHeight) {
        if (currentState != AppChainSyncState.Idle)
            return false;
        MsgRequestRange request = new MsgRequestRange(chainId, fromHeight, toHeight);
        if (!pendingRequest.compareAndSet(null, request))
            return false;
        Channel ch = getChannel();
        if (ch != null && ch.isActive()) {
            ch.eventLoop().execute(this::sendNextMessage);
            return true;
        }
        pendingRequest.set(null);
        return false;
    }

    public boolean isIdle() {
        return currentState == AppChainSyncState.Idle && pendingRequest.get() == null;
    }

    @Override
    public Message buildNextMessage() {
        if (currentState == AppChainSyncState.Idle) {
            return pendingRequest.getAndSet(null);
        }
        return null;
    }

    @Override
    public void processResponse(Message message) {
        if (message instanceof MsgBlocks msgBlocks) {
            getAgentListeners().forEach(l ->
                    l.blocksReceived(msgBlocks.getBlocks(), msgBlocks.getTipHeight()));
        } else if (message instanceof MsgNoBlocks msgNoBlocks) {
            getAgentListeners().forEach(l ->
                    l.blocksReceived(List.of(), msgNoBlocks.getTipHeight()));
        }
    }

    @Override
    public boolean isDone() {
        return currentState == AppChainSyncState.Done;
    }

    @Override
    public void reset() {
        pendingRequest.set(null);
        this.currentState = AppChainSyncState.Idle;
    }
}
