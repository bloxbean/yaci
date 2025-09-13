package com.bloxbean.cardano.yaci.node.app.api.status;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.runtime.utxo.UtxoStatusProvider;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

@Path("/api/v1/status")
@Produces(MediaType.APPLICATION_JSON)
public class StatusResource {

    @Inject
    NodeAPI nodeAPI;

    @GET
    public Response status() {
        ChainState cs = nodeAPI.getChainState();
        ChainTip tip = cs != null ? cs.getTip() : null;

        Map<String, Object> chain = new HashMap<>();
        if (tip != null) {
            chain.put("slot", tip.getSlot());
            chain.put("blockNumber", tip.getBlockNumber());
            chain.put("blockHash", tip.getBlockHash() != null ? HexUtil.encodeHexString(tip.getBlockHash()) : null);
        }

        Map<String, Object> utxo = new HashMap<>();
        UtxoState u = nodeAPI.getUtxoState();
        utxo.put("enabled", u != null && u.isEnabled());
        long lastAppliedBlock = 0L;
        long lastAppliedSlot = 0L;
        String storeType = null;
        Map<String, Object> prune = new HashMap<>();

        if (u != null && u.isEnabled() && (u instanceof UtxoStatusProvider sp)) {
            storeType = sp.storeType();
            lastAppliedBlock = sp.getLastAppliedBlock();
            lastAppliedSlot = sp.getLastAppliedSlot();
            prune.put("pruneDepth", sp.getPruneDepth());
            prune.put("rollbackWindow", sp.getRollbackWindow());
            prune.put("pruneBatchSize", sp.getPruneBatchSize());
            byte[] dc = sp.getDeltaCursorKey();
            byte[] sc = sp.getSpentCursorKey();
            prune.put("deltaCursorKey", dc != null ? HexUtil.encodeHexString(dc) : null);
            prune.put("spentCursorKey", sc != null ? HexUtil.encodeHexString(sc) : null);
        }
        utxo.put("store", storeType);
        utxo.put("lastAppliedBlock", lastAppliedBlock);
        utxo.put("lastAppliedSlot", lastAppliedSlot);
        long tipBlock = tip != null ? tip.getBlockNumber() : 0L;
        utxo.put("lagBlocks", Math.max(0L, tipBlock - lastAppliedBlock));
        utxo.put("prune", prune);
        if (u instanceof UtxoStatusProvider sp) {
            utxo.put("metrics", sp.getMetrics());
            utxo.put("cfEstimates", sp.getCfEstimates());
        }
        // Always expose MMR section if UTXO state implements UtxoMmrState (even if not a UtxoStatusProvider)
        if (u instanceof com.bloxbean.cardano.yaci.node.api.utxo.UtxoMmrState mmr) {
            Map<String, Object> mmrMap = new HashMap<>();
            mmrMap.put("leafCount", mmr.getMmrLeafCount());
            mmrMap.put("root", mmr.getMmrRootHex());
            utxo.put("mmr", mmrMap);
        }

        Map<String, Object> cfEstimates = new HashMap<>();

        Map<String, Object> body = new HashMap<>();
        body.put("chain", chain);
        body.put("utxo", utxo);
        body.put("cfEstimates", cfEstimates);
        return Response.ok(body).build();
    }

    // Heavy RocksDB stats endpoint intentionally omitted in initial status implementation to avoid
    // additional dependencies in node-app. Consider adding via a runtime status provider if needed.
}
