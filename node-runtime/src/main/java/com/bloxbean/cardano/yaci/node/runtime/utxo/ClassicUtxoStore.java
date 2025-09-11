package com.bloxbean.cardano.yaci.node.runtime.utxo;

import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yaci.node.api.utxo.model.AssetAmount;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo;
import com.bloxbean.cardano.yaci.node.runtime.db.RocksDbSupplier;
import com.bloxbean.cardano.yaci.node.runtime.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.runtime.events.RollbackEvent;
import org.rocksdb.*;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Classic UTXO store backed by RocksDB column families.
 * Listens to BlockAppliedEvent and RollbackEvent, applies compact deltas.
 */
public final class ClassicUtxoStore implements UtxoState, AutoCloseable {
    private final RocksDB db;
    private final Logger log;
    private final boolean enabled;

    private final ColumnFamilyHandle cfUnspent;
    private final ColumnFamilyHandle cfSpent;
    private final ColumnFamilyHandle cfAddr;
    private final ColumnFamilyHandle cfDelta;

    private final int pruneDepth;
    private final int rollbackWindow;
    private final int pruneBatchSize;
    private final boolean indexAddressHash;
    private final boolean indexPaymentCred;

    public ClassicUtxoStore(RocksDbSupplier supplier,
                            com.bloxbean.cardano.yaci.events.api.EventBus bus,
                            Logger logger,
                            java.util.Map<String, Object> config) {
        this.db = supplier.rocks().db();
        this.log = logger;
        Object ev = config != null ? config.getOrDefault("yaci.node.utxo.enabled", Boolean.TRUE) : Boolean.TRUE;
        this.enabled = (ev instanceof Boolean b) ? b : Boolean.parseBoolean(String.valueOf(ev));

        this.cfUnspent = supplier.rocks().handle(com.bloxbean.cardano.yaci.node.runtime.db.UtxoCfNames.UTXO_UNSPENT);
        this.cfSpent = supplier.rocks().handle(com.bloxbean.cardano.yaci.node.runtime.db.UtxoCfNames.UTXO_SPENT);
        this.cfAddr = supplier.rocks().handle(com.bloxbean.cardano.yaci.node.runtime.db.UtxoCfNames.UTXO_ADDR);
        this.cfDelta = supplier.rocks().handle(com.bloxbean.cardano.yaci.node.runtime.db.UtxoCfNames.UTXO_BLOCK_DELTA);

        this.pruneDepth = getInt(config, "yaci.node.utxo.pruneDepth", 2160);
        this.rollbackWindow = getInt(config, "yaci.node.utxo.rollbackWindow", 4320);
        this.pruneBatchSize = getInt(config, "yaci.node.utxo.pruneBatchSize", 500);
        // Indexing strategy
        boolean addrIdx = getBool(config, "yaci.node.utxo.index.address_hash", true);
        boolean payCredIdx = getBool(config, "yaci.node.utxo.index.payment_credential", true);
        Object strat = config != null ? config.get("yaci.node.utxo.indexingStrategy") : null;
        if (strat != null) {
            String s = String.valueOf(strat);
            if ("address_hash".equalsIgnoreCase(s)) {
                addrIdx = true;
                payCredIdx = false;
            } else if ("payment_credential".equalsIgnoreCase(s)) {
                addrIdx = false;
                payCredIdx = true;
            }
        }
        this.indexAddressHash = addrIdx;
        this.indexPaymentCred = payCredIdx;

        // Register listeners
        SubscriptionOptions defaults = SubscriptionOptions.builder().build();
        AnnotationListenerRegistrar.register(bus, this, defaults);
        log.info("ClassicUtxoStore initialized (enabled={})", enabled);
    }

    @Override
    public List<Utxo> getUtxosByAddress(String bech32OrHexAddress, int page, int pageSize) {
        if (!enabled) return List.of();
        try {
            if (page < 1 || pageSize <= 0) return List.of();
            byte[] addrKey = UtxoKeyUtil.addrHash28(bech32OrHexAddress);
            try (RocksIterator it = db.newIterator(cfAddr)) {
                it.seek(addrKey);
                int skipped = (page - 1) * pageSize;
                List<Utxo> results = new ArrayList<>();
                while (it.isValid()) {
                    byte[] key = it.key();
                    boolean match = true;
                    for (int i = 0; i < 28; i++) {
                        if (key[i] != addrKey[i]) {
                            match = false;
                            break;
                        }
                    }
                    if (!match) break;
                    if (skipped > 0) {
                        skipped--;
                        it.next();
                        continue;
                    }
                    if (results.size() >= pageSize) break;
                    // suffix: 28 addr | 8 slot | 32 hash | 2 idx
                    int off = 28 + 8;
                    String txHash = com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(java.util.Arrays.copyOfRange(key, off, off + 32));
                    int idx = ByteBuffer.wrap(key, off + 32, 2).order(ByteOrder.BIG_ENDIAN).getShort() & 0xffff;
                    byte[] ukey = UtxoKeyUtil.outpointKey(txHash, idx);
                    byte[] val = db.get(cfUnspent, ukey);
                    if (val != null) {
                        var stored = UtxoCborCodec.decodeUtxoRecord(val);
                        List<AssetAmount> amts = new ArrayList<>();
                        if (stored.assets != null) {
                            for (com.bloxbean.cardano.yaci.core.model.Amount a : stored.assets) {
                                if (a.getPolicyId() == null) continue;
                                String nameHex = a.getAssetNameBytes() != null ? com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(a.getAssetNameBytes()) : null;
                                amts.add(new AssetAmount(a.getPolicyId(), nameHex, a.getQuantity()));
                            }
                        }
                        Utxo dto = new Utxo(new Outpoint(txHash, idx), stored.address, stored.lovelace,
                                amts, stored.datumHash, stored.inlineDatum, null,
                                stored.collateralReturn, stored.slot, stored.blockNumber, stored.blockHash);
                        results.add(dto);
                    }
                    it.next();
                }
                return results;
            }
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public List<Utxo> getUtxosByPaymentCredential(String credentialHexOrAddress, int page, int pageSize) {
        if (!enabled) return List.of();
        try {
            if (page < 1 || pageSize <= 0) return List.of();
            // Derive 28-byte prefix from credential hex or address
            byte[] prefix = UtxoKeyUtil.hex28(credentialHexOrAddress);
            if (prefix == null) {
                // Try to extract from address
                prefix = UtxoKeyUtil.paymentCred28(credentialHexOrAddress);
            }
            if (prefix == null) return List.of();

            try (RocksIterator it = db.newIterator(cfAddr)) {
                it.seek(prefix);
                int skipped = (page - 1) * pageSize;
                List<Utxo> results = new ArrayList<>();
                while (it.isValid()) {
                    byte[] key = it.key();
                    boolean match = true;
                    for (int i = 0; i < 28; i++) {
                        if (key[i] != prefix[i]) {
                            match = false;
                            break;
                        }
                    }
                    if (!match) break;
                    if (skipped > 0) {
                        skipped--;
                        it.next();
                        continue;
                    }
                    if (results.size() >= pageSize) break;
                    int off = 28 + 8;
                    String txHash = com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(java.util.Arrays.copyOfRange(key, off, off + 32));
                    int idx = ByteBuffer.wrap(key, off + 32, 2).order(ByteOrder.BIG_ENDIAN).getShort() & 0xffff;
                    byte[] ukey = UtxoKeyUtil.outpointKey(txHash, idx);
                    byte[] val = db.get(cfUnspent, ukey);
                    if (val != null) {
                        var stored = UtxoCborCodec.decodeUtxoRecord(val);
                        List<AssetAmount> amts = new ArrayList<>();
                        if (stored.assets != null) {
                            for (com.bloxbean.cardano.yaci.core.model.Amount a : stored.assets) {
                                if (a.getPolicyId() == null) continue;
                                String nameHex = a.getAssetNameBytes() != null ? com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(a.getAssetNameBytes()) : null;
                                amts.add(new AssetAmount(a.getPolicyId(), nameHex, a.getQuantity()));
                            }
                        }
                        Utxo dto = new Utxo(new Outpoint(txHash, idx), stored.address, stored.lovelace,
                                amts, stored.datumHash, stored.inlineDatum, null,
                                stored.collateralReturn, stored.slot, stored.blockNumber, stored.blockHash);
                        results.add(dto);
                    }
                    it.next();
                }
                return results;
            }
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public Optional<Utxo> getUtxo(Outpoint outpoint) {
        if (!enabled) return Optional.empty();
        try {
            byte[] key = UtxoKeyUtil.outpointKey(outpoint.txHash(), outpoint.index());
            byte[] val = db.get(cfUnspent, key);
            if (val == null) return Optional.empty();
            var stored = UtxoCborCodec.decodeUtxoRecord(val);
            List<AssetAmount> amts = new ArrayList<>();
            if (stored.assets != null) {
                for (com.bloxbean.cardano.yaci.core.model.Amount a : stored.assets) {
                    if (a.getPolicyId() == null) continue;
                    String nameHex = a.getAssetNameBytes() != null ? com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(a.getAssetNameBytes()) : null;
                    amts.add(new AssetAmount(a.getPolicyId(), nameHex, a.getQuantity()));
                }
            }
            Utxo dto = new Utxo(outpoint, stored.address, stored.lovelace,
                    amts, stored.datumHash, stored.inlineDatum, null,
                    stored.collateralReturn, stored.slot, stored.blockNumber, stored.blockHash);
            return Optional.of(dto);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @DomainEventListener(order = 100)
    public void onBlockApplied(BlockAppliedEvent e) {
        if (!enabled) return;
        if (e.block() == null) return; // header-only or EBB
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            long slot = e.slot();
            long blockNo = e.blockNumber();
            String blockHash = e.blockHash();
            var block = e.block();

            java.util.List<Integer> invList = block.getInvalidTransactions();
            java.util.Set<Integer> invalidIdx = (invList != null)
                    ? new java.util.HashSet<>(invList)
                    : java.util.Collections.emptySet();
            List<com.bloxbean.cardano.yaci.core.model.TransactionBody> txs = block.getTransactionBodies();
            List<UtxoDeltaCodec.OutRef> createdRefs = new ArrayList<>();
            List<UtxoDeltaCodec.OutRef> spentRefs = new ArrayList<>();

            for (int i = 0; i < txs.size(); i++) {
                var tx = txs.get(i);
                boolean invalid = invalidIdx.contains(i);
                if (!invalid) {
                    if (tx.getInputs() != null) {
                        for (var in : tx.getInputs()) {
                            byte[] key = UtxoKeyUtil.outpointKey(in.getTransactionId(), in.getIndex());
                            byte[] prev = db.get(cfUnspent, key);
                            if (prev != null) {
                                Map spentMap = new Map();
                                spentMap.put(new UnsignedInteger(6), com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.deserializeOne(prev));
                                spentMap.put(new UnsignedInteger(1), new UnsignedInteger(slot));
                                byte[] spentVal = com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.serialize(spentMap, true);
                                batch.put(cfSpent, key, spentVal);
                                batch.delete(cfUnspent, key);
                                var stored = UtxoCborCodec.decodeUtxoRecord(prev);
                                if (indexAddressHash) {
                                    byte[] akey = UtxoKeyUtil.addrHash28(stored.address);
                                    byte[] aIdx = UtxoKeyUtil.addressIndexKey(akey, stored.slot, in.getTransactionId(), in.getIndex());
                                    batch.delete(cfAddr, aIdx);
                                }
                                if (indexPaymentCred) {
                                    byte[] pc = UtxoKeyUtil.paymentCred28(stored.address);
                                    if (pc != null) {
                                        byte[] pIdx = UtxoKeyUtil.addressIndexKey(pc, stored.slot, in.getTransactionId(), in.getIndex());
                                        batch.delete(cfAddr, pIdx);
                                    }
                                }
                                spentRefs.add(new UtxoDeltaCodec.OutRef(in.getTransactionId(), in.getIndex()));
                            }
                        }
                    }
                    if (tx.getOutputs() != null) {
                        for (int outIdx = 0; outIdx < tx.getOutputs().size(); outIdx++) {
                            var out = tx.getOutputs().get(outIdx);
                            java.math.BigInteger lovelace = java.math.BigInteger.ZERO;
                            var amounts = out.getAmounts();
                            if (amounts != null) for (com.bloxbean.cardano.yaci.core.model.Amount a : amounts)
                                if ("lovelace".equals(a.getUnit())) lovelace = a.getQuantity();
                            byte[] val = UtxoCborCodec.encodeUtxoRecord(out.getAddress(), lovelace, amounts,
                                    out.getDatumHash(), out.getInlineDatum() != null ? com.bloxbean.cardano.yaci.core.util.HexUtil.decodeHexString(out.getInlineDatum()) : null,
                                    out.getScriptRef(), null, false, slot, blockNo, blockHash);
                            byte[] outKey = UtxoKeyUtil.outpointKey(tx.getTxHash(), outIdx);
                            batch.put(cfUnspent, outKey, val);
                            //log.info("UTXO created: {}:{}", tx.getTxHash(), outIdx);
                            if (indexAddressHash) {
                                byte[] addrHash = UtxoKeyUtil.addrHash28(out.getAddress());
                                byte[] addrIdxKey = UtxoKeyUtil.addressIndexKey(addrHash, slot, tx.getTxHash(), outIdx);
                                batch.put(cfAddr, addrIdxKey, new byte[0]);
                            }
                            if (indexPaymentCred) {
                                byte[] pc = UtxoKeyUtil.paymentCred28(out.getAddress());
                                if (pc != null) {
                                    byte[] pIdx = UtxoKeyUtil.addressIndexKey(pc, slot, tx.getTxHash(), outIdx);
                                    batch.put(cfAddr, pIdx, new byte[0]);
                                }
                            }
                            createdRefs.add(new UtxoDeltaCodec.OutRef(tx.getTxHash(), outIdx));
                        }
                    }
                } else {
                    if (tx.getCollateralInputs() != null) {
                        for (var in : tx.getCollateralInputs()) {
                            byte[] key = UtxoKeyUtil.outpointKey(in.getTransactionId(), in.getIndex());
                            byte[] prev = db.get(cfUnspent, key);
                            if (prev != null) {
                                Map spentMap = new Map();
                                spentMap.put(new UnsignedInteger(6), com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.deserializeOne(prev));
                                spentMap.put(new UnsignedInteger(1), new UnsignedInteger(slot));
                                byte[] spentVal = com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.serialize(spentMap, true);
                                batch.put(cfSpent, key, spentVal);
                                batch.delete(cfUnspent, key);
                                var stored = UtxoCborCodec.decodeUtxoRecord(prev);
                                if (indexAddressHash) {
                                    byte[] akey = UtxoKeyUtil.addrHash28(stored.address);
                                    byte[] aIdx = UtxoKeyUtil.addressIndexKey(akey, stored.slot, in.getTransactionId(), in.getIndex());
                                    batch.delete(cfAddr, aIdx);
                                }
                                if (indexPaymentCred) {
                                    byte[] pc = UtxoKeyUtil.paymentCred28(stored.address);
                                    if (pc != null) {
                                        byte[] pIdx = UtxoKeyUtil.addressIndexKey(pc, stored.slot, in.getTransactionId(), in.getIndex());
                                        batch.delete(cfAddr, pIdx);
                                    }
                                }
                                spentRefs.add(new UtxoDeltaCodec.OutRef(in.getTransactionId(), in.getIndex()));
                            }
                        }
                    }
                    if (tx.getCollateralReturn() != null) {
                        var out = tx.getCollateralReturn();
                        java.math.BigInteger lovelace = java.math.BigInteger.ZERO;
                        var amounts = out.getAmounts();
                        if (amounts != null) for (com.bloxbean.cardano.yaci.core.model.Amount a : amounts)
                            if ("lovelace".equals(a.getUnit())) lovelace = a.getQuantity();
                        int outIdx = tx.getOutputs() != null ? tx.getOutputs().size() : 0;
                        byte[] val = UtxoCborCodec.encodeUtxoRecord(out.getAddress(), lovelace, amounts,
                                out.getDatumHash(), out.getInlineDatum() != null ? com.bloxbean.cardano.yaci.core.util.HexUtil.decodeHexString(out.getInlineDatum()) : null,
                                out.getScriptRef(), null, true, slot, blockNo, blockHash);
                        byte[] outKey = UtxoKeyUtil.outpointKey(tx.getTxHash(), outIdx);
                        batch.put(cfUnspent, outKey, val);
                        if (indexAddressHash) {
                            byte[] addrHash = UtxoKeyUtil.addrHash28(out.getAddress());
                            byte[] addrIdxKey = UtxoKeyUtil.addressIndexKey(addrHash, slot, tx.getTxHash(), outIdx);
                            batch.put(cfAddr, addrIdxKey, new byte[0]);
                        }
                        if (indexPaymentCred) {
                            byte[] pc = UtxoKeyUtil.paymentCred28(out.getAddress());
                            if (pc != null) {
                                byte[] pIdx = UtxoKeyUtil.addressIndexKey(pc, slot, tx.getTxHash(), outIdx);
                                batch.put(cfAddr, pIdx, new byte[0]);
                            }
                        }
                        createdRefs.add(new UtxoDeltaCodec.OutRef(tx.getTxHash(), outIdx));
                    }
                }
            }

            // Write delta
            byte[] dval = UtxoDeltaCodec.encode(blockNo, slot, blockHash, createdRefs, spentRefs);
            byte[] dkey = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockNo).array();
            batch.put(cfDelta, dkey, dval);
            db.write(wo, batch);

            // Bounded prune
            prune(slot);

            if (log.isDebugEnabled()) {
                log.debug("UTXO applied: block={} slot={} created={} spent={} era={}",
                        blockNo, slot, createdRefs.size(), spentRefs.size(), e.era());
            }
        } catch (Exception ex) {
            log.error("UTXO apply failed for block {}: {}", e.blockNumber(), ex.toString());
        }
    }

    @DomainEventListener(order = 100)
    public void onRollback(RollbackEvent e) {
        if (!enabled) return;
        long targetSlot = e.target().getSlot();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions(); RocksIterator it = db.newIterator(cfDelta)) {
            it.seekToLast();
            while (it.isValid()) {
                var dec = UtxoDeltaCodec.decode(it.value());
                if (dec.slot() <= targetSlot) break;
                // Delete created
                for (UtxoDeltaCodec.OutRef r : dec.created()) {
                    byte[] okey = UtxoKeyUtil.outpointKey(r.txHash(), r.index());
                    byte[] prev = db.get(cfUnspent, okey);
                    if (prev != null) {
                        var stored = UtxoCborCodec.decodeUtxoRecord(prev);
                        if (indexAddressHash) {
                            byte[] akey = UtxoKeyUtil.addrHash28(stored.address);
                            byte[] aIdx = UtxoKeyUtil.addressIndexKey(akey, stored.slot, r.txHash(), r.index());
                            batch.delete(cfAddr, aIdx);
                        }
                        if (indexPaymentCred) {
                            byte[] pc = UtxoKeyUtil.paymentCred28(stored.address);
                            if (pc != null) {
                                byte[] pIdx = UtxoKeyUtil.addressIndexKey(pc, stored.slot, r.txHash(), r.index());
                                batch.delete(cfAddr, pIdx);
                            }
                        }
                        batch.delete(cfUnspent, okey);
                    }
                }
                // Restore spent
                for (UtxoDeltaCodec.OutRef r : dec.spent()) {
                    byte[] okey = UtxoKeyUtil.outpointKey(r.txHash(), r.index());
                    byte[] sval = db.get(cfSpent, okey);
                    if (sval != null) {
                        Map m = (Map) com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.deserializeOne(sval);
                        co.nstant.in.cbor.model.DataItem di = m.get(new UnsignedInteger(6));
                        byte[] unspentVal = com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.serialize(di, true);
                        batch.put(cfUnspent, okey, unspentVal);
                        var stored = UtxoCborCodec.decodeUtxoRecord(unspentVal);
                        if (indexAddressHash) {
                            byte[] akey = UtxoKeyUtil.addrHash28(stored.address);
                            byte[] aIdx = UtxoKeyUtil.addressIndexKey(akey, stored.slot, r.txHash(), r.index());
                            batch.put(cfAddr, aIdx, new byte[0]);
                        }
                        if (indexPaymentCred) {
                            byte[] pc = UtxoKeyUtil.paymentCred28(stored.address);
                            if (pc != null) {
                                byte[] pIdx = UtxoKeyUtil.addressIndexKey(pc, stored.slot, r.txHash(), r.index());
                                batch.put(cfAddr, pIdx, new byte[0]);
                            }
                        }
                        batch.delete(cfSpent, okey);
                    }
                }
                batch.delete(cfDelta, it.key());
                it.prev();
            }
            db.write(wo, batch);
        } catch (Exception ex) {
            log.error("UTXO rollback failed: {}", ex.toString());
        }
    }

    @Override
    public void close() {
    }

    private static int getInt(java.util.Map<String, Object> cfg, String key, int def) {
        Object v = cfg != null ? cfg.get(key) : null;
        if (v instanceof Number n) return n.intValue();
        if (v != null) try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception ignored) {
        }
        return def;
    }

    private static boolean getBool(java.util.Map<String, Object> cfg, String key, boolean def) {
        Object v = cfg != null ? cfg.get(key) : null;
        if (v instanceof Boolean b) return b;
        if (v != null) {
            String s = String.valueOf(v);
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        return def;
    }


    private void prune(long currentSlot) {
        // Keep spent entries at least as long as deltas to guarantee compact-delta rollback
        // Effective windows
        long deltaCutoff = currentSlot - rollbackWindow;
        long spentRetentionWindow = Math.max(pruneDepth, rollbackWindow);
        long spentCutoff = currentSlot - spentRetentionWindow;
        if (rollbackWindow > pruneDepth) {
            log.debug("UTXO prune windows: spent retained for {} slots (>= rollbackWindow {}), deltas retained for {} slots",
                    spentRetentionWindow, rollbackWindow, rollbackWindow);
        }
        int remaining = pruneBatchSize;
        // Deltas
        try (RocksIterator it = db.newIterator(cfDelta); WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            it.seekToFirst();
            while (it.isValid() && remaining > 0) {
                var dec = UtxoDeltaCodec.decode(it.value());
                if (dec.slot() <= deltaCutoff) {
                    batch.delete(cfDelta, it.key());
                    remaining--;
                }
                it.next();
            }
            if (remaining != pruneBatchSize) db.write(wo, batch);
        } catch (Exception ignored) {
        }

        // Spent by spentSlot
        remaining = pruneBatchSize;
        try (RocksIterator it = db.newIterator(cfSpent); WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            it.seekToFirst();
            while (it.isValid() && remaining > 0) {
                Map m = (Map) com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.deserializeOne(it.value());
                co.nstant.in.cbor.model.DataItem d = m.get(new UnsignedInteger(1));
                long s = d != null ? com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toLong(d) : 0L;
                if (s > 0 && s <= spentCutoff) {
                    batch.delete(cfSpent, it.key());
                    remaining--;
                }
                it.next();
            }
            if (remaining != pruneBatchSize) db.write(wo, batch);
        } catch (Exception ignored) {
        }
    }
}
