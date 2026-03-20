package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.node.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.api.events.BlockProducedEvent;
import com.bloxbean.cardano.yaci.node.api.model.MemPoolTransaction;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.ledgerrules.ValidationResult;
import com.bloxbean.cardano.yaci.node.runtime.chain.MemPool;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared utilities for block producer implementations.
 * Eliminates code duplication between {@link DevnetBlockProducer} and {@link SlotLeaderBlockProducer}.
 */
@Slf4j
public final class BlockProducerHelper {

    private BlockProducerHelper() {}

    public static void storeBlock(ChainState chainState, DevnetBlockBuilder.BlockBuildResult result) {
        chainState.storeBlock(result.blockHash(), result.blockNumber(), result.slot(), result.blockCbor());
        chainState.storeBlockHeader(result.blockHash(), result.blockNumber(), result.slot(), result.wrappedHeaderCbor());
    }

    public static void publishEvent(EventBus eventBus, DevnetBlockBuilder.BlockBuildResult result,
                              int txCount, String origin) {
        if (eventBus == null) return;

        String hashHex = HexUtil.encodeHexString(result.blockHash());
        EventMetadata meta = EventMetadata.builder()
                .origin(origin)
                .slot(result.slot())
                .blockNo(result.blockNumber())
                .blockHash(hashHex)
                .build();
        PublishOptions opts = PublishOptions.builder().build();

        try {
            eventBus.publish(
                    new BlockProducedEvent(6, result.slot(), result.blockNumber(),
                            result.blockHash(), txCount),
                    meta, opts);

            Block block = BlockSerializer.INSTANCE.deserialize(result.blockCbor());
            eventBus.publish(
                    new BlockAppliedEvent(Era.Conway, result.slot(), result.blockNumber(),
                            hashHex, block),
                    meta, opts);
        } catch (Exception e) {
            log.debug("Failed to publish block events: {}", e.getMessage());
        }
    }

    public static void notifyServer(NodeServer nodeServer) {
        if (nodeServer == null) return;
        try {
            nodeServer.notifyNewDataAvailable();
        } catch (Exception e) {
            log.warn("Failed to notify server of new block: {}", e.getMessage());
        }
    }

    public static List<byte[]> drainMempool(MemPool memPool,
                                      TransactionValidationService validatorService,
                                      UtxoState utxoState) {
        if (validatorService == null || utxoState == null) {
            List<byte[]> txList = new ArrayList<>();
            while (!memPool.isEmpty()) {
                MemPoolTransaction mpt = memPool.getNextTransaction();
                if (mpt == null) break;
                txList.add(mpt.txBytes());
            }
            return txList;
        }

        BlockBuildUtxoOverlay overlay = new BlockBuildUtxoOverlay(utxoState);
        List<byte[]> txList = new ArrayList<>();
        while (!memPool.isEmpty()) {
            MemPoolTransaction mpt = memPool.getNextTransaction();
            if (mpt == null) break;

            ValidationResult result = validatorService.validate(mpt.txBytes(), overlay.resolver());
            if (result.valid()) {
                txList.add(mpt.txBytes());
                overlay.markSpent(mpt.txBytes());
            } else {
                String txHashHex = HexUtil.encodeHexString(mpt.txHash());
                log.warn("Dropping invalid tx {} during block production: {}",
                        txHashHex, result.firstErrorMessage("unknown error"));
            }
        }
        return txList;
    }
}
