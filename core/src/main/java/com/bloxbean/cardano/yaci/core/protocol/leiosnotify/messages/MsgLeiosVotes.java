package com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.serializers.LeiosNotifySerializers;

import java.util.List;
import java.util.Objects;

public class MsgLeiosVotes implements Message {
    private final List<LeiosRawCbor> votes;

    public MsgLeiosVotes(List<LeiosRawCbor> votes) {
        Objects.requireNonNull(votes, "votes");
        if (votes.isEmpty()) {
            throw new IllegalArgumentException("votes cannot be empty");
        }
        this.votes = List.copyOf(votes);
    }

    public List<LeiosRawCbor> getVotes() {
        return votes;
    }

    @Override
    public byte[] serialize() {
        return LeiosNotifySerializers.MsgLeiosVotesSerializer.INSTANCE.serialize(this);
    }
}
