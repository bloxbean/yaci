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

    public BlockfetchAgent(Point from, Point to) {
        this.from = from;
        this.to = to;
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
                if (from != null) {
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
            log.info("Batch starting !!!");
            onBachStart();
        } else if (message instanceof BatchDone) { //Blocks found
            log.debug("BatchDone ------------------>> " + message);
            onBatchDone();
        } else if (message instanceof NoBlocks) {
            log.debug("NoBlocks ******************>> " + message);
            log.debug("NoBlocks : {} to {}", from, to);
            onNoBlocks();
        } else if (message instanceof MsgBlock) {
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
//        log.info("Version >> " + version);

//        Array headerArray = (Array) ((Array)array.getDataItems().get(1)).getDataItems().get(0);
//        BlockHeader blockHeader = BlockHeaderSerializer.INSTANCE.getBlockHeaderFromHeaderArray(headerArray);

        try {
            Block block = BlockSerializer.INSTANCE.deserializeDI(array.getDataItems().get(1));
//            log.info("Block >> " + JsonUtil.getPrettyJson(block));
            log.info("Block >> " + version + ", " + block.getHeader().getHeaderBody().getBlockNumber() + ", " + block.getHeader().getHeaderBody().getSlot());
            //redissionHelper.put(String.valueOf(block.getHeader().getHeaderBody().getBlockNumber()), block);
            counter++;

            getAgentListeners().stream().forEach(blockfetchAgentListener -> blockfetchAgentListener.blockFound(block));
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
        log.info("BatchDone >>>>>>>>>>>>");
        from = null;
        to = null;

        long timeTaken = ((System.currentTimeMillis() - startTime) / 1000) / 60;

        log.info("Total no of block processed: " + counter);
        log.info("Total no of err blocks: " + errorBlks);
        log.info("Agent finished in : " + timeTaken + " min");
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

    public void reset(Point from, Point to) {
        this.from = from;
        this.to = to;

    }
}
