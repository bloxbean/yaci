package com.bloxbean.cardano.yaci.node.app.api.nonce;

import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * REST endpoint for epoch nonce verification and debugging.
 */
@Path("/api/v1/node")
@Produces(MediaType.APPLICATION_JSON)
public class EpochNonceResource {

    @Inject
    NodeAPI nodeAPI;

    @GET
    @Path("/epoch-nonce")
    public Response getCurrentEpochNonce() {
        Map<String, Object> info = nodeAPI.getEpochNonceInfo();
        if (info == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Epoch nonce state not available"))
                    .build();
        }
        return Response.ok(info).build();
    }
}
