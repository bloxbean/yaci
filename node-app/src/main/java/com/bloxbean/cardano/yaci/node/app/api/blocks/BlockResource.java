package com.bloxbean.cardano.yaci.node.app.api.blocks;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.app.api.EpochUtil;
import com.bloxbean.cardano.yaci.node.app.api.blocks.dto.BlockDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigInteger;
import java.util.Map;

@Path("/api/v1/blocks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BlockResource {

    @Inject
    NodeAPI nodeAPI;

    @GET
    @Path("/{hashOrNumber}")
    public Response getBlock(@PathParam("hashOrNumber") String hashOrNumber) {
        ChainState chainState = nodeAPI.getChainState();
        if (chainState == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Chain state not available"))
                    .build();
        }

        byte[] blockCbor = null;
        try {
            // Detect if parameter is a block number (all digits) or a hash (hex)
            if (hashOrNumber.matches("\\d+")) {
                long blockNumber = Long.parseLong(hashOrNumber);
                blockCbor = chainState.getBlockByNumber(blockNumber);
            } else {
                blockCbor = chainState.getBlock(HexUtil.decodeHexString(hashOrNumber));
            }
        } catch (Exception e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Block not found"))
                    .build();
        }

        if (blockCbor == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Block not found",
                            "status_code", 404,
                            "message", "The requested component has not been found."))
                    .build();
        }

        try {
            Block block = BlockSerializer.INSTANCE.deserialize(blockCbor);
            ChainTip tip = chainState.getTip();
            int confirmations = 0;
            if (tip != null) {
                confirmations = (int) (tip.getBlockNumber() - block.getHeader().getHeaderBody().getBlockNumber());
            }
            BlockDto dto = toBlockDto(block, confirmations);
            return Response.ok(dto).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to deserialize block: " + e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/latest")
    public Response getLatestBlock() {
        ChainState chainState = nodeAPI.getChainState();
        if (chainState == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Chain state not available"))
                    .build();
        }

        ChainTip tip = chainState.getTip();
        if (tip == null || tip.getBlockHash() == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No blocks found"))
                    .build();
        }

        byte[] blockCbor = chainState.getBlock(tip.getBlockHash());
        if (blockCbor == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Block body not found"))
                    .build();
        }

        try {
            Block block = BlockSerializer.INSTANCE.deserialize(blockCbor);
            BlockDto dto = toBlockDto(block, 0);
            return Response.ok(dto).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to deserialize block: " + e.getMessage()))
                    .build();
        }
    }

    private BlockDto toBlockDto(Block block, int confirmations) {
        HeaderBody hb = block.getHeader().getHeaderBody();
        long slot = hb.getSlot();
        int epoch = EpochUtil.slotToEpoch(slot, nodeAPI.getConfig());
        int epochSlot = EpochUtil.slotToEpochSlot(slot, nodeAPI.getConfig());

        int txCount = block.getTransactionBodies() != null ? block.getTransactionBodies().size() : 0;

        // Sum fees and outputs from transaction bodies
        BigInteger totalFees = BigInteger.ZERO;
        BigInteger totalOutput = BigInteger.ZERO;
        if (block.getTransactionBodies() != null) {
            for (TransactionBody tx : block.getTransactionBodies()) {
                if (tx.getFee() != null) {
                    totalFees = totalFees.add(tx.getFee());
                }
                if (tx.getOutputs() != null) {
                    for (var out : tx.getOutputs()) {
                        if (out.getAmounts() != null) {
                            for (var amt : out.getAmounts()) {
                                if ("lovelace".equals(amt.getUnit()) && amt.getQuantity() != null) {
                                    totalOutput = totalOutput.add(amt.getQuantity());
                                }
                            }
                        }
                    }
                }
            }
        }

        int eraNum = block.getEra() != null ? block.getEra().getValue() : 0;

        return new BlockDto(
                nodeAPI.slotToUnixTime(slot),
                hb.getBlockNumber(),
                hb.getBlockNumber(),
                hb.getBlockHash(),
                slot,
                epoch,
                epochSlot,
                hb.getIssuerVkey(),
                hb.getBlockBodySize(),
                txCount,
                totalOutput.toString(),
                totalFees.toString(),
                hb.getPrevHash(),
                null, // nextBlock
                confirmations,
                eraNum
        );
    }
}
