package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.node.runtime.db.RocksDbSupplier;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Factory to select UTXO backend implementation (classic|mmr) from config.
 */
public final class UtxoStoreFactory {
    private UtxoStoreFactory() {}

    public static UtxoStoreWriter create(RocksDbSupplier rocks, Logger log, Map<String, Object> cfg) {
        Object storeOpt = cfg != null ? cfg.getOrDefault("yaci.node.utxo.store", "classic") : "classic";
        String name = String.valueOf(storeOpt);
        if ("mmr".equalsIgnoreCase(name)) {
            return new MmrUtxoStore(rocks, log, cfg);
        } else {
            return new ClassicUtxoStore(rocks, log, cfg);
        }
    }
}

