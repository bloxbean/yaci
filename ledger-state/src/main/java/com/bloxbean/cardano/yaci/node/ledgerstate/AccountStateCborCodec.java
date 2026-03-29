package com.bloxbean.cardano.yaci.node.ledgerstate;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

/**
 * CBOR codec for account state values stored in RocksDB.
 * Uses integer-keyed CBOR maps, same pattern as UtxoCborCodec.
 */
public final class AccountStateCborCodec {
    private AccountStateCborCodec() {}

    // --- Stake Account (prefix 0x01): {0: reward(uint), 1: deposit(uint)} ---

    static byte[] encodeStakeAccount(BigInteger reward, BigInteger deposit) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(reward));
        map.put(new UnsignedInteger(1), new UnsignedInteger(deposit));
        return CborSerializationUtil.serialize(map, true);
    }

    record StakeAccount(BigInteger reward, BigInteger deposit) {}

    static StakeAccount decodeStakeAccount(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        BigInteger reward = CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(0)));
        BigInteger deposit = CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(1)));
        return new StakeAccount(reward, deposit);
    }

    // --- Pool Delegation (prefix 0x02): {0: poolHash(bstr28), 1: slot(uint), 2: txIdx(uint), 3: certIdx(uint)} ---

    static byte[] encodePoolDelegation(String poolHash, long slot, int txIdx, int certIdx) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new ByteString(HexUtil.decodeHexString(poolHash)));
        map.put(new UnsignedInteger(1), new UnsignedInteger(slot));
        map.put(new UnsignedInteger(2), new UnsignedInteger(txIdx));
        map.put(new UnsignedInteger(3), new UnsignedInteger(certIdx));
        return CborSerializationUtil.serialize(map, true);
    }

    record PoolDelegation(String poolHash, long slot, int txIdx, int certIdx) {}

    static PoolDelegation decodePoolDelegation(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        String poolHash = HexUtil.encodeHexString(((ByteString) map.get(new UnsignedInteger(0))).getBytes());
        long slot = CborSerializationUtil.toLong(map.get(new UnsignedInteger(1)));
        int txIdx = CborSerializationUtil.toInt(map.get(new UnsignedInteger(2)));
        int certIdx = CborSerializationUtil.toInt(map.get(new UnsignedInteger(3)));
        return new PoolDelegation(poolHash, slot, txIdx, certIdx);
    }

    // --- DRep Delegation (prefix 0x03): {0: drepType(uint), 1: drepHash(bstr), 2: slot(uint), 3: txIdx(uint), 4: certIdx(uint)} ---

    static byte[] encodeDRepDelegation(int drepType, String drepHash, long slot, int txIdx, int certIdx) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(drepType));
        if (drepHash != null) {
            map.put(new UnsignedInteger(1), new ByteString(HexUtil.decodeHexString(drepHash)));
        }
        map.put(new UnsignedInteger(2), new UnsignedInteger(slot));
        map.put(new UnsignedInteger(3), new UnsignedInteger(txIdx));
        map.put(new UnsignedInteger(4), new UnsignedInteger(certIdx));
        return CborSerializationUtil.serialize(map, true);
    }

    record DRepDelegationRecord(int drepType, String drepHash, long slot, int txIdx, int certIdx) {}

    static DRepDelegationRecord decodeDRepDelegation(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        int drepType = CborSerializationUtil.toInt(map.get(new UnsignedInteger(0)));
        DataItem hashDi = map.get(new UnsignedInteger(1));
        String drepHash = (hashDi instanceof ByteString bs) ? HexUtil.encodeHexString(bs.getBytes()) : null;
        long slot = CborSerializationUtil.toLong(map.get(new UnsignedInteger(2)));
        int txIdx = CborSerializationUtil.toInt(map.get(new UnsignedInteger(3)));
        int certIdx = CborSerializationUtil.toInt(map.get(new UnsignedInteger(4)));
        return new DRepDelegationRecord(drepType, drepHash, slot, txIdx, certIdx);
    }

    // --- Pool Registration (prefix 0x10): {0: deposit, 1: marginNum, 2: marginDen, 3: cost, 4: pledge, 5: rewardAccount(bstr), 6: owners(array of bstr)} ---
    // Backward-compatible: old format had only {0: deposit}.

    record PoolRegistrationData(BigInteger deposit, BigInteger marginNum, BigInteger marginDen,
                                BigInteger cost, BigInteger pledge, String rewardAccount, Set<String> owners) {}

    static byte[] encodePoolRegistration(PoolRegistrationData data) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(data.deposit()));
        map.put(new UnsignedInteger(1), new UnsignedInteger(data.marginNum()));
        map.put(new UnsignedInteger(2), new UnsignedInteger(data.marginDen()));
        map.put(new UnsignedInteger(3), new UnsignedInteger(data.cost()));
        map.put(new UnsignedInteger(4), new UnsignedInteger(data.pledge()));
        if (data.rewardAccount() != null && !data.rewardAccount().isEmpty()) {
            map.put(new UnsignedInteger(5), new ByteString(HexUtil.decodeHexString(data.rewardAccount())));
        }
        if (data.owners() != null && !data.owners().isEmpty()) {
            Array ownersArr = new Array();
            for (String owner : data.owners()) {
                ownersArr.add(new ByteString(HexUtil.decodeHexString(owner)));
            }
            map.put(new UnsignedInteger(6), ownersArr);
        }
        return CborSerializationUtil.serialize(map, true);
    }

    static PoolRegistrationData decodePoolRegistration(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        BigInteger deposit = CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(0)));

        // Backward-compatible: old format only has key 0
        DataItem marginNumDi = map.get(new UnsignedInteger(1));
        if (marginNumDi == null) {
            return new PoolRegistrationData(deposit, BigInteger.ZERO, BigInteger.ONE,
                    BigInteger.ZERO, BigInteger.ZERO, "", Set.of());
        }

        BigInteger marginNum = CborSerializationUtil.toBigInteger(marginNumDi);
        BigInteger marginDen = CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(2)));
        BigInteger cost = CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(3)));
        BigInteger pledge = CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(4)));

        DataItem rewardDi = map.get(new UnsignedInteger(5));
        String rewardAccount = (rewardDi instanceof ByteString bs)
                ? HexUtil.encodeHexString(bs.getBytes()) : "";

        DataItem ownersDi = map.get(new UnsignedInteger(6));
        Set<String> owners = new HashSet<>();
        if (ownersDi instanceof Array arr) {
            for (DataItem item : arr.getDataItems()) {
                if (item instanceof ByteString bs) {
                    owners.add(HexUtil.encodeHexString(bs.getBytes()));
                }
            }
        }
        return new PoolRegistrationData(deposit, marginNum, marginDen, cost, pledge, rewardAccount, owners);
    }

    /** Legacy decode: returns only the deposit from pool registration data (backward-compatible). */
    static BigInteger decodePoolDeposit(byte[] bytes) {
        return decodePoolRegistration(bytes).deposit();
    }

    // --- Pool Retirement (prefix 0x11): {0: retireEpoch(uint)} ---

    static byte[] encodePoolRetirement(long epoch) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(epoch));
        return CborSerializationUtil.serialize(map, true);
    }

    static long decodePoolRetirement(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        return CborSerializationUtil.toLong(map.get(new UnsignedInteger(0)));
    }

    // --- DRep Registration (prefix 0x20): {0: deposit(uint)} ---

    static byte[] encodeDRepRegistration(BigInteger deposit) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(deposit));
        return CborSerializationUtil.serialize(map, true);
    }

    static BigInteger decodeDRepDeposit(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        return CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(0)));
    }

    // --- Committee Hot Key (prefix 0x30): {0: credType(uint), 1: hotHash(bstr)} ---

    static byte[] encodeCommitteeHotKey(int hotCredType, String hotHash) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(hotCredType));
        map.put(new UnsignedInteger(1), new ByteString(HexUtil.decodeHexString(hotHash)));
        return CborSerializationUtil.serialize(map, true);
    }

    record CommitteeHotKey(int hotCredType, String hotHash) {}

    static CommitteeHotKey decodeCommitteeHotKey(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        int hotCredType = CborSerializationUtil.toInt(map.get(new UnsignedInteger(0)));
        String hotHash = HexUtil.encodeHexString(((ByteString) map.get(new UnsignedInteger(1))).getBytes());
        return new CommitteeHotKey(hotCredType, hotHash);
    }

    // --- Committee Resignation (prefix 0x31): empty marker value ---

    static byte[] encodeCommitteeResignation() {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(1)); // marker
        return CborSerializationUtil.serialize(map, true);
    }

    // --- MIR Instant Reward (prefix 0x40): {0: amount(uint)} ---
    // Accumulated per-credential instant reward from MIR certificates.

    static byte[] encodeMirReward(BigInteger amount) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(amount));
        return CborSerializationUtil.serialize(map, true);
    }

    static BigInteger decodeMirReward(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        return CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(0)));
    }

    // --- Epoch Delegation Snapshot (CF epoch_deleg_snapshot): {0: poolHash(bstr 28), 1: amount(uint)} ---

    static byte[] encodeEpochDelegSnapshot(String poolHash) {
        return encodeEpochDelegSnapshot(poolHash, BigInteger.ZERO);
    }

    static byte[] encodeEpochDelegSnapshot(String poolHash, BigInteger amount) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new ByteString(HexUtil.decodeHexString(poolHash)));
        if (amount != null && amount.signum() > 0) {
            map.put(new UnsignedInteger(1), new UnsignedInteger(amount));
        }
        return CborSerializationUtil.serialize(map, true);
    }

    public record EpochDelegSnapshot(String poolHash, BigInteger amount) {}

    static EpochDelegSnapshot decodeEpochDelegSnapshot(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        String poolHash = HexUtil.encodeHexString(((ByteString) map.get(new UnsignedInteger(0))).getBytes());
        DataItem amountDi = map.get(new UnsignedInteger(1));
        BigInteger amount = (amountDi != null) ? CborSerializationUtil.toBigInteger(amountDi) : BigInteger.ZERO;
        return new EpochDelegSnapshot(poolHash, amount);
    }

    // --- Pool Block Count (prefix 0x50): {0: blockCount(uint)} ---

    static byte[] encodePoolBlockCount(long blockCount) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(blockCount));
        return CborSerializationUtil.serialize(map, true);
    }

    static long decodePoolBlockCount(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        return CborSerializationUtil.toLong(map.get(new UnsignedInteger(0)));
    }

    // --- Epoch Fees (prefix 0x51): {0: totalFees(uint)} ---

    static byte[] encodeEpochFees(BigInteger totalFees) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(totalFees));
        return CborSerializationUtil.serialize(map, true);
    }

    static BigInteger decodeEpochFees(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        return CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(0)));
    }

    // --- AdaPot (prefix 0x52): {0: treasury, 1: reserves, 2: deposits, 3: fees, 4: distributed, 5: undistributed, 6: rewardsPot, 7: poolRewardsPot} ---

    public record AdaPot(BigInteger treasury, BigInteger reserves, BigInteger deposits, BigInteger fees,
                  BigInteger distributed, BigInteger undistributed, BigInteger rewardsPot, BigInteger poolRewardsPot) {}

    static byte[] encodeAdaPot(AdaPot pot) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(pot.treasury()));
        map.put(new UnsignedInteger(1), new UnsignedInteger(pot.reserves()));
        map.put(new UnsignedInteger(2), new UnsignedInteger(pot.deposits()));
        map.put(new UnsignedInteger(3), new UnsignedInteger(pot.fees()));
        map.put(new UnsignedInteger(4), new UnsignedInteger(pot.distributed()));
        map.put(new UnsignedInteger(5), new UnsignedInteger(pot.undistributed()));
        map.put(new UnsignedInteger(6), new UnsignedInteger(pot.rewardsPot()));
        map.put(new UnsignedInteger(7), new UnsignedInteger(pot.poolRewardsPot()));
        return CborSerializationUtil.serialize(map, true);
    }

    static AdaPot decodeAdaPot(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        return new AdaPot(
                CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(0))),
                CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(1))),
                CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(2))),
                CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(3))),
                CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(4))),
                CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(5))),
                CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(6))),
                CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(7)))
        );
    }

    // --- Stake Event (prefix 0x55): {0: eventType(uint)}  0=REGISTRATION, 1=DEREGISTRATION ---

    static final int EVENT_REGISTRATION = 0;
    static final int EVENT_DEREGISTRATION = 1;

    static byte[] encodeStakeEvent(int eventType) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(eventType));
        return CborSerializationUtil.serialize(map, true);
    }

    static int decodeStakeEvent(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        return CborSerializationUtil.toInt(map.get(new UnsignedInteger(0)));
    }

    // --- Accumulated Reward (prefix 0x54): {0: earnedEpoch(uint), 1: type(uint), 2: amount(uint), 3: poolHash(bstr)} ---

    record AccumulatedReward(int earnedEpoch, int type, BigInteger amount, String poolHash) {}

    static byte[] encodeAccumulatedReward(AccumulatedReward reward) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(reward.earnedEpoch()));
        map.put(new UnsignedInteger(1), new UnsignedInteger(reward.type()));
        map.put(new UnsignedInteger(2), new UnsignedInteger(reward.amount()));
        if (reward.poolHash() != null) {
            map.put(new UnsignedInteger(3), new ByteString(HexUtil.decodeHexString(reward.poolHash())));
        }
        return CborSerializationUtil.serialize(map, true);
    }

    static AccumulatedReward decodeAccumulatedReward(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        int earnedEpoch = CborSerializationUtil.toInt(map.get(new UnsignedInteger(0)));
        int type = CborSerializationUtil.toInt(map.get(new UnsignedInteger(1)));
        BigInteger amount = CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(2)));
        DataItem poolDi = map.get(new UnsignedInteger(3));
        String poolHash = (poolDi instanceof ByteString bs) ? HexUtil.encodeHexString(bs.getBytes()) : null;
        return new AccumulatedReward(earnedEpoch, type, amount, poolHash);
    }
}
