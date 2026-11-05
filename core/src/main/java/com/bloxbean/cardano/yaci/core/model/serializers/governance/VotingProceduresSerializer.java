package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.RawGovActionId;
import com.bloxbean.cardano.client.transaction.raw.RawVoter;
import com.bloxbean.cardano.client.transaction.raw.RawVotingProcedure;
import com.bloxbean.cardano.client.transaction.raw.RawVotingProcedures;
import com.bloxbean.cardano.yaci.core.model.governance.*;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps voting procedures read by {@link RawVotingProcedures} to Yaci's model (ADR 0003 D8). */
public enum VotingProceduresSerializer implements Serializer<VotingProcedures> {
    INSTANCE;

    /**
     * @param bytes voting procedures, exactly one CBOR item
     * @return the voting procedures; null for empty input
     */
    @Override
    public VotingProcedures deserialize(byte[] bytes) {
        if (bytes.length == 0)
            return null;
        return read(RawVotingProcedures.lenient(CborSerializationUtil.span(bytes)));
    }

    /**
     * @deprecated Decoded voting procedures have lost their source encoding. Use {@link #deserialize(byte[])} with
     * their bytes, or {@link #read(RawVotingProcedures)}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public VotingProcedures deserializeDI(DataItem di) {
        return read(RawVotingProcedures.lenient(CborSerializationUtil.span(di)));
    }

    /**
     * @param procedures voting procedures as received
     * @return each voter's votes, in the order received
     */
    public VotingProcedures read(RawVotingProcedures procedures) {
        VotingProcedures votingProcedures = new VotingProcedures();
        for (RawVotingProcedures.Votes votes : procedures.voters()) {
            Map<GovActionId, VotingProcedure> read = new LinkedHashMap<>();
            for (Map.Entry<RawGovActionId, RawVotingProcedure> vote : votes.votes())
                read.put(GovActionIdSerializer.INSTANCE.read(vote.getKey()), procedure(vote.getValue()));
            votingProcedures.getVoting().put(voter(votes.voter()), read);
        }
        return votingProcedures;
    }

    private static Voter voter(RawVoter voter) {
        VoterType type = switch (voter.kind()) {
            case COMMITTEE_HOT_KEY_HASH -> VoterType.CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH;
            case COMMITTEE_HOT_SCRIPT_HASH -> VoterType.CONSTITUTIONAL_COMMITTEE_HOT_SCRIPT_HASH;
            case DREP_KEY_HASH -> VoterType.DREP_KEY_HASH;
            case DREP_SCRIPT_HASH -> VoterType.DREP_SCRIPT_HASH;
            case STAKING_POOL_KEY_HASH -> VoterType.STAKING_POOL_KEY_HASH;
        };
        return new Voter(type, HexUtil.encodeHexString(voter.hash()));
    }

    private static VotingProcedure procedure(RawVotingProcedure procedure) {
        return new VotingProcedure(vote(procedure.vote()),
                procedure.anchor().map(AnchorSerializer.INSTANCE::read).orElse(null));
    }

    private static Vote vote(int vote) {
        return switch (vote) {
            case 0 -> Vote.NO;
            case 1 -> Vote.YES;
            case 2 -> Vote.ABSTAIN;
            default -> throw new IllegalArgumentException("Invalid vote. Expected 0,1,2. Found : " + vote);
        };
    }
}
