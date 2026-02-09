package com.bloxbean.cardano.yaci.node.app.api.utxos;

import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoMmrState;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.api.utxo.model.MmrProof;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Path("/api/v1/utxo/mmr")
@Produces(MediaType.APPLICATION_JSON)
public class MmrResource {

    @Inject
    NodeAPI nodeAPI;

    private UtxoMmrState mmr() {
        UtxoState s = nodeAPI.getUtxoState();
        if (s instanceof UtxoMmrState m) return m;
        throw new NotFoundException("MMR backend not enabled");
    }

    @GET
    @Path("/root")
    public Response root() {
        UtxoMmrState m = mmr();
        Map<String, Object> body = new HashMap<>();
        body.put("root", m.getMmrRootHex());
        body.put("leafCount", m.getMmrLeafCount());
        return Response.ok(body).build();
    }

    @GET
    @Path("/proof/{txHash}/{index}")
    public Response proof(@PathParam("txHash") String txHash, @PathParam("index") int index) {
        UtxoMmrState m = mmr();
        Optional<Long> idx = m.getLeafIndex(new Outpoint(txHash, index));
        if (idx.isEmpty()) throw new NotFoundException("No MMR leaf for given outpoint");
        Optional<MmrProof> pr = m.getProof(idx.get());
        if (pr.isEmpty()) throw new NotFoundException("Proof not found for leaf " + idx.get());
        return Response.ok(pr.get()).build();
    }

    @GET
    @Path("/leaf-index/{txHash}/{index}")
    public Response leafIndex(@PathParam("txHash") String txHash, @PathParam("index") int index) {
        UtxoMmrState m = mmr();
        Optional<Long> idx = m.getLeafIndex(new Outpoint(txHash, index));
        if (idx.isEmpty()) throw new NotFoundException("No MMR leaf for given outpoint");
        Map<String, Object> body = new HashMap<>();
        body.put("leafIndex", idx.get());
        return Response.ok(body).build();
    }
}
