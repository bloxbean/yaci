package com.bloxbean.cardano.yaci.core.protocol.blockfetch;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.common.GenesisConfig;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.ByronBlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.ByronEbBlockSerializer;
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
        this(true);
    }
    public BlockfetchAgent(boolean isClient) {
        super(isClient);
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
    public void processResponse(Message message) {
        if (message == null) return;
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

            log.warn("NoBlocks : {} to {}", from, to);
            onNoBlocks(from, to);
        } else if (message instanceof MsgBlock) {
            if (log.isDebugEnabled())
                log.debug("Msg block");
            onReceiveBlocks((MsgBlock) message);
        }
    }

    private void onNoBlocks(Point fromPoint, Point toPoint) {
        getAgentListeners().stream()
                .forEach(blockfetchAgentListener -> blockfetchAgentListener.noBlockFound(fromPoint, toPoint));
    }

    private void onBachStart() {
        getAgentListeners().stream().forEach(blockfetchAgentListener -> blockfetchAgentListener.batchStarted());
    }

    private void onReceiveBlocks(MsgBlock message) {
        byte[] body = message.getBytes();

        Array array = (Array) CborSerializationUtil.deserializeOne(body);
        int eraValue = ((UnsignedInteger)array.getDataItems().get(0)).getValue().intValue();
        try {
            Era era = EraUtil.getEra(eraValue);

            if (era == Era.Byron) {
                if (eraValue == 0) { //Epoch boundry block
                    ByronEbBlock block = ByronEbBlockSerializer.INSTANCE.deserialize(body);

                    //move from cursor
                    counter++;
                    getAgentListeners().stream().forEach(blockfetchAgentListener -> blockfetchAgentListener.byronEbBlockFound(block));

                    long absoluteSlot = GenesisConfig.getInstance().absoluteSlot(Era.Byron,
                            block.getHeader().getConsensusData().getEpoch(), 0);
                    this.from = new Point(absoluteSlot, block.getHeader().getBlockHash());
                } else if (eraValue == 1) {
                    ByronMainBlock block = ByronBlockSerializer.INSTANCE.deserialize(body);

                    //move from cursor
                    counter++;
                    getAgentListeners().stream().forEach(blockfetchAgentListener -> blockfetchAgentListener.byronBlockFound(block));

                    long absoluteSlot = GenesisConfig.getInstance().absoluteSlot(Era.Byron,
                            block.getHeader().getConsensusData().getSlotId().getEpoch(),
                            block.getHeader().getConsensusData().getSlotId().getSlot());
                    this.from = new Point(absoluteSlot, block.getHeader().getBlockHash());
                }
            } else {
                Block block = BlockSerializer.INSTANCE.deserialize(body);
                if (log.isDebugEnabled())
                    log.info("Block >> {}, {}, {}", eraValue, block.getHeader().getHeaderBody().getBlockNumber(), block.getHeader().getHeaderBody().getSlot());

                //move from cursor
                counter++;
                getAgentListeners().stream().forEach(blockfetchAgentListener -> blockfetchAgentListener.blockFound(block));
                this.from = new Point(block.getHeader().getHeaderBody().getSlot(), block.getHeader().getHeaderBody().getBlockHash());
            }
        } catch (Exception e) {
            errorBlks++;
            log.error("Error in parsing", e);

            //Catch exception to avoid exception propagation
            try {
                Array headerArray = (Array) ((Array) array.getDataItems().get(1)).getDataItems().get(0);
                BlockHeader blockHeader = BlockHeaderSerializer.INSTANCE.getBlockHeaderFromHeaderArray(headerArray);
                log.error("BlockHeader >> Block No: " + blockHeader.getHeaderBody().getBlockNumber() + ", Slot: " + blockHeader.getHeaderBody().getSlot());
            } catch (Exception e1) {
                log.error("Error in parsing block header", e1);
            }

            getAgentListeners().stream().forEach(blockfetchAgentListener -> blockfetchAgentListener.onParsingError(e));
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
