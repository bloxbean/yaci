package com.bloxbean.cardano.yaci.node.ledgerstate.governance;

import com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore.DeltaOp;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.CommitteeMemberRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Parses Conway genesis JSON and bootstraps initial governance state:
 * committee members (with correct expiry epochs), committee threshold,
 * and constitution.
 * <p>
 * Called once at the Conway era transition (first Conway epoch boundary).
 */
public class ConwayGenesisBootstrap {
    private static final Logger log = LoggerFactory.getLogger(ConwayGenesisBootstrap.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final GovernanceStateStore governanceStore;
    private final String conwayGenesisFilePath;

    public ConwayGenesisBootstrap(GovernanceStateStore governanceStore, String conwayGenesisFilePath) {
        this.governanceStore = governanceStore;
        this.conwayGenesisFilePath = conwayGenesisFilePath;
    }

    /**
     * Bootstrap governance state from Conway genesis at the first Conway epoch boundary.
     *
     * @param conwayFirstEpoch The first epoch of Conway era
     * @param batch            WriteBatch for atomic writes
     * @param deltaOps         Delta ops for rollback
     * @return true if bootstrap succeeded
     */
    public boolean bootstrap(int conwayFirstEpoch, WriteBatch batch, List<DeltaOp> deltaOps) {
        try {
            JsonNode genesis = loadGenesisJson();
            if (genesis == null) {
                log.warn("Conway genesis file not found, skipping bootstrap");
                return false;
            }

            int committeeCount = bootstrapCommittee(genesis, conwayFirstEpoch, batch, deltaOps);
            boolean constitutionSet = bootstrapConstitution(genesis, batch, deltaOps);
            bootstrapGovParams(genesis);

            log.info("Conway genesis bootstrap complete: {} committee members, constitution={}, epoch={}",
                    committeeCount, constitutionSet, conwayFirstEpoch);
            return true;
        } catch (Exception e) {
            log.error("Conway genesis bootstrap failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Parse and store committee members with correct expiry epochs and threshold.
     */
    private int bootstrapCommittee(JsonNode genesis, int epoch,
                                   WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        JsonNode committeeNode = genesis.get("committee");
        if (committeeNode == null) return 0;

        // Parse and store committee members
        JsonNode membersNode = committeeNode.get("members");
        int count = 0;
        if (membersNode != null && membersNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = membersNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                int expiryEpoch = field.getValue().asInt();

                // Parse credential type and hash from key
                // Format: "scriptHash-<hash>" or "keyHash-<hash>" or just "<hash>"
                int credType;
                String hash;
                if (key.startsWith("scriptHash-")) {
                    credType = 1; // script
                    hash = key.substring("scriptHash-".length());
                } else if (key.startsWith("keyHash-")) {
                    credType = 0; // key
                    hash = key.substring("keyHash-".length());
                } else {
                    credType = 0; // default to key
                    hash = key;
                }

                // Store committee member with genesis expiry — no hot key yet
                CommitteeMemberRecord record = CommitteeMemberRecord.noHotKey(expiryEpoch);
                governanceStore.storeCommitteeMember(credType, hash, record, batch, deltaOps);
                count++;
                log.debug("Genesis committee member: credType={}, hash={}..., expiry={}",
                        credType, hash.substring(0, Math.min(16, hash.length())), expiryEpoch);
            }
        }

        // Parse and store committee threshold
        JsonNode thresholdNode = committeeNode.get("threshold");
        if (thresholdNode != null) {
            BigInteger numerator;
            BigInteger denominator;
            if (thresholdNode.isObject()) {
                numerator = thresholdNode.get("numerator").bigIntegerValue();
                denominator = thresholdNode.get("denominator").bigIntegerValue();
            } else {
                // Decimal format — convert to fraction
                double val = thresholdNode.doubleValue();
                denominator = BigInteger.valueOf(1000);
                numerator = BigInteger.valueOf(Math.round(val * 1000));
            }
            governanceStore.storeCommitteeThreshold(numerator, denominator, batch, deltaOps);
            log.info("Genesis committee threshold: {}/{}", numerator, denominator);
        }

        return count;
    }

    /**
     * Parse and store initial constitution from genesis.
     */
    private boolean bootstrapConstitution(JsonNode genesis,
                                          WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        JsonNode constitutionNode = genesis.get("constitution");
        if (constitutionNode == null) return false;

        JsonNode anchorNode = constitutionNode.get("anchor");
        String anchorUrl = null;
        String anchorHash = null;
        if (anchorNode != null) {
            anchorUrl = anchorNode.has("url") ? anchorNode.get("url").asText() : null;
            anchorHash = anchorNode.has("dataHash") ? anchorNode.get("dataHash").asText() : null;
        }

        String scriptHash = constitutionNode.has("script") ? constitutionNode.get("script").asText() : null;

        governanceStore.storeConstitution(
                new GovernanceCborCodec.ConstitutionRecord(anchorUrl, anchorHash, scriptHash),
                batch, deltaOps);
        log.info("Genesis constitution: url={}, scriptHash={}",
                anchorUrl != null ? anchorUrl.substring(0, Math.min(40, anchorUrl.length())) : null,
                scriptHash != null ? scriptHash.substring(0, Math.min(16, scriptHash.length())) : null);
        return true;
    }

    /**
     * Log governance parameters from genesis (they're already in EpochParamProvider defaults).
     */
    private void bootstrapGovParams(JsonNode genesis) {
        String[] paramNames = {"govActionLifetime", "govActionDeposit", "dRepDeposit", "dRepActivity",
                "committeeMinSize", "committeeMaxTermLength"};
        for (String name : paramNames) {
            JsonNode node = genesis.get(name);
            if (node != null) {
                log.debug("Genesis gov param: {}={}", name, node.asText());
            }
        }
    }

    /**
     * Load Conway genesis JSON from file or classpath.
     */
    private JsonNode loadGenesisJson() {
        try {
            // Try file path first
            if (conwayGenesisFilePath != null && !conwayGenesisFilePath.isEmpty()) {
                File file = new File(conwayGenesisFilePath);
                if (file.exists()) {
                    return JSON.readTree(file);
                }
            }

            // Try classpath
            InputStream is = getClass().getClassLoader().getResourceAsStream("conway-genesis.json");
            if (is != null) {
                try (is) {
                    return JSON.readTree(is);
                }
            }

            log.warn("Conway genesis not found at path '{}' or classpath", conwayGenesisFilePath);
            return null;
        } catch (Exception e) {
            log.error("Failed to load Conway genesis: {}", e.getMessage());
            return null;
        }
    }
}
