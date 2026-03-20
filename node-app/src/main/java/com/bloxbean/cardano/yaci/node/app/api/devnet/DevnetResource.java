package com.bloxbean.cardano.yaci.node.app.api.devnet;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.config.YaciNodeConfig;
import com.bloxbean.cardano.yaci.node.api.model.FundResult;
import com.bloxbean.cardano.yaci.node.api.model.SnapshotInfo;
import com.bloxbean.cardano.yaci.node.api.model.TimeAdvanceResult;
import com.bloxbean.cardano.yaci.node.app.api.devnet.dto.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.time.Instant;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Path("/api/v1/devnet")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DevnetResource {

    private static final Logger log = LoggerFactory.getLogger(DevnetResource.class);

    @Inject
    NodeAPI nodeAPI;

    private void requireDevMode() {
        if (!(nodeAPI.getConfig() instanceof YaciNodeConfig config) || !config.isDevMode()) {
            throw new DevnetOnlyException("This endpoint requires dev mode (set yaci.node.dev-mode=true)");
        }
    }

    // --- Rollback ---

    @POST
    @Path("/rollback")
    public Response rollback(RollbackRequest request) {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

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

        ChainTip newTip = nodeAPI.getChainState().getTip();
        long newSlot = newTip != null ? newTip.getSlot() : 0;
        long newBlock = newTip != null ? newTip.getBlockNumber() : 0;

        return Response.ok(new RollbackResponse(
                "Rolled back to slot " + newSlot + ", block " + newBlock,
                newSlot, newBlock
        )).build();
    }

    // --- Snapshot ---

    @POST
    @Path("/snapshot")
    public Response createSnapshot(SnapshotRequest request) {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        try {
            SnapshotInfo info = nodeAPI.createSnapshot(request.name());
            return Response.ok(new SnapshotResponse(
                    info.name(), info.slot(), info.blockNumber(), info.createdAt()
            )).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Snapshot failed: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/restore/{name}")
    public Response restoreSnapshot(@PathParam("name") String name) {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        try {
            nodeAPI.restoreSnapshot(name);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Restore failed: " + e.getMessage()))
                    .build();
        }

        ChainTip newTip = nodeAPI.getChainState().getTip();
        long newSlot = newTip != null ? newTip.getSlot() : 0;
        long newBlock = newTip != null ? newTip.getBlockNumber() : 0;

        return Response.ok(Map.of(
                "message", "Restored snapshot '" + name + "'",
                "slot", newSlot,
                "block_number", newBlock
        )).build();
    }

    @GET
    @Path("/snapshots")
    public Response listSnapshots() {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        List<SnapshotInfo> snapshots = nodeAPI.listSnapshots();
        var response = snapshots.stream()
                .map(s -> new SnapshotResponse(s.name(), s.slot(), s.blockNumber(), s.createdAt()))
                .toList();
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/snapshot/{name}")
    public Response deleteSnapshot(@PathParam("name") String name) {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        try {
            nodeAPI.deleteSnapshot(name);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Delete failed: " + e.getMessage()))
                    .build();
        }

        return Response.ok(Map.of("message", "Snapshot '" + name + "' deleted")).build();
    }

    // --- Faucet ---

    @POST
    @Path("/fund")
    public Response fundAddress(FundRequest request) {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        if (request.ada() == null || request.ada().signum() <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "ADA amount must be positive"))
                    .build();
        }
        long lovelace = request.ada().multiply(java.math.BigDecimal.valueOf(1_000_000)).longValueExact();

        try {
            FundResult result = nodeAPI.fundAddress(request.address(), lovelace);
            return Response.ok(new FundResponse(
                    result.txHash(), result.index(), result.lovelace()
            )).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Fund failed: " + e.getMessage()))
                    .build();
        }
    }

    // --- Time/Slot Advance ---

    @POST
    @Path("/time/advance")
    public Response advanceTime(TimeAdvanceRequest request) {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        // Validate exactly one of slots/seconds/epochs
        int paramCount = 0;
        if (request.slots() != null) paramCount++;
        if (request.seconds() != null) paramCount++;
        if (request.epochs() != null) paramCount++;
        if (paramCount != 1) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Exactly one of 'slots', 'seconds', or 'epochs' must be provided"))
                    .build();
        }

        try {
            TimeAdvanceResult result;
            if (request.slots() != null) {
                result = nodeAPI.advanceTimeBySlots(request.slots());
            } else if (request.epochs() != null) {
                // Convert epochs to slots using configured epoch length
                YaciNodeConfig config = (YaciNodeConfig) nodeAPI.getConfig();
                long epochLength = config.getEpochLength();
                int slots = (int) (request.epochs() * epochLength);
                result = nodeAPI.advanceTimeBySlots(slots);
            } else {
                result = nodeAPI.advanceTimeBySeconds(request.seconds());
            }

            String message = "Advanced " + result.blocksProduced() + " blocks";
            return Response.ok(new TimeAdvanceResponse(
                    message, result.newSlot(), result.newBlockNumber(), result.blocksProduced()
            )).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Time advance failed: " + e.getMessage()))
                    .build();
        }
    }

    // --- Epoch Shift (Past Time Travel Mode) ---

    @POST
    @Path("/epochs/shift")
    public Response shiftEpochs(EpochShiftRequest request) {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        try {
            long shiftMillis = nodeAPI.shiftGenesisAndStartProducer(request.epochs());

            YaciNodeConfig config = (YaciNodeConfig) nodeAPI.getConfig();
            String systemStart = Instant.ofEpochMilli(config.getGenesisTimestamp()).toString();

            return Response.ok(Map.of(
                    "message", "Shifted genesis back by " + request.epochs() + " epochs and started block producer",
                    "shift_millis", shiftMillis,
                    "new_system_start", systemStart,
                    "genesis_slot", 0
            )).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Epoch shift failed: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/epochs/catch-up")
    public Response catchUpToWallClock() {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        try {
            TimeAdvanceResult result = nodeAPI.catchUpToWallClock();

            String message = "Caught up to wall-clock: " + result.blocksProduced() + " blocks produced";
            return Response.ok(new TimeAdvanceResponse(
                    message, result.newSlot(), result.newBlockNumber(), result.blocksProduced()
            )).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Catch-up failed: " + e.getMessage()))
                    .build();
        }
    }

    // --- Genesis Download ---

    @GET
    @Path("/genesis/download")
    @Produces("application/zip")
    public Response downloadGenesis() {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        // Safe cast — requireDevMode() already verified config is YaciNodeConfig
        YaciNodeConfig config = (YaciNodeConfig) nodeAPI.getConfig();

        // Collect genesis files
        Map<String, File> genesisFiles = new LinkedHashMap<>();
        addIfExists(genesisFiles, "shelley-genesis.json", config.getShelleyGenesisFile());
        addIfExists(genesisFiles, "byron-genesis.json", config.getByronGenesisFile());
        addIfExists(genesisFiles, "alonzo-genesis.json", config.getAlonzoGenesisFile());
        addIfExists(genesisFiles, "conway-genesis.json", config.getConwayGenesisFile());
        addIfExists(genesisFiles, "protocol-params.json", config.getProtocolParametersFile());

        if (genesisFiles.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "No genesis files available"))
                    .build();
        }

        StreamingOutput stream = output -> {
            try (ZipOutputStream zos = new ZipOutputStream(output)) {
                for (var entry : genesisFiles.entrySet()) {
                    zos.putNextEntry(new ZipEntry(entry.getKey()));
                    try (FileInputStream fis = new FileInputStream(entry.getValue())) {
                        fis.transferTo(zos);
                    }
                    zos.closeEntry();
                }
            }
        };

        return Response.ok(stream)
                .header("Content-Disposition", "attachment; filename=\"genesis.zip\"")
                .build();
    }

    private void addIfExists(Map<String, File> map, String zipName, String path) {
        if (path != null && !path.isBlank()) {
            File f = new File(path);
            if (f.exists() && f.isFile()) {
                map.put(zipName, f);
            }
        }
    }

    // --- Helpers ---

    private long resolveTargetSlot(RollbackRequest request) {
        int paramCount = 0;
        if (request.slot() != null) paramCount++;
        if (request.blockNumber() != null) paramCount++;
        if (request.count() != null) paramCount++;

        if (paramCount == 0 || paramCount > 1) {
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

    /**
     * Exception for endpoints that are only available in devnet (block producer) mode.
     */
    public static class DevnetOnlyException extends RuntimeException {
        public DevnetOnlyException(String message) {
            super(message);
        }
    }
}
