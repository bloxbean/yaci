package com.bloxbean.cardano.yaci.core.protocol.blockfetch;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import lombok.extern.slf4j.Slf4j;

import static com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchState.Idle;

@Slf4j
public class BlockfetchAgent extends Agent<BlockfetchAgentListener> {
    private Point from;
    private Point to;
    private boolean shutDown;
    private final long startTime;
    private long counter;
    private long errorBlks;

    public BlockfetchAgent() {
        this.currenState = Idle;

        this.startTime = System.currentTimeMillis();
    }

    @Override
    public int getProtocolId() {
        return 3;
    }

    @Override
    public Message buildNextMessage() {
        if (shutDown)
            return new ClientDone();

        switch ((BlockfetchState) currenState) {
            case Idle:
                if (from != null && to != null) {
                    return new RequestRange(from, to);
                } else
                    return null;
            default:
                return null;
        }
    }

    @Override
    public Message deserializeResponse(byte[] bytes) {
        Message message = this.currenState.handleInbound(bytes);
        if (message instanceof StartBatch) {
            if (log.isDebugEnabled())
                log.debug("Batch starting !!!");
            onBachStart();
        } else if (message instanceof BatchDone) { //Blocks found
            if (log.isDebugEnabled())
                log.debug("BatchDone >> {}", message);
            onBatchDone();
        } else if (message instanceof NoBlocks) {
            if (log.isDebugEnabled()) {
                log.debug("NoBlocks {}", message);
                log.debug("NoBlocks : {} to {}", from, to);
            }
            onNoBlocks();
        } else if (message instanceof MsgBlock) {
            if (log.isDebugEnabled())
                log.debug("Msg block");
            onReceiveBlocks((MsgBlock) message);
        }

        return message;
    }

    private void onNoBlocks() {
        getAgentListeners().stream().forEach(blockfetchAgentListener -> blockfetchAgentListener.noBlockFound());
    }

    private void onBachStart() {
        getAgentListeners().stream().forEach(blockfetchAgentListener -> blockfetchAgentListener.batchStarted());
    }

    private void onReceiveBlocks(MsgBlock message) {
        byte[] body = message.getBytes();

        Array array = (Array) CborSerializationUtil.deserialize(body);
        int version = ((UnsignedInteger)array.getDataItems().get(0)).getValue().intValue();
        try {
            Block block = BlockSerializer.INSTANCE.deserializeDI(array.getDataItems().get(1));
            if (log.isDebugEnabled())
                log.info("Block >> {}, {}, {}", version, block.getHeader().getHeaderBody().getBlockNumber(), block.getHeader().getHeaderBody().getSlot());

            //move from cursor
            counter++;
            getAgentListeners().stream().forEach(blockfetchAgentListener -> blockfetchAgentListener.blockFound(block));
            this.from = new Point(block.getHeader().getHeaderBody().getSlot(), block.getHeader().getHeaderBody().getBlockHash());
        } catch (Exception e) {
            errorBlks++;
            log.error("Error in parsing", e);
            Array headerArray = (Array) ((Array)array.getDataItems().get(1)).getDataItems().get(0);
            BlockHeader blockHeader = BlockHeaderSerializer.INSTANCE.getBlockHeaderFromHeaderArray(headerArray);
            log.error("BlockHeader >> Block No: " + blockHeader.getHeaderBody().getBlockNumber() +", Slot: " + blockHeader.getHeaderBody().getSlot());
        }
    }

    private void onBatchDone() {
        getAgentListeners().stream().forEach(blockfetchAgentListener -> blockfetchAgentListener.batchDone());
        from = null;
        to = null;

        long timeTaken = ((System.currentTimeMillis() - startTime) / 1000) / 60;

        if (log.isDebugEnabled()) {
            log.debug("Batch done");
            log.debug("Total no of block processed: " + counter);
            log.debug("Total no of err blocks: " + errorBlks);
            log.debug("Agent finished in : " + timeTaken + " min");
        }

        getAgentListeners().stream().forEach(blockfetchAgentListener -> blockfetchAgentListener.readyForNextBatch());
    }

    @Override
    public boolean isDone() {
        return currenState == BlockfetchState.Done;
    }

    @Override
    public void shutdown() {
        this.shutDown = true;
    }


    @Override
    public void reset() {
        this.currenState = Idle;
    }

    public void resetPoints(Point from, Point to) {
        this.from = from;
        this.to = to;
    }
}
