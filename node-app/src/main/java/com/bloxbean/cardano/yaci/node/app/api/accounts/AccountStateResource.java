package com.bloxbean.cardano.yaci.node.app.api.accounts;

import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.account.AccountStateStore;
import com.bloxbean.cardano.yaci.node.app.api.accounts.dto.AccountStateDtos.*;
import com.bloxbean.cardano.yaci.node.runtime.YaciNode;
import io.quarkus.arc.ClientProxy;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
public class AccountStateResource {

    @Inject
    NodeAPI nodeAPI;

    private AccountStateStore store() {
        YaciNode yaciNode = (YaciNode) ClientProxy.unwrap(nodeAPI);
        return yaciNode.getAccountStateStore();
    }

    private Response unavailable() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity("{\"error\":\"Account state not available\"}")
                .build();
    }

    private static int clampCount(int count) {
        if (count <= 0) return 20;
        return Math.min(count, 100);
    }

    private static int clampPage(int page) {
        return page < 1 ? 1 : page;
    }

    private static String credTypeLabel(int credType) {
        return credType == 0 ? "key" : "script";
    }

    private static String drepTypeLabel(int drepType) {
        return switch (drepType) {
            case 0 -> "key_hash";
            case 1 -> "script_hash";
            case 2 -> "abstain";
            case 3 -> "no_confidence";
            default -> "unknown";
        };
    }

    @GET
    @Path("/registrations")
    public Response listRegistrations(@QueryParam("page") @DefaultValue("1") int page,
                                      @QueryParam("count") @DefaultValue("20") int count) {
        AccountStateStore s = store();
        if (s == null || !s.isEnabled()) return unavailable();
        page = clampPage(page);
        count = clampCount(count);

        List<StakeRegistrationDto> body = s.listStakeRegistrations(page, count).stream()
                .map(e -> new StakeRegistrationDto(
                        e.credentialHash(), credTypeLabel(e.credType()),
                        e.reward().toString(), e.deposit().toString()))
                .toList();
        return Response.ok(body).build();
    }

    @GET
    @Path("/delegations")
    public Response listDelegations(@QueryParam("page") @DefaultValue("1") int page,
                                    @QueryParam("count") @DefaultValue("20") int count) {
        AccountStateStore s = store();
        if (s == null || !s.isEnabled()) return unavailable();
        page = clampPage(page);
        count = clampCount(count);

        List<PoolDelegationDto> body = s.listPoolDelegations(page, count).stream()
                .map(e -> new PoolDelegationDto(
                        e.credentialHash(), credTypeLabel(e.credType()),
                        e.poolHash(), e.slot(), e.txIdx(), e.certIdx()))
                .toList();
        return Response.ok(body).build();
    }

    @GET
    @Path("/drep-delegations")
    public Response listDRepDelegations(@QueryParam("page") @DefaultValue("1") int page,
                                        @QueryParam("count") @DefaultValue("20") int count) {
        AccountStateStore s = store();
        if (s == null || !s.isEnabled()) return unavailable();
        page = clampPage(page);
        count = clampCount(count);

        List<DRepDelegationDto> body = s.listDRepDelegations(page, count).stream()
                .map(e -> new DRepDelegationDto(
                        e.credentialHash(), credTypeLabel(e.credType()),
                        drepTypeLabel(e.drepType()), e.drepHash(),
                        e.slot(), e.txIdx(), e.certIdx()))
                .toList();
        return Response.ok(body).build();
    }

    @GET
    @Path("/pools")
    public Response listPools(@QueryParam("page") @DefaultValue("1") int page,
                              @QueryParam("count") @DefaultValue("20") int count) {
        AccountStateStore s = store();
        if (s == null || !s.isEnabled()) return unavailable();
        page = clampPage(page);
        count = clampCount(count);

        List<PoolDto> body = s.listPools(page, count).stream()
                .map(e -> new PoolDto(e.poolHash(), e.deposit().toString()))
                .toList();
        return Response.ok(body).build();
    }

    @GET
    @Path("/pool-retirements")
    public Response listPoolRetirements(@QueryParam("page") @DefaultValue("1") int page,
                                        @QueryParam("count") @DefaultValue("20") int count) {
        AccountStateStore s = store();
        if (s == null || !s.isEnabled()) return unavailable();
        page = clampPage(page);
        count = clampCount(count);

        List<PoolRetirementDto> body = s.listPoolRetirements(page, count).stream()
                .map(e -> new PoolRetirementDto(e.poolHash(), e.retirementEpoch()))
                .toList();
        return Response.ok(body).build();
    }
}
