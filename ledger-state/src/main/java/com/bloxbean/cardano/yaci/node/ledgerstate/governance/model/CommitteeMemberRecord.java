package com.bloxbean.cardano.yaci.node.ledgerstate.governance.model;

/**
 * Stored committee member state in RocksDB (prefix 0x63).
 *
 * @param hotCredType  Hot credential type (0=key, 1=script), -1 if no hot key authorized
 * @param hotHash      Hot credential hash (hex), null if no hot key authorized
 * @param expiryEpoch  Committee member term expiry epoch
 * @param resigned     Whether the member has resigned
 */
public record CommitteeMemberRecord(
        int hotCredType,
        String hotHash,
        int expiryEpoch,
        boolean resigned
) {
    /**
     * Create a record for a member with no hot key authorized.
     */
    public static CommitteeMemberRecord noHotKey(int expiryEpoch) {
        return new CommitteeMemberRecord(-1, null, expiryEpoch, false);
    }

    /**
     * Create an updated copy with hot key authorization.
     */
    public CommitteeMemberRecord withHotKey(int hotCredType, String hotHash) {
        return new CommitteeMemberRecord(hotCredType, hotHash, expiryEpoch, false);
    }

    /**
     * Create an updated copy marking the member as resigned.
     */
    public CommitteeMemberRecord asResigned() {
        return new CommitteeMemberRecord(hotCredType, hotHash, expiryEpoch, true);
    }

    /**
     * Whether this member has a hot key authorized.
     */
    public boolean hasHotKey() {
        return hotHash != null && hotCredType >= 0;
    }
}
