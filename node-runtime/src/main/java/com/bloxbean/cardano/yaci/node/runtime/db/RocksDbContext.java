package com.bloxbean.cardano.yaci.node.runtime.db;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;

import java.util.Collections;
import java.util.Map;

public class RocksDbContext {
    private final RocksDB db;
    private final Map<String, ColumnFamilyHandle> handles;

    public RocksDbContext(RocksDB db, Map<String, ColumnFamilyHandle> handles) {
        this.db = db;
        this.handles = Collections.unmodifiableMap(handles);
    }

    public RocksDB db() {
        return db;
    }

    public ColumnFamilyHandle handle(String name) {
        return handles.get(name);
    }

    public Map<String, ColumnFamilyHandle> allHandles() {
        return handles;
    }
}

