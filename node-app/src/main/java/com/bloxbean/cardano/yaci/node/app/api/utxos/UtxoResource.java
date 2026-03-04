package com.bloxbean.cardano.yaci.node.app.api.utxos;

import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yaci.node.app.api.utxos.dto.UtxoDto;
import com.bloxbean.cardano.yaci.node.app.api.utxos.dto.UtxoDtoMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UtxoResource {

    @Inject
    NodeAPI nodeAPI;

    private UtxoState utxo() {
        return nodeAPI.getUtxoState();
    }

    @GET
    @Path("/addresses/{address}/utxos")
    public Response getUtxosByAddress(@PathParam("address") String address,
                                      @QueryParam("page") @DefaultValue("1") int page,
                                      @QueryParam("limit") @DefaultValue("20") int limit,
                                      @QueryParam("use_payment_credential") @DefaultValue("false") boolean usePaymentCredential) {
        UtxoState u = utxo();
        if (u == null || !u.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"UTXO state disabled\"}")
                    .build();
        }
        if (limit <= 0) limit = 20;
        if (page < 1) page = 1;
        var list = usePaymentCredential
                ? u.getUtxosByPaymentCredential(address, page, limit)
                : u.getUtxosByAddress(address, page, limit);
        List<UtxoDto> body = UtxoDtoMapper.toDtoList(list);
        return Response.ok(body).build();
    }

    @GET
    @Path("/utxos/{txHash}/{index}")
    public Response getUtxo(@PathParam("txHash") String txHash,
                            @PathParam("index") int index) {
        UtxoState u = utxo();
        if (u == null || !u.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"UTXO state disabled\"}")
                    .build();
        }
        return u.getUtxo(new Outpoint(txHash, index))
                .map(utxo -> Response.ok(UtxoDtoMapper.toDto(utxo)).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    @Path("/credentials/{paymentCredential}/utxos")
    public Response getUtxosByPaymentCredential(@PathParam("paymentCredential") String paymentCredential,
                                                @QueryParam("page") @DefaultValue("1") int page,
                                                @QueryParam("limit") @DefaultValue("20") int limit) {
        UtxoState u = utxo();
        if (u == null || !u.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"UTXO state disabled\"}")
                    .build();
        }
        if (limit <= 0) limit = 20;
        if (page < 1) page = 1;
        var list = u.getUtxosByPaymentCredential(paymentCredential, page, limit);
        List<UtxoDto> body = UtxoDtoMapper.toDtoList(list);
        return Response.ok(body).build();
}
}

