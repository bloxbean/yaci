package com.bloxbean.cardano.yaci.node.ledgerstate;

import com.bloxbean.cardano.yaci.node.api.account.AccountStateStore;
import com.bloxbean.cardano.yaci.node.api.account.AccountStateStoreContext;
import com.bloxbean.cardano.yaci.node.api.account.AccountStateStoreProvider;
import com.bloxbean.cardano.yaci.node.api.db.RocksDbAccess;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;

/**
 * Built-in RocksDB-backed {@link AccountStateStoreProvider} (priority 0).
 * Available when the runtime {@link com.bloxbean.cardano.yaci.core.storage.ChainState} implements
 * {@link RocksDbAccess}.
 */
public class DefaultAccountStateStoreProvider implements AccountStateStoreProvider {

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public String name() {
        return "rocksdb";
    }

    @Override
    public boolean isAvailable(AccountStateStoreContext context) {
        return context.chainState() instanceof RocksDbAccess;
    }

    @Override
    public AccountStateStore create(AccountStateStoreContext context) {
        RocksDbAccess access = (RocksDbAccess) context.chainState();
        RocksDB db = (RocksDB) access.getDb();
        return new DefaultAccountStateStore(
                db,
                new DefaultAccountStateStore.CfSupplier() {
                    @Override
                    public ColumnFamilyHandle handle(String name) {
                        return (ColumnFamilyHandle) access.getColumnFamilyHandle(name);
                    }

                    @Override
                    public RocksDB db() {
                        return (RocksDB) access.getDb();
                    }
                },
                context.logger(), true, context.epochParamProvider());
    }
}
