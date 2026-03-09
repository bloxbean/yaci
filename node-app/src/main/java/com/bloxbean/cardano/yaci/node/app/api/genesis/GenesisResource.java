package com.bloxbean.cardano.yaci.node.app.api.genesis;

import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.model.GenesisParameters;
import com.bloxbean.cardano.yaci.node.app.api.genesis.dto.GenesisDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/v1/genesis")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GenesisResource {

    @Inject
    NodeAPI nodeAPI;

    @GET
    public Response getGenesis() {
        GenesisParameters params = nodeAPI.getGenesisParameters();
        if (params == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Genesis data not available"))
                    .build();
        }

        GenesisDto dto = new GenesisDto(
                params.activeSlotsCoefficient(),
                params.updateQuorum(),
                params.maxLovelaceSupply(),
                params.networkMagic(),
                params.epochLength(),
                params.systemStart(),
                params.slotsPerKesPeriod(),
                params.slotLength(),
                params.maxKesEvolutions(),
                params.securityParam()
        );
        return Response.ok(dto).build();
    }
}
