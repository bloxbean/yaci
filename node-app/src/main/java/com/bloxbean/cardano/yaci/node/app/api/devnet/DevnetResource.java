package com.bloxbean.cardano.yaci.node.app.api.devnet;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.app.api.devnet.dto.RollbackRequest;
import com.bloxbean.cardano.yaci.node.app.api.devnet.dto.RollbackResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/v1/devnet")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DevnetResource {

    @Inject
    NodeAPI nodeAPI;

    @POST
    @Path("/rollback")
    public Response rollback(RollbackRequest request) {
        // Resolve target slot from request params
        long targetSlot;
        try {
            targetSlot = resolveTargetSlot(request);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        try {
            nodeAPI.rollbackTo(targetSlot);
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Rollback failed: " + e.getMessage()))
                    .build();
        }

        // Return new tip info
        ChainTip newTip = nodeAPI.getChainState().getTip();
        long newSlot = newTip != null ? newTip.getSlot() : 0;
        long newBlock = newTip != null ? newTip.getBlockNumber() : 0;

        return Response.ok(new RollbackResponse(
                "Rolled back to slot " + newSlot + ", block " + newBlock,
                newSlot, newBlock
        )).build();
    }

    private long resolveTargetSlot(RollbackRequest request) {
        int paramCount = 0;
        if (request.slot() != null) paramCount++;
        if (request.blockNumber() != null) paramCount++;
        if (request.count() != null) paramCount++;

        if (paramCount == 0) {
            throw new IllegalArgumentException("Exactly one of 'slot', 'blockNumber', or 'count' must be provided");
        }
        if (paramCount > 1) {
            throw new IllegalArgumentException("Exactly one of 'slot', 'blockNumber', or 'count' must be provided");
        }

        ChainState chainState = nodeAPI.getChainState();

        if (request.slot() != null) {
            return request.slot();
        }

        if (request.blockNumber() != null) {
            Long slot = chainState.getSlotByBlockNumber(request.blockNumber());
            if (slot == null) {
                throw new IllegalArgumentException("No block found with number " + request.blockNumber());
            }
            return slot;
        }

        // count mode
        ChainTip tip = chainState.getTip();
        if (tip == null) {
            throw new IllegalArgumentException("Chain is empty, cannot rollback by count");
        }

        long targetBlockNumber = tip.getBlockNumber() - request.count();
        if (targetBlockNumber < 0) {
            throw new IllegalArgumentException("Count " + request.count()
                    + " exceeds current chain height " + tip.getBlockNumber());
        }

        Long slot = chainState.getSlotByBlockNumber(targetBlockNumber);
        if (slot == null) {
            throw new IllegalArgumentException("No block found at block number " + targetBlockNumber);
        }
        return slot;
    }
}
