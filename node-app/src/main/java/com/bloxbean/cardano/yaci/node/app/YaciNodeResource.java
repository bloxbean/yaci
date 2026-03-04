package com.bloxbean.cardano.yaci.node.app;

import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/v1/node")
@Produces(MediaType.APPLICATION_JSON)
public class YaciNodeResource {

    @Inject
    NodeAPI nodeAPI;

    @GET
    @Path("/status")
    public Response getStatus() {
        return Response.ok(nodeAPI.getStatus()).build();
    }

    @POST
    @Path("/start")
    public Response start() {
        if (nodeAPI.isRunning()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Node is already running"))
                    .build();
        }

        try {
            nodeAPI.start();
            return Response.ok(Map.of("message", "Node started successfully")).build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Failed to start node: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/stop")
    public Response stop() {
        if (!nodeAPI.isRunning()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Node is not running"))
                    .build();
        }

        try {
            nodeAPI.stop();
            return Response.ok(Map.of("message", "Node stopped successfully")).build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Failed to stop node: " + e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/tip")
    public Response getLocalTip() {
        var tip = nodeAPI.getLocalTip();
        if (tip == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No local tip available"))
                    .build();
        }
        return Response.ok(tip).build();
    }

    @GET
    @Path("/config")
    public Response getConfig() {
        return Response.ok(nodeAPI.getConfig()).build();
    }

    @POST
    @Path("/recover")
    public Response recoverChainState() {
        try {
            if (nodeAPI.isRunning()) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "Cannot recover chain state while node is running. Please stop the node first."))
                        .build();
            }

            boolean recovered = nodeAPI.recoverChainState();

            if (recovered) {
                return Response.ok(Map.of("message", "Chain state recovery completed successfully")).build();
            } else {
                return Response.ok(Map.of("message", "No corruption detected or recovery not needed")).build();
            }

        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Recovery failed: " + e.getMessage()))
                    .build();
        }
    }
}
