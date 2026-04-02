package com.bloxbean.cardano.yaci.node.ledgerstate;

import java.math.BigInteger;

/**
 * Test helper in the same package as AccountStateCborCodec to access package-private encoders.
 * Used by RocksDB-backed tests that need to populate data in the correct CBOR format.
 */
public class TestCborHelper {

    public static byte[] encodeDRepDelegation(int drepType, String drepHash, long slot, int txIdx, int certIdx) {
        return AccountStateCborCodec.encodeDRepDelegation(drepType, drepHash, slot, txIdx, certIdx);
    }

    public static byte[] encodeStakeAccount(BigInteger reward, BigInteger deposit) {
        return AccountStateCborCodec.encodeStakeAccount(reward, deposit);
    }

    public static byte[] encodePoolDelegation(String poolHash, long slot, int txIdx, int certIdx) {
        return AccountStateCborCodec.encodePoolDelegation(poolHash, slot, txIdx, certIdx);
    }

    public static byte[] encodeEpochDelegSnapshot(String poolHash, BigInteger amount) {
        return AccountStateCborCodec.encodeEpochDelegSnapshot(poolHash, amount);
    }
}
