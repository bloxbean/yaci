package com.bloxbean.cardano.yaci.node.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.*;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.model.governance.DrepType;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.api.EpochParamProvider;
import com.bloxbean.cardano.yaci.node.api.account.AccountStateStore;
import com.bloxbean.cardano.yaci.node.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yaci.node.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.api.events.RollbackEvent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import org.rocksdb.*;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * RocksDB-backed account state store.
 * Tracks stake registration, delegation, DRep delegation, pool registration/retirement,
 * and reward balances with delta-journal rollback support.
 */
public class DefaultAccountStateStore implements AccountStateStore {

    // Key prefixes
    public static final byte PREFIX_ACCT = 0x01;
    public static final byte PREFIX_POOL_DELEG = 0x02;
    public static final byte PREFIX_DREP_DELEG = 0x03;
    static final byte PREFIX_POOL_DEPOSIT = 0x10;
    static final byte PREFIX_POOL_RETIRE = 0x11;
    static final byte PREFIX_DREP_REG = 0x20;
    static final byte PREFIX_COMMITTEE_HOT = 0x30;
    static final byte PREFIX_COMMITTEE_RESIGN = 0x31;
    static final byte PREFIX_MIR_REWARD = 0x40;

    static final byte PREFIX_POOL_PARAMS_HIST = 0x12; // pool params history: poolHash + epoch → PoolRegistrationData
    static final byte PREFIX_POOL_REG_SLOT = 0x13;   // pool registration slot: poolHash → slot (long BE)
    static final byte PREFIX_ACCT_REG_SLOT = 0x14;   // stake account registration slot: credType + credHash → slot (long BE)

    // Epoch-scoped prefixes for reward calculation
    static final byte PREFIX_POOL_BLOCK_COUNT = 0x50;
    static final byte PREFIX_EPOCH_FEES = 0x51;
    static final byte PREFIX_ADAPOT = 0x52;
    // 0x53 used by epoch_params CF
    static final byte PREFIX_ACCUMULATED_REWARD = 0x54;
    static final byte PREFIX_STAKE_EVENT = 0x55;
    public static final byte PREFIX_REWARD_REST = 0x56;

    // Reward rest type constants
    public static final byte REWARD_REST_PROPOSAL_REFUND = 0;
    public static final byte REWARD_REST_TREASURY_WITHDRAWAL = 1;
    public static final byte REWARD_REST_POOL_REFUND = 2;
    public static final byte REWARD_REST_MIR = 3;

    // MIR pot transfer metadata keys
    private static final byte[] META_MIR_TO_RESERVES = "mir.to_reserves".getBytes(StandardCharsets.UTF_8);
    private static final byte[] META_MIR_TO_TREASURY = "mir.to_treasury".getBytes(StandardCharsets.UTF_8);

    // Metadata keys
    private static final byte[] META_TOTAL_DEPOSITED = "total_dep".getBytes(StandardCharsets.UTF_8);
    private static final byte[] META_LAST_APPLIED_BLOCK = "meta.last_block".getBytes(StandardCharsets.UTF_8);
    private static final byte[] META_LAST_SNAPSHOT_EPOCH = "meta.last_snapshot_epoch".getBytes(StandardCharsets.UTF_8);
    // Retain snapshots for enough epochs so the background epoch boundary processor can read them.
    // During fast sync, the main thread creates snapshots and prunes old ones rapidly while
    // the background thread processes epoch boundaries sequentially. With a queue depth of N,
    // we need at least N + 3 (snapshotKey = epoch - 3) epochs of retention.
    // 50 epochs covers even the largest fast-sync queue gaps (~20MB storage on preprod).
    private static final int SNAPSHOT_RETENTION_EPOCHS = 50;

    // Delta op types
    public static final byte OP_PUT = 0x01;
    public static final byte OP_DELETE = 0x02;

    private final Logger log;
    private final boolean enabled;
    private final EpochParamProvider epochParamProvider;

    private RocksDB db;
    private ColumnFamilyHandle cfState;
    private ColumnFamilyHandle cfDelta;
    private ColumnFamilyHandle cfEpochSnapshot;

    // Optional epoch reward subsystems (null = disabled)
    private volatile EpochStakeSnapshotService stakeSnapshotService;
    private volatile AdaPotTracker adaPotTracker;
    private volatile EpochRewardCalculator rewardCalculator;
    private volatile EpochParamTracker paramTracker;
    private volatile EpochBoundaryProcessor epochBoundaryProcessor;
    private volatile com.bloxbean.cardano.yaci.node.api.utxo.UtxoState utxoState;
    private volatile long networkMagic;
    private final PointerAddressResolver pointerAddressResolver = new PointerAddressResolver();

    // Optional epoch snapshot exporter for debugging (NOOP when disabled)
    private com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter snapshotExporter =
            com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter.NOOP;

    // Optional governance subsystem
    private volatile com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceBlockProcessor governanceBlockProcessor;


    // Supplier for re-initialization after snapshot restore
    private final CfSupplier cfSupplier;

    @FunctionalInterface
    public interface CfSupplier {
        ColumnFamilyHandle handle(String name);

        default RocksDB db() { return null; }
    }

    private static final EpochParamProvider ZERO_PROVIDER = new EpochParamProvider() {
        @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.ZERO; }
        @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.ZERO; }
    };

    public DefaultAccountStateStore(RocksDB db, CfSupplier supplier, Logger log, boolean enabled) {
        this(db, supplier, log, enabled, ZERO_PROVIDER);
    }

    public DefaultAccountStateStore(RocksDB db, CfSupplier supplier, Logger log, boolean enabled,
                                    EpochParamProvider epochParamProvider) {
        this.db = db;
        this.cfSupplier = supplier;
        this.log = log;
        this.enabled = enabled;
        this.epochParamProvider = epochParamProvider != null ? epochParamProvider : ZERO_PROVIDER;
        this.cfState = supplier.handle(AccountStateCfNames.ACCT_STATE);
        this.cfDelta = supplier.handle(AccountStateCfNames.ACCT_DELTA);
        this.cfEpochSnapshot = supplier.handle(AccountStateCfNames.EPOCH_DELEG_SNAPSHOT);
    }

    // --- Optional subsystem wiring ---

    /**
     * Set the UtxoState reference for UTXO balance aggregation at epoch boundary.
     * Must be called before epoch snapshots with amounts are needed.
     */
    public void setUtxoState(com.bloxbean.cardano.yaci.node.api.utxo.UtxoState utxoState) {
        this.utxoState = utxoState;
    }

    /**
     * Set the epoch stake snapshot service for UTXO balance aggregation.
     */
    public void setStakeSnapshotService(EpochStakeSnapshotService service) {
        this.stakeSnapshotService = service;
    }

    /**
     * Set the AdaPot tracker for treasury/reserves tracking.
     */
    public void setAdaPotTracker(AdaPotTracker tracker) {
        this.adaPotTracker = tracker;
    }

    /**
     * Set the reward calculator for epoch reward distribution.
     */
    public void setRewardCalculator(EpochRewardCalculator calculator) {
        this.rewardCalculator = calculator;
    }

    /**
     * Get the AdaPot tracker (for external use, e.g., bootstrapping).
     */
    public AdaPotTracker getAdaPotTracker() {
        return adaPotTracker;
    }

    /**
     * Get the reward calculator (for external querying).
     */
    public EpochRewardCalculator getRewardCalculator() {
        return rewardCalculator;
    }

    /**
     * Set the protocol parameter tracker.
     */
    public void setParamTracker(EpochParamTracker tracker) {
        this.paramTracker = tracker;
    }

    public EpochParamTracker getParamTracker() {
        return paramTracker;
    }

    /**
     * Set the epoch boundary processor for coordinating epoch transitions.
     */
    public void setEpochBoundaryProcessor(EpochBoundaryProcessor processor) {
        this.epochBoundaryProcessor = processor;
    }

    public void setSnapshotExporter(com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter exporter) {
        this.snapshotExporter = exporter != null ? exporter
                : com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter.NOOP;
    }

    /**
     * Set the network magic (needed for reward calculation).
     */
    public void setNetworkMagic(long networkMagic) {
        this.networkMagic = networkMagic;
    }

    public long getNetworkMagic() {
        return networkMagic;
    }

    public EpochParamProvider getEpochParamProvider() {
        return epochParamProvider;
    }

    /**
     * Set the governance block processor for Conway-era governance state tracking.
     */
    public void setGovernanceBlockProcessor(
            com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceBlockProcessor processor) {
        this.governanceBlockProcessor = processor;
    }

    public com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceBlockProcessor getGovernanceBlockProcessor() {
        return governanceBlockProcessor;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void reinitialize() {
        this.cfState = cfSupplier.handle(AccountStateCfNames.ACCT_STATE);
        this.cfDelta = cfSupplier.handle(AccountStateCfNames.ACCT_DELTA);
        this.cfEpochSnapshot = cfSupplier.handle(AccountStateCfNames.EPOCH_DELEG_SNAPSHOT);
        if (cfSupplier.db() != null) {
            this.db = cfSupplier.db();
        }
        log.info("DefaultAccountStateStore reinitialized after snapshot restore");
    }

    /**
     * One-time migration: populate PREFIX_ACCT_REG_SLOT from existing PREFIX_STAKE_EVENT entries.
     * This enables stale delegation detection in snapshots for credentials that were registered
     * before this feature was added. Idempotent — safe to run on every startup.
     */
    public void migrateAcctRegSlots() {
        if (!enabled) return;
        int written = 0;
        // Track latest registration slot per credential from stake events
        java.util.Map<String, long[]> latestRegSlots = new java.util.HashMap<>(); // credKey → [slot]
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_STAKE_EVENT});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 14 || key[0] != PREFIX_STAKE_EVENT) break;
                int eventType = AccountStateCborCodec.decodeStakeEvent(it.value());
                if (eventType == AccountStateCborCodec.EVENT_REGISTRATION) {
                    long evSlot = ByteBuffer.wrap(key, 1, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                    int evCredType = key[13] & 0xFF;
                    String evCredHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 14, key.length));
                    String credKey = evCredType + ":" + evCredHash;
                    long[] existing = latestRegSlots.get(credKey);
                    if (existing == null || evSlot > existing[0]) {
                        latestRegSlots.put(credKey, new long[]{evSlot});
                    }
                }
                it.next();
            }
        }
        if (latestRegSlots.isEmpty()) return;

        // Write PREFIX_ACCT_REG_SLOT for each, only if not already set or if newer
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            for (var entry : latestRegSlots.entrySet()) {
                String credKey = entry.getKey();
                long regSlot = entry.getValue()[0];
                int colonIdx = credKey.indexOf(':');
                int credType = Integer.parseInt(credKey.substring(0, colonIdx));
                String credHash = credKey.substring(colonIdx + 1);
                byte[] slotKey = acctRegSlotKey(credType, credHash);
                byte[] existing = db.get(cfState, slotKey);
                if (existing != null) {
                    long existingSlot = ByteBuffer.wrap(existing).order(ByteOrder.BIG_ENDIAN).getLong();
                    if (existingSlot >= regSlot) continue; // Already up to date
                }
                batch.put(cfState, slotKey, ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(regSlot).array());
                written++;
            }
            if (written > 0) {
                db.write(wo, batch);
                log.info("Migrated {} PREFIX_ACCT_REG_SLOT entries from stake events", written);
            }
        } catch (Exception e) {
            log.error("migrateAcctRegSlots failed: {}", e.toString());
        }
    }

    // --- Key builders ---

    static byte[] accountKey(int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_ACCT;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    static byte[] poolDelegKey(int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_POOL_DELEG;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    static byte[] drepDelegKey(int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_DREP_DELEG;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    static byte[] poolDepositKey(String poolHash) {
        byte[] hash = HexUtil.decodeHexString(poolHash);
        byte[] key = new byte[1 + hash.length];
        key[0] = PREFIX_POOL_DEPOSIT;
        System.arraycopy(hash, 0, key, 1, hash.length);
        return key;
    }

    static byte[] poolRegSlotKey(String poolHash) {
        byte[] hash = HexUtil.decodeHexString(poolHash);
        byte[] key = new byte[1 + hash.length];
        key[0] = PREFIX_POOL_REG_SLOT;
        System.arraycopy(hash, 0, key, 1, hash.length);
        return key;
    }

    static byte[] poolRetireKey(String poolHash) {
        byte[] hash = HexUtil.decodeHexString(poolHash);
        byte[] key = new byte[1 + hash.length];
        key[0] = PREFIX_POOL_RETIRE;
        System.arraycopy(hash, 0, key, 1, hash.length);
        return key;
    }

    /**
     * Key for pool params history: PREFIX_POOL_PARAMS_HIST + poolHash(28) + epoch(4 BE).
     * Ordered so that seekForPrev can find the latest entry for a pool with epoch <= target.
     */
    static byte[] poolParamsHistKey(String poolHash, int epoch) {
        byte[] hash = HexUtil.decodeHexString(poolHash);
        byte[] key = new byte[1 + hash.length + 4];
        key[0] = PREFIX_POOL_PARAMS_HIST;
        System.arraycopy(hash, 0, key, 1, hash.length);
        ByteBuffer.wrap(key, 1 + hash.length, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
        return key;
    }

    static byte[] drepRegKey(int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_DREP_REG;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    static byte[] committeeHotKey(int credType, String coldHash) {
        byte[] hash = HexUtil.decodeHexString(coldHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_COMMITTEE_HOT;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    static byte[] committeeResignKey(int credType, String coldHash) {
        byte[] hash = HexUtil.decodeHexString(coldHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_COMMITTEE_RESIGN;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    static byte[] mirRewardKey(int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_MIR_REWARD;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    // --- Key builders for epoch-scoped data ---

    static byte[] poolBlockCountKey(int epoch, String poolHash) {
        byte[] hash = HexUtil.decodeHexString(poolHash);
        byte[] key = new byte[1 + 4 + hash.length];
        key[0] = PREFIX_POOL_BLOCK_COUNT;
        ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
        System.arraycopy(hash, 0, key, 5, hash.length);
        return key;
    }

    static byte[] epochFeesKey(int epoch) {
        byte[] key = new byte[1 + 4];
        key[0] = PREFIX_EPOCH_FEES;
        ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
        return key;
    }

    static byte[] adaPotKey(int epoch) {
        byte[] key = new byte[1 + 4];
        key[0] = PREFIX_ADAPOT;
        ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
        return key;
    }

    static byte[] accumulatedRewardKey(int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_ACCUMULATED_REWARD;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    /**
     * Build stake event key: [0x55][slot(8 BE)][txIdx(2 BE)][certIdx(2 BE)][credType(1)][credHash(28)]
     * Slot-first ordering enables efficient range scans for "all events in slot range".
     */
    static byte[] stakeEventKey(long slot, int txIdx, int certIdx, int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        // 1 prefix + 8 slot + 2 txIdx + 2 certIdx + 1 credType + 28 hash = 42
        byte[] key = new byte[1 + 8 + 2 + 2 + 1 + hash.length];
        key[0] = PREFIX_STAKE_EVENT;
        ByteBuffer.wrap(key, 1, 8).order(ByteOrder.BIG_ENDIAN).putLong(slot);
        ByteBuffer.wrap(key, 9, 2).order(ByteOrder.BIG_ENDIAN).putShort((short) txIdx);
        ByteBuffer.wrap(key, 11, 2).order(ByteOrder.BIG_ENDIAN).putShort((short) certIdx);
        key[13] = (byte) credType;
        System.arraycopy(hash, 0, key, 14, hash.length);
        return key;
    }

    private static int credTypeFromModel(com.bloxbean.cardano.yaci.core.model.Credential cred) {
        return cred.getType() == StakeCredType.ADDR_KEYHASH ? 0 : 1;
    }

    private static int credTypeInt(StakeCredType t) {
        return t == StakeCredType.ADDR_KEYHASH ? 0 : 1;
    }

    private static int drepTypeInt(DrepType t) {
        return switch (t) {
            case ADDR_KEYHASH -> 0;
            case SCRIPTHASH -> 1;
            case ABSTAIN -> 2;
            case NO_CONFIDENCE -> 3;
        };
    }

    private int epochForSlot(long slot) {
        long epochLength = epochParamProvider.getEpochLength();
        long shelleyStart = epochParamProvider.getShelleyStartSlot();
        if (shelleyStart <= 0) return (int) (slot / epochLength);
        long byronEpochLen = epochParamProvider.getByronSlotsPerEpoch();
        long shelleyStartEpoch = shelleyStart / byronEpochLen;
        return (int) (shelleyStartEpoch + (slot - shelleyStart) / epochLength);
    }

    public long slotForEpochStart(int epoch) {
        long epochLength = epochParamProvider.getEpochLength();
        long shelleyStart = epochParamProvider.getShelleyStartSlot();
        if (shelleyStart <= 0) return (long) epoch * epochLength;
        long byronEpochLen = epochParamProvider.getByronSlotsPerEpoch();
        long shelleyStartEpoch = shelleyStart / byronEpochLen;
        if (epoch <= shelleyStartEpoch) return (long) epoch * byronEpochLen;
        return shelleyStart + (epoch - shelleyStartEpoch) * epochLength;
    }

    private int getLastSnapshotEpoch() {
        try {
            byte[] val = db.get(cfState, META_LAST_SNAPSHOT_EPOCH);
            if (val != null && val.length == 4) {
                return java.nio.ByteBuffer.wrap(val).order(java.nio.ByteOrder.BIG_ENDIAN).getInt();
            }
        } catch (RocksDBException ignored) {}
        return -1;
    }

    // --- LedgerStateProvider reads ---

    @Override
    public Optional<BigInteger> getRewardBalance(int credType, String credentialHash) {
        try {
            byte[] val = db.get(cfState, accountKey(credType, credentialHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeStakeAccount(val).reward());
        } catch (RocksDBException e) {
            log.error("getRewardBalance failed: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Optional<BigInteger> getStakeDeposit(int credType, String credentialHash) {
        try {
            byte[] val = db.get(cfState, accountKey(credType, credentialHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeStakeAccount(val).deposit());
        } catch (RocksDBException e) {
            log.error("getStakeDeposit failed: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> getDelegatedPool(int credType, String credentialHash) {
        try {
            byte[] val = db.get(cfState, poolDelegKey(credType, credentialHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodePoolDelegation(val).poolHash());
        } catch (RocksDBException e) {
            log.error("getDelegatedPool failed: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Optional<LedgerStateProvider.DRepDelegation> getDRepDelegation(int credType, String credentialHash) {
        try {
            byte[] val = db.get(cfState, drepDelegKey(credType, credentialHash));
            if (val == null) return Optional.empty();
            var rec = AccountStateCborCodec.decodeDRepDelegation(val);
            return Optional.of(new DRepDelegation(rec.drepType(), rec.drepHash()));
        } catch (RocksDBException e) {
            log.error("getDRepDelegation failed: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public boolean isStakeCredentialRegistered(int credType, String credentialHash) {
        try {
            byte[] val = db.get(cfState, accountKey(credType, credentialHash));
            return val != null;
        } catch (RocksDBException e) {
            log.error("isStakeCredentialRegistered failed: {}", e.toString());
            return false;
        }
    }

    @Override
    public BigInteger getTotalDeposited() {
        try {
            byte[] val = db.get(cfState, META_TOTAL_DEPOSITED);
            if (val == null || val.length < 8) return BigInteger.ZERO;
            return new BigInteger(1, val);
        } catch (RocksDBException e) {
            log.error("getTotalDeposited failed: {}", e.toString());
            return BigInteger.ZERO;
        }
    }

    @Override
    public boolean isPoolRegistered(String poolHash) {
        try {
            byte[] val = db.get(cfState, poolDepositKey(poolHash));
            return val != null;
        } catch (RocksDBException e) {
            log.error("isPoolRegistered failed: {}", e.toString());
            return false;
        }
    }

    @Override
    public Optional<BigInteger> getPoolDeposit(String poolHash) {
        try {
            byte[] val = db.get(cfState, poolDepositKey(poolHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodePoolDeposit(val));
        } catch (RocksDBException e) {
            log.error("getPoolDeposit failed: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * Get full pool registration data (deposit + params) for a pool.
     */
    public Optional<AccountStateCborCodec.PoolRegistrationData> getPoolRegistrationData(String poolHash) {
        try {
            byte[] val = db.get(cfState, poolDepositKey(poolHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodePoolRegistration(val));
        } catch (RocksDBException e) {
            log.error("getPoolRegistrationData failed: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Optional<LedgerStateProvider.PoolParams> getPoolParams(String poolHash) {
        return getPoolRegistrationData(poolHash).map(data -> {
            double margin = UnitIntervalUtil.safeRatio(data.marginNum(), data.marginDen()).doubleValue();
            return new LedgerStateProvider.PoolParams(
                    data.deposit(), margin, data.cost(), data.pledge(),
                    data.rewardAccount(), data.owners());
        });
    }

    @Override
    public Optional<LedgerStateProvider.PoolParams> getPoolParams(String poolHash, int epoch) {
        return getPoolRegistrationDataAtEpoch(poolHash, epoch).map(data -> {
            double margin = UnitIntervalUtil.safeRatio(data.marginNum(), data.marginDen()).doubleValue();
            return new LedgerStateProvider.PoolParams(
                    data.deposit(), margin, data.cost(), data.pledge(),
                    data.rewardAccount(), data.owners());
        });
    }

    /**
     * Get pool registration data that was active at the given epoch.
     * Uses seekForPrev to find the latest history entry with epoch <= target.
     * Falls back to the current (latest) registration if no history found.
     */
    public Optional<AccountStateCborCodec.PoolRegistrationData> getPoolRegistrationDataAtEpoch(
            String poolHash, int epoch) {
        try (RocksIterator it = db.newIterator(cfState)) {
            byte[] seekKey = poolParamsHistKey(poolHash, epoch);
            it.seekForPrev(seekKey);

            if (it.isValid()) {
                byte[] foundKey = it.key();
                // Verify the key matches: prefix + same poolHash
                byte[] poolHashBytes = HexUtil.decodeHexString(poolHash);
                if (foundKey.length == 1 + poolHashBytes.length + 4
                        && foundKey[0] == PREFIX_POOL_PARAMS_HIST
                        && Arrays.equals(poolHashBytes, 0, poolHashBytes.length,
                                foundKey, 1, 1 + poolHashBytes.length)) {
                    return Optional.of(AccountStateCborCodec.decodePoolRegistration(it.value()));
                }
            }
        }
        // Fall back to current params if no history entry found (pre-existing pools before history tracking)
        return getPoolRegistrationData(poolHash);
    }

    @Override
    public Optional<Long> getPoolRetirementEpoch(String poolHash) {
        try {
            byte[] val = db.get(cfState, poolRetireKey(poolHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodePoolRetirement(val));
        } catch (RocksDBException e) {
            log.error("getPoolRetirementEpoch failed: {}", e.toString());
            return Optional.empty();
        }
    }

    // --- DRep State reads ---

    @Override
    public boolean isDRepRegistered(int credType, String credentialHash) {
        try {
            byte[] val = db.get(cfState, drepRegKey(credType, credentialHash));
            return val != null;
        } catch (RocksDBException e) {
            log.error("isDRepRegistered failed: {}", e.toString());
            return false;
        }
    }

    @Override
    public Optional<BigInteger> getDRepDeposit(int credType, String credentialHash) {
        try {
            byte[] val = db.get(cfState, drepRegKey(credType, credentialHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeDRepDeposit(val));
        } catch (RocksDBException e) {
            log.error("getDRepDeposit failed: {}", e.toString());
            return Optional.empty();
        }
    }

    // --- Committee State reads ---

    @Override
    public boolean isCommitteeMember(int credType, String coldCredentialHash) {
        try {
            byte[] val = db.get(cfState, committeeHotKey(credType, coldCredentialHash));
            return val != null;
        } catch (RocksDBException e) {
            log.error("isCommitteeMember failed: {}", e.toString());
            return false;
        }
    }

    @Override
    public Optional<String> getCommitteeHotCredential(int credType, String coldCredentialHash) {
        try {
            byte[] val = db.get(cfState, committeeHotKey(credType, coldCredentialHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeCommitteeHotKey(val).hotHash());
        } catch (RocksDBException e) {
            log.error("getCommitteeHotCredential failed: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public boolean hasCommitteeMemberResigned(int credType, String coldCredentialHash) {
        try {
            byte[] val = db.get(cfState, committeeResignKey(credType, coldCredentialHash));
            return val != null;
        } catch (RocksDBException e) {
            log.error("hasCommitteeMemberResigned failed: {}", e.toString());
            return false;
        }
    }

    // --- MIR State reads ---

    @Override
    public Optional<BigInteger> getInstantReward(int credType, String credentialHash) {
        try {
            byte[] val = db.get(cfState, mirRewardKey(credType, credentialHash));
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeMirReward(val));
        } catch (RocksDBException e) {
            log.error("getInstantReward failed: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public BigInteger getMirPotTransfer(boolean toReserves) {
        try {
            byte[] key = toReserves ? META_MIR_TO_RESERVES : META_MIR_TO_TREASURY;
            byte[] val = db.get(cfState, key);
            if (val == null || val.length < 8) return BigInteger.ZERO;
            return new BigInteger(1, val);
        } catch (RocksDBException e) {
            log.error("getMirPotTransfer failed: {}", e.toString());
            return BigInteger.ZERO;
        }
    }

    // --- Epoch Block Count and Fee queries ---

    @Override
    public long getPoolBlockCount(int epoch, String poolHash) {
        try {
            byte[] key = poolBlockCountKey(epoch, poolHash);
            byte[] val = db.get(cfState, key);
            if (val == null) return 0;
            return AccountStateCborCodec.decodePoolBlockCount(val);
        } catch (RocksDBException e) {
            log.error("getPoolBlockCount failed: {}", e.toString());
            return 0;
        }
    }

    @Override
    public Map<String, Long> getPoolBlockCounts(int epoch) {
        Map<String, Long> counts = new HashMap<>();
        byte[] epochBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();
        byte[] seekKey = new byte[5];
        seekKey[0] = PREFIX_POOL_BLOCK_COUNT;
        System.arraycopy(epochBytes, 0, seekKey, 1, 4);

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 5 || key[0] != PREFIX_POOL_BLOCK_COUNT) break;
                int keyEpoch = ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (keyEpoch != epoch) break;
                String poolHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 5, key.length));
                long count = AccountStateCborCodec.decodePoolBlockCount(it.value());
                counts.put(poolHash, count);
                it.next();
            }
        }
        return counts;
    }

    @Override
    public BigInteger getEpochFees(int epoch) {
        try {
            byte[] key = epochFeesKey(epoch);
            byte[] val = db.get(cfState, key);
            if (val == null) return BigInteger.ZERO;
            return AccountStateCborCodec.decodeEpochFees(val);
        } catch (RocksDBException e) {
            log.error("getEpochFees failed: {}", e.toString());
            return BigInteger.ZERO;
        }
    }

    // --- Retired pool queries ---

    @Override
    public List<LedgerStateProvider.RetiringPool> getPoolsRetiringAtEpoch(int retireEpoch) {
        List<LedgerStateProvider.RetiringPool> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_POOL_RETIRE});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_POOL_RETIRE) break;

                long epoch = AccountStateCborCodec.decodePoolRetirement(it.value());
                if (epoch == retireEpoch) {
                    String poolHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 1, key.length));
                    BigInteger deposit = getPoolDeposit(poolHash).orElse(BigInteger.ZERO);
                    result.add(new LedgerStateProvider.RetiringPool(poolHash, deposit, epoch));
                }
                it.next();
            }
        }
        return result;
    }

    // --- Registered credential queries ---

    @Override
    public java.util.Set<String> getAllRegisteredCredentials() {
        java.util.Set<String> credentials = new java.util.HashSet<>();
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_ACCT});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_ACCT) break;
                int credType = key[1] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 2, key.length));
                credentials.add(credType + ":" + credHash);
                it.next();
            }
        }
        return credentials;
    }

    // --- AdaPot queries ---

    @Override
    public Optional<BigInteger> getTreasury(int epoch) {
        try {
            byte[] key = adaPotKey(epoch);
            byte[] val = db.get(cfState, key);
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeAdaPot(val).treasury());
        } catch (RocksDBException e) {
            log.error("getTreasury failed: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Optional<BigInteger> getReserves(int epoch) {
        try {
            byte[] key = adaPotKey(epoch);
            byte[] val = db.get(cfState, key);
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeAdaPot(val).reserves());
        } catch (RocksDBException e) {
            log.error("getReserves failed: {}", e.toString());
            return Optional.empty();
        }
    }

    // --- Epoch Delegation Snapshot queries ---

    @Override
    public Optional<String> getEpochDelegation(int epoch, int credType, String credentialHash) {
        try {
            byte[] epochBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();
            byte[] hash = HexUtil.decodeHexString(credentialHash);
            byte[] key = new byte[4 + 1 + hash.length];
            System.arraycopy(epochBytes, 0, key, 0, 4);
            key[4] = (byte) credType;
            System.arraycopy(hash, 0, key, 5, hash.length);

            byte[] val = db.get(cfEpochSnapshot, key);
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeEpochDelegSnapshot(val).poolHash());
        } catch (RocksDBException e) {
            log.error("getEpochDelegation failed: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public List<LedgerStateProvider.EpochDelegator> getPoolDelegatorsAtEpoch(int epoch, String poolHash) {
        List<LedgerStateProvider.EpochDelegator> result = new ArrayList<>();
        try {
            byte[] epochPrefix = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();
            try (RocksIterator it = db.newIterator(cfEpochSnapshot)) {
                it.seek(epochPrefix);
                while (it.isValid()) {
                    byte[] key = it.key();
                    if (key.length < 5) break;
                    int keyEpoch = ByteBuffer.wrap(key, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                    if (keyEpoch != epoch) break;

                    var snapshot = AccountStateCborCodec.decodeEpochDelegSnapshot(it.value());
                    if (snapshot.poolHash().equals(poolHash)) {
                        int credType = key[4] & 0xFF;
                        String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 5, key.length));
                        result.add(new LedgerStateProvider.EpochDelegator(credType, credHash));
                    }
                    it.next();
                }
            }
        } catch (Exception e) {
            log.error("getPoolDelegatorsAtEpoch failed: {}", e.toString());
        }
        return result;
    }

    @Override
    public int getLatestSnapshotEpoch() {
        return getLastSnapshotEpoch();
    }

    // --- Reward Rest (deferred rewards: proposal refunds, treasury withdrawals, etc.) ---

    /**
     * Key: PREFIX_REWARD_REST(1) + spendable_epoch(4 BE) + type(1) + credType(1) + credHash(28) = 35 bytes
     */
    static byte[] rewardRestKey(int spendableEpoch, byte type, int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 4 + 1 + 1 + hash.length];
        key[0] = PREFIX_REWARD_REST;
        ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(spendableEpoch);
        key[5] = type;
        key[6] = (byte) credType;
        System.arraycopy(hash, 0, key, 7, hash.length);
        return key;
    }

    /**
     * Store a reward_rest entry (deferred reward: proposal refund, treasury withdrawal, etc.).
     * The reward becomes part of stake at the spendable_epoch snapshot.
     *
     * @param spendableEpoch Epoch when the reward becomes spendable and counts toward stake
     * @param type           Reward type (REWARD_REST_PROPOSAL_REFUND, etc.)
     * @param rewardAccountHex Reward account hex (header + credential hash)
     * @param amount         Amount in lovelace
     * @param earnedEpoch    Epoch when the reward was earned
     * @param slot           Slot of the event that triggered the reward
     * @param batch          WriteBatch for atomic writes
     * @param deltaOps       Delta ops for rollback
     * @return true if stored successfully (account address is valid)
     */
    public boolean storeRewardRest(int spendableEpoch, byte type, String rewardAccountHex,
                                   BigInteger amount, int earnedEpoch, long slot,
                                   org.rocksdb.WriteBatch batch,
                                   java.util.List<DeltaOp> deltaOps) throws RocksDBException {
        if (rewardAccountHex == null || rewardAccountHex.length() < 58 || amount.signum() <= 0) {
            return false;
        }
        int headerByte;
        try {
            headerByte = Integer.parseInt(rewardAccountHex.substring(0, 2), 16);
        } catch (NumberFormatException e) {
            return false;
        }
        int credType = ((headerByte & 0x10) != 0) ? 1 : 0;
        String credHash = rewardAccountHex.substring(2, 58);

        // Check if the stake credential is registered. Per Haskell spec,
        // deposits for deregistered addresses go to treasury (return false).
        // Use the account key (PREFIX_ACCT) which is deleted on deregistration.
        byte[] acctKey = accountKey(credType, credHash);
        byte[] acctVal = db.get(cfState, acctKey);
        if (acctVal == null) {
            return false; // Not registered → deposit goes to treasury
        }

        byte[] key = rewardRestKey(spendableEpoch, type, credType, credHash);
        byte[] prev = db.get(cfState, key);

        // If entry already exists for same key in committed state, add amounts.
        // NOTE: Callers must pre-aggregate amounts per credential before calling this method,
        // because db.get() can't see uncommitted WriteBatch entries from the same batch.
        BigInteger existing = BigInteger.ZERO;
        if (prev != null) {
            existing = AccountStateCborCodec.decodeRewardRest(prev).amount();
        }
        BigInteger total = existing.add(amount);

        byte[] val = AccountStateCborCodec.encodeRewardRest(total, earnedEpoch, slot);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
        return true;
    }

    /**
     * Get all spendable reward_rest amounts aggregated per credential for the given epoch.
     * Includes ALL types. Used during snapshot creation.
     *
     * @param epoch Include entries with spendable_epoch ≤ epoch
     * @return map from "credType:credHash" to total spendable reward_rest amount
     */
    public java.util.Map<String, BigInteger> getSpendableRewardRest(int epoch) {
        java.util.Map<String, BigInteger> result = new java.util.HashMap<>();
        byte[] seekKey = new byte[]{PREFIX_REWARD_REST};

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 7 || key[0] != PREFIX_REWARD_REST) break;

                int spendableEpoch = ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (spendableEpoch > epoch) break; // Keys sorted by epoch, no more spendable entries

                int credType = key[6] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 7, key.length));
                String credKey = credType + ":" + credHash;

                var rest = AccountStateCborCodec.decodeRewardRest(it.value());
                result.merge(credKey, rest.amount(), BigInteger::add);

                it.next();
            }
        } catch (Exception e) {
            log.error("getSpendableRewardRest failed: {}", e.toString());
        }
        return result;
    }

    /**
     * Credit all spendable reward_rest entries to PREFIX_ACCT.reward and remove them.
     * Called at epoch boundary BEFORE snapshot creation.
     * Entries with spendable_epoch ≤ epoch are credited to the account's reward balance.
     */
    private void creditAndRemoveSpendableRewardRest(int epoch, org.rocksdb.WriteBatch batch) {
        int credited = 0;
        BigInteger totalCredited = BigInteger.ZERO;
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_REWARD_REST});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 7 || key[0] != PREFIX_REWARD_REST) break;

                int spendableEpoch = ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (spendableEpoch > epoch) break;

                int credType = key[6] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 7, key.length));

                var rest = AccountStateCborCodec.decodeRewardRest(it.value());
                BigInteger amount = rest.amount();

                // Credit to PREFIX_ACCT.reward
                byte[] acctKey = accountKey(credType, credHash);
                byte[] acctVal = db.get(cfState, acctKey);
                if (acctVal != null && amount.signum() > 0) {
                    var acct = AccountStateCborCodec.decodeStakeAccount(acctVal);
                    BigInteger newReward = acct.reward().add(amount);
                    byte[] newVal = AccountStateCborCodec.encodeStakeAccount(newReward, acct.deposit());
                    batch.put(cfState, acctKey, newVal);
                    credited++;
                    totalCredited = totalCredited.add(amount);
                }

                // Remove the reward_rest entry
                batch.delete(cfState, key);

                it.next();
            }
        } catch (Exception e) {
            log.error("creditAndRemoveSpendableRewardRest failed: {}", e.toString());
        }
        if (credited > 0) {
            log.info("Credited {} spendable reward_rest entries for epoch {}: total={}",
                    credited, epoch, totalCredited);
        }
    }

    // --- Governance support: RewardCreditor and PoolStakeResolver ---

    /**
     * Credit a reward amount to the account identified by reward account hex.
     * Used by GovernanceEpochProcessor for proposal deposit refunds and treasury withdrawals.
     *
     * @param rewardAccountHex Reward account in hex (header byte + 28-byte credential hash)
     * @param amount           Amount to credit (lovelace)
     * @param batch            WriteBatch for atomic writes
     * @param deltaOps         Delta ops for rollback
     * @return true if credited to a registered account, false if account not registered (unclaimed)
     */
    public boolean creditRewardAccount(String rewardAccountHex, BigInteger amount,
                                       org.rocksdb.WriteBatch batch,
                                       java.util.List<DeltaOp> deltaOps) throws RocksDBException {
        if (rewardAccountHex == null || rewardAccountHex.length() < 58 || amount.signum() <= 0) {
            return false;
        }

        // Parse reward account: header(1 byte) + credential_hash(28 bytes)
        int headerByte;
        try {
            headerByte = Integer.parseInt(rewardAccountHex.substring(0, 2), 16);
        } catch (NumberFormatException e) {
            return false;
        }
        int credType = ((headerByte & 0x10) != 0) ? 1 : 0; // bit 4: 0=key, 1=script
        String credHash = rewardAccountHex.substring(2, 58);

        byte[] key = accountKey(credType, credHash);
        byte[] prev = db.get(cfState, key);
        if (prev == null) {
            // Account not registered — unclaimed
            return false;
        }

        // Add amount to existing reward balance
        var acct = AccountStateCborCodec.decodeStakeAccount(prev);
        BigInteger newReward = acct.reward().add(amount);
        byte[] val = AccountStateCborCodec.encodeStakeAccount(newReward, acct.deposit());
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
        return true;
    }

    /**
     * Resolve pool stake distribution from epoch delegation snapshot.
     * Aggregates per-pool stakes from individual delegation entries.
     * Also builds pool→DRep delegation type map for SPO default votes.
     *
     * @param epoch Snapshot epoch number
     * @return PoolStakeData with pool stakes and DRep delegation mapping
     */
    public com.bloxbean.cardano.yaci.node.ledgerstate.governance.epoch.GovernanceEpochProcessor.PoolStakeData
    resolvePoolStakeForEpoch(int epoch) throws RocksDBException {
        java.util.Map<String, BigInteger> poolStakes = new java.util.HashMap<>();
        java.util.Map<String, Integer> poolDRepDelegation = new java.util.HashMap<>();

        // Iterate epoch delegation snapshot: epoch(4 BE) + credType(1) + credHash(28) → {poolHash, amount}
        byte[] epochPrefix = new byte[4];
        java.nio.ByteBuffer.wrap(epochPrefix).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(epoch);

        try (org.rocksdb.RocksIterator it = db.newIterator(cfEpochSnapshot)) {
            it.seek(epochPrefix);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 5) break;
                int keyEpoch = java.nio.ByteBuffer.wrap(key, 0, 4).order(java.nio.ByteOrder.BIG_ENDIAN).getInt();
                if (keyEpoch != epoch) break;

                var snapshot = AccountStateCborCodec.decodeEpochDelegSnapshot(it.value());
                String poolHash = snapshot.poolHash();
                BigInteger stakeAmount = snapshot.amount();

                // Aggregate per-pool stake
                poolStakes.merge(poolHash, stakeAmount, BigInteger::add);

                it.next();
            }
        }

        // Build pool → DRep delegation type map
        // For each pool, look up its reward account's DRep delegation
        for (String poolHash : poolStakes.keySet()) {
            try {
                byte[] poolDepositKey = poolDepositKey(poolHash);
                byte[] poolVal = db.get(cfState, poolDepositKey);
                if (poolVal != null) {
                    var poolData = AccountStateCborCodec.decodePoolRegistration(poolVal);
                    String rewardAccount = poolData.rewardAccount();
                    if (rewardAccount != null && rewardAccount.length() >= 58) {
                        // Extract credential from pool reward account
                        int hdr = Integer.parseInt(rewardAccount.substring(0, 2), 16);
                        int ct = ((hdr & 0x10) != 0) ? 1 : 0;
                        String ch = rewardAccount.substring(2, 58);
                        byte[] drepDelegKey = drepDelegKey(ct, ch);
                        byte[] drepVal = db.get(cfState, drepDelegKey);
                        if (drepVal != null) {
                            var deleg = AccountStateCborCodec.decodeDRepDelegation(drepVal);
                            poolDRepDelegation.put(poolHash, deleg.drepType());
                        }
                    }
                }
            } catch (Exception e) {
                // Skip this pool's DRep delegation lookup on error
            }
        }

        return new com.bloxbean.cardano.yaci.node.ledgerstate.governance.epoch.GovernanceEpochProcessor.PoolStakeData(
                poolStakes, poolDRepDelegation);
    }

    // --- Listing ---

    @Override
    public List<StakeRegistrationEntry> listStakeRegistrations(int page, int count) {
        if (page < 1) page = 1;
        if (count < 1) count = 1;
        int skip = (page - 1) * count;
        List<StakeRegistrationEntry> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_ACCT});
            int skipped = 0;
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_ACCT) break;
                if (skipped++ < skip) { it.next(); continue; }
                int credType = key[1] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 2, key.length));
                var acct = AccountStateCborCodec.decodeStakeAccount(it.value());
                result.add(new StakeRegistrationEntry(credType, credHash, acct.reward(), acct.deposit()));
                if (result.size() >= count) break;
                it.next();
            }
        } catch (Exception e) {
            log.error("listStakeRegistrations failed: {}", e.toString());
        }
        return result;
    }

    @Override
    public List<PoolDelegationEntry> listPoolDelegations(int page, int count) {
        if (page < 1) page = 1;
        if (count < 1) count = 1;
        int skip = (page - 1) * count;
        List<PoolDelegationEntry> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_POOL_DELEG});
            int skipped = 0;
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_POOL_DELEG) break;
                if (skipped++ < skip) { it.next(); continue; }
                int credType = key[1] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 2, key.length));
                var deleg = AccountStateCborCodec.decodePoolDelegation(it.value());
                result.add(new PoolDelegationEntry(credType, credHash, deleg.poolHash(), deleg.slot(), deleg.txIdx(), deleg.certIdx()));
                if (result.size() >= count) break;
                it.next();
            }
        } catch (Exception e) {
            log.error("listPoolDelegations failed: {}", e.toString());
        }
        return result;
    }

    @Override
    public List<DRepDelegationEntry> listDRepDelegations(int page, int count) {
        if (page < 1) page = 1;
        if (count < 1) count = 1;
        int skip = (page - 1) * count;
        List<DRepDelegationEntry> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_DREP_DELEG});
            int skipped = 0;
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_DREP_DELEG) break;
                if (skipped++ < skip) { it.next(); continue; }
                int credType = key[1] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 2, key.length));
                var drep = AccountStateCborCodec.decodeDRepDelegation(it.value());
                result.add(new DRepDelegationEntry(credType, credHash, drep.drepType(), drep.drepHash(), drep.slot(), drep.txIdx(), drep.certIdx()));
                if (result.size() >= count) break;
                it.next();
            }
        } catch (Exception e) {
            log.error("listDRepDelegations failed: {}", e.toString());
        }
        return result;
    }

    @Override
    public List<PoolEntry> listPools(int page, int count) {
        if (page < 1) page = 1;
        if (count < 1) count = 1;
        int skip = (page - 1) * count;
        List<PoolEntry> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_POOL_DEPOSIT});
            int skipped = 0;
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_POOL_DEPOSIT) break;
                if (skipped++ < skip) { it.next(); continue; }
                String poolHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 1, key.length));
                var deposit = AccountStateCborCodec.decodePoolDeposit(it.value());
                result.add(new PoolEntry(poolHash, deposit));
                if (result.size() >= count) break;
                it.next();
            }
        } catch (Exception e) {
            log.error("listPools failed: {}", e.toString());
        }
        return result;
    }

    @Override
    public List<PoolRetirementEntry> listPoolRetirements(int page, int count) {
        if (page < 1) page = 1;
        if (count < 1) count = 1;
        int skip = (page - 1) * count;
        List<PoolRetirementEntry> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_POOL_RETIRE});
            int skipped = 0;
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_POOL_RETIRE) break;
                if (skipped++ < skip) { it.next(); continue; }
                String poolHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 1, key.length));
                long retireEpoch = AccountStateCborCodec.decodePoolRetirement(it.value());
                result.add(new PoolRetirementEntry(poolHash, retireEpoch));
                if (result.size() >= count) break;
                it.next();
            }
        } catch (Exception e) {
            log.error("listPoolRetirements failed: {}", e.toString());
        }
        return result;
    }

    // --- Epoch transition (called BEFORE first block of new epoch) ---

    @Override
    public void handleEpochTransition(int previousEpoch, int newEpoch) {
        if (!enabled) return;
        // Process epoch boundary: rewards, adapot, protocol params, governance
        if (epochBoundaryProcessor != null) {
            try {
                epochBoundaryProcessor.processEpochBoundary(previousEpoch, newEpoch);
            } catch (Exception e) {
                log.warn("Epoch boundary processing failed for {} -> {}: {}",
                        previousEpoch, newEpoch, e.getMessage());
            }
        }
    }

    @Override
    public void handleEpochTransitionSnapshot(int previousEpoch, int newEpoch) {
        if (!enabled) return;
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            // Snapshot already created in handleEpochTransition (between rewards and governance).
            // Here we only prune old snapshots/events and credit reward_rest.

            // Prune old snapshots and stake events
            pruneOldSnapshots(newEpoch - SNAPSHOT_RETENTION_EPOCHS, batch);
            int oldestEventEpoch = newEpoch - SNAPSHOT_RETENTION_EPOCHS;
            if (oldestEventEpoch > 0) {
                pruneOldStakeEvents(slotForEpochStart(oldestEventEpoch), batch);
            }

            // Credit spendable reward_rest to PREFIX_ACCT in the SAME batch.
            // This makes the credited amounts available for subsequent snapshots and withdrawals.
            creditAndRemoveSpendableRewardRest(previousEpoch, batch);

            db.write(wo, batch);
            log.info("Epoch transition {} -> {} completed (prune, reward_rest credit)", previousEpoch, newEpoch);

        } catch (Exception ex) {
            log.error("Epoch transition post-snapshot failed for {} -> {}: {}", previousEpoch, newEpoch, ex.toString());
        }
    }

    @Override
    public void handlePostEpochTransition(int previousEpoch, int newEpoch) {
        if (!enabled || epochBoundaryProcessor == null) return;
        try {
            epochBoundaryProcessor.processPostEpochBoundary(newEpoch);
        } catch (Exception e) {
            log.warn("Post-epoch boundary processing failed for {} -> {}: {}",
                    previousEpoch, newEpoch, e.getMessage());
        }
    }

    // --- Block application ---

    @Override
    public void applyBlock(BlockAppliedEvent event) {
        if (!enabled) return;
        Block block = event.block();
        if (block == null) return;

        long slot = event.slot();
        long blockNo = event.blockNumber();

        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            int currentEpoch = epochForSlot(slot);

            List<DeltaOp> deltaOps = new ArrayList<>();
            BigInteger totalDepositedDelta = BigInteger.ZERO;

            // Identify invalid transactions
            List<Integer> invList = block.getInvalidTransactions();
            Set<Integer> invalidIdx = (invList != null) ? new HashSet<>(invList) : Collections.emptySet();
            List<TransactionBody> txs = block.getTransactionBodies();

            if (txs != null) {
                for (int txIdx = 0; txIdx < txs.size(); txIdx++) {
                    if (invalidIdx.contains(txIdx)) continue;
                    TransactionBody tx = txs.get(txIdx);

                    // Process certificates
                    List<Certificate> certs = tx.getCertificates();
                    if (certs != null) {
                        for (int certIdx = 0; certIdx < certs.size(); certIdx++) {
                            totalDepositedDelta = totalDepositedDelta.add(
                                    processCertificate(certs.get(certIdx), slot, currentEpoch,
                                            txIdx, certIdx, batch, deltaOps));
                        }
                    }

                    // Process withdrawals — debit reward balance
                    Map<String, BigInteger> withdrawals = tx.getWithdrawals();
                    if (withdrawals != null) {
                        for (var entry : withdrawals.entrySet()) {
                            processWithdrawal(entry.getKey(), entry.getValue(), batch, deltaOps);
                        }
                    }

                    // Track protocol parameter updates
                    if (paramTracker != null && paramTracker.isEnabled()) {
                        paramTracker.processTransaction(tx);
                    }
                }
            }

            // Process governance actions (proposals, votes, donations)
            if (governanceBlockProcessor != null) {
                try {
                    governanceBlockProcessor.processBlock(block, slot, currentEpoch, batch, deltaOps);
                } catch (Exception e) {
                    log.warn("Governance block processing failed for block {}: {}", blockNo, e.getMessage());
                }
            }

            // Track per-pool block count and per-epoch fees
            trackBlockCountAndFees(block, currentEpoch, txs, invalidIdx, batch, deltaOps);

            // Update total deposited
            if (totalDepositedDelta.signum() != 0) {
                BigInteger current = getTotalDeposited();
                BigInteger updated = current.add(totalDepositedDelta);
                if (updated.signum() < 0) updated = BigInteger.ZERO;

                byte[] prev = totalDepositedToBytes(current);
                byte[] newVal = totalDepositedToBytes(updated);
                batch.put(cfState, META_TOTAL_DEPOSITED, newVal);
                deltaOps.add(new DeltaOp(OP_PUT, META_TOTAL_DEPOSITED, prev));
            }

            // Write delta log
            byte[] deltaKey = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockNo).array();
            byte[] deltaVal = encodeDelta(slot, deltaOps);
            batch.put(cfDelta, deltaKey, deltaVal);

            // Update last applied block
            byte[] blockNoBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockNo).array();
            batch.put(cfState, META_LAST_APPLIED_BLOCK, blockNoBytes);

            db.write(wo, batch);
        } catch (Exception ex) {
            log.error("Account state apply failed for block {}: {}", blockNo, ex.toString());
        }
    }

    private BigInteger processCertificate(Certificate cert, long slot, int currentEpoch,
                                          int txIdx, int certIdx,
                                          WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        BigInteger depositDelta = BigInteger.ZERO;

        switch (cert) {
            case StakeRegistration sr -> {
                depositDelta = registerStake(sr.getStakeCredential(),
                        epochParamProvider.getKeyDeposit(0), slot, txIdx, certIdx, batch, deltaOps);
            }
            case RegCert rc -> {
                BigInteger deposit = rc.getCoin() != null ? rc.getCoin() : BigInteger.ZERO;
                depositDelta = registerStake(rc.getStakeCredential(), deposit,
                        slot, txIdx, certIdx, batch, deltaOps);
            }
            case StakeDeregistration sd -> {
                depositDelta = deregisterStake(sd.getStakeCredential(),
                        slot, txIdx, certIdx, batch, deltaOps);
            }
            case UnregCert uc -> {
                depositDelta = deregisterStake(uc.getStakeCredential(),
                        slot, txIdx, certIdx, batch, deltaOps);
            }
            case StakeDelegation sd -> {
                delegateToPool(sd.getStakeCredential(), sd.getStakePoolId().getPoolKeyHash(),
                        slot, txIdx, certIdx, batch, deltaOps);
            }
            case VoteDelegCert vd -> {
                delegateToDRep(vd.getStakeCredential(), vd.getDrep(),
                        slot, txIdx, certIdx, batch, deltaOps);
            }
            case StakeVoteDelegCert svd -> {
                delegateToPool(svd.getStakeCredential(), svd.getPoolKeyHash(),
                        slot, txIdx, certIdx, batch, deltaOps);
                delegateToDRep(svd.getStakeCredential(), svd.getDrep(),
                        slot, txIdx, certIdx, batch, deltaOps);
            }
            case StakeRegDelegCert srd -> {
                BigInteger deposit = srd.getCoin() != null ? srd.getCoin() : BigInteger.ZERO;
                depositDelta = registerStake(srd.getStakeCredential(), deposit,
                        slot, txIdx, certIdx, batch, deltaOps);
                delegateToPool(srd.getStakeCredential(), srd.getPoolKeyHash(),
                        slot, txIdx, certIdx, batch, deltaOps);
            }
            case VoteRegDelegCert vrd -> {
                BigInteger deposit = vrd.getCoin() != null ? vrd.getCoin() : BigInteger.ZERO;
                depositDelta = registerStake(vrd.getStakeCredential(), deposit,
                        slot, txIdx, certIdx, batch, deltaOps);
                delegateToDRep(vrd.getStakeCredential(), vrd.getDrep(),
                        slot, txIdx, certIdx, batch, deltaOps);
            }
            case StakeVoteRegDelegCert svrd -> {
                BigInteger deposit = svrd.getCoin() != null ? svrd.getCoin() : BigInteger.ZERO;
                depositDelta = registerStake(svrd.getStakeCredential(), deposit,
                        slot, txIdx, certIdx, batch, deltaOps);
                delegateToPool(svrd.getStakeCredential(), svrd.getPoolKeyHash(),
                        slot, txIdx, certIdx, batch, deltaOps);
                delegateToDRep(svrd.getStakeCredential(), svrd.getDrep(),
                        slot, txIdx, certIdx, batch, deltaOps);
            }
            case PoolRegistration pr -> {
                var params = pr.getPoolParams();
                String poolHash = params.getOperator();
                byte[] key = poolDepositKey(poolHash);
                byte[] prev = db.get(cfState, key);

                var margin = params.getMargin();
                BigInteger marginNum = margin != null ? margin.getNumerator() : BigInteger.ZERO;
                BigInteger marginDen = margin != null ? margin.getDenominator() : BigInteger.ONE;
                BigInteger cost = params.getCost() != null ? params.getCost() : BigInteger.ZERO;
                BigInteger pledge = params.getPledge() != null ? params.getPledge() : BigInteger.ZERO;
                String rewardAccount = params.getRewardAccount() != null ? params.getRewardAccount() : "";
                Set<String> owners = params.getPoolOwners() != null ? params.getPoolOwners() : Set.of();

                var data = new AccountStateCborCodec.PoolRegistrationData(
                        epochParamProvider.getPoolDeposit(0),
                        marginNum, marginDen, cost, pledge, rewardAccount, owners);
                byte[] val = AccountStateCborCodec.encodePoolRegistration(data);
                batch.put(cfState, key, val);
                deltaOps.add(new DeltaOp(OP_PUT, key, prev));

                // Write pool params history keyed by ACTIVE epoch.
                // On Cardano, a new pool registration takes effect at epoch + 2.
                // A re-registration (update of existing pool) takes effect at epoch + 3.
                // This matches yaci-store's pool table: REGISTRATION → active_epoch = epoch + 2,
                // UPDATE → active_epoch = epoch + 3.
                boolean isNewPool = (prev == null); // no existing pool deposit entry = first registration
                int activeEpoch = isNewPool ? currentEpoch + 2 : currentEpoch + 3;
                byte[] histKey = poolParamsHistKey(poolHash, activeEpoch);
                byte[] histPrev = db.get(cfState, histKey);
                batch.put(cfState, histKey, val);
                deltaOps.add(new DeltaOp(OP_PUT, histKey, histPrev));

                // Cancel any pending retirement
                byte[] retKey = poolRetireKey(poolHash);
                byte[] retPrev = db.get(cfState, retKey);
                boolean reRegisteredAfterRetirement = false;
                if (retPrev != null) {
                    long retireEpoch = AccountStateCborCodec.decodePoolRetirement(retPrev);
                    reRegisteredAfterRetirement = (retireEpoch <= currentEpoch);
                }
                if (retPrev != null) {
                    batch.delete(cfState, retKey);
                    deltaOps.add(new DeltaOp(OP_DELETE, retKey, retPrev));
                }

                // Track pool registration slot: set on first registration or re-registration after retirement.
                // Used by snapshot creation to exclude stale delegations (delegated before pool's current lifecycle).
                if (isNewPool || reRegisteredAfterRetirement) {
                    byte[] regSlotKey = poolRegSlotKey(poolHash);
                    byte[] regSlotPrev = db.get(cfState, regSlotKey);
                    byte[] regSlotVal = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(slot).array();
                    batch.put(cfState, regSlotKey, regSlotVal);
                    deltaOps.add(new DeltaOp(OP_PUT, regSlotKey, regSlotPrev));
                }
            }
            case PoolRetirement pr -> {
                byte[] key = poolRetireKey(pr.getPoolKeyHash());
                byte[] prev = db.get(cfState, key);
                byte[] val = AccountStateCborCodec.encodePoolRetirement(pr.getEpoch());
                batch.put(cfState, key, val);
                deltaOps.add(new DeltaOp(OP_PUT, key, prev));
            }
            case RegDrepCert rd -> {
                int ct = credTypeFromModel(rd.getDrepCredential());
                String hash = rd.getDrepCredential().getHash();
                BigInteger deposit = rd.getCoin() != null ? rd.getCoin() : BigInteger.ZERO;
                byte[] key = drepRegKey(ct, hash);
                byte[] prev = db.get(cfState, key);
                byte[] val = AccountStateCborCodec.encodeDRepRegistration(deposit);
                batch.put(cfState, key, val);
                deltaOps.add(new DeltaOp(OP_PUT, key, prev));
                depositDelta = deposit;
                // Governance dual-write: richer DRepStateRecord
                if (governanceBlockProcessor != null) {
                    governanceBlockProcessor.processDRepRegistration(rd, slot, currentEpoch, batch, deltaOps);
                }
            }
            case UnregDrepCert ud -> {
                int ct = credTypeFromModel(ud.getDrepCredential());
                String hash = ud.getDrepCredential().getHash();
                byte[] key = drepRegKey(ct, hash);
                byte[] prev = db.get(cfState, key);
                if (prev != null) {
                    BigInteger refund = AccountStateCborCodec.decodeDRepDeposit(prev);
                    depositDelta = refund.negate();
                    batch.delete(cfState, key);
                    deltaOps.add(new DeltaOp(OP_DELETE, key, prev));
                }
                // Governance dual-write: track deregistration for v9 bug
                if (governanceBlockProcessor != null) {
                    governanceBlockProcessor.processDRepDeregistration(ud, slot, batch, deltaOps);
                }
            }
            case UpdateDrepCert upd -> {
                // DRep update only changes anchor — deposit stays the same.
                // We re-write the existing entry to keep the key alive.
                int ct = credTypeFromModel(upd.getDrepCredential());
                String hash = upd.getDrepCredential().getHash();
                byte[] key = drepRegKey(ct, hash);
                byte[] prev = db.get(cfState, key);
                if (prev != null) {
                    // Preserve existing deposit
                    batch.put(cfState, key, prev);
                    deltaOps.add(new DeltaOp(OP_PUT, key, prev));
                }
                // Governance dual-write: update anchor + track interaction
                if (governanceBlockProcessor != null) {
                    governanceBlockProcessor.processDRepUpdate(upd, currentEpoch, batch, deltaOps);
                }
            }
            case AuthCommitteeHotCert ac -> {
                int coldCt = credTypeFromModel(ac.getCommitteeColdCredential());
                String coldHash = ac.getCommitteeColdCredential().getHash();
                int hotCt = credTypeFromModel(ac.getCommitteeHotCredential());
                String hotHash = ac.getCommitteeHotCredential().getHash();

                byte[] key = committeeHotKey(coldCt, coldHash);
                byte[] prev = db.get(cfState, key);
                byte[] val = AccountStateCborCodec.encodeCommitteeHotKey(hotCt, hotHash);
                batch.put(cfState, key, val);
                deltaOps.add(new DeltaOp(OP_PUT, key, prev));
                // Governance dual-write: richer CommitteeMemberRecord
                if (governanceBlockProcessor != null) {
                    governanceBlockProcessor.processCommitteeHotKeyAuth(ac, batch, deltaOps);
                }
            }
            case ResignCommitteeColdCert rc -> {
                int ct = credTypeFromModel(rc.getCommitteeColdCredential());
                String coldHash = rc.getCommitteeColdCredential().getHash();

                byte[] key = committeeResignKey(ct, coldHash);
                byte[] prev = db.get(cfState, key);
                byte[] val = AccountStateCborCodec.encodeCommitteeResignation();
                batch.put(cfState, key, val);
                deltaOps.add(new DeltaOp(OP_PUT, key, prev));
                // Governance dual-write: mark member as resigned
                if (governanceBlockProcessor != null) {
                    governanceBlockProcessor.processCommitteeResignation(rc, batch, deltaOps);
                }
            }
            case MoveInstataneous mir -> {
                processMir(mir, batch, deltaOps);
            }
            default -> {
                // Unknown certificate type — skip
            }
        }

        return depositDelta;
    }

    private static byte[] acctRegSlotKey(int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = PREFIX_ACCT_REG_SLOT;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    private BigInteger registerStake(StakeCredential cred, BigInteger deposit,
                                     long slot, int txIdx, int certIdx,
                                     WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        int ct = credTypeInt(cred.getType());
        byte[] key = accountKey(ct, cred.getHash());
        byte[] prev = db.get(cfState, key);
        byte[] val = AccountStateCborCodec.encodeStakeAccount(BigInteger.ZERO, deposit);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));

        // Re-registration after deregistration: clean up stale pool/DRep delegation entries.
        // Per Haskell ledger (Deleg.hs): re-registration starts fresh with no delegation.
        // deregisterStake already deletes these, but backups from older code may have stale entries.
        if (prev == null) {
            byte[] delegKey = poolDelegKey(ct, cred.getHash());
            byte[] delegPrev = db.get(cfState, delegKey);
            if (delegPrev != null) {
                batch.delete(cfState, delegKey);
                deltaOps.add(new DeltaOp(OP_DELETE, delegKey, delegPrev));
            }
            byte[] drepKey = drepDelegKey(ct, cred.getHash());
            byte[] drepPrev = db.get(cfState, drepKey);
            if (drepPrev != null) {
                batch.delete(cfState, drepKey);
                deltaOps.add(new DeltaOp(OP_DELETE, drepKey, drepPrev));
            }
        }

        // Track registration slot — used by snapshot to detect stale delegations
        // from before the last deregistration/re-registration cycle.
        byte[] regSlotKey = acctRegSlotKey(ct, cred.getHash());
        byte[] regSlotPrev = db.get(cfState, regSlotKey);
        byte[] regSlotVal = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(slot).array();
        batch.put(cfState, regSlotKey, regSlotVal);
        deltaOps.add(new DeltaOp(OP_PUT, regSlotKey, regSlotPrev));

        // Write stake event
        byte[] eventKey = stakeEventKey(slot, txIdx, certIdx, ct, cred.getHash());
        byte[] eventVal = AccountStateCborCodec.encodeStakeEvent(AccountStateCborCodec.EVENT_REGISTRATION);
        batch.put(cfState, eventKey, eventVal);
        deltaOps.add(new DeltaOp(OP_PUT, eventKey, null));

        // Register pointer for pointer address resolution
        pointerAddressResolver.registerPointer(slot, txIdx, certIdx, ct, cred.getHash());

        return deposit;
    }

    private BigInteger deregisterStake(StakeCredential cred,
                                       long slot, int txIdx, int certIdx,
                                       WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        int ct = credTypeInt(cred.getType());
        BigInteger depositRefund = BigInteger.ZERO;

        // Remove account
        byte[] acctKey = accountKey(ct, cred.getHash());
        byte[] acctPrev = db.get(cfState, acctKey);
        if (acctPrev != null) {
            depositRefund = AccountStateCborCodec.decodeStakeAccount(acctPrev).deposit().negate();
            batch.delete(cfState, acctKey);
            deltaOps.add(new DeltaOp(OP_DELETE, acctKey, acctPrev));
        }

        // Per Haskell ledger (Deleg.hs): deregistration completely removes the account entry
        // from dsAccounts via Map.extract, discarding sasStakePoolDelegation with it.
        // Re-registration starts fresh with sasStakePoolDelegation = SNothing (no delegation).
        // Also cleans the reverse index: removes credential from the pool's spsDelegators set.
        //
        // Always delete unconditionally — the delegation may exist in the uncommitted WriteBatch
        // (e.g., delegation and deregistration in the same block) where db.get() won't find it.
        byte[] delegKey = poolDelegKey(ct, cred.getHash());
        byte[] delegPrev = db.get(cfState, delegKey);
        batch.delete(cfState, delegKey);
        deltaOps.add(new DeltaOp(OP_DELETE, delegKey, delegPrev)); // delegPrev may be null — rollback handles it

        // Remove DRep delegation (same behavior: Conway unregisterConwayAccount calls
        // unDelegReDelegDRep with Nothing, clearing the DRep delegation)
        byte[] drepKey = drepDelegKey(ct, cred.getHash());
        byte[] drepPrev = db.get(cfState, drepKey);
        batch.delete(cfState, drepKey);
        deltaOps.add(new DeltaOp(OP_DELETE, drepKey, drepPrev)); // drepPrev may be null

        // Write stake event
        byte[] eventKey = stakeEventKey(slot, txIdx, certIdx, ct, cred.getHash());
        byte[] eventVal = AccountStateCborCodec.encodeStakeEvent(AccountStateCborCodec.EVENT_DEREGISTRATION);
        batch.put(cfState, eventKey, eventVal);
        deltaOps.add(new DeltaOp(OP_PUT, eventKey, null));

        return depositRefund;
    }

    private void delegateToPool(StakeCredential cred, String poolHash,
                                long slot, int txIdx, int certIdx,
                                WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        int ct = credTypeInt(cred.getType());
        byte[] key = poolDelegKey(ct, cred.getHash());
        byte[] prev = db.get(cfState, key);
        byte[] val = AccountStateCborCodec.encodePoolDelegation(poolHash, slot, txIdx, certIdx);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
    }

    private void delegateToDRep(StakeCredential cred, Drep drep,
                                long slot, int txIdx, int certIdx,
                                WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        int ct = credTypeInt(cred.getType());
        byte[] key = drepDelegKey(ct, cred.getHash());
        byte[] prev = db.get(cfState, key);
        byte[] val = AccountStateCborCodec.encodeDRepDelegation(
                drepTypeInt(drep.getType()), drep.getHash(), slot, txIdx, certIdx);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
    }

    private void processWithdrawal(String rewardAddrHex, BigInteger amount,
                                   WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        // Reward address format: header(1) + credential(28)
        // header byte: network_id(4 bits) | type(4 bits)
        // type 0xe0 or 0xf0 for stake addresses
        byte[] addrBytes = HexUtil.decodeHexString(rewardAddrHex);
        if (addrBytes.length < 29) return;

        int headerByte = addrBytes[0] & 0xFF;
        // Extract credential type from bit 4 of header
        // e0 = key hash stake addr, f0 = script hash stake addr
        int credType = ((headerByte & 0x10) != 0) ? 1 : 0;
        byte[] credHash = new byte[28];
        System.arraycopy(addrBytes, 1, credHash, 0, 28);
        String credHashHex = HexUtil.encodeHexString(credHash);

        byte[] key = accountKey(credType, credHashHex);
        byte[] prev = db.get(cfState, key);
        if (prev == null) return;

        var acct = AccountStateCborCodec.decodeStakeAccount(prev);
        BigInteger newReward = acct.reward().subtract(amount);
        if (newReward.signum() < 0) newReward = BigInteger.ZERO;

        byte[] val = AccountStateCborCodec.encodeStakeAccount(newReward, acct.deposit());
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
    }

    /**
     * Track per-pool block production count and per-epoch total fees.
     * issuerVkey from block header identifies the pool that produced the block.
     */
    private void trackBlockCountAndFees(Block block, int epoch,
                                        List<TransactionBody> txs, Set<Integer> invalidIdx,
                                        WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        // Track block producer (issuerVkey = pool cold VKey hash = pool ID)
        if (block.getHeader() != null && block.getHeader().getHeaderBody() != null) {
            String issuerVkey = block.getHeader().getHeaderBody().getIssuerVkey();
            if (issuerVkey != null && !issuerVkey.isEmpty()) {
                // Hash the VKey to get the pool ID (28-byte Blake2b-224)
                byte[] vkeyBytes = HexUtil.decodeHexString(issuerVkey);
                String poolHash = HexUtil.encodeHexString(
                        com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash224(vkeyBytes));

                byte[] key = poolBlockCountKey(epoch, poolHash);
                byte[] prev = db.get(cfState, key);
                long currentCount = (prev != null) ? AccountStateCborCodec.decodePoolBlockCount(prev) : 0;
                byte[] val = AccountStateCborCodec.encodePoolBlockCount(currentCount + 1);
                batch.put(cfState, key, val);
                deltaOps.add(new DeltaOp(OP_PUT, key, prev));
            }
        }

        // Track total fees for this epoch (include invalid tx collateral fees)
        if (txs != null) {
            var feeResolver = new FeeResolver(utxoState);
            BigInteger blockFees = BigInteger.ZERO;
            for (int i = 0; i < txs.size(); i++) {
                BigInteger fee = feeResolver.resolveFee(txs.get(i), invalidIdx.contains(i));
                if (fee != null) blockFees = blockFees.add(fee);
            }

            if (blockFees.signum() > 0) {
                byte[] feeKey = epochFeesKey(epoch);
                byte[] feePrev = db.get(cfState, feeKey);
                BigInteger currentFees = (feePrev != null)
                        ? AccountStateCborCodec.decodeEpochFees(feePrev)
                        : BigInteger.ZERO;
                byte[] feeVal = AccountStateCborCodec.encodeEpochFees(currentFees.add(blockFees));
                batch.put(cfState, feeKey, feeVal);
                deltaOps.add(new DeltaOp(OP_PUT, feeKey, feePrev));
            }
        }
    }

    /**
     * Process a MIR (Move Instantaneous Rewards) certificate.
     * <p>
     * Two modes:
     * 1. Stake credential distribution: adds instant reward amounts to per-credential MIR state
     * 2. Pot transfer: accumulates reserves↔treasury transfer amounts in metadata keys
     */
    private void processMir(MoveInstataneous mir, WriteBatch batch,
                            List<DeltaOp> deltaOps) throws RocksDBException {
        Map<StakeCredential, BigInteger> credMap = mir.getStakeCredentialCoinMap();

        if (credMap != null && !credMap.isEmpty()) {
            // Mode 1: distribute rewards to individual stake credentials
            for (var entry : credMap.entrySet()) {
                StakeCredential cred = entry.getKey();
                BigInteger amount = entry.getValue();
                if (amount == null || amount.signum() <= 0) continue;

                int ct = credTypeInt(cred.getType());
                byte[] key = mirRewardKey(ct, cred.getHash());
                byte[] prev = db.get(cfState, key);

                // Accumulate: existing + new
                BigInteger existing = (prev != null)
                        ? AccountStateCborCodec.decodeMirReward(prev)
                        : BigInteger.ZERO;
                BigInteger updated = existing.add(amount);

                byte[] val = AccountStateCborCodec.encodeMirReward(updated);
                batch.put(cfState, key, val);
                deltaOps.add(new DeltaOp(OP_PUT, key, prev));
            }
        } else if (mir.getAccountingPotCoin() != null && mir.getAccountingPotCoin().signum() > 0) {
            // Mode 2: pot transfer (reserves ↔ treasury)
            // reserves=true means source is reserves (reserves → treasury)
            // treasury=true means source is treasury (treasury → reserves)
            byte[] metaKey = mir.isTreasury() ? META_MIR_TO_RESERVES : META_MIR_TO_TREASURY;
            byte[] prev = db.get(cfState, metaKey);

            BigInteger existing = (prev != null && prev.length >= 8)
                    ? new BigInteger(1, prev) : BigInteger.ZERO;
            BigInteger updated = existing.add(mir.getAccountingPotCoin());

            byte[] val = totalDepositedToBytes(updated);
            batch.put(cfState, metaKey, val);
            deltaOps.add(new DeltaOp(OP_PUT, metaKey, prev));
        }
    }

    /**
     * Create and commit the delegation snapshot in its own WriteBatch.
     * Called from EpochBoundaryProcessor between rewards and governance so the snapshot
     * captures post-reward state and is available for DRep distribution calculation.
     */
    /**
     * Create and commit the delegation snapshot. Returns the UTXO balance aggregation
     * so it can be reused for DRep distribution calculation (which needs actual balances
     * for ALL credentials, not just pool-delegated ones).
     */
    public java.util.Map<UtxoBalanceAggregator.CredentialKey, java.math.BigInteger> createAndCommitDelegationSnapshot(int epoch) {
        java.util.Map<UtxoBalanceAggregator.CredentialKey, java.math.BigInteger> utxoBalances = null;
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            utxoBalances = createDelegationSnapshot(epoch, batch);
            db.write(wo, batch);
        } catch (Exception ex) {
            log.error("Failed to create delegation snapshot for epoch {}: {}", epoch, ex.toString());
        }
        return utxoBalances;
    }

    private java.util.Map<UtxoBalanceAggregator.CredentialKey, java.math.BigInteger> createDelegationSnapshot(int epoch, WriteBatch batch) throws RocksDBException {
        byte[] epochBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();
        int count = 0;
        int skippedUnregistered = 0;
        int skippedZeroBalance = 0;
        int skippedRetiredPool = 0;

        // Build set of retired pool hashes (pools with retireEpoch <= snapshot epoch).
        // Delegations to retired pools must be excluded from the snapshot, matching
        // yaci-store's `NOT EXISTS (... p.status = 'RETIRED')` and Haskell node behavior.
        Set<String> retiredPools = new HashSet<>();
        try (RocksIterator retireIt = db.newIterator(cfState)) {
            retireIt.seek(new byte[]{PREFIX_POOL_RETIRE});
            while (retireIt.isValid()) {
                byte[] rk = retireIt.key();
                if (rk.length < 2 || rk[0] != PREFIX_POOL_RETIRE) break;
                long retireEpoch = AccountStateCborCodec.decodePoolRetirement(retireIt.value());
                String poolHash = HexUtil.encodeHexString(Arrays.copyOfRange(rk, 1, rk.length));
                if (retireEpoch <= epoch) {
                    retiredPools.add(poolHash);
                }
                retireIt.next();
            }
        }

        // Build map of pool registration slots for stale delegation check.
        // A delegation is stale if it was made before the pool's current registration slot
        // (i.e., the pool retired and re-registered after the delegation was made).
        Map<String, Long> poolRegSlots = new HashMap<>();
        try (RocksIterator regSlotIt = db.newIterator(cfState)) {
            regSlotIt.seek(new byte[]{PREFIX_POOL_REG_SLOT});
            while (regSlotIt.isValid()) {
                byte[] rk = regSlotIt.key();
                if (rk.length < 2 || rk[0] != PREFIX_POOL_REG_SLOT) break;
                String poolHash = HexUtil.encodeHexString(Arrays.copyOfRange(rk, 1, rk.length));
                long regSlot = ByteBuffer.wrap(regSlotIt.value()).order(ByteOrder.BIG_ENDIAN).getLong();
                poolRegSlots.put(poolHash, regSlot);
                regSlotIt.next();
            }
        }
        int skippedStaleDelegation = 0;
        int skippedDeregAfterDeleg = 0;

        // Pre-build map: credential → latest deregistration position (slot, txIdx, certIdx)
        // Used to detect delegations invalidated by a subsequent deregistration.
        java.util.Map<String, long[]> latestDeregistrations = new java.util.HashMap<>();
        try (RocksIterator deregIt = db.newIterator(cfState)) {
            deregIt.seek(new byte[]{PREFIX_STAKE_EVENT});
            while (deregIt.isValid()) {
                byte[] dk = deregIt.key();
                if (dk.length < 14 || dk[0] != PREFIX_STAKE_EVENT) break;
                int eventType = AccountStateCborCodec.decodeStakeEvent(deregIt.value());
                if (eventType == AccountStateCborCodec.EVENT_DEREGISTRATION) {
                    long evSlot = ByteBuffer.wrap(dk, 1, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                    int evTxIdx = ByteBuffer.wrap(dk, 9, 2).order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF;
                    int evCertIdx = ByteBuffer.wrap(dk, 11, 2).order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF;
                    int evCredType = dk[13] & 0xFF;
                    String evCredHash = HexUtil.encodeHexString(Arrays.copyOfRange(dk, 14, dk.length));
                    String credKey = evCredType + ":" + evCredHash;

                    long[] existing = latestDeregistrations.get(credKey);
                    if (existing == null
                            || evSlot > existing[0]
                            || (evSlot == existing[0] && evTxIdx > existing[1])
                            || (evSlot == existing[0] && evTxIdx == existing[1] && evCertIdx > existing[2])) {
                        latestDeregistrations.put(credKey, new long[]{evSlot, evTxIdx, evCertIdx});
                    }
                }
                deregIt.next();
            }
        }
        if (!latestDeregistrations.isEmpty()) {
            log.debug("Pre-built deregistration map: {} credentials with deregistrations", latestDeregistrations.size());
        }

        // TODO (mainnet readiness): At the Allegra hardfork boundary, bootstrap (Byron redeem, base58)
        // UTXOs should be removed from cfUnspent. They are unspendable after Allegra and bloat the
        // UTXO store (~318.2M ADA on mainnet). They don't affect stake snapshots (no Shelley stake
        // credential) or reward calculation (cf-rewards handles reserve adjustment internally).
        // Custom networks (devnet) start fresh with no Byron era — no action needed.

        // Optionally aggregate UTXO balances for enhanced snapshots.
        // Pointer address resolution: enabled pre-Conway, disabled in Conway+ (pointer addresses removed).
        // In Conway era, any remaining pointer address UTXOs lose their stake credential association.
        java.util.Map<UtxoBalanceAggregator.CredentialKey, java.math.BigInteger> utxoBalances = null;
        if (stakeSnapshotService != null && stakeSnapshotService.isEnabled() && utxoState != null) {
            // Use paramTracker (which has actual on-chain protocol version) if available,
            // otherwise fall back to base provider. The base provider returns genesis defaults
            // which don't reflect hardfork updates.
            int protocolMajor = (paramTracker != null && paramTracker.isEnabled())
                    ? paramTracker.getProtocolMajor(epoch) : epochParamProvider.getProtocolMajor(epoch);
            boolean conwayEra = protocolMajor >= 9;
            PointerAddressResolver ptrResolver = conwayEra ? null : pointerAddressResolver;
            // Use epoch's last slot as cutoff for UTXO inclusion.
            // This ensures only UTXOs created within or before this epoch are counted,
            // even if blocks from the next epoch have already been processed.
            long epochLastSlot = slotForEpochStart(epoch + 1) - 1;
            utxoBalances = stakeSnapshotService.aggregateStakeBalances(utxoState, ptrResolver, epochLastSlot);
        }

        // Pre-build spendable reward_rest amounts per credential.
        // These are added to stakeAmount in the snapshot loop because the credit to PREFIX_ACCT
        // happens in the same uncommitted WriteBatch — db.get() won't see it yet.
        java.util.Map<String, BigInteger> spendableRewardRest = getSpendableRewardRest(epoch);
        if (!spendableRewardRest.isEmpty()) {
            log.info("Spendable reward_rest for epoch {}: {} credentials, total={}",
                    epoch, spendableRewardRest.size(),
                    spendableRewardRest.values().stream().reduce(BigInteger.ZERO, BigInteger::add));
        }

        // Collect entries for export (only allocate list when exporter is active)
        final java.util.List<com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter.StakeEntry> exportEntries =
                (snapshotExporter != com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter.NOOP)
                        ? new java.util.ArrayList<>() : null;

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(new byte[]{PREFIX_POOL_DELEG});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_POOL_DELEG) break;

                int credType = key[1] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 2, key.length));

                // Only include registered stake credentials (must have PREFIX_ACCT entry)
                byte[] acctKey = accountKey(credType, credHash);
                byte[] acctVal = db.get(cfState, acctKey);
                if (acctVal == null) {
                    skippedUnregistered++;
                    it.next();
                    continue;
                }

                var deleg = AccountStateCborCodec.decodePoolDelegation(it.value());

                // Skip delegations to retired pools
                if (retiredPools.contains(deleg.poolHash())) {
                    skippedRetiredPool++;
                    it.next();
                    continue;
                }

                // Skip stale delegations: delegation made before pool's current registration slot.
                // This happens when a pool retires and re-registers — old delegations are invalid.
                Long poolRegSlot = poolRegSlots.get(deleg.poolHash());
                if (poolRegSlot != null && deleg.slot() < poolRegSlot) {
                    skippedStaleDelegation++;
                    it.next();
                    continue;
                }

                // Skip delegations invalidated by a subsequent deregistration.
                // If a credential was deregistered AFTER the delegation was made (comparing
                // slot, then txIndex, then certIndex), the delegation is stale even if the
                // credential was re-registered later — a new delegation would be needed.
                String deregKey = credType + ":" + credHash;
                long[] latestDereg = latestDeregistrations.get(deregKey);
                if (latestDereg != null) {
                    long dSlot = latestDereg[0];
                    long dTxIdx = latestDereg[1];
                    long dCertIdx = latestDereg[2];
                    if (dSlot > deleg.slot()
                            || (dSlot == deleg.slot() && dTxIdx > deleg.txIdx())
                            || (dSlot == deleg.slot() && dTxIdx == deleg.txIdx() && dCertIdx > deleg.certIdx())) {
                        skippedDeregAfterDeleg++;
                        it.next();
                        continue;
                    }
                }

                // Skip delegations that predate the credential's current registration.
                // This catches stale delegations from before a deregistration/re-registration
                // cycle, even when the deregistration stake event has been pruned.
                byte[] acctRegSlotVal = db.get(cfState, acctRegSlotKey(credType, credHash));
                if (acctRegSlotVal != null) {
                    long acctRegSlot = ByteBuffer.wrap(acctRegSlotVal).order(ByteOrder.BIG_ENDIAN).getLong();
                    if (deleg.slot() < acctRegSlot) {
                        skippedDeregAfterDeleg++;
                        it.next();
                        continue;
                    }
                }

                // Compute stake amount = UTXO balance + withdrawable rewards
                java.math.BigInteger stakeAmount = java.math.BigInteger.ZERO;
                if (utxoBalances != null) {
                    var credKey = new UtxoBalanceAggregator.CredentialKey(credType, credHash);
                    java.math.BigInteger utxoBal = utxoBalances.getOrDefault(credKey, java.math.BigInteger.ZERO);
                    // Add reward balance (withdrawable rewards)
                    var acctData = AccountStateCborCodec.decodeStakeAccount(acctVal);
                    java.math.BigInteger rewardBal = acctData.reward();
                    // Add spendable reward_rest (proposal refunds, treasury withdrawals)
                    String restKey = credType + ":" + credHash;
                    java.math.BigInteger rewardRestBal = spendableRewardRest.getOrDefault(restKey, java.math.BigInteger.ZERO);
                    stakeAmount = utxoBal.add(rewardBal).add(rewardRestBal);

                    // Include zero-balance delegators in the snapshot to match yaci-store's epoch_stake.
                    // Zero-balance delegators may be pool owners, affecting ownerActiveStake in cf-rewards.
                    if (stakeAmount.signum() == 0) {
                        skippedZeroBalance++;
                    }
                }

                // Build snapshot key: [epoch(4)][credType(1)][credHash(28)]
                byte[] snapshotKey = new byte[4 + key.length - 1];
                System.arraycopy(epochBytes, 0, snapshotKey, 0, 4);
                System.arraycopy(key, 1, snapshotKey, 4, key.length - 1);

                byte[] snapshotVal = AccountStateCborCodec.encodeEpochDelegSnapshot(deleg.poolHash(), stakeAmount);
                batch.put(cfEpochSnapshot, snapshotKey, snapshotVal);
                count++;

                // Collect for export (only if exporter is active)
                if (exportEntries != null) {
                    exportEntries.add(new com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter.StakeEntry(
                            credType, credHash, deleg.poolHash(), stakeAmount));
                }


                it.next();
            }
        }

        byte[] epochMeta = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();
        batch.put(cfState, META_LAST_SNAPSHOT_EPOCH, epochMeta);
        log.info("Created delegation snapshot for epoch {} ({} delegations, amounts={}, skipped: {} unregistered, {} zero-balance, {} retired-pool, {} stale-delegation, {} dereg-after-deleg)",
                epoch, count, utxoBalances != null, skippedUnregistered, skippedZeroBalance, skippedRetiredPool, skippedStaleDelegation, skippedDeregAfterDeleg);

        // Export stake snapshot for debugging
        if (exportEntries != null) {
            snapshotExporter.exportStakeSnapshot(epoch, exportEntries);
        }

        return utxoBalances;
    }

    private void pruneOldSnapshots(int oldestToKeep, WriteBatch batch) throws RocksDBException {
        if (oldestToKeep <= 0) return;

        try (RocksIterator it = db.newIterator(cfEpochSnapshot)) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 4) break;
                int epoch = ByteBuffer.wrap(key, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (epoch >= oldestToKeep) break;
                batch.delete(cfEpochSnapshot, key);
                it.next();
            }
        }
    }


    /**
     * Prune stake events older than the given cutoff slot.
     * Called at epoch boundary alongside pruneOldSnapshots.
     */
    void pruneOldStakeEvents(long cutoffSlot, WriteBatch batch) throws RocksDBException {
        byte[] seekKey = new byte[1 + 8];
        seekKey[0] = PREFIX_STAKE_EVENT;
        // Start from slot 0

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 9 || key[0] != PREFIX_STAKE_EVENT) break;
                long slot = ByteBuffer.wrap(key, 1, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                if (slot >= cutoffSlot) break;
                batch.delete(cfState, key);
                it.next();
            }
        }
    }

    // --- Stake event queries (for reward calculation) ---

    /**
     * Get credentials whose last stake event in [startSlot, endSlot) is DEREGISTRATION.
     * Returns "credType:credHash" strings.
     */
    @Override
    public Set<String> getDeregisteredAccountsInSlotRange(long startSlot, long endSlot) {
        // Track last event per credential using LinkedHashMap
        Map<String, Integer> lastEvent = new LinkedHashMap<>();

        byte[] seekKey = new byte[1 + 8];
        seekKey[0] = PREFIX_STAKE_EVENT;
        ByteBuffer.wrap(seekKey, 1, 8).order(ByteOrder.BIG_ENDIAN).putLong(startSlot);

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 14 || key[0] != PREFIX_STAKE_EVENT) break;
                long slot = ByteBuffer.wrap(key, 1, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                if (slot >= endSlot) break;

                int credType = key[13] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 14, key.length));
                String credKey = credType + ":" + credHash;
                int eventType = AccountStateCborCodec.decodeStakeEvent(it.value());
                lastEvent.put(credKey, eventType);
                it.next();
            }
        }

        Set<String> deregistered = new HashSet<>();
        for (var entry : lastEvent.entrySet()) {
            if (entry.getValue() == AccountStateCborCodec.EVENT_DEREGISTRATION) {
                deregistered.add(entry.getKey());
            }
        }
        return deregistered;
    }

    /**
     * Get pool reward addresses that had ANY REGISTRATION event before cutoffSlot
     * and are in the given poolRewardAddresses set.
     * <p>
     * This implements "was EVER registered" semantics — once a registration event is found,
     * the credential stays in the set regardless of subsequent deregistrations.
     * cf-rewards uses this as {@code accountsRegisteredInThePast}: whether the operator's
     * reward address was ever registered determines if the operator gets any leader reward.
     * Returns "credType:credHash" strings.
     */
    @Override
    public Set<String> getRegisteredPoolRewardAddressesBeforeSlot(long cutoffSlot, Set<String> poolRewardAddresses) {
        if (poolRewardAddresses == null || poolRewardAddresses.isEmpty()) return Set.of();

        Set<String> registered = new HashSet<>();

        // Scan event log for ANY REGISTRATION event before cutoff.
        // Deregistrations are intentionally ignored — this checks "was ever registered",
        // matching yaci-store's SQL: WHERE type = 'STAKE_REGISTRATION' (no dereg check).
        byte[] seekKey = new byte[1 + 8];
        seekKey[0] = PREFIX_STAKE_EVENT;

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 14 || key[0] != PREFIX_STAKE_EVENT) break;
                long slot = ByteBuffer.wrap(key, 1, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                if (slot >= cutoffSlot) break;

                int credType = key[13] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 14, key.length));
                String credKey = credType + ":" + credHash;

                if (poolRewardAddresses.contains(credKey)) {
                    int eventType = AccountStateCborCodec.decodeStakeEvent(it.value());
                    if (eventType == AccountStateCborCodec.EVENT_REGISTRATION) {
                        registered.add(credKey);
                    }
                    // Deregistration events are NOT removed — "was ever registered" semantics
                }
                it.next();
            }
        }

        // Second: fallback for credentials registered at genesis or before event tracking.
        // If a pool reward address has an active account (PREFIX_ACCT) but no event was found,
        // consider it registered.
        for (String credKey : poolRewardAddresses) {
            if (registered.contains(credKey)) continue;
            String[] parts = credKey.split(":", 2);
            if (parts.length == 2) {
                int credType = Integer.parseInt(parts[0]);
                byte[] acctKey = accountKey(credType, parts[1]);
                try {
                    byte[] val = db.get(cfState, acctKey);
                    if (val != null) {
                        registered.add(credKey);
                    }
                } catch (RocksDBException e) {
                    log.warn("Failed to check account for pool reward addr {}: {}", credKey, e.getMessage());
                }
            }
        }

        return registered;
    }

    // --- Rollback ---

    @Override
    public void rollbackTo(RollbackEvent event) {
        if (!enabled) return;
        long targetSlot = event.target().getSlot();

        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions();
             RocksIterator it = db.newIterator(cfDelta)) {

            it.seekToLast();
            while (it.isValid()) {
                byte[] deltaVal = it.value();
                DecodedDelta delta = decodeDelta(deltaVal);

                if (delta.slot <= targetSlot) break;

                // Undo operations in reverse order
                for (int i = delta.ops.size() - 1; i >= 0; i--) {
                    DeltaOp op = delta.ops.get(i);
                    if (op.prevValue != null) {
                        batch.put(cfState, op.key, op.prevValue);
                    } else {
                        batch.delete(cfState, op.key);
                    }
                }

                batch.delete(cfDelta, it.key());
                it.prev();
            }

            // Clean up epoch snapshots, AdaPot entries, and pending jobs BEYOND the rollback target.
            // Snapshots have stale reward balances from rolled-back epoch boundary processing.
            // AdaPot entries bypass delta tracking (written via db.put, not WriteBatch).
            // Pending epoch boundary jobs for rolled-back epochs must be cancelled.
            int targetEpoch = epochForSlot(targetSlot);
            int lastSnapshot = getLastSnapshotEpoch();

            if (targetEpoch < lastSnapshot) {
                // Delete snapshots for epochs > targetEpoch
                try (RocksIterator snapIt = db.newIterator(cfEpochSnapshot)) {
                    byte[] seekKey = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                            .putInt(targetEpoch + 1).array();
                    snapIt.seek(seekKey);
                    while (snapIt.isValid()) {
                        batch.delete(cfEpochSnapshot, snapIt.key());
                        snapIt.next();
                    }
                }

                // Delete AdaPot entries for epochs > targetEpoch
                // These bypass delta tracking and may have stale values from rolled-back processing.
                for (int e = targetEpoch + 1; e <= lastSnapshot + 5; e++) {
                    byte[] adapotKey = adaPotKey(e);
                    byte[] existing = db.get(cfState, adapotKey);
                    if (existing != null) {
                        batch.delete(cfState, adapotKey);
                    }
                }

                byte[] epochMeta = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                        .putInt(targetEpoch).array();
                batch.put(cfState, META_LAST_SNAPSHOT_EPOCH, epochMeta);
            }

            db.write(wo, batch);
            log.info("Account state rolled back to slot {}", targetSlot);
        } catch (Exception ex) {
            log.error("Account state rollback failed: {}", ex.toString());
        }
    }

    // --- Reconcile ---

    @Override
    public void reconcile(ChainState chainState) {
        if (!enabled || chainState == null) return;

        long lastAppliedBlock = 0L;
        try {
            byte[] b = db.get(cfState, META_LAST_APPLIED_BLOCK);
            if (b != null && b.length == 8) {
                lastAppliedBlock = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getLong();
            }
        } catch (Exception ignored) {}

        ChainTip tip = chainState.getTip();
        if (tip == null) return;
        long tipBlock = tip.getBlockNumber();

        if (lastAppliedBlock == tipBlock) return;

        if (lastAppliedBlock > tipBlock) {
            String hashHex = tip.getBlockHash() != null ? HexUtil.encodeHexString(tip.getBlockHash()) : null;
            rollbackTo(new RollbackEvent(new Point(tip.getSlot(), hashHex), true));
            log.info("Account state reconciled: rolled back from {} to tip {}", lastAppliedBlock, tipBlock);
            return;
        }

        // Forward replay
        for (long bn = lastAppliedBlock + 1; bn <= tipBlock; bn++) {
            byte[] blockBytes = chainState.getBlockByNumber(bn);
            if (blockBytes == null) continue;

            try {
                Block block = com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer.INSTANCE
                        .deserialize(blockBytes);
                long blockSlot = block.getHeader().getHeaderBody().getSlot();
                String blockHash = block.getHeader().getHeaderBody().getBlockHash();
                applyBlock(new BlockAppliedEvent(block.getEra(), blockSlot, bn, blockHash, block));
            } catch (Throwable t) {
                log.warn("Account state reconcile: skip block {} due to: {}", bn, t.toString());
            }
        }
        log.info("Account state reconciled: replayed from {} to tip {}", lastAppliedBlock, tipBlock);
    }

    // --- Delta encoding ---

    public record DeltaOp(byte opType, byte[] key, byte[] prevValue) {}
    private record DecodedDelta(long slot, List<DeltaOp> ops) {}

    private byte[] encodeDelta(long slot, List<DeltaOp> ops) {
        // Format: slot(8) + numOps(4) + [opType(1) + keyLen(2) + key(N) + prevLen(2) + prev(M)]*
        int size = 8 + 4;
        for (DeltaOp op : ops) {
            size += 1 + 2 + op.key.length + 2 + (op.prevValue != null ? op.prevValue.length : 0);
        }

        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(slot);
        buf.putInt(ops.size());
        for (DeltaOp op : ops) {
            buf.put(op.opType);
            buf.putShort((short) op.key.length);
            buf.put(op.key);
            if (op.prevValue != null) {
                buf.putShort((short) op.prevValue.length);
                buf.put(op.prevValue);
            } else {
                buf.putShort((short) 0);
            }
        }
        return buf.array();
    }

    private DecodedDelta decodeDelta(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        long slot = buf.getLong();
        int numOps = buf.getInt();
        List<DeltaOp> ops = new ArrayList<>(numOps);
        for (int i = 0; i < numOps; i++) {
            byte opType = buf.get();
            int keyLen = buf.getShort() & 0xFFFF;
            byte[] key = new byte[keyLen];
            buf.get(key);
            int prevLen = buf.getShort() & 0xFFFF;
            byte[] prevValue = null;
            if (prevLen > 0) {
                prevValue = new byte[prevLen];
                buf.get(prevValue);
            }
            ops.add(new DeltaOp(opType, key, prevValue));
        }
        return new DecodedDelta(slot, ops);
    }

    private static byte[] totalDepositedToBytes(BigInteger value) {
        // Store as big-endian bytes, padded to at least 8 bytes
        byte[] raw = value.toByteArray();
        if (raw.length >= 8) return raw;
        byte[] padded = new byte[8];
        System.arraycopy(raw, 0, padded, 8 - raw.length, raw.length);
        return padded;
    }
}
