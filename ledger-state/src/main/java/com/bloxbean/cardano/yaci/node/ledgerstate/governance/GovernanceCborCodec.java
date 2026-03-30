package com.bloxbean.cardano.yaci.node.ledgerstate.governance;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.GovAction;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.GovActionSerializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.CommitteeMemberRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.DRepStateRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.GovActionRecord;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

/**
 * CBOR codec for governance state values stored in RocksDB.
 * Uses integer-keyed CBOR maps, same pattern as AccountStateCborCodec.
 */
public final class GovernanceCborCodec {

    private GovernanceCborCodec() {}

    // --- GovActionRecord (prefix 0x60) ---
    // {0: deposit, 1: returnAddress, 2: proposedInEpoch, 3: expiresAfterEpoch,
    //  4: actionType, 5: prevActionTxHash, 6: prevActionIndex, 7: govActionJson, 8: proposalSlot}

    public static byte[] encodeGovAction(GovActionRecord rec) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(rec.deposit()));
        map.put(new UnsignedInteger(1), new UnicodeString(rec.returnAddress()));
        map.put(new UnsignedInteger(2), new UnsignedInteger(rec.proposedInEpoch()));
        map.put(new UnsignedInteger(3), new UnsignedInteger(rec.expiresAfterEpoch()));
        map.put(new UnsignedInteger(4), new UnsignedInteger(rec.actionType().ordinal()));
        if (rec.prevActionTxHash() != null) {
            map.put(new UnsignedInteger(5), new ByteString(HexUtil.decodeHexString(rec.prevActionTxHash())));
            map.put(new UnsignedInteger(6), new UnsignedInteger(rec.prevActionIndex()));
        }
        if (rec.govAction() != null) {
            try {
                byte[] govActionCbor = GovActionSerializer.INSTANCE.serialize(rec.govAction());
                map.put(new UnsignedInteger(7), new ByteString(govActionCbor));
            } catch (Exception e) {
                // Log but don't fail — govAction is needed for enactment but not for ratification
            }
        }
        map.put(new UnsignedInteger(8), new UnsignedInteger(rec.proposalSlot()));
        return CborSerializationUtil.serialize(map, true);
    }

    public static GovActionRecord decodeGovAction(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);

        BigInteger deposit = CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(0)));
        String returnAddress = ((UnicodeString) map.get(new UnsignedInteger(1))).getString();
        int proposedInEpoch = CborSerializationUtil.toInt(map.get(new UnsignedInteger(2)));
        int expiresAfterEpoch = CborSerializationUtil.toInt(map.get(new UnsignedInteger(3)));
        int actionTypeOrd = CborSerializationUtil.toInt(map.get(new UnsignedInteger(4)));
        GovActionType actionType = GovActionType.values()[actionTypeOrd];

        DataItem prevTxDi = map.get(new UnsignedInteger(5));
        String prevActionTxHash = null;
        Integer prevActionIndex = null;
        if (prevTxDi instanceof ByteString bs) {
            prevActionTxHash = HexUtil.encodeHexString(bs.getBytes());
            prevActionIndex = CborSerializationUtil.toInt(map.get(new UnsignedInteger(6)));
        }

        GovAction govAction = null;
        DataItem govCborDi = map.get(new UnsignedInteger(7));
        if (govCborDi instanceof ByteString bs) {
            try {
                govAction = GovActionSerializer.INSTANCE.deserialize(bs.getBytes());
            } catch (Exception e) {
                // Could not deserialize — govAction will be null
            }
        }

        long proposalSlot = CborSerializationUtil.toLong(map.get(new UnsignedInteger(8)));

        return new GovActionRecord(deposit, returnAddress, proposedInEpoch, expiresAfterEpoch,
                actionType, prevActionTxHash, prevActionIndex, govAction, proposalSlot);
    }

    // --- DRepStateRecord (prefix 0x62) ---
    // {0: deposit, 1: anchorUrl, 2: anchorHash, 3: registeredAtEpoch,
    //  4: lastInteractionEpoch, 5: expiryEpoch, 6: isActive, 7: registeredAtSlot,
    //  8: protocolVersionAtRegistration}

    public static byte[] encodeDRepState(DRepStateRecord rec) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(rec.deposit()));
        if (rec.anchorUrl() != null) {
            map.put(new UnsignedInteger(1), new UnicodeString(rec.anchorUrl()));
        }
        if (rec.anchorHash() != null) {
            map.put(new UnsignedInteger(2), new ByteString(HexUtil.decodeHexString(rec.anchorHash())));
        }
        map.put(new UnsignedInteger(3), new UnsignedInteger(rec.registeredAtEpoch()));
        if (rec.lastInteractionEpoch() != null) {
            map.put(new UnsignedInteger(4), new UnsignedInteger(rec.lastInteractionEpoch()));
        }
        map.put(new UnsignedInteger(5), new UnsignedInteger(rec.expiryEpoch()));
        map.put(new UnsignedInteger(6), new UnsignedInteger(rec.active() ? 1 : 0));
        map.put(new UnsignedInteger(7), new UnsignedInteger(rec.registeredAtSlot()));
        map.put(new UnsignedInteger(8), new UnsignedInteger(rec.protocolVersionAtRegistration()));
        if (rec.previousDeregistrationSlot() != null) {
            map.put(new UnsignedInteger(9), new UnsignedInteger(rec.previousDeregistrationSlot()));
        }
        return CborSerializationUtil.serialize(map, true);
    }

    public static DRepStateRecord decodeDRepState(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);

        BigInteger deposit = CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(0)));

        DataItem urlDi = map.get(new UnsignedInteger(1));
        String anchorUrl = (urlDi instanceof UnicodeString us) ? us.getString() : null;

        DataItem hashDi = map.get(new UnsignedInteger(2));
        String anchorHash = (hashDi instanceof ByteString bs) ? HexUtil.encodeHexString(bs.getBytes()) : null;

        int registeredAtEpoch = CborSerializationUtil.toInt(map.get(new UnsignedInteger(3)));

        DataItem interDi = map.get(new UnsignedInteger(4));
        Integer lastInteractionEpoch = (interDi != null) ? CborSerializationUtil.toInt(interDi) : null;

        int expiryEpoch = CborSerializationUtil.toInt(map.get(new UnsignedInteger(5)));
        boolean active = CborSerializationUtil.toInt(map.get(new UnsignedInteger(6))) == 1;
        long registeredAtSlot = CborSerializationUtil.toLong(map.get(new UnsignedInteger(7)));
        int protocolVersion = CborSerializationUtil.toInt(map.get(new UnsignedInteger(8)));

        DataItem prevDeregDi = map.get(new UnsignedInteger(9));
        Long previousDeregistrationSlot = (prevDeregDi != null) ? CborSerializationUtil.toLong(prevDeregDi) : null;

        return new DRepStateRecord(deposit, anchorUrl, anchorHash, registeredAtEpoch,
                lastInteractionEpoch, expiryEpoch, active, registeredAtSlot, protocolVersion,
                previousDeregistrationSlot);
    }

    // --- CommitteeMemberRecord (prefix 0x63) ---
    // {0: hotCredType, 1: hotHash, 2: expiryEpoch, 3: isResigned}

    public static byte[] encodeCommitteeMember(CommitteeMemberRecord rec) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(rec.hotCredType() >= 0 ? rec.hotCredType() : 255));
        if (rec.hotHash() != null) {
            map.put(new UnsignedInteger(1), new ByteString(HexUtil.decodeHexString(rec.hotHash())));
        }
        map.put(new UnsignedInteger(2), new UnsignedInteger(rec.expiryEpoch()));
        map.put(new UnsignedInteger(3), new UnsignedInteger(rec.resigned() ? 1 : 0));
        return CborSerializationUtil.serialize(map, true);
    }

    public static CommitteeMemberRecord decodeCommitteeMember(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);

        int hotCredType = CborSerializationUtil.toInt(map.get(new UnsignedInteger(0)));
        if (hotCredType == 255) hotCredType = -1; // sentinel for no hot key

        DataItem hotDi = map.get(new UnsignedInteger(1));
        String hotHash = (hotDi instanceof ByteString bs) ? HexUtil.encodeHexString(bs.getBytes()) : null;

        int expiryEpoch = CborSerializationUtil.toInt(map.get(new UnsignedInteger(2)));
        boolean resigned = CborSerializationUtil.toInt(map.get(new UnsignedInteger(3))) == 1;

        return new CommitteeMemberRecord(hotCredType, hotHash, expiryEpoch, resigned);
    }

    // --- Constitution (prefix 0x64) ---
    // {0: anchorUrl(tstr), 1: anchorHash(bstr), 2: scriptHash(bstr)}

    public record ConstitutionRecord(String anchorUrl, String anchorHash, String scriptHash) {}

    public static byte[] encodeConstitution(ConstitutionRecord rec) {
        Map map = new Map();
        if (rec.anchorUrl() != null) {
            map.put(new UnsignedInteger(0), new UnicodeString(rec.anchorUrl()));
        }
        if (rec.anchorHash() != null) {
            map.put(new UnsignedInteger(1), new ByteString(HexUtil.decodeHexString(rec.anchorHash())));
        }
        if (rec.scriptHash() != null) {
            map.put(new UnsignedInteger(2), new ByteString(HexUtil.decodeHexString(rec.scriptHash())));
        }
        return CborSerializationUtil.serialize(map, true);
    }

    public static ConstitutionRecord decodeConstitution(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);

        DataItem urlDi = map.get(new UnsignedInteger(0));
        String anchorUrl = (urlDi instanceof UnicodeString us) ? us.getString() : null;

        DataItem hashDi = map.get(new UnsignedInteger(1));
        String anchorHash = (hashDi instanceof ByteString bs) ? HexUtil.encodeHexString(bs.getBytes()) : null;

        DataItem scriptDi = map.get(new UnsignedInteger(2));
        String scriptHash = (scriptDi instanceof ByteString bs) ? HexUtil.encodeHexString(bs.getBytes()) : null;

        return new ConstitutionRecord(anchorUrl, anchorHash, scriptHash);
    }

    // --- Committee Threshold (prefix 0x6B) ---
    // {0: numerator(uint), 1: denominator(uint)}

    public record CommitteeThreshold(BigInteger numerator, BigInteger denominator) {}

    public static byte[] encodeCommitteeThreshold(BigInteger numerator, BigInteger denominator) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(numerator));
        map.put(new UnsignedInteger(1), new UnsignedInteger(denominator));
        return CborSerializationUtil.serialize(map, true);
    }

    public static CommitteeThreshold decodeCommitteeThreshold(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        BigInteger numerator = CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(0)));
        BigInteger denominator = CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(1)));
        return new CommitteeThreshold(numerator, denominator);
    }

    // --- Dormant Epochs (prefix 0x65) ---
    // Stored as CBOR array of epoch numbers

    public static byte[] encodeDormantEpochs(Set<Integer> epochs) {
        Array arr = new Array();
        for (int epoch : epochs) {
            arr.add(new UnsignedInteger(epoch));
        }
        return CborSerializationUtil.serialize(arr, true);
    }

    public static Set<Integer> decodeDormantEpochs(byte[] bytes) {
        Array arr = (Array) CborSerializationUtil.deserializeOne(bytes);
        Set<Integer> result = new HashSet<>();
        for (DataItem item : arr.getDataItems()) {
            result.add(CborSerializationUtil.toInt(item));
        }
        return result;
    }

    // --- Vote (prefix 0x61) ---
    // Single byte: 0=NO, 1=YES, 2=ABSTAIN

    public static byte[] encodeVote(int vote) {
        return new byte[]{(byte) vote};
    }

    public static int decodeVote(byte[] bytes) {
        return bytes[0] & 0xFF;
    }

    // --- DRep Distribution entry (prefix 0x66) ---
    // Single uint: stake amount

    public static byte[] encodeDRepDistStake(BigInteger stake) {
        return CborSerializationUtil.serialize(new UnsignedInteger(stake), true);
    }

    public static BigInteger decodeDRepDistStake(byte[] bytes) {
        return CborSerializationUtil.toBigInteger(CborSerializationUtil.deserializeOne(bytes));
    }

    // --- Epoch Donations (prefix 0x68) ---

    public static byte[] encodeDonations(BigInteger amount) {
        return CborSerializationUtil.serialize(new UnsignedInteger(amount), true);
    }

    public static BigInteger decodeDonations(byte[] bytes) {
        return CborSerializationUtil.toBigInteger(CborSerializationUtil.deserializeOne(bytes));
    }

    // --- Last Enacted Action (prefix 0x69) ---
    // {0: txHash(bstr), 1: govActionIndex(uint)}

    public static byte[] encodeLastEnactedAction(String txHash, int govActionIndex) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new ByteString(HexUtil.decodeHexString(txHash)));
        map.put(new UnsignedInteger(1), new UnsignedInteger(govActionIndex));
        return CborSerializationUtil.serialize(map, true);
    }

    public record LastEnactedAction(String txHash, int govActionIndex) {}

    public static LastEnactedAction decodeLastEnactedAction(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        String txHash = HexUtil.encodeHexString(((ByteString) map.get(new UnsignedInteger(0))).getBytes());
        int idx = CborSerializationUtil.toInt(map.get(new UnsignedInteger(1)));
        return new LastEnactedAction(txHash, idx);
    }
}
