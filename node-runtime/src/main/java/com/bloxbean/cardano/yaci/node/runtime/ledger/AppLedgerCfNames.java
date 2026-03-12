package com.bloxbean.cardano.yaci.node.runtime.ledger;

/**
 * Column family name constants for the app ledger RocksDB instance.
 */
public final class AppLedgerCfNames {
    private AppLedgerCfNames() {}

    /** Finalized app blocks keyed by {topicId}:{blockNumber} */
    public static final String APP_BLOCKS = "app_blocks";

    /** Topic tip state keyed by {topicId} */
    public static final String APP_STATE = "app_state";

    /** Consensus proofs keyed by {topicId}:{blockNumber} */
    public static final String APP_CONSENSUS_PROOFS = "app_consensus_proofs";

    /** Topic configuration/metadata keyed by {topicId} */
    public static final String APP_TOPICS = "app_topics";
}
