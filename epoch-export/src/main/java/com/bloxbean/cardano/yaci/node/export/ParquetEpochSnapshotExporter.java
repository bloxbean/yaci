package com.bloxbean.cardano.yaci.node.export;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Bech32;
import com.bloxbean.cardano.client.governance.GovId;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * Exports epoch boundary data to Parquet files using DuckDB JDBC.
 * <p>
 * Files are written in Hive-style partitioning: {@code data/epoch=250/drep_dist.parquet}.
 * DuckDB auto-discovers the epoch column from the directory name when querying across epochs:
 * {@code SELECT * FROM 'data/epoch=* /drep_dist.parquet'}
 * <p>
 * Includes human-readable bech32 addresses (stake_address, pool_id, drep_id) derived from
 * raw credential hashes using CCL AddressProvider.
 * <p>
 * Discovered via {@link java.util.ServiceLoader} when the epoch-export module is on the classpath.
 */
public class ParquetEpochSnapshotExporter implements EpochSnapshotExporter {
    private static final Logger log = LoggerFactory.getLogger(ParquetEpochSnapshotExporter.class);

    private String outputDir;
    private Network network;

    public ParquetEpochSnapshotExporter() {
        this("data");
    }

    public ParquetEpochSnapshotExporter(String outputDir) {
        this.outputDir = outputDir;
        this.network = Networks.preprod(); // default, overridden by setNetworkMagic
    }

    @Override
    public void setOutputDir(String dir) {
        if (dir != null && !dir.isEmpty()) {
            this.outputDir = dir;
        }
    }

    @Override
    public void setNetworkMagic(long magic) {
        this.network = (magic == 764824073) ? Networks.mainnet() : Networks.testnet();
    }

    @Override
    public void exportStakeSnapshot(int epoch, List<StakeEntry> entries) {
        try {
            String dir = epochDir(epoch);
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
                Statement stmt = conn.createStatement();
                stmt.execute("CREATE TABLE t (cred_type INT, cred_hash VARCHAR, stake_address VARCHAR, " +
                        "pool_hash VARCHAR, pool_id VARCHAR, amount DECIMAL(38,0))");
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?,?,?,?,?,?)")) {
                    for (var e : entries) {
                        ps.setInt(1, e.credType());
                        ps.setString(2, e.credHash());
                        ps.setString(3, toStakeAddress(e.credType(), e.credHash()));
                        ps.setString(4, e.poolHash());
                        ps.setString(5, toPoolId(e.poolHash()));
                        ps.setBigDecimal(6, new BigDecimal(e.amount()));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                stmt.execute("COPY t TO '" + dir + "/epoch_stake.parquet' (FORMAT PARQUET, COMPRESSION ZSTD)");
            }
            log.info("Exported stake snapshot for epoch {} ({} entries) to {}", epoch, entries.size(), dir);
        } catch (Exception e) {
            log.warn("Failed to export stake snapshot for epoch {}: {}", epoch, e.getMessage());
        }
    }

    @Override
    public void exportDRepDistribution(int epoch, List<DRepDistEntry> entries) {
        try {
            String dir = epochDir(epoch);
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
                Statement stmt = conn.createStatement();
                stmt.execute("CREATE TABLE t (drep_type INT, drep_hash VARCHAR, drep_id VARCHAR, amount DECIMAL(38,0))");
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?,?,?,?)")) {
                    for (var e : entries) {
                        ps.setInt(1, e.drepType());
                        ps.setString(2, e.drepHash());
                        ps.setString(3, toDRepId(e.drepType(), e.drepHash()));
                        ps.setBigDecimal(4, new BigDecimal(e.amount()));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                stmt.execute("COPY t TO '" + dir + "/drep_dist.parquet' (FORMAT PARQUET, COMPRESSION ZSTD)");
            }
            log.info("Exported DRep distribution for epoch {} ({} entries) to {}", epoch, entries.size(), dir);
        } catch (Exception e) {
            log.warn("Failed to export DRep distribution for epoch {}: {}", epoch, e.getMessage());
        }
    }

    @Override
    public void exportAdaPot(int epoch, AdaPotEntry entry) {
        try {
            String dir = epochDir(epoch);
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
                Statement stmt = conn.createStatement();
                stmt.execute("CREATE TABLE t (epoch INT, treasury DECIMAL(38,0), reserves DECIMAL(38,0), " +
                        "deposits DECIMAL(38,0), fees DECIMAL(38,0), distributed DECIMAL(38,0), undistributed DECIMAL(38,0), " +
                        "rewards_pot DECIMAL(38,0), pool_rewards_pot DECIMAL(38,0))");
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?,?,?,?,?,?,?,?,?)")) {
                    ps.setInt(1, entry.epoch());
                    ps.setBigDecimal(2, new BigDecimal(entry.treasury()));
                    ps.setBigDecimal(3, new BigDecimal(entry.reserves()));
                    ps.setBigDecimal(4, new BigDecimal(entry.deposits()));
                    ps.setBigDecimal(5, new BigDecimal(entry.fees()));
                    ps.setBigDecimal(6, new BigDecimal(entry.distributed()));
                    ps.setBigDecimal(7, new BigDecimal(entry.undistributed()));
                    ps.setBigDecimal(8, new BigDecimal(entry.rewardsPot()));
                    ps.setBigDecimal(9, new BigDecimal(entry.poolRewardsPot()));
                    ps.executeUpdate();
                }
                stmt.execute("COPY t TO '" + dir + "/adapot.parquet' (FORMAT PARQUET, COMPRESSION ZSTD)");
            }
            log.info("Exported AdaPot for epoch {} to {}", epoch, dir);
        } catch (Exception e) {
            log.warn("Failed to export AdaPot for epoch {}: {}", epoch, e.getMessage());
        }
    }

    @Override
    public void exportProposalStatus(int epoch, List<ProposalStatusEntry> entries) {
        if (entries.isEmpty()) return;
        try {
            String dir = epochDir(epoch);
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
                Statement stmt = conn.createStatement();
                stmt.execute("CREATE TABLE t (tx_hash VARCHAR, gov_action_index INT, gov_action_id VARCHAR, " +
                        "action_type VARCHAR, status VARCHAR, deposit DECIMAL(38,0), return_address VARCHAR, " +
                        "submitted_epoch INT, expires_after INT)");
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?,?,?,?,?,?,?,?,?)")) {
                    for (var e : entries) {
                        ps.setString(1, e.txHash());
                        ps.setInt(2, e.govActionIndex());
                        ps.setString(3, toGovActionId(e.txHash(), e.govActionIndex()));
                        ps.setString(4, e.actionType());
                        ps.setString(5, e.status());
                        ps.setBigDecimal(6, new BigDecimal(e.deposit()));
                        ps.setString(7, e.returnAddress());
                        ps.setInt(8, e.submittedEpoch());
                        ps.setInt(9, e.expiresAfter());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                stmt.execute("COPY t TO '" + dir + "/proposal_status.parquet' (FORMAT PARQUET, COMPRESSION ZSTD)");
            }
            log.info("Exported proposal status for epoch {} ({} entries) to {}", epoch, entries.size(), dir);
        } catch (Exception e) {
            log.warn("Failed to export proposal status for epoch {}: {}", epoch, e.getMessage());
        }
    }

    // ===== Address Derivation Helpers =====

    private String toStakeAddress(int credType, String credHash) {
        try {
            Credential cred = (credType == 0)
                    ? Credential.fromKey(credHash) : Credential.fromScript(credHash);
            return AddressProvider.getRewardAddress(cred, network).toBech32();
        } catch (Exception e) {
            return null;
        }
    }

    private String toPoolId(String poolHash) {
        try {
            if (poolHash == null || poolHash.isEmpty()) return null;
            return Bech32.encode(HexUtil.decodeHexString(poolHash), "pool");
        } catch (Exception e) {
            return null;
        }
    }

    private String toDRepId(int drepType, String drepHash) {
        try {
            if (drepHash == null || drepHash.isEmpty()) return null;
            byte[] hashBytes = HexUtil.decodeHexString(drepHash);
            return (drepType == 0)
                    ? GovId.drepFromKeyHash(hashBytes) : GovId.drepFromScriptHash(hashBytes);
        } catch (Exception e) {
            return null;
        }
    }

    private String toGovActionId(String txHash, int index) {
        try {
            return GovId.govAction(txHash, index);
        } catch (Exception e) {
            return null;
        }
    }

    private String epochDir(int epoch) throws java.io.IOException {
        Path dir = Path.of(outputDir, "epoch=" + epoch);
        Files.createDirectories(dir);
        return dir.toString();
    }
}
