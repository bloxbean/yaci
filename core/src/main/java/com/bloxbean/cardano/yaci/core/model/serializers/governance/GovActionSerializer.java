package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.ProtocolVersion;
import com.bloxbean.cardano.yaci.core.model.governance.Anchor;
import com.bloxbean.cardano.yaci.core.model.governance.Constitution;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.actions.*;
import com.bloxbean.cardano.yaci.core.model.serializers.UpdateSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.WithdrawalsSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.*;

/**
 * gov_action =
 * [ parameter_change_action
 * // hard_fork_initiation_action
 * // treasury_withdrawals_action
 * // no_confidence
 * // update_committee
 * // new_constitution
 * // info_action
 * ]
 * {@literal
 * parameter_change_action = (0, gov_action_id / null, protocol_param_update)
 * hard_fork_initiation_action = (1, gov_action_id / null, [protocol_version])
 * treasury_withdrawals_action = (2, { reward_account => coin })
 * no_confidence = (3, gov_action_id / null)
 * update_committee = (4, gov_action_id / null, set<committee_cold_credential>, { committee_cold_credential => epoch }, unit_interval)
 * new_constitution = (5, gov_action_id / null, constitution)
 * committee = [{ committee_cold_credential => epoch }, unit_interval]
 * }
 */
//TODO -- Test this class
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

    @Override
    public GovAction deserializeDI(DataItem di) {
        Array govActionArray = (Array) di;
        DataItem fistItem = govActionArray.getDataItems().get(0);
        List<DataItem> govActionDIList = govActionArray.getDataItems();
        int actionType = toInt(fistItem);
        switch (actionType) {
            case 0:
                return deserializeParameterChangeAction(govActionDIList);
            case 1:
                return deserializeHardForkInitiationAction(govActionDIList);
            case 2:
                return deserializeTreasuryWithdrawalAction(govActionDIList);
            case 3:
                return deserializeNoConfidenceAction(govActionDIList);
            case 4:
                return deserializeUpdateCommitteeAction(govActionDIList);
            case 5:
                return deserializeNewConstitutionAction(govActionDIList);
            case 6:
                return new InfoAction();
            default:
                throw new IllegalArgumentException("GovAction is not a valid type : " + actionType);
        }
    }

    private GovAction deserializeParameterChangeAction(List<DataItem> govActionArray) {
        DataItem actionIdDI = govActionArray.get(1);
        GovActionId govActionId = getGovActionId(actionIdDI);

        ProtocolParamUpdate protocolParamUpdate = UpdateSerializer.INSTANCE.getProtocolParams((Map) govActionArray.get(2));

        String policyHash = null;
        if (govActionArray.size() > 3) { //TODO -- Remove this once the node supports policy hash
            var policyHashDI = govActionArray.get(3);
            policyHash = policyHashDI == SimpleValue.NULL ? null : HexUtil.encodeHexString(toBytes(policyHashDI));
        }

        return new ParameterChangeAction(govActionId, protocolParamUpdate, policyHash);
    }

    private GovAction deserializeHardForkInitiationAction(List<DataItem> govActionArray) {
        DataItem actionIdDI = govActionArray.get(1);
        GovActionId govActionId = getGovActionId(actionIdDI);

        List<DataItem> protocolVerDIList = ((Array) govActionArray.get(2)).getDataItems();
        if (protocolVerDIList.size() != 2)
            throw new IllegalArgumentException("Invalid protocol version array. Expected 2 items. Found : "
                    + protocolVerDIList.size());

        int major = toInt(protocolVerDIList.get(0));
        int minor = toInt(protocolVerDIList.get(1));

        return new HardForkInitiationAction(govActionId, new ProtocolVersion(major, minor));

    }

    private GovAction deserializeTreasuryWithdrawalAction(List<DataItem> govActionArray) {
        java.util.Map<String, BigInteger> withdrawals = WithdrawalsSerializer.INSTANCE.deserializeDI(govActionArray.get(1));

        String policyHash = null;
        if (govActionArray.size() > 2) { //TODO -- Remove this once the node supports policy hash
            var policyHashDI = govActionArray.get(2);
            policyHash = policyHashDI == SimpleValue.NULL ? null : HexUtil.encodeHexString(toBytes(policyHashDI));
        }

        return new TreasuryWithdrawalsAction(withdrawals, policyHash);
    }

    private GovAction deserializeNoConfidenceAction(List<DataItem> govActionArray) {
        DataItem actionIdDI = govActionArray.get(1);
        GovActionId govActionId = getGovActionId(actionIdDI);

        return new NoConfidence(govActionId);
    }

    /**
     * update_committee = (4, gov_action_id / null, set<committee_cold_credential>, { committee_cold_credential => epoch }, unit_interval)
     *
     * @param govActionArray
     * @return
     */
    private GovAction deserializeUpdateCommitteeAction(List<DataItem> govActionArray) {
        DataItem actionIdDI = govActionArray.get(1);
        GovActionId govActionId = getGovActionId(actionIdDI);

        //committee_cold_credentials
        List<DataItem> committeeColdCredArray = ((Array) govActionArray.get(2)).getDataItems();
        Set<Credential> committeeColdCredSet = new LinkedHashSet<>();
        committeeColdCredArray.stream()
                .map(coldCredDI -> Credential.deserialize((Array) coldCredDI))
                .forEach(coldCred -> committeeColdCredSet.add(coldCred));

        //committee_cold_credential => epoch
        java.util.Map<Credential, Integer> committeeColdCredEpochMap = new java.util.LinkedHashMap<>();
        Map committeeColdCredEpochMapDI = (Map) govActionArray.get(3);
        for (DataItem key : committeeColdCredEpochMapDI.getKeys()) {
            Credential cred = Credential.deserialize((Array) key);
            int epoch = toInt(committeeColdCredEpochMapDI.get(key));
            committeeColdCredEpochMap.put(cred, epoch);
        }

        //unit_interval
        UnitInterval unitInterval = toUnitInterval(govActionArray.get(4));
        return new UpdateCommittee(govActionId, committeeColdCredSet, committeeColdCredEpochMap, unitInterval);
    }

    private GovAction deserializeNewConstitutionAction(List<DataItem> govActionArray) {
        DataItem actionIdDI = govActionArray.get(1);
        GovActionId govActionId = getGovActionId(actionIdDI);

        //consititution
        //constitution =
        //  [ anchor
        //  , scripthash / null
        //  ]

        List<DataItem> constitutionDIList = ((Array) govActionArray.get(2)).getDataItems();
        if (constitutionDIList.size() != 2)
            throw new IllegalArgumentException("Invalid constitution array. Expected 2 items. Found : "
                    + constitutionDIList.size());

        Anchor anchor = AnchorSerializer.INSTANCE.deserializeDI(constitutionDIList.get(0));

        DataItem scriptHashDI = constitutionDIList.get(1);
        String scriptHash;
        if (scriptHashDI == SimpleValue.NULL)
            scriptHash = null;
        else
            scriptHash = toHex(scriptHashDI);

        return new NewConstitution(govActionId, new Constitution(anchor, scriptHash));
    }

    private static GovActionId getGovActionId(DataItem actionIdDI) {
        GovActionId govActionId;
        if (actionIdDI == SimpleValue.NULL)
            govActionId = null;
        else {
            govActionId = GovActionIdSerializer.INSTANCE.deserializeDI(actionIdDI);
        }
        return govActionId;
    }
}
