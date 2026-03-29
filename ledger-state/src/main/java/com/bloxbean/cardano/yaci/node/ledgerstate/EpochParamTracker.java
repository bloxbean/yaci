package com.bloxbean.cardano.yaci.node.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Update;
import com.bloxbean.cardano.yaci.core.model.governance.ProposalProcedure;
import com.bloxbean.cardano.yaci.core.model.governance.actions.GovAction;
import com.bloxbean.cardano.yaci.core.model.governance.actions.ParameterChangeAction;
import com.bloxbean.cardano.yaci.node.api.EpochParamProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks protocol parameter updates from blocks and provides epoch-resolved params.
 * <p>
 * Two sources of parameter changes:
 * <ul>
 *   <li><b>Pre-Conway:</b> {@link Update} field in transaction body containing
 *       {@link ProtocolParamUpdate} proposals. Effective at the specified epoch.</li>
 *   <li><b>Conway+:</b> {@link ParameterChangeAction} governance proposals.
 *       Ratified proposals take effect after the voting period.</li>
 * </ul>
 * <p>
 * This tracker accumulates proposed updates per epoch and merges them at epoch boundary
 * to produce a resolved set of protocol parameters. Falls back to the base
 * {@link EpochParamProvider} for any parameters not tracked from blocks.
 */
public class EpochParamTracker implements EpochParamProvider {
    private static final Logger log = LoggerFactory.getLogger(EpochParamTracker.class);

    private final EpochParamProvider baseProvider;
    private final boolean enabled;

    // Accumulated per-epoch resolved params (epoch → merged update)
    private final ConcurrentHashMap<Integer, ProtocolParamUpdate> epochParams = new ConcurrentHashMap<>();

    // Pending proposals for next epoch (pre-Conway Update mechanism)
    private final ConcurrentHashMap<Integer, ProtocolParamUpdate> pendingUpdates = new ConcurrentHashMap<>();

    public EpochParamTracker(EpochParamProvider baseProvider, boolean enabled) {
        this.baseProvider = baseProvider;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Process a transaction body to extract protocol parameter update proposals.
     */
    public void processTransaction(TransactionBody tx) {
        if (!enabled) return;

        // Pre-Conway: Update field
        // The epoch field in the Update CBOR is the proposal epoch (current epoch).
        // The update takes effect at the start of epoch + 1 (next epoch boundary).
        var update = tx.getUpdate();
        if (update != null && update.getProtocolParamUpdates() != null) {
            int effectiveEpoch = (int) update.getEpoch() + 1;
            for (var entry : update.getProtocolParamUpdates().values()) {
                pendingUpdates.merge(effectiveEpoch, entry, this::mergeUpdates);
            }
            log.debug("Tracked pre-Conway param update for epoch {} (proposal epoch {})",
                    effectiveEpoch, update.getEpoch());
        }

        // Conway: ProposalProcedures with ParameterChangeAction
        List<ProposalProcedure> proposals = tx.getProposalProcedures();
        if (proposals != null) {
            for (var proposal : proposals) {
                GovAction action = proposal.getGovAction();
                if (action instanceof ParameterChangeAction pca && pca.getProtocolParamUpdate() != null) {
                    // Conway proposals don't specify target epoch directly;
                    // they become effective after ratification. For now, we store
                    // the proposal for future epoch resolution.
                    log.debug("Tracked Conway ParameterChangeAction proposal");
                    // TODO: Track governance voting and ratification to determine effective epoch
                }
            }
        }
    }

    /**
     * Finalize parameters for an epoch. Called at epoch boundary.
     * Merges any pending updates into the resolved epoch params.
     */
    public void finalizeEpoch(int epoch) {
        if (!enabled) return;
        var pending = pendingUpdates.remove(epoch);
        var prev = epoch > 0 ? epochParams.get(epoch - 1) : null;

        if (pending != null) {
            // Merge new update on top of previous epoch's params so unchanged fields carry forward.
            // Without this merge, a hardfork update that only changes protocolVersion would lose
            // previously set fields like decentralisation.
            if (prev != null) {
                epochParams.put(epoch, mergeUpdates(prev, pending));
            } else {
                epochParams.put(epoch, pending);
            }
            log.info("Finalized protocol params for epoch {} from block updates", epoch);
        } else if (!epochParams.containsKey(epoch) && prev != null) {
            epochParams.put(epoch, prev);
        }
    }

    /**
     * Get the resolved ProtocolParamUpdate for an epoch, or null if none tracked.
     */
    public ProtocolParamUpdate getResolvedParams(int epoch) {
        return epochParams.get(epoch);
    }

    // --- EpochParamProvider delegation ---

    @Override
    public BigInteger getKeyDeposit(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getKeyDeposit() != null) return update.getKeyDeposit();
        return baseProvider.getKeyDeposit(epoch);
    }

    @Override
    public BigInteger getPoolDeposit(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getPoolDeposit() != null) return update.getPoolDeposit();
        return baseProvider.getPoolDeposit(epoch);
    }

    @Override
    public long getEpochLength() {
        return baseProvider.getEpochLength();
    }

    @Override
    public long getByronSlotsPerEpoch() {
        return baseProvider.getByronSlotsPerEpoch();
    }

    @Override
    public long getShelleyStartSlot() {
        return baseProvider.getShelleyStartSlot();
    }

    @Override
    public BigDecimal getRho(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getExpansionRate() != null) {
            return update.getExpansionRate().safeRatio();
        }
        return baseProvider.getRho(epoch);
    }

    @Override
    public BigDecimal getTau(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getTreasuryGrowthRate() != null) {
            return update.getTreasuryGrowthRate().safeRatio();
        }
        return baseProvider.getTau(epoch);
    }

    @Override
    public BigDecimal getA0(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getPoolPledgeInfluence() != null) {
            return update.getPoolPledgeInfluence().safeRatio();
        }
        return baseProvider.getA0(epoch);
    }

    @Override
    public BigDecimal getDecentralization(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getDecentralisationParam() != null) {
            return update.getDecentralisationParam().safeRatio();
        }
        return baseProvider.getDecentralization(epoch);
    }

    @Override
    public int getNOpt(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getNOpt() != null) return update.getNOpt();
        return baseProvider.getNOpt(epoch);
    }

    @Override
    public BigInteger getMinPoolCost(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getMinPoolCost() != null) return update.getMinPoolCost();
        return baseProvider.getMinPoolCost(epoch);
    }

    @Override
    public int getProtocolMajor(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getProtocolMajorVer() != null) return update.getProtocolMajorVer();
        return baseProvider.getProtocolMajor(epoch);
    }

    @Override
    public int getProtocolMinor(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getProtocolMinorVer() != null) return update.getProtocolMinorVer();
        return baseProvider.getProtocolMinor(epoch);
    }

    /**
     * Merge two ProtocolParamUpdate instances. Non-null fields in {@code newer} override {@code older}.
     */
    private ProtocolParamUpdate mergeUpdates(ProtocolParamUpdate older, ProtocolParamUpdate newer) {
        // Build a merged update — newer fields override older
        return ProtocolParamUpdate.builder()
                .minFeeA(newer.getMinFeeA() != null ? newer.getMinFeeA() : older.getMinFeeA())
                .minFeeB(newer.getMinFeeB() != null ? newer.getMinFeeB() : older.getMinFeeB())
                .maxBlockSize(newer.getMaxBlockSize() != null ? newer.getMaxBlockSize() : older.getMaxBlockSize())
                .maxTxSize(newer.getMaxTxSize() != null ? newer.getMaxTxSize() : older.getMaxTxSize())
                .maxBlockHeaderSize(newer.getMaxBlockHeaderSize() != null ? newer.getMaxBlockHeaderSize() : older.getMaxBlockHeaderSize())
                .keyDeposit(newer.getKeyDeposit() != null ? newer.getKeyDeposit() : older.getKeyDeposit())
                .poolDeposit(newer.getPoolDeposit() != null ? newer.getPoolDeposit() : older.getPoolDeposit())
                .maxEpoch(newer.getMaxEpoch() != null ? newer.getMaxEpoch() : older.getMaxEpoch())
                .nOpt(newer.getNOpt() != null ? newer.getNOpt() : older.getNOpt())
                .poolPledgeInfluence(newer.getPoolPledgeInfluence() != null ? newer.getPoolPledgeInfluence() : older.getPoolPledgeInfluence())
                .expansionRate(newer.getExpansionRate() != null ? newer.getExpansionRate() : older.getExpansionRate())
                .treasuryGrowthRate(newer.getTreasuryGrowthRate() != null ? newer.getTreasuryGrowthRate() : older.getTreasuryGrowthRate())
                .decentralisationParam(newer.getDecentralisationParam() != null ? newer.getDecentralisationParam() : older.getDecentralisationParam())
                .protocolMajorVer(newer.getProtocolMajorVer() != null ? newer.getProtocolMajorVer() : older.getProtocolMajorVer())
                .protocolMinorVer(newer.getProtocolMinorVer() != null ? newer.getProtocolMinorVer() : older.getProtocolMinorVer())
                .minPoolCost(newer.getMinPoolCost() != null ? newer.getMinPoolCost() : older.getMinPoolCost())
                .build();
    }
}
