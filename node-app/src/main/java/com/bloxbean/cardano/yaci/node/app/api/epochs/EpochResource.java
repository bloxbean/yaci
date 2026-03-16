package com.bloxbean.cardano.yaci.node.app.api.epochs;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.app.api.EpochUtil;
import com.bloxbean.cardano.yaci.node.app.api.epochs.dto.EpochDto;
import com.bloxbean.cardano.yaci.node.app.api.epochs.dto.ProtocolParamsDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Path("/api/v1/epochs")
@Produces(MediaType.APPLICATION_JSON)
public class EpochResource {

    private static final Logger log = LoggerFactory.getLogger(EpochResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    NodeAPI nodeAPI;

    @GET
    @Path("/latest")
    public Response getLatestEpoch() {
        int epoch = currentEpoch();
        return Response.ok(EpochDto.ofEpoch(epoch)).build();
    }

    @GET
    @Path("/latest/parameters")
    public Response getLatestParameters() {
        return protocolParamsResponse(currentEpoch());
    }

    @GET
    @Path("/{number}/parameters")
    public Response getParametersByEpoch(@PathParam("number") int number) {
        return protocolParamsResponse(number);
    }

    private Response protocolParamsResponse(int epoch) {
        String json = nodeAPI.getProtocolParameters();
        if (json == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Protocol parameters not available"))
                    .build();
        }
        try {
            ProtocolParamsDto dto = MAPPER.readValue(json, ProtocolParamsDto.class);
            dto.setEpoch(epoch);
            return Response.ok(dto).build();
        } catch (Exception e) {
            log.error("Failed to parse protocol parameters", e);
            return Response.serverError().build();
        }
    }

    private int currentEpoch() {
        ChainState cs = nodeAPI.getChainState();
        ChainTip tip = cs != null ? cs.getTip() : null;
        if (tip == null) return 0;
        return EpochUtil.slotToEpoch(tip.getSlot(), nodeAPI.getConfig());
    }
}
