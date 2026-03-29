package com.bloxbean.cardano.yaci.node.app.api.accounts;

import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.ledgerstate.AccountStateCborCodec;
import com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore;
import com.bloxbean.cardano.yaci.node.ledgerstate.EpochRewardCalculator;
import com.bloxbean.cardano.yaci.node.runtime.YaciNode;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.arc.ClientProxy;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

@Path("/api/debug")
@Produces(MediaType.APPLICATION_JSON)
public class DebugSnapshotResource {

    @Inject
    NodeAPI nodeAPI;

    // --- DTOs ---

    record EpochSnapshotEntry(
            @JsonProperty("cred_type") int credType,
            @JsonProperty("cred_hash") String credHash,
            @JsonProperty("pool_hash") String poolHash,
            @JsonProperty("amount") BigInteger amount
    ) {}

    record PoolBlockCountEntry(
            @JsonProperty("pool_hash") String poolHash,
            @JsonProperty("block_count") long blockCount
    ) {}

    record EpochSnapshotSummary(
            @JsonProperty("epoch") int epoch,
            @JsonProperty("total_entries") int totalEntries,
            @JsonProperty("total_active_stake") BigInteger totalActiveStake,
            @JsonProperty("entries") List<EpochSnapshotEntry> entries
    ) {}

    record PoolBlockCountSummary(
            @JsonProperty("epoch") int epoch,
            @JsonProperty("total_pools") int totalPools,
            @JsonProperty("total_blocks") long totalBlocks,
            @JsonProperty("entries") List<PoolBlockCountEntry> entries
    ) {}

    record ProtocolParamsDto(
            @JsonProperty("decentralisation") BigDecimal decentralisation,
            @JsonProperty("treasury_grow_rate") BigDecimal treasuryGrowRate,
            @JsonProperty("monetary_expand_rate") BigDecimal monetaryExpandRate,
            @JsonProperty("optimal_pool_count") int optimalPoolCount,
            @JsonProperty("pool_owner_influence") BigDecimal poolOwnerInfluence
    ) {}

    record EpochInfoDto(
            @JsonProperty("number") int number,
            @JsonProperty("fees") BigInteger fees,
            @JsonProperty("block_count") int blockCount,
            @JsonProperty("active_stake") BigInteger activeStake,
            @JsonProperty("non_obft_block_count") int nonOBFTBlockCount
    ) {}

    record PoolStateDto(
            @JsonProperty("pool_id") String poolId,
            @JsonProperty("block_count") int blockCount,
            @JsonProperty("active_stake") BigInteger activeStake,
            @JsonProperty("delegator_count") int delegatorCount,
            @JsonProperty("margin") double margin,
            @JsonProperty("fixed_cost") BigInteger fixedCost,
            @JsonProperty("pledge") BigInteger pledge,
            @JsonProperty("reward_address") String rewardAddress,
            @JsonProperty("owners") Set<String> owners,
            @JsonProperty("owner_active_stake") BigInteger ownerActiveStake
    ) {}

    record RetiredPoolDto(
            @JsonProperty("pool_id") String poolId,
            @JsonProperty("reward_address") String rewardAddress,
            @JsonProperty("deposit_amount") BigInteger depositAmount
    ) {}

    record AdaPotDto(
            @JsonProperty("epoch") int epoch,
            @JsonProperty("treasury") BigInteger treasury,
            @JsonProperty("reserves") BigInteger reserves,
            @JsonProperty("deposits") BigInteger deposits,
            @JsonProperty("fees") BigInteger fees,
            @JsonProperty("distributed") BigInteger distributed,
            @JsonProperty("undistributed") BigInteger undistributed,
            @JsonProperty("rewards_pot") BigInteger rewardsPot,
            @JsonProperty("pool_rewards_pot") BigInteger poolRewardsPot
    ) {}

    record PoolParamsDto(
            @JsonProperty("pool_hash") String poolHash,
            @JsonProperty("deposit") BigInteger deposit,
            @JsonProperty("margin") double margin,
            @JsonProperty("cost") BigInteger cost,
            @JsonProperty("pledge") BigInteger pledge,
            @JsonProperty("reward_account") String rewardAccount,
            @JsonProperty("owners") Set<String> owners
    ) {}

    record RewardInputsDto(
            @JsonProperty("epoch") int epoch,
            @JsonProperty("stake_epoch") int stakeEpoch,
            @JsonProperty("fee_epoch") int feeEpoch,
            @JsonProperty("prev_treasury") BigInteger prevTreasury,
            @JsonProperty("prev_reserves") BigInteger prevReserves,
            @JsonProperty("protocol_parameters") ProtocolParamsDto protocolParameters,
            @JsonProperty("epoch_info") EpochInfoDto epochInfo,
            @JsonProperty("stake_snapshot_count") int stakeSnapshotCount,
            @JsonProperty("stake_snapshot_total") BigInteger stakeSnapshotTotal,
            @JsonProperty("pool_block_counts") Map<String, Long> poolBlockCounts,
            @JsonProperty("total_blocks") long totalBlocks,
            @JsonProperty("epoch_fees") BigInteger epochFees,
            @JsonProperty("pool_states") List<PoolStateDto> poolStates,
            @JsonProperty("retired_pools") List<RetiredPoolDto> retiredPools,
            @JsonProperty("deregistered") Set<String> deregistered,
            @JsonProperty("late_deregistered") Set<String> lateDeregistered,
            @JsonProperty("deregistered_on_boundary") Set<String> deregisteredOnBoundary,
            @JsonProperty("registered_since_last") Set<String> registeredSinceLast,
            @JsonProperty("registered_until_now") Set<String> registeredUntilNow,
            @JsonProperty("shared_pool_reward_addresses") Set<String> sharedPoolRewardAddresses,
            @JsonProperty("mir_certificates_count") int mirCertificatesCount
    ) {}

    // --- Helpers ---

    private DefaultAccountStateStore defaultStore() {
        YaciNode yaciNode = (YaciNode) ClientProxy.unwrap(nodeAPI);
        var store = yaciNode.getAccountStateStore();
        if (store instanceof DefaultAccountStateStore ds) return ds;
        return null;
    }

    private EpochRewardCalculator rewardCalculator() {
        var store = defaultStore();
        return store != null ? store.getRewardCalculator() : null;
    }

    // --- Existing Endpoints ---

    @GET
    @Path("/epoch-snapshot/{epoch}")
    public Response getEpochSnapshot(@PathParam("epoch") int epoch) {
        EpochRewardCalculator calc = rewardCalculator();
        if (calc == null || !calc.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"Reward calculator not available\"}")
                    .build();
        }

        var snapshot = calc.getStakeSnapshot(epoch);
        if (snapshot.isEmpty()) {
            return Response.ok(new EpochSnapshotSummary(epoch, 0, BigInteger.ZERO, List.of())).build();
        }

        List<EpochSnapshotEntry> entries = new ArrayList<>();
        BigInteger totalStake = BigInteger.ZERO;

        for (var entry : snapshot.entrySet()) {
            String credKey = entry.getKey(); // "credType:credHash"
            var deleg = entry.getValue();

            int credType = 0;
            String credHash = credKey;
            int colonIdx = credKey.indexOf(':');
            if (colonIdx >= 0) {
                credType = Integer.parseInt(credKey.substring(0, colonIdx));
                credHash = credKey.substring(colonIdx + 1);
            }

            entries.add(new EpochSnapshotEntry(credType, credHash, deleg.poolHash(), deleg.amount()));
            totalStake = totalStake.add(deleg.amount());
        }

        entries.sort(Comparator.comparing(EpochSnapshotEntry::credHash));

        return Response.ok(new EpochSnapshotSummary(epoch, entries.size(), totalStake, entries)).build();
    }

    @GET
    @Path("/epoch-block-counts/{epoch}")
    public Response getEpochBlockCounts(@PathParam("epoch") int epoch) {
        EpochRewardCalculator calc = rewardCalculator();
        if (calc == null || !calc.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"Reward calculator not available\"}")
                    .build();
        }

        var blockCounts = calc.getPoolBlockCounts(epoch);
        long totalBlocks = blockCounts.values().stream().mapToLong(Long::longValue).sum();

        List<PoolBlockCountEntry> entries = blockCounts.entrySet().stream()
                .map(e -> new PoolBlockCountEntry(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(PoolBlockCountEntry::poolHash))
                .toList();

        return Response.ok(new PoolBlockCountSummary(epoch, entries.size(), totalBlocks, entries)).build();
    }

    @GET
    @Path("/epoch-fees/{epoch}")
    public Response getEpochFees(@PathParam("epoch") int epoch) {
        EpochRewardCalculator calc = rewardCalculator();
        if (calc == null || !calc.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"Reward calculator not available\"}")
                    .build();
        }

        BigInteger fees = calc.getEpochFees(epoch);
        return Response.ok(Map.of("epoch", epoch, "fees", fees)).build();
    }

    @GET
    @Path("/utxo-balance/{credHash}")
    public Response getUtxoBalance(@PathParam("credHash") String credHash) {
        YaciNode yaciNode = (YaciNode) ClientProxy.unwrap(nodeAPI);
        UtxoState utxoState = yaciNode.getUtxoState();
        if (utxoState == null || !utxoState.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"UTXO state not available\"}")
                    .build();
        }

        Map<String, BigInteger> byAddress = new HashMap<>();
        BigInteger[] total = {BigInteger.ZERO};
        int[] utxoCount = {0};

        utxoState.forEachUtxo((addressStr, lovelace) -> {
            if (lovelace == null || lovelace.signum() <= 0) return;
            try {
                var address = new com.bloxbean.cardano.client.address.Address(addressStr);
                byte[] delegHash = address.getDelegationCredentialHash().orElse(null);
                if (delegHash == null) return;
                String hex = com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(delegHash);
                if (hex.equals(credHash)) {
                    total[0] = total[0].add(lovelace);
                    utxoCount[0]++;
                    byAddress.merge(addressStr, lovelace, BigInteger::add);
                }
            } catch (Exception ignored) {}
        });

        return Response.ok(Map.of(
                "cred_hash", credHash,
                "total_lovelace", total[0],
                "utxo_count", utxoCount[0],
                "by_address", byAddress
        )).build();
    }

    // --- New Endpoints: Reward Input Verification ---

    @GET
    @Path("/reward-inputs/{epoch}")
    public Response getRewardInputs(@PathParam("epoch") int epoch) {
        EpochRewardCalculator calc = rewardCalculator();
        DefaultAccountStateStore store = defaultStore();
        if (calc == null || !calc.isEnabled() || store == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"Reward calculator not available\"}")
                    .build();
        }

        // Get previous epoch AdaPot for treasury/reserves
        var adaPotTracker = store.getAdaPotTracker();
        BigInteger prevTreasury = BigInteger.ZERO;
        BigInteger prevReserves = BigInteger.ZERO;
        if (adaPotTracker != null && adaPotTracker.isEnabled()) {
            var prevPot = adaPotTracker.getAdaPot(epoch - 1);
            if (prevPot.isPresent()) {
                prevTreasury = prevPot.get().treasury();
                prevReserves = prevPot.get().reserves();
            }
        }

        // Use effective param provider: prefer tracker over base provider
        var paramTracker = store.getParamTracker();
        var paramProvider = (paramTracker != null && paramTracker.isEnabled())
                ? (com.bloxbean.cardano.yaci.node.api.EpochParamProvider) paramTracker
                : store.getEpochParamProvider();
        long networkMagic = store.getNetworkMagic();

        var inputsOpt = calc.assembleRewardInputs(epoch, prevTreasury, prevReserves, paramProvider, networkMagic);
        if (inputsOpt.isEmpty()) {
            return Response.ok(Map.of("error", "Unable to assemble inputs for epoch " + epoch,
                    "hint", "epoch must be >= 2 and reward calculator must be enabled")).build();
        }

        var inputs = inputsOpt.get();

        // Convert to DTO
        var pp = inputs.protocolParameters();
        var ppDto = new ProtocolParamsDto(
                pp.getDecentralisation(), pp.getTreasuryGrowRate(), pp.getMonetaryExpandRate(),
                pp.getOptimalPoolCount(), pp.getPoolOwnerInfluence());

        var ei = inputs.epochInfo();
        var eiDto = new EpochInfoDto(
                ei.getNumber(), ei.getFees(), ei.getBlockCount(),
                ei.getActiveStake(), ei.getNonOBFTBlockCount());

        var snapshotTotal = inputs.stakeSnapshot().values().stream()
                .map(AccountStateCborCodec.EpochDelegSnapshot::amount)
                .reduce(BigInteger.ZERO, BigInteger::add);

        long totalBlocks = inputs.poolBlockCounts().values().stream().mapToLong(Long::longValue).sum();

        var poolStateDtos = inputs.poolStates().stream()
                .map(ps -> new PoolStateDto(
                        ps.getPoolId(), ps.getBlockCount(), ps.getActiveStake(),
                        ps.getDelegators() != null ? ps.getDelegators().size() : 0,
                        ps.getMargin(), ps.getFixedCost(), ps.getPledge(),
                        ps.getRewardAddress(), ps.getOwners(), ps.getOwnerActiveStake()))
                .sorted(Comparator.comparing(PoolStateDto::poolId))
                .toList();

        var retiredPoolDtos = inputs.retiredPools().stream()
                .map(rp -> new RetiredPoolDto(rp.getPoolId(), rp.getRewardAddress(), rp.getDepositAmount()))
                .sorted(Comparator.comparing(RetiredPoolDto::poolId))
                .toList();

        var dto = new RewardInputsDto(
                inputs.epoch(), inputs.stakeEpoch(), inputs.feeEpoch(),
                inputs.prevTreasury(), inputs.prevReserves(),
                ppDto, eiDto,
                inputs.stakeSnapshot().size(), snapshotTotal,
                inputs.poolBlockCounts(), totalBlocks, inputs.epochFees(),
                poolStateDtos, retiredPoolDtos,
                inputs.deregistered(), inputs.lateDeregistered(),
                inputs.deregisteredOnBoundary(),
                inputs.registeredSinceLast(), inputs.registeredUntilNow(),
                inputs.sharedPoolRewardAddresses(),
                inputs.mirCertificates().size());

        return Response.ok(dto).build();
    }

    @GET
    @Path("/adapot/{epoch}")
    public Response getAdaPot(@PathParam("epoch") int epoch) {
        DefaultAccountStateStore store = defaultStore();
        if (store == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"Account state store not available\"}")
                    .build();
        }

        var adaPotTracker = store.getAdaPotTracker();
        if (adaPotTracker == null || !adaPotTracker.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"AdaPot tracker not enabled\"}")
                    .build();
        }

        var potOpt = adaPotTracker.getAdaPot(epoch);
        if (potOpt.isEmpty()) {
            return Response.ok(Map.of("epoch", epoch, "error", "No AdaPot data for epoch " + epoch)).build();
        }

        var pot = potOpt.get();
        return Response.ok(new AdaPotDto(
                epoch, pot.treasury(), pot.reserves(), pot.deposits(),
                pot.fees(), pot.distributed(), pot.undistributed(),
                pot.rewardsPot(), pot.poolRewardsPot()
        )).build();
    }

    @GET
    @Path("/pool-params/{poolHash}")
    public Response getPoolParams(@PathParam("poolHash") String poolHash) {
        EpochRewardCalculator calc = rewardCalculator();
        if (calc == null || !calc.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"Reward calculator not available\"}")
                    .build();
        }

        var paramsOpt = calc.getPoolParams(poolHash);
        if (paramsOpt.isEmpty()) {
            return Response.ok(Map.of("pool_hash", poolHash, "error", "Pool not found")).build();
        }

        var pp = paramsOpt.get();
        return Response.ok(new PoolParamsDto(
                poolHash, pp.deposit(), pp.margin(), pp.cost(),
                pp.pledge(), pp.rewardAccount(), pp.owners()
        )).build();
    }

    @GET
    @Path("/pool-params/{poolHash}/epoch/{epoch}")
    public Response getPoolParamsAtEpoch(@PathParam("poolHash") String poolHash,
                                         @PathParam("epoch") int epoch) {
        DefaultAccountStateStore store = defaultStore();
        if (store == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"Account state store not available\"}")
                    .build();
        }

        var paramsOpt = store.getPoolParams(poolHash, epoch);
        if (paramsOpt.isEmpty()) {
            return Response.ok(Map.of("pool_hash", poolHash, "epoch", epoch,
                    "error", "Pool not found at epoch")).build();
        }

        var pp = paramsOpt.get();
        return Response.ok(new PoolParamsDto(
                poolHash, pp.deposit(), pp.margin(), pp.cost(),
                pp.pledge(), pp.rewardAccount(), pp.owners()
        )).build();
    }

    @GET
    @Path("/retired-pools/{epoch}")
    public Response getRetiredPools(@PathParam("epoch") int epoch) {
        DefaultAccountStateStore store = defaultStore();
        if (store == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"Account state store not available\"}")
                    .build();
        }

        var retiring = store.getPoolsRetiringAtEpoch(epoch);
        var dtos = retiring.stream()
                .map(rp -> Map.of(
                        "pool_hash", rp.poolHash(),
                        "deposit", rp.deposit(),
                        "retire_epoch", rp.retireEpoch()))
                .toList();

        return Response.ok(Map.of("epoch", epoch, "count", retiring.size(), "pools", dtos)).build();
    }

    @GET
    @Path("/deregistered-accounts/{epoch}")
    public Response getDeregisteredAccounts(@PathParam("epoch") int epoch) {
        DefaultAccountStateStore store = defaultStore();
        EpochRewardCalculator calc = rewardCalculator();
        if (store == null || calc == null || !calc.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"Account state store not available\"}")
                    .build();
        }

        // Compute slot range for epoch-1 (the fee epoch when reward epoch = epoch)
        int feeEpoch = epoch - 1;
        long feeEpochStartSlot = store.slotForEpochStart(feeEpoch);
        long feeEpochEndSlot = store.slotForEpochStart(feeEpoch + 1);

        var deregistered = store.getDeregisteredAccountsInSlotRange(feeEpochStartSlot, feeEpochEndSlot);

        return Response.ok(Map.of(
                "epoch", epoch,
                "fee_epoch", feeEpoch,
                "fee_epoch_start_slot", feeEpochStartSlot,
                "fee_epoch_end_slot", feeEpochEndSlot,
                "count", deregistered.size(),
                "accounts", new TreeSet<>(deregistered)
        )).build();
    }

    @GET
    @Path("/adapot-chain")
    public Response getAdaPotChain(
            @QueryParam("from") @DefaultValue("0") int fromEpoch,
            @QueryParam("to") @DefaultValue("10") int toEpoch) {
        DefaultAccountStateStore store = defaultStore();
        if (store == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"Account state store not available\"}")
                    .build();
        }

        var adaPotTracker = store.getAdaPotTracker();
        if (adaPotTracker == null || !adaPotTracker.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"AdaPot tracker not enabled\"}")
                    .build();
        }

        List<AdaPotDto> chain = new ArrayList<>();
        for (int e = fromEpoch; e <= toEpoch; e++) {
            var potOpt = adaPotTracker.getAdaPot(e);
            if (potOpt.isPresent()) {
                var pot = potOpt.get();
                chain.add(new AdaPotDto(e, pot.treasury(), pot.reserves(), pot.deposits(),
                        pot.fees(), pot.distributed(), pot.undistributed(),
                        pot.rewardsPot(), pot.poolRewardsPot()));
            }
        }

        return Response.ok(Map.of("from_epoch", fromEpoch, "to_epoch", toEpoch,
                "count", chain.size(), "adapots", chain)).build();
    }
}
