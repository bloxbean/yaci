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
 * <p>
 * parameter_change_action = (0, gov_action_id / null, protocol_param_update)
 * <p>
 * hard_fork_initiation_action = (1, gov_action_id / null, [protocol_version])
 * <p>
 * treasury_withdrawals_action = (2, { reward_account => coin })
 * <p>
 * no_confidence = (3, gov_action_id / null)
 * <p>
 * update_committee = (4, gov_action_id / null, set<committee_cold_credential>, { committee_cold_credential => epoch }, unit_interval)
 * <p>
 * new_constitution = (5, gov_action_id / null, constitution)
 * <p>
 * committee = [{ committee_cold_credential => epoch }, unit_interval]
 */
//TODO -- Test this class
public enum GovActionSerializer implements Serializer<GovAction> {
    INSTANCE;

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
        return new ParameterChangeAction(govActionId, protocolParamUpdate);
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
        return new TreasuryWithdrawalsAction(withdrawals);
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
        BigDecimal unitInterval = toRationalNumber(govActionArray.get(4));
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
