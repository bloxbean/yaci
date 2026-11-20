package com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.serializers.LeiosNotifySerializers;

import com.bloxbean.cardano.client.common.cbor.CborSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MsgLeiosVotes implements Message {
    private final List<LeiosRawCbor> votes;
    /** The list as received, or null for a message built here, which writes a definite array. */
    private final LeiosRawCbor voteList;

    public MsgLeiosVotes(List<LeiosRawCbor> votes) {
        this(votes, null);
    }

    private MsgLeiosVotes(List<LeiosRawCbor> votes, LeiosRawCbor voteList) {
        Objects.requireNonNull(votes, "votes");
        if (votes.isEmpty()) {
            throw new IllegalArgumentException("votes cannot be empty");
        }
        this.votes = List.copyOf(votes);
        this.voteList = voteList;
    }

    /**
     * A message for a vote list as received: the votes are its items, and it is re-sent byte for byte.
     *
     * @param voteList the list, as received
     * @return the message
     * @throws IllegalArgumentException if it is not an array of CBOR items
     */
    public static MsgLeiosVotes fromList(LeiosRawCbor voteList) {
        CborSpan list = CborSpan.of(voteList.getCbor());
        if (list.majorType() != 4 || list.isTagged())
            throw new IllegalArgumentException("MsgLeiosVotes must carry an array of votes");
        List<LeiosRawCbor> votes = new ArrayList<>(list.size());
        for (CborSpan vote : list.items())
            votes.add(LeiosRawCbor.of(vote.bytes()));
        return new MsgLeiosVotes(votes, voteList);
    }

    /** @return the vote list as received, or null for a message built here */
    public LeiosRawCbor getVoteList() {
        return voteList;
    }

    public List<LeiosRawCbor> getVotes() {
        return votes;
    }

    @Override
    public byte[] serialize() {
        return LeiosNotifySerializers.MsgLeiosVotesSerializer.INSTANCE.serialize(this);
    }
}
