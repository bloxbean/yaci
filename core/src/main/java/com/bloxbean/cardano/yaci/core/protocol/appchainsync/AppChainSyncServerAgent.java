package com.bloxbean.cardano.yaci.core.protocol.appchainsync;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.messages.*;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Server side of App Chain Sync (protocol 103): serves finalized app blocks
 * from a pluggable {@link BlockRangeProvider} (the embedding node's app ledger).
 */
@Slf4j
public class AppChainSyncServerAgent extends Agent<AppChainSyncListener> {

    /** Supplies raw app-block CBOR for a height range plus the current tip. */
    public interface BlockRangeProvider {
        Range blocks(String chainId, long fromHeight, long toHeight);

        record Range(List<byte[]> blocks, long tipHeight) {
        }
    }

    private static final int MAX_BLOCKS_PER_REPLY = 50;

    private final BlockRangeProvider provider;
    private volatile Message pendingReply;

    public AppChainSyncServerAgent(BlockRangeProvider provider) {
        super(false);
        this.provider = provider;
        this.currentState = AppChainSyncState.Idle;
    }

    @Override
    public int getProtocolId() {
        return 103;
    }

    @Override
    public Message buildNextMessage() {
        if (currentState == AppChainSyncState.Busy && pendingReply != null) {
            Message reply = pendingReply;
            pendingReply = null;
            return reply;
        }
        return null;
    }

    @Override
    public void processResponse(Message message) {
        if (!(message instanceof MsgRequestRange request)) {
            return;
        }
        try {
            long to = Math.min(request.getToHeight(),
                    request.getFromHeight() + MAX_BLOCKS_PER_REPLY - 1);
            BlockRangeProvider.Range range =
                    provider.blocks(request.getChainId(), request.getFromHeight(), to);
            if (range == null || range.blocks() == null || range.blocks().isEmpty()) {
                pendingReply = new MsgNoBlocks(range != null ? range.tipHeight() : 0);
            } else {
                pendingReply = new MsgBlocks(range.blocks(), range.tipHeight());
            }
        } catch (Exception e) {
            log.warn("AppChainSync range lookup failed: {}", e.toString());
            pendingReply = new MsgNoBlocks(0);
        }
        Channel ch = getChannel();
        if (ch != null && ch.isActive()) {
            ch.eventLoop().execute(this::sendNextMessage);
        }
    }

    @Override
    public boolean isDone() {
        return currentState == AppChainSyncState.Done;
    }

    @Override
    public void reset() {
        pendingReply = null;
        this.currentState = AppChainSyncState.Idle;
    }
}
