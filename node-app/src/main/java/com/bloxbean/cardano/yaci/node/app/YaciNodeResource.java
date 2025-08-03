package com.bloxbean.cardano.yaci.node.app;

import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.model.NodeStatus;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST API for managing the Yaci Node
 */
@Path("/api/v1/node")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class YaciNodeResource {

    @Inject
    NodeAPI nodeAPI;

    @GET
    @Path("/status")
    public NodeStatus getStatus() {
        return nodeAPI.getStatus();
    }

    @POST
    @Path("/start")
    public Response start() {
        if (nodeAPI.isRunning()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\": \"Node is already running\"}")
                    .build();
        }

        try {
            nodeAPI.start();
            return Response.ok()
                    .entity("{\"message\": \"Node started successfully\"}")
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to start node: " + e.getMessage() + "\"}")
                    .build();
        }
    }

    @POST
    @Path("/stop")
    public Response stop() {
        if (!nodeAPI.isRunning()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\": \"Node is not running\"}")
                    .build();
        }

        try {
            nodeAPI.stop();
            return Response.ok()
                    .entity("{\"message\": \"Node stopped successfully\"}")
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to stop node: " + e.getMessage() + "\"}")
                    .build();
        }
    }

    @GET
    @Path("/tip")
    public Response getLocalTip() {
        var tip = nodeAPI.getLocalTip();
        if (tip == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"No local tip available\"}")
                    .build();
        }
        return Response.ok(tip).build();
    }

    @GET
    @Path("/config")
    public Response getConfig() {
        return Response.ok(nodeAPI.getConfig()).build();
    }
}
