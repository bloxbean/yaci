package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.RawConstitution;
import com.bloxbean.cardano.client.transaction.raw.RawCredential;
import com.bloxbean.cardano.client.transaction.raw.RawGovAction;
import com.bloxbean.cardano.client.transaction.raw.RawProtocolVersion;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.ProtocolVersion;
import com.bloxbean.cardano.yaci.core.model.governance.Constitution;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.actions.*;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.UpdateSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.WithdrawalsSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Maps governance actions read by {@link RawGovAction} to Yaci's models (ADR 0003 D8), and writes them.
 */
public enum GovActionSerializer implements Serializer<GovAction> {
    INSTANCE;

    @Override
    public DataItem serializeDI(GovAction action) {
        Array array = new Array();
        switch (action) {
            case ParameterChangeAction pca -> {
                array.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
                array.add(serializeGovActionId(pca.getGovActionId()));
                array.add(UpdateSerializer.serializePPUpdate(pca.getProtocolParamUpdate()));
                if (pca.getPolicyHash() != null) {
                    array.add(new co.nstant.in.cbor.model.ByteString(HexUtil.decodeHexString(pca.getPolicyHash())));
                } else {
                    array.add(SimpleValue.NULL);
                }
            }
            case HardForkInitiationAction hf -> {
                array.add(new co.nstant.in.cbor.model.UnsignedInteger(1));
                array.add(serializeGovActionId(hf.getGovActionId()));
                Array pvArr = new Array();
                pvArr.add(new co.nstant.in.cbor.model.UnsignedInteger(hf.getProtocolVersion().get_1()));
                pvArr.add(new co.nstant.in.cbor.model.UnsignedInteger(hf.getProtocolVersion().get_2()));
                array.add(pvArr);
            }
            case TreasuryWithdrawalsAction twa -> {
                array.add(new co.nstant.in.cbor.model.UnsignedInteger(2));
                Map wMap = new Map();
                if (twa.getWithdrawals() != null) {
                    for (var entry : twa.getWithdrawals().entrySet()) {
                        wMap.put(new co.nstant.in.cbor.model.ByteString(HexUtil.decodeHexString(entry.getKey())),
                                new co.nstant.in.cbor.model.UnsignedInteger(entry.getValue()));
                    }
                }
                array.add(wMap);
                if (twa.getPolicyHash() != null) {
                    array.add(new co.nstant.in.cbor.model.ByteString(HexUtil.decodeHexString(twa.getPolicyHash())));
                } else {
                    array.add(SimpleValue.NULL);
                }
            }
            case NoConfidence nc -> {
                array.add(new co.nstant.in.cbor.model.UnsignedInteger(3));
                array.add(serializeGovActionId(nc.getGovActionId()));
            }
            case UpdateCommittee uc -> {
                array.add(new co.nstant.in.cbor.model.UnsignedInteger(4));
                array.add(serializeGovActionId(uc.getGovActionId()));
                // set<committee_cold_credential>
                Array removals = new Array();
                if (uc.getMembersForRemoval() != null) {
                    for (Credential cred : uc.getMembersForRemoval()) {
                        removals.add(cred.serialize());
                    }
                }
                array.add(removals);
                // { committee_cold_credential => epoch }
                Map newMembers = new Map();
                if (uc.getNewMembersAndTerms() != null) {
                    for (var entry : uc.getNewMembersAndTerms().entrySet()) {
                        newMembers.put(entry.getKey().serialize(),
                                new co.nstant.in.cbor.model.UnsignedInteger(entry.getValue()));
                    }
                }
                array.add(newMembers);
                // unit_interval (required per CDDL, serialize NULL if absent)
                if (uc.getThreshold() != null) {
                    array.add(serializeUnitInterval(uc.getThreshold()));
                } else {
                    array.add(SimpleValue.NULL);
                }
            }
            case NewConstitution nc -> {
                array.add(new co.nstant.in.cbor.model.UnsignedInteger(5));
                array.add(serializeGovActionId(nc.getGovActionId()));
                // constitution = [anchor, scripthash / null]
                Array constArr = new Array();
                if (nc.getConstitution() != null && nc.getConstitution().getAnchor() != null) {
                    constArr.add(serializeAnchor(nc.getConstitution().getAnchor()));
                } else {
                    constArr.add(SimpleValue.NULL);
                }
                if (nc.getConstitution() != null && nc.getConstitution().getScripthash() != null) {
                    constArr.add(new co.nstant.in.cbor.model.ByteString(
                            HexUtil.decodeHexString(nc.getConstitution().getScripthash())));
                } else {
                    constArr.add(SimpleValue.NULL);
                }
                array.add(constArr);
            }
            case InfoAction ia -> {
                array.add(new co.nstant.in.cbor.model.UnsignedInteger(6));
            }
            default -> throw new IllegalArgumentException("Unknown GovAction type: " + action.getClass());
        }
        return array;
    }

    private static DataItem serializeGovActionId(com.bloxbean.cardano.yaci.core.model.governance.GovActionId id) {
        if (id == null) return SimpleValue.NULL;
        return GovActionIdSerializer.INSTANCE.serializeDI(id);
    }

    private static DataItem serializeAnchor(com.bloxbean.cardano.yaci.core.model.governance.Anchor anchor) {
        Array arr = new Array();
        arr.add(new co.nstant.in.cbor.model.UnicodeString(anchor.getAnchor_url()));
        arr.add(new co.nstant.in.cbor.model.ByteString(HexUtil.decodeHexString(anchor.getAnchor_data_hash())));
        return arr;
    }

    private static DataItem serializeUnitInterval(UnitInterval ui) {
        return CborSerializationUtil.serializeRational(ui);
    }

    /**
     * @param bytes a governance action, exactly one CBOR item
     * @return the action, read as Conway; null for empty input
     */
    @Override
    public GovAction deserialize(byte[] bytes) {
        if (bytes.length == 0)
            return null;
        return read(RawGovAction.lenient(CborSerializationUtil.span(bytes), Era.Conway));
    }

    /**
     * @deprecated A decoded governance action has lost its source encoding. Use {@link #deserialize(byte[])} with its
     * bytes, or {@link #read(RawGovAction)}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public GovAction deserializeDI(DataItem di) {
        return read(RawGovAction.lenient(CborSerializationUtil.span(di), Era.Conway));
    }

    /**
     * @param action a governance action as received
     * @return the action
     */
    public GovAction read(RawGovAction action) {
        return switch (action.kind()) {
            case PARAMETER_CHANGE -> new ParameterChangeAction(previous(action),
                    UpdateSerializer.INSTANCE.read(action.protocolParamUpdate()), policy(action));
            case HARD_FORK_INITIATION -> {
                RawProtocolVersion version = action.protocolVersion();
                // the minor version keeps only its low 32 bits, as it always has
                yield new HardForkInitiationAction(previous(action),
                        new ProtocolVersion(version.major(), version.minor().intValue()));
            }
            case TREASURY_WITHDRAWALS -> new TreasuryWithdrawalsAction(
                    WithdrawalsSerializer.INSTANCE.read(action.withdrawals()), policy(action));
            case NO_CONFIDENCE -> new NoConfidence(previous(action));
            case UPDATE_COMMITTEE -> updateCommittee(action);
            case NEW_CONSTITUTION -> {
                RawConstitution constitution = action.constitution();
                yield new NewConstitution(previous(action), new Constitution(
                        AnchorSerializer.INSTANCE.read(constitution.anchor()),
                        constitution.scriptHash().map(HexUtil::encodeHexString).orElse(null)));
            }
            case INFO -> new InfoAction();
        };
    }

    private static GovAction updateCommittee(RawGovAction action) {
        Set<Credential> removed = new LinkedHashSet<>();
        for (RawCredential member : action.membersToRemove())
            removed.add(CertificateSerializer.credential(member));
        java.util.Map<Credential, Integer> added = new LinkedHashMap<>();
        for (java.util.Map.Entry<RawCredential, Long> member : action.membersToAdd())
            added.put(CertificateSerializer.credential(member.getKey()), member.getValue().intValue());
        com.bloxbean.cardano.client.spec.UnitInterval quorum = action.quorum();
        return new UpdateCommittee(previous(action), removed, added,
                new UnitInterval(quorum.getNumerator(), quorum.getDenominator()));
    }

    private static GovActionId previous(RawGovAction action) {
        return action.previousActionId().map(GovActionIdSerializer.INSTANCE::read).orElse(null);
    }

    private static String policy(RawGovAction action) {
        return action.policyHash().map(HexUtil::encodeHexString).orElse(null);
    }
}
