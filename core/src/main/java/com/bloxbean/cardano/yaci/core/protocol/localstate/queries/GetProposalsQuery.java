package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.certs.StakePoolId;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.ProposalProcedure;
import com.bloxbean.cardano.yaci.core.model.governance.Vote;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.AnchorSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.GovActionIdSerializer;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.Proposal;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBigInteger;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toInt;

@Getter
@AllArgsConstructor
@ToString
public class GetProposalsQuery implements EraQuery<GetProposalQueryResult> {
    private Era era;
    private List<GovActionId> govActionIds;

    public GetProposalsQuery() {
        this.era = Era.Conway;
    }

    public GetProposalsQuery(List<GovActionId> govActionIds) {
        this.era = Era.Conway;
        this.govActionIds = govActionIds;
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array array = new Array();
        array.add(new UnsignedInteger(31));

        Array govActionIdArray = new Array();

        govActionIds.forEach(govActionId -> govActionIdArray.add(GovActionIdSerializer.INSTANCE.serializeDI(govActionId)));

        array.add(govActionIdArray);

        return wrapWithOuterArray(array);
    }

    @Override
    public GetProposalQueryResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        List<Proposal> proposals = new ArrayList<>();
        var proposalsDI = extractResultArray(di[0]);
        var proposalArray = (Array) proposalsDI.get(0);

        for (DataItem item : proposalArray.getDataItems()) {
            if (item == SimpleValue.BREAK) {
                continue;
            }
            Proposal proposal = deserializeProposalResult(item);
            proposals.add(proposal);
        }

        return new GetProposalQueryResult(proposals);
    }

    private Proposal deserializeProposalResult(DataItem proposalDI) {
        Array proposalArray = (Array) proposalDI;
        GovActionId govActionId = deserializeGovActionIdResult(proposalArray.getDataItems().get(0));

        var proposalProcedureDI = (Array) proposalArray.getDataItems().get(4);

        ProposalProcedure proposalProcedure = ProposalProcedure.builder()
                .anchor(AnchorSerializer.INSTANCE.deserializeDI(proposalProcedureDI.getDataItems().get(3)))
                .rewardAccount(HexUtil.encodeHexString(((ByteString) proposalProcedureDI.getDataItems().get(1)).getBytes()))
                // TODO: 'govAction' field
                .deposit(toBigInteger(proposalProcedureDI.getDataItems().get(0)))
                .build();

        // committee votes
        java.util.Map<Credential, Vote> committeeVotes = new HashMap<>();
        var committeeVotesDI = (Map) proposalArray.getDataItems().get(1);

        for (DataItem key : committeeVotesDI.getKeys()) {
            var credentialDI = (Array) key;
            int credType = toInt(credentialDI.getDataItems().get(0));
            String credHash = HexUtil.encodeHexString(((ByteString) credentialDI.getDataItems().get(1)).getBytes());
            var voteDI = committeeVotesDI.get(credentialDI);
            Vote vote = Vote.values()[toInt(voteDI)];
            committeeVotes.put(Credential.builder()
                    .type(credType == 0 ? StakeCredType.ADDR_KEYHASH : StakeCredType.SCRIPTHASH)
                    .hash(credHash)
                    .build(), vote);
        }

        // dRep votes
        java.util.Map<Drep, Vote> dRepVotes = new HashMap<>();
        var dRepVotesDI = (Map) proposalArray.getDataItems().get(2);

        for (DataItem key : dRepVotesDI.getKeys()) {
            var credentialDI = (Array) key;
            int credType = toInt(credentialDI.getDataItems().get(0));
            String credHash = HexUtil.encodeHexString(((ByteString) credentialDI.getDataItems().get(1)).getBytes());
            var voteDI = dRepVotesDI.get(credentialDI);
            Vote vote = Vote.values()[toInt(voteDI)];
            dRepVotes.put(credType == 0 ? Drep.addrKeyHash(credHash) : Drep.scriptHash(credHash), vote);
        }

        // stake pool votes
        java.util.Map<StakePoolId, Vote> stakePoolVotes = new HashMap<>();
        var stakePoolVotesDI = (Map) proposalArray.getDataItems().get(3);
        for (DataItem key : stakePoolVotesDI.getKeys()) {
            String poolHash = HexUtil.encodeHexString(((ByteString) key).getBytes());
            var voteDI = stakePoolVotesDI.get(key);
            Vote vote = Vote.values()[toInt(voteDI)];
            stakePoolVotes.put(StakePoolId.builder().poolKeyHash(poolHash).build(), vote);
        }

        // expiredAfter
        Integer expiredAfter = toInt(proposalArray.getDataItems().get(6));
        // proposedIn
        Integer proposedIn = toInt(proposalArray.getDataItems().get(5));

        return Proposal.builder()
                .govActionId(govActionId)
                .committeeVotes(committeeVotes)
                .dRepVotes(dRepVotes)
                .stakePoolVotes(stakePoolVotes)
                .proposalProcedure(proposalProcedure)
                .expiredAfter(expiredAfter)
                .proposedIn(proposedIn)
                .build();
    }

    private GovActionId deserializeGovActionIdResult(DataItem govActionId) {
        Array govActionIdDI = (Array) govActionId;

        if (govActionIdDI.getDataItems().isEmpty()) {
            return null;
        }

        return GovActionId.builder()
                .transactionId(HexUtil.encodeHexString(((ByteString) govActionIdDI.getDataItems().get(0)).getBytes()))
                .gov_action_index(toInt(govActionIdDI.getDataItems().get(1)))
                .build();
    }

}
