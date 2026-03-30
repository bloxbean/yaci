package com.bloxbean.cardano.yaci.node.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.node.api.EpochParamProvider;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks protocol parameter updates and provides epoch-resolved params.
 * <p>
 * Two sources of parameter changes depending on era:
 * <ul>
 *   <li><b>Pre-Conway (Byron–Babbage):</b> {@code Update} field in transaction body containing
 *       {@link ProtocolParamUpdate} proposals. Takes effect at the specified epoch + 1.</li>
 *   <li><b>Conway+:</b> {@code ParameterChangeAction} governance proposals. These go through
 *       voting → ratification → enactment. When enacted by {@code GovernanceEpochProcessor},
 *       it calls {@link #applyEnactedParamChange(int, ProtocolParamUpdate)} to apply the
 *       update for the target epoch.</li>
 * </ul>
 * <p>
 * Falls back to the base {@link EpochParamProvider} for any parameters not tracked from blocks.
 */
public class EpochParamTracker implements EpochParamProvider {
    private static final Logger log = LoggerFactory.getLogger(EpochParamTracker.class);

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final EpochParamProvider baseProvider;
    private final boolean enabled;

    // RocksDB persistence — dedicated column family (null = in-memory only, e.g. tests)
    private final RocksDB db;
    private final ColumnFamilyHandle cfEpochParams;

    // Accumulated per-epoch resolved params (epoch → merged update)
    private final ConcurrentHashMap<Integer, ProtocolParamUpdate> epochParams = new ConcurrentHashMap<>();

    // Pending proposals for next epoch (pre-Conway Update mechanism)
    private final ConcurrentHashMap<Integer, ProtocolParamUpdate> pendingUpdates = new ConcurrentHashMap<>();

    /**
     * Create a tracker with RocksDB persistence. On construction, loads all persisted
     * epoch params into the in-memory map so lookups work immediately after restart.
     *
     * @param baseProvider   Fallback provider for params not tracked from blocks
     * @param enabled        Whether tracking is enabled
     * @param db             RocksDB instance
     * @param cfEpochParams  Dedicated column family for epoch params (key: epoch(4 BE), value: JSON bytes)
     */
    public EpochParamTracker(EpochParamProvider baseProvider, boolean enabled,
                             RocksDB db, ColumnFamilyHandle cfEpochParams) {
        this.baseProvider = baseProvider;
        this.enabled = enabled;
        this.db = db;
        this.cfEpochParams = cfEpochParams;
        if (enabled && db != null && cfEpochParams != null) {
            loadPersistedParams();
        }
    }

    /** Create a tracker without RocksDB persistence (in-memory only, for tests). */
    public EpochParamTracker(EpochParamProvider baseProvider, boolean enabled) {
        this(baseProvider, enabled, null, null);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Process a transaction body to extract pre-Conway protocol parameter update proposals.
     * <p>
     * In pre-Conway eras, the {@code Update} field in the transaction body contains proposed
     * parameter changes with a target epoch. These take effect at epoch + 1.
     * <p>
     * In Conway+, parameter changes come through governance (ParameterChangeAction proposals).
     * These are NOT processed here — they go through ratification in GovernanceEpochProcessor
     * and are applied via {@link #applyEnactedParamChange(int, ProtocolParamUpdate)}.
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
        // Conway ParameterChangeAction proposals are handled by GovernanceEpochProcessor
        // after ratification. They call applyEnactedParamChange() at epoch boundary.
    }

    /**
     * Apply a ratified and enacted ParameterChangeAction for the given epoch.
     * Called by GovernanceEpochProcessor when a ParameterChangeAction is enacted at epoch boundary.
     * <p>
     * In Conway+, the flow is:
     * <ol>
     *   <li>ParameterChangeAction proposal submitted</li>
     *   <li>Voted on during voting window</li>
     *   <li>Ratified at epoch N boundary (thresholds met)</li>
     *   <li>Enacted at epoch N+1 boundary → this method is called with epoch=N+1</li>
     *   <li>New params take effect for epoch N+1 onwards</li>
     * </ol>
     *
     * @param epoch  The epoch at which the enacted params take effect
     * @param update The protocol parameter update from the enacted ParameterChangeAction
     */
    public void applyEnactedParamChange(int epoch, ProtocolParamUpdate update) {
        if (!enabled || update == null) return;
        // Directly update epochParams (not pendingUpdates) because finalizeEpoch(epoch)
        // has already run by the time governance enactment calls this method.
        // The governance epoch processing runs AFTER param finalization in EpochBoundaryProcessor.
        var prev = epochParams.get(epoch);
        if (prev != null) {
            epochParams.put(epoch, mergeUpdates(prev, update));
        } else {
            var prevEpoch = epoch > 0 ? epochParams.get(epoch - 1) : null;
            epochParams.put(epoch, prevEpoch != null ? mergeUpdates(prevEpoch, update) : update);
        }
        // Persist the updated params
        persistEpochParams(epoch, epochParams.get(epoch));
        log.info("Applied enacted governance param change for epoch {} (direct update)", epoch);
    }

    /**
     * Finalize parameters for an epoch. Called at epoch boundary.
     * Merges any pending updates into the resolved epoch params and persists to RocksDB.
     */
    public void finalizeEpoch(int epoch) {
        if (!enabled) return;
        var pending = pendingUpdates.remove(epoch);
        var prev = epoch > 0 ? epochParams.get(epoch - 1) : null;

        ProtocolParamUpdate resolved = null;
        if (pending != null) {
            // Merge new update on top of previous epoch's params so unchanged fields carry forward.
            if (prev != null) {
                resolved = mergeUpdates(prev, pending);
            } else {
                resolved = pending;
            }
            epochParams.put(epoch, resolved);
            log.info("Finalized protocol params for epoch {} from block updates", epoch);
        } else if (!epochParams.containsKey(epoch) && prev != null) {
            resolved = prev;
            epochParams.put(epoch, resolved);
        }

        // Persist to RocksDB
        if (resolved != null) {
            persistEpochParams(epoch, resolved);
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

    // --- Conway governance parameters ---

    @Override
    public int getGovActionLifetime(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getGovActionLifetime() != null) return update.getGovActionLifetime();
        return baseProvider.getGovActionLifetime(epoch);
    }

    @Override
    public int getDRepActivity(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getDrepActivity() != null) return update.getDrepActivity();
        return baseProvider.getDRepActivity(epoch);
    }

    @Override
    public BigInteger getGovActionDeposit(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getGovActionDeposit() != null) return update.getGovActionDeposit();
        return baseProvider.getGovActionDeposit(epoch);
    }

    @Override
    public BigInteger getDRepDeposit(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getDrepDeposit() != null) return update.getDrepDeposit();
        return baseProvider.getDRepDeposit(epoch);
    }

    @Override
    public int getCommitteeMinSize(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getCommitteeMinSize() != null) return update.getCommitteeMinSize();
        return baseProvider.getCommitteeMinSize(epoch);
    }

    @Override
    public int getCommitteeMaxTermLength(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getCommitteeMaxTermLength() != null) return update.getCommitteeMaxTermLength();
        return baseProvider.getCommitteeMaxTermLength(epoch);
    }

    // ===== RocksDB Persistence (dedicated epoch_params column family) =====

    /** Key: epoch as 4-byte big-endian int */
    private static byte[] epochKey(int epoch) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();
    }

    private void persistEpochParams(int epoch, ProtocolParamUpdate params) {
        if (db == null || cfEpochParams == null) return;
        try {
            byte[] val = JSON.writeValueAsBytes(params);
            db.put(cfEpochParams, epochKey(epoch), val);
        } catch (Exception e) {
            log.warn("Failed to persist epoch params for epoch {}: {}", epoch, e.getMessage());
        }
    }

    /**
     * Load all persisted epoch params from RocksDB into the in-memory map.
     * Called once at construction to restore state after restart.
     */
    private void loadPersistedParams() {
        int count = 0;
        try (RocksIterator it = db.newIterator(cfEpochParams)) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length != 4) { it.next(); continue; }

                int epoch = ByteBuffer.wrap(key).order(ByteOrder.BIG_ENDIAN).getInt();
                try {
                    ProtocolParamUpdate params = JSON.readValue(it.value(), ProtocolParamUpdate.class);
                    epochParams.put(epoch, params);
                    count++;
                } catch (Exception e) {
                    log.warn("Failed to deserialize epoch params for epoch {}: {}", epoch, e.getMessage());
                }
                it.next();
            }
        }
        if (count > 0) {
            int minEpoch = epochParams.keySet().stream().mapToInt(Integer::intValue).min().orElse(-1);
            int maxEpoch = epochParams.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
            log.info("Loaded {} persisted epoch params from RocksDB (epochs {}-{})", count, minEpoch, maxEpoch);
        }
    }

    /**
     * Merge two ProtocolParamUpdate instances. Non-null fields in {@code newer} override {@code older}.
     */
    private ProtocolParamUpdate mergeUpdates(ProtocolParamUpdate older, ProtocolParamUpdate newer) {
        return ProtocolParamUpdate.builder()
                // Pre-Conway fields
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
                .adaPerUtxoByte(newer.getAdaPerUtxoByte() != null ? newer.getAdaPerUtxoByte() : older.getAdaPerUtxoByte())
                // Conway governance fields
                .govActionLifetime(newer.getGovActionLifetime() != null ? newer.getGovActionLifetime() : older.getGovActionLifetime())
                .govActionDeposit(newer.getGovActionDeposit() != null ? newer.getGovActionDeposit() : older.getGovActionDeposit())
                .drepDeposit(newer.getDrepDeposit() != null ? newer.getDrepDeposit() : older.getDrepDeposit())
                .drepActivity(newer.getDrepActivity() != null ? newer.getDrepActivity() : older.getDrepActivity())
                .committeeMinSize(newer.getCommitteeMinSize() != null ? newer.getCommitteeMinSize() : older.getCommitteeMinSize())
                .committeeMaxTermLength(newer.getCommitteeMaxTermLength() != null ? newer.getCommitteeMaxTermLength() : older.getCommitteeMaxTermLength())
                .poolVotingThresholds(newer.getPoolVotingThresholds() != null ? newer.getPoolVotingThresholds() : older.getPoolVotingThresholds())
                .drepVotingThresholds(newer.getDrepVotingThresholds() != null ? newer.getDrepVotingThresholds() : older.getDrepVotingThresholds())
                .build();
    }
}
