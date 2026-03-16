package com.bloxbean.cardano.yaci.node.app.api.scripts;

import com.bloxbean.cardano.client.api.util.ReferenceScriptUtil;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.app.api.scripts.dto.ScriptCborDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/scripts")
@Produces(MediaType.APPLICATION_JSON)
public class ScriptResource {

    @Inject
    NodeAPI nodeAPI;

    @GET
    @Path("/{script_hash}/cbor")
    public Response getScriptCbor(@PathParam("script_hash") String scriptHash) {
        UtxoState u = nodeAPI.getUtxoState();
        if (u == null || !u.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"UTXO state disabled\"}")
                    .build();
        }

        return u.getScriptRefBytesByHash(scriptHash)
                .map(ReferenceScriptUtil::deserializeScriptRef)
                .map(script -> {
                    try {
                        return Response.ok(new ScriptCborDto(
                                HexUtil.encodeHexString(script.serializeScriptBody()))).build();
                    } catch (CborSerializationException e) {
                        throw new RuntimeException(e);
                    }
                })
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"Script not found\"}")
                        .build());
    }
}
