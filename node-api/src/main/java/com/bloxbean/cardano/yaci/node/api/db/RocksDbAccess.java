package com.bloxbean.cardano.yaci.node.api.db;

/**
 * Thin interface exposing RocksDB handles without requiring {@code org.rocksdb} on the compile path.
 * Implemented by {@code DirectRocksDBChainState}. Used by SPI providers that need RocksDB access.
 *
 * <p>Returns {@link Object} types so that {@code node-api} stays free of the {@code org.rocksdb} dependency.
 * Consumers in modules that depend on {@code org.rocksdb} (e.g., {@code ledger-state}) cast to the
 * concrete RocksDB types.
 */
public interface RocksDbAccess {

    /** Returns the {@code org.rocksdb.RocksDB} instance. */
    Object getDb();

    /** Returns the {@code org.rocksdb.ColumnFamilyHandle} for the given column family name, or null. */
    Object getColumnFamilyHandle(String cfName);
}
