package com.bloxbean.cardano.yaci.node.ledgerstate.test;

import com.bloxbean.cardano.yaci.node.ledgerstate.AccountStateCfNames;
import com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceStateStore;
import org.rocksdb.*;

import java.nio.file.Path;
import java.util.*;

/**
 * Test utility that creates a temporary RocksDB instance with the column families
 * used by ledger-state production code. Use with JUnit 5 {@code @TempDir}.
 *
 * <pre>
 * {@code @TempDir Path tempDir;}
 *
 * try (var rocks = TestRocksDBHelper.create(tempDir)) {
 *     GovernanceStateStore store = rocks.governanceStore();
 *     // ... test code
 * }
 * </pre>
 */
public class TestRocksDBHelper implements AutoCloseable {

    private final RocksDB db;
    private final Map<String, ColumnFamilyHandle> cfHandles;
    private final List<ColumnFamilyHandle> allHandles;

    private TestRocksDBHelper(RocksDB db, Map<String, ColumnFamilyHandle> cfHandles,
                               List<ColumnFamilyHandle> allHandles) {
        this.db = db;
        this.cfHandles = cfHandles;
        this.allHandles = allHandles;
    }

    /**
     * Create a new temporary RocksDB with all required column families.
     *
     * @param tempDir JUnit {@code @TempDir} path (auto-cleaned after test)
     */
    public static TestRocksDBHelper create(Path tempDir) throws RocksDBException {
        RocksDB.loadLibrary();

        List<String> cfNames = List.of(
                new String(RocksDB.DEFAULT_COLUMN_FAMILY),
                AccountStateCfNames.ACCT_STATE,
                AccountStateCfNames.ACCT_DELTA,
                AccountStateCfNames.EPOCH_DELEG_SNAPSHOT
        );

        List<ColumnFamilyDescriptor> cfDescriptors = cfNames.stream()
                .map(name -> new ColumnFamilyDescriptor(name.getBytes()))
                .toList();

        List<ColumnFamilyHandle> handles = new ArrayList<>();
        DBOptions dbOptions = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
        RocksDB db = RocksDB.open(dbOptions, tempDir.resolve("testdb").toString(), cfDescriptors, handles);

        Map<String, ColumnFamilyHandle> handleMap = new HashMap<>();
        for (int i = 0; i < cfNames.size(); i++) {
            handleMap.put(cfNames.get(i), handles.get(i));
        }

        return new TestRocksDBHelper(db, handleMap, handles);
    }

    public RocksDB db() { return db; }

    public ColumnFamilyHandle cf(String name) {
        ColumnFamilyHandle h = cfHandles.get(name);
        if (h == null) throw new IllegalArgumentException("Unknown CF: " + name);
        return h;
    }

    /** Column family used by both account state and governance stores. */
    public ColumnFamilyHandle cfState() { return cf(AccountStateCfNames.ACCT_STATE); }
    public ColumnFamilyHandle cfDelta() { return cf(AccountStateCfNames.ACCT_DELTA); }
    public ColumnFamilyHandle cfSnapshot() { return cf(AccountStateCfNames.EPOCH_DELEG_SNAPSHOT); }

    /** Create a GovernanceStateStore backed by this RocksDB instance. */
    public GovernanceStateStore governanceStore() {
        return new GovernanceStateStore(db, cfState());
    }

    /** Create a CfSupplier for DefaultAccountStateStore. */
    public DefaultAccountStateStore.CfSupplier cfSupplier() {
        return name -> cf(name);
    }

    @Override
    public void close() {
        for (ColumnFamilyHandle h : allHandles) {
            h.close();
        }
        db.close();
    }
}
