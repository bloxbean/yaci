package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.model.governance.*;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toHex;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toUnicodeString;

@Slf4j
public enum VotingProceduresSerializer implements Serializer<VotingProcedures> {
    INSTANCE;

    /**
     * {@literal
     * voting_procedures = { + voter => { + gov_action_id => voting_procedure } }
     * }
     *
     * @param di
     * @return
     */
    @Override
    public VotingProcedures deserializeDI(DataItem di) {
        log.trace("VotingProceduresSerializer.deserializeDI ------");

        Map votingProceduresMap = (Map) di;

        VotingProcedures votingProcedures = new VotingProcedures();
        votingProceduresMap.getKeys().forEach(key -> {
            Array voterArray = (Array) key;
            Voter voter = deserializeVoter(voterArray);

            Map votingProcedureMapDI = (Map) votingProceduresMap.get(key);
            java.util.Map<GovActionId, VotingProcedure> votingProcedureMap
                    = deserializeVotingProcedureMap(votingProcedureMapDI);
            votingProcedures.getVoting().put(voter, votingProcedureMap);
        });

        System.out.println(votingProcedures);
        return votingProcedures;
    }

    /**
     * ; Constitutional Committee Hot KeyHash: 0
     * ; Constitutional Committee Hot ScriptHash: 1
     * ; DRep KeyHash: 2
     * ; DRep ScriptHash: 3
     * ; StakingPool KeyHash: 4
     * voter =
     * [ 0, addr_keyhash
     * // 1, scripthash
     * // 2, addr_keyhash
     * // 3, scripthash
     * // 4, addr_keyhash
     * ]
     *
     * @param voterArray
     * @return
     */
    private Voter deserializeVoter(Array voterArray) {
        if (voterArray != null && voterArray.getDataItems().size() != 2)
            throw new IllegalArgumentException("Invalid voter array. Expected 2 items. Found : "
                    + voterArray.getDataItems().size());

        List<DataItem> diList = voterArray.getDataItems();
        int key = ((UnsignedInteger) diList.get(0)).getValue().intValue();
        String hash = HexUtil.encodeHexString(((ByteString) diList.get(1)).getBytes());

        switch (key) {
            case 0:
                return new Voter(VoterType.CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH, hash);
            case 1:
                return new Voter(VoterType.CONSTITUTIONAL_COMMITTEE_HOT_SCRIPT_HASH, hash);
            case 2:
                return new Voter(VoterType.DREP_KEY_HASH, hash);
            case 3:
                return new Voter(VoterType.DREP_SCRIPT_HASH, hash);
            case 4:
                return new Voter(VoterType.STAKING_POOL_KEY_HASH, hash);
            default:
                throw new IllegalArgumentException("Invalid voter key. Expected 0,1,2,3,4. Found : " + key);
        }
    }

    /**
     * { + gov_action_id => voting_procedure }
     *
     * @param votingProcedureMapDI
     * @return
     */
    private java.util.Map<GovActionId, VotingProcedure> deserializeVotingProcedureMap(Map votingProcedureMapDI) {
        java.util.Map<GovActionId, VotingProcedure> votingProcedureMap = new LinkedHashMap<>();
        votingProcedureMapDI.getKeys().forEach(key -> {
            GovActionId govActionId = GovActionIdSerializer.INSTANCE.deserializeDI(key);
            VotingProcedure votingProcedure = deserializeVotingProcedure((Array) votingProcedureMapDI.get(key));
            votingProcedureMap.put(govActionId, votingProcedure);
        });

        return votingProcedureMap;
    }


    /**
     * voting_procedure =
     * [ vote
     * , anchor / null
     * ]
     *
     * @param array
     * @return
     */
    private VotingProcedure deserializeVotingProcedure(Array array) {
        if (array != null && array.getDataItems().size() != 2)
            throw new IllegalArgumentException("Invalid voting_procedure array. Expected 2 items. Found : "
                    + array.getDataItems().size());

        List<DataItem> diList = array.getDataItems();
        Vote vote = deserializeVote((UnsignedInteger) diList.get(0));

        //anchor
        Anchor anchor;
        if (diList.get(1) == SimpleValue.NULL) {
            anchor = null;
        } else {
            anchor = deserializeAnchor((Array) diList.get(1));
        }

        return new VotingProcedure(vote, anchor);
    }

    /**
     * ; no - 0
     * ; yes - 1
     * ; abstain - 2
     * vote = 0 .. 2
     *
     * @param voteDI
     * @return
     */
    private Vote deserializeVote(UnsignedInteger voteDI) {
        int vote = voteDI.getValue().intValue();
        switch (vote) {
            case 0:
                return Vote.NO;
            case 1:
                return Vote.YES;
            case 2:
                return Vote.ABSTAIN;
            default:
                throw new IllegalArgumentException("Invalid vote. Expected 0,1,2. Found : " + vote);
        }
    }

    /**
     * anchor =
     * [ anchor_url       : url
     * , anchor_data_hash : $hash32
     * ]
     *
     * @param array
     * @return
     */
    private Anchor deserializeAnchor(Array array) {
        if (array == null)
            return null;

        if (array != null && array.getDataItems().size() != 2)
            throw new IllegalArgumentException("Invalid anchor array. Expected 2 items. Found : "
                    + array.getDataItems().size());

        List<DataItem> diList = array.getDataItems();
        String anchorUrl = toUnicodeString(diList.get(0));
        String anchorDataHash = toHex(diList.get(1));

        return new Anchor(anchorUrl, anchorDataHash);
    }
}
