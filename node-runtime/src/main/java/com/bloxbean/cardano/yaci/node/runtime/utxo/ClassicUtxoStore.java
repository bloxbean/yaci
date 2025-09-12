package com.bloxbean.cardano.yaci.node.runtime.utxo;

import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
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
public final class ClassicUtxoStore implements UtxoState, UtxoStoreWriter, Prunable, UtxoStatusProvider, AutoCloseable {
    private final RocksDB db;
    private final Logger log;
    private final boolean enabled;

    private final ColumnFamilyHandle cfUnspent;
    private final ColumnFamilyHandle cfSpent;
    private final ColumnFamilyHandle cfAddr;
    private final ColumnFamilyHandle cfDelta;
    private final ColumnFamilyHandle cfMeta;

    private final int pruneDepth;
    private final int rollbackWindow;
    private final int pruneBatchSize;
    private final boolean indexAddressHash;
    private final boolean indexPaymentCred;
    private final UtxoProcessor processor;
    // Metrics
    private final boolean metricsEnabled;
    private final java.util.concurrent.ScheduledExecutorService metricsScheduler;
    private final long rocksSampleMillis;
    private volatile long lastPruneMs = 0L;
    private volatile long lastDeltaDeleted = 0L;
    private volatile long lastSpentDeleted = 0L;
    private final java.util.ArrayDeque<Long> applyLatencies = new java.util.ArrayDeque<>();
    private final int applyLatencyWindow = 200;
    private volatile int lastApplyCreated = 0;
    private volatile int lastApplySpent = 0;
    private final java.util.ArrayDeque<Long> applyTimestamps = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Long> blockSizes = new java.util.ArrayDeque<>();
    private final int blockSizeWindow = 200;
    private volatile long lastBlockSize = 0L;
    private final java.util.concurrent.atomic.AtomicReference<java.util.Map<String, Long>> cfEstimates = new java.util.concurrent.atomic.AtomicReference<>(java.util.Map.of());

    public ClassicUtxoStore(RocksDbSupplier supplier,
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
        this.cfMeta = supplier.rocks().handle(com.bloxbean.cardano.yaci.node.runtime.db.UtxoCfNames.UTXO_META);

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

        this.processor = new ClassicUtxoProcessor(this.db);

        // Metrics setup
        this.metricsEnabled = getBool(config, "yaci.node.metrics.enabled", true);
        int sampleSec = getInt(config, "yaci.node.metrics.sample.rocksdb.seconds", 30);
        this.rocksSampleMillis = Math.max(0, sampleSec) * 1000L;
        if (metricsEnabled && rocksSampleMillis > 0) {
            this.metricsScheduler = java.util.concurrent.Executors.newScheduledThreadPool(1, java.lang.Thread.ofVirtual().factory());
            this.metricsScheduler.scheduleAtFixedRate(this::sampleCfEstimates, rocksSampleMillis, rocksSampleMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } else {
            this.metricsScheduler = null;
        }

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

    @Override
    public void applyBlock(BlockAppliedEvent e) {
        if (!enabled) return;
        if (e.block() == null) return; // header-only or EBB
        long t0 = System.nanoTime();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions(); UtxoProcessor.ApplyContext ctx = processor.prepare(e, cfUnspent)) {
            long slot = e.slot();
            long blockNo = e.blockNumber();
            String blockHash = e.blockHash();
            var block = e.block();
            // Capture block body size for metrics
            try {
                long bodySize = block.getHeader() != null && block.getHeader().getHeaderBody() != null
                        ? block.getHeader().getHeaderBody().getBlockBodySize() : 0L;
                if (metricsEnabled) {
                    lastBlockSize = bodySize;
                    synchronized (blockSizes) {
                        blockSizes.addLast(bodySize);
                        if (blockSizes.size() > blockSizeWindow) blockSizes.removeFirst();
                    }
                }
            } catch (Throwable ignored) {}

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
                            byte[] prev = ctx.getUnspent(key);
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
                            byte[] prev = ctx.getUnspent(key);
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
            // Update meta high-water marks atomically with the block apply
            batch.put(cfMeta, META_LAST_APPLIED_SLOT, ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(slot).array());
            batch.put(cfMeta, META_LAST_APPLIED_BLOCK, ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockNo).array());
            db.write(wo, batch);

            if (log.isDebugEnabled()) {
                log.debug("UTXO applied: block={} slot={} created={} spent={} era={}",
                        blockNo, slot, createdRefs.size(), spentRefs.size(), e.era());
            }
            if (metricsEnabled) {
                long dtMs = (System.nanoTime() - t0) / 1_000_000L;
                synchronized (applyLatencies) {
                    applyLatencies.addLast(dtMs);
                    if (applyLatencies.size() > applyLatencyWindow) applyLatencies.removeFirst();
                }
                lastApplyCreated = createdRefs.size();
                lastApplySpent = spentRefs.size();
                long now = System.currentTimeMillis();
                synchronized (applyTimestamps) {
                    applyTimestamps.addLast(now);
                    while (!applyTimestamps.isEmpty() && now - applyTimestamps.peekFirst() > 30_000L) applyTimestamps.removeFirst();
                }
            }
        } catch (Exception ex) {
            log.error("UTXO apply failed for block {}: {}", e.blockNumber(), ex.toString());
        }
    }

    @Override
    public void rollbackTo(RollbackEvent e) {
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
        if (metricsScheduler != null) metricsScheduler.shutdownNow();
    }

    @Override
    public void reconcile(ChainState chainState) {
        if (!enabled || chainState == null) return;
        long lastAppliedBlock = 0L;
        try {
            byte[] b = db.get(cfMeta, META_LAST_APPLIED_BLOCK);
            if (b != null && b.length == 8) lastAppliedBlock = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getLong();
        } catch (Exception ignored) {}

        ChainTip tip = chainState.getTip();
        if (tip == null) return;
        long tipBlock = tip.getBlockNumber();

        if (lastAppliedBlock == tipBlock) return; // in sync

        if (lastAppliedBlock > tipBlock) {
            // Roll back to tip slot (fork safe within rollback window)
            String hashHex = tip.getBlockHash() != null ? HexUtil.encodeHexString(tip.getBlockHash()) : null;
            rollbackTo(new com.bloxbean.cardano.yaci.node.runtime.events.RollbackEvent(
                    new com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point(tip.getSlot(), hashHex), true));
            return;
        }

        // Forward replay: apply missing blocks using stored bodies
        for (long bn = lastAppliedBlock + 1; bn <= tipBlock; bn++) {
            byte[] blockBytes = chainState.getBlockByNumber(bn);
            if (blockBytes == null) {
                // Body missing locally; skip and let live sync catch up
                continue;
            }
            Block block;
            try {
                block = BlockSerializer.INSTANCE.deserialize(blockBytes);
            } catch (Throwable t) {
                // If decode fails, skip this block; live sync will republish later
                continue;
            }
            long slot = block.getHeader().getHeaderBody().getSlot();
            String blockHash = block.getHeader().getHeaderBody().getBlockHash();
            Era era = block.getEra();
            applyBlock(new com.bloxbean.cardano.yaci.node.runtime.events.BlockAppliedEvent(era, slot, bn, blockHash, block));
        }
    }

    // ---- Prune Scheduler Support ----

    private static final byte[] META_LAST_APPLIED_SLOT = "meta.last_applied_slot".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] META_LAST_APPLIED_BLOCK = "meta.last_applied_block".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] META_PRUNE_DELTA_CURSOR = "prune.delta.cursor".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] META_PRUNE_SPENT_CURSOR = "prune.spent.cursor".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /**
     * Execute one bounded prune pass using persisted cursors, outside the hot apply path.
     * Uses lastAppliedSlot to compute safe cutoffs.
     */
    @Override
    public void pruneOnce() {
        if (!enabled) return;
        long t0 = System.nanoTime();
        long currentSlot = readLastAppliedSlot();
        if (currentSlot <= 0) return;
        long deltaCutoff = currentSlot - rollbackWindow;
        long spentRetentionWindow = Math.max(pruneDepth, rollbackWindow);
        long spentCutoff = currentSlot - spentRetentionWindow;

        // Deltas CF: sequential keys by block number; stop at cutoff
        long dd = pruneDeltasAndCount(deltaCutoff);
        // Spent CF: key order unrelated to slot; scan in slices across runs
        long sd = pruneSpentAndCount(spentCutoff);
        if (metricsEnabled) {
            lastPruneMs = (System.nanoTime() - t0) / 1_000_000L;
            lastDeltaDeleted = dd;
            lastSpentDeleted = sd;
        }
    }

    private long readLastAppliedSlot() {
        try {
            byte[] v = db.get(cfMeta, META_LAST_APPLIED_SLOT);
            if (v == null || v.length != 8) return 0L;
            return ByteBuffer.wrap(v).order(ByteOrder.BIG_ENDIAN).getLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    public long readLastAppliedBlock() {
        try {
            byte[] v = db.get(cfMeta, META_LAST_APPLIED_BLOCK);
            if (v == null || v.length != 8) return 0L;
            return ByteBuffer.wrap(v).order(ByteOrder.BIG_ENDIAN).getLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    // ---- UtxoStatusProvider ----
    @Override public String storeType() { return "classic"; }
    @Override public int getPruneDepth() { return pruneDepth; }
    @Override public int getRollbackWindow() { return rollbackWindow; }
    @Override public int getPruneBatchSize() { return pruneBatchSize; }
    @Override public long getLastAppliedBlock() { return readLastAppliedBlock(); }
    @Override public long getLastAppliedSlot() { return readLastAppliedSlot(); }
    @Override public byte[] getDeltaCursorKey() {
        try { return db.get(cfMeta, META_PRUNE_DELTA_CURSOR); } catch (Exception e) { return null; }
    }
    @Override public byte[] getSpentCursorKey() {
        try { return db.get(cfMeta, META_PRUNE_SPENT_CURSOR); } catch (Exception e) { return null; }
    }

    @Override
    public java.util.Map<String, Object> getMetrics() {
        if (!metricsEnabled) return java.util.Map.of();
        java.util.List<Long> snap;
        synchronized (applyLatencies) { snap = new java.util.ArrayList<>(applyLatencies); }
        double avg = 0, p95 = 0;
        if (!snap.isEmpty()) {
            long sum = 0; for (long v : snap) sum += v; avg = sum * 1.0 / snap.size();
            java.util.Collections.sort(snap);
            p95 = snap.get((int)Math.floor(0.95 * (snap.size() - 1)));
        }
        long now = System.currentTimeMillis();
        int within;
        synchronized (applyTimestamps) {
            while (!applyTimestamps.isEmpty() && now - applyTimestamps.peekFirst() > 30_000L) applyTimestamps.removeFirst();
            within = applyTimestamps.size();
        }
        double bps = within / 30.0;
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("apply.ms.avg", avg);
        m.put("apply.ms.p95", p95);
        m.put("apply.created.last", lastApplyCreated);
        m.put("apply.spent.last", lastApplySpent);
        m.put("throughput.blocksPerSec", bps);
        m.put("prune.ms.last", lastPruneMs);
        m.put("prune.deltaDeleted.last", lastDeltaDeleted);
        m.put("prune.spentDeleted.last", lastSpentDeleted);
        // Block size metrics
        long bsAvg = 0;
        java.util.List<Long> bsnap;
        synchronized (blockSizes) { bsnap = new java.util.ArrayList<>(blockSizes); }
        if (!bsnap.isEmpty()) {
            long sum = 0; for (long v : bsnap) sum += v; bsAvg = Math.round(sum * 1.0 / bsnap.size());
        }
        m.put("block.size.last", lastBlockSize);
        m.put("block.size.avg", bsAvg);
        return m;
    }

    @Override
    public java.util.Map<String, Long> getCfEstimates() {
        return cfEstimates.get();
    }

    private void sampleCfEstimates() {
        try {
            java.util.Map<String, Long> m = new java.util.HashMap<>();
            m.put("utxo_unspent.estimateNumKeys", parseEstimate(cfUnspent));
            m.put("utxo_spent.estimateNumKeys", parseEstimate(cfSpent));
            m.put("utxo_addr.estimateNumKeys", parseEstimate(cfAddr));
            m.put("utxo_block_delta.estimateNumKeys", parseEstimate(cfDelta));
            cfEstimates.set(java.util.Collections.unmodifiableMap(m));
        } catch (Throwable ignored) {}
    }

    private long parseEstimate(ColumnFamilyHandle cf) {
        try {
            String v = db.getProperty(cf, "rocksdb.estimate-num-keys");
            return Long.parseLong(v.trim());
        } catch (Exception e) { return -1L; }
    }

    private long pruneDeltasAndCount(long deltaCutoff) {
        int remaining = pruneBatchSize;
        byte[] cursor = null;
        try { cursor = db.get(cfMeta, META_PRUNE_DELTA_CURSOR); } catch (Exception ignored) {}
        long deleted = 0L;
        try (RocksIterator it = db.newIterator(cfDelta); WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            if (cursor != null) {
                it.seek(cursor);
                if (it.isValid() && java.util.Arrays.equals(it.key(), cursor)) it.next();
            } else {
                it.seekToFirst();
            }
            byte[] lastProcessed = cursor;
            while (it.isValid() && remaining > 0) {
                byte[] k = it.key();
                byte[] v = it.value();
                var dec = UtxoDeltaCodec.decode(v);
                if (dec.slot() <= deltaCutoff) {
                    batch.delete(cfDelta, k);
                    lastProcessed = k;
                    remaining--;
                    deleted++;
                    it.next();
                } else {
                    break;
                }
            }
            if (remaining != pruneBatchSize) {
                db.write(wo, batch);
            }
            // Persist cursor (last processed). If reached end, keep the last key; next run will seek and advance.
            if (lastProcessed != null) {
                try (WriteBatch mb = new WriteBatch(); WriteOptions mwo = new WriteOptions()) {
                    mb.put(cfMeta, META_PRUNE_DELTA_CURSOR, lastProcessed);
                    db.write(mwo, mb);
                }
            }
        } catch (Exception ignored) {
        }
        return deleted;
    }

    private long pruneSpentAndCount(long spentCutoff) {
        int remaining = pruneBatchSize;
        byte[] cursor = null;
        try { cursor = db.get(cfMeta, META_PRUNE_SPENT_CURSOR); } catch (Exception ignored) {}
        boolean wrapped = false;
        long deleted = 0L;
        try (RocksIterator it = db.newIterator(cfSpent); WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            if (cursor != null) {
                it.seek(cursor);
                if (it.isValid() && java.util.Arrays.equals(it.key(), cursor)) it.next();
            } else {
                it.seekToFirst();
            }
            byte[] lastProcessed = cursor;
            while (remaining > 0) {
                if (!it.isValid()) {
                    if (wrapped) break; // completed a full pass
                    // wrap to start and continue
                    it.seekToFirst();
                    wrapped = true;
                    if (!it.isValid()) break;
                }
                byte[] k = it.key();
                byte[] v = it.value();
                try {
                    Map m = (Map) com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.deserializeOne(v);
                    co.nstant.in.cbor.model.DataItem d = m.get(new UnsignedInteger(1));
                    long s = d != null ? com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toLong(d) : 0L;
                    if (s > 0 && s <= spentCutoff) {
                        batch.delete(cfSpent, k);
                        remaining--;
                        deleted++;
                    }
                } catch (Exception ignore) {}
                lastProcessed = k;
                it.next();
            }
            if (remaining != pruneBatchSize) {
                db.write(wo, batch);
            }
            // Update cursor. If we wrapped and reached end of pass without progress, clear cursor.
            try (WriteBatch mb = new WriteBatch(); WriteOptions mwo = new WriteOptions()) {
                if (lastProcessed != null) mb.put(cfMeta, META_PRUNE_SPENT_CURSOR, lastProcessed);
                db.write(mwo, mb);
            }
        } catch (Exception ignored) {
        }
        return deleted;
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


    // ---- end prune support ----
}
