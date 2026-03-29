package com.bloxbean.cardano.yaci.node.ledgerstate;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * Tracks the AdaPot (treasury and reserves) per epoch.
 * <p>
 * Bootstrap: epoch 0 has treasury=0, reserves = maxSupply - genesisBalances.
 * Each epoch: reserves reduced by distributed rewards, treasury grows by τ × rewardsPot.
 * <p>
 * Stored in the acct_state CF under PREFIX_ADAPOT (0x52).
 */
public class AdaPotTracker {
    private static final Logger log = LoggerFactory.getLogger(AdaPotTracker.class);

    /** Max supply of ADA in lovelace: 45 billion ADA = 45_000_000_000 * 1_000_000 lovelace */
    public static final BigInteger MAX_SUPPLY_LOVELACE = new BigInteger("45000000000000000");

    private final RocksDB db;
    private final ColumnFamilyHandle cfState;
    private volatile boolean enabled;

    public AdaPotTracker(RocksDB db, ColumnFamilyHandle cfState, boolean enabled) {
        this.db = db;
        this.cfState = cfState;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Bootstrap the AdaPot for epoch 0.
     *
     * @param genesisUtxoTotal total lovelace in genesis UTXOs
     */
    public void bootstrap(int epoch, BigInteger genesisUtxoTotal) {
        if (!enabled) return;

        try {
            byte[] key = DefaultAccountStateStore.adaPotKey(epoch);
            byte[] existing = db.get(cfState, key);
            if (existing != null) {
                log.debug("AdaPot already bootstrapped for epoch {}", epoch);
                return;
            }

            BigInteger reserves = MAX_SUPPLY_LOVELACE.subtract(genesisUtxoTotal);
            var pot = new AccountStateCborCodec.AdaPot(
                    BigInteger.ZERO, // treasury
                    reserves,
                    BigInteger.ZERO, // deposits
                    BigInteger.ZERO, // fees
                    BigInteger.ZERO, // distributed
                    BigInteger.ZERO, // undistributed
                    BigInteger.ZERO, // rewardsPot
                    BigInteger.ZERO  // poolRewardsPot
            );

            db.put(cfState, key, AccountStateCborCodec.encodeAdaPot(pot));
            log.info("AdaPot bootstrapped for epoch {}: reserves={}", epoch, reserves);
        } catch (RocksDBException e) {
            log.error("Failed to bootstrap AdaPot: {}", e.toString());
        }
    }

    /**
     * Store the AdaPot for a given epoch.
     */
    public void storeAdaPot(int epoch, AccountStateCborCodec.AdaPot pot) {
        if (!enabled) return;
        try {
            byte[] key = DefaultAccountStateStore.adaPotKey(epoch);
            db.put(cfState, key, AccountStateCborCodec.encodeAdaPot(pot));
            log.info("AdaPot stored for epoch {}: treasury={}, reserves={}",
                    epoch, pot.treasury(), pot.reserves());
        } catch (RocksDBException e) {
            log.error("Failed to store AdaPot for epoch {}: {}", epoch, e.toString());
        }
    }

    /**
     * Get the AdaPot for a given epoch.
     */
    public Optional<AccountStateCborCodec.AdaPot> getAdaPot(int epoch) {
        try {
            byte[] key = DefaultAccountStateStore.adaPotKey(epoch);
            byte[] val = db.get(cfState, key);
            if (val == null) return Optional.empty();
            return Optional.of(AccountStateCborCodec.decodeAdaPot(val));
        } catch (RocksDBException e) {
            log.error("Failed to get AdaPot for epoch {}: {}", epoch, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Get the latest stored AdaPot by scanning backwards from the given epoch.
     */
    public Optional<AccountStateCborCodec.AdaPot> getLatestAdaPot(int maxEpoch) {
        for (int e = maxEpoch; e >= 0; e--) {
            var pot = getAdaPot(e);
            if (pot.isPresent()) return pot;
        }
        return Optional.empty();
    }
}
