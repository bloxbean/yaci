package com.bloxbean.cardano.yaci.core.model.serializers.leios;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosVote;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosVoteFormat;
import com.bloxbean.cardano.yaci.core.protocol.leios.serializers.LeiosCborUtil;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.List;

/**
 * Decodes the known Musashi and prototype-head Leios vote shapes, degrading unknown shapes to raw-only votes.
 */
public enum LeiosVoteSerializer implements Serializer<LeiosVote> {
    INSTANCE;

    @Override
    public LeiosVote deserialize(byte[] bytes) {
        String cbor = HexUtil.encodeHexString(bytes);
        try {
            DataItem dataItem = CborSerializationUtil.deserializeOne(bytes);
            if (!(dataItem instanceof Array voteArray)) {
                return unknown(cbor);
            }

            List<DataItem> items = LeiosCborUtil.arrayItems(voteArray, "Leios vote");
            if (items.size() == 4) {
                return readSlotEbHashVote(items, cbor);
            }
            if (items.size() == 3) {
                return readAnnouncingRbHashVote(items, cbor);
            }
            return unknown(cbor);
        } catch (Exception e) {
            return unknown(cbor);
        }
    }

    @Override
    public LeiosVote deserializeDI(DataItem di) {
        return deserialize(CborSerializationUtil.serialize(di, false));
    }

    private LeiosVote readSlotEbHashVote(List<DataItem> items, String cbor) {
        if (!(items.get(0) instanceof UnsignedInteger slot)
                || !(items.get(1) instanceof ByteString ebHash)
                || !(items.get(2) instanceof UnsignedInteger voterId)
                || !(items.get(3) instanceof ByteString signature)
                || ebHash.getBytes().length != 32 || signature.getBytes().length != 48) {
            return unknown(cbor);
        }

        return LeiosVote.builder()
                .format(LeiosVoteFormat.SLOT_EB_HASH)
                .slot(LeiosCborUtil.toLong(slot, "vote slot"))
                .ebHash(HexUtil.encodeHexString(ebHash.getBytes()))
                .voterId(LeiosCborUtil.toInt(voterId, "voter id"))
                .voteSignature(HexUtil.encodeHexString(signature.getBytes()))
                .cbor(cbor)
                .build();
    }

    private LeiosVote readAnnouncingRbHashVote(List<DataItem> items, String cbor) {
        if (!(items.get(0) instanceof ByteString rbHash)
                || !(items.get(1) instanceof UnsignedInteger voterId)
                || !(items.get(2) instanceof ByteString signature)
                || rbHash.getBytes().length != 32 || signature.getBytes().length != 48) {
            return unknown(cbor);
        }

        return LeiosVote.builder()
                .format(LeiosVoteFormat.ANNOUNCING_RB_HASH)
                .announcingRbHash(HexUtil.encodeHexString(rbHash.getBytes()))
                .voterId(LeiosCborUtil.toInt(voterId, "voter id"))
                .voteSignature(HexUtil.encodeHexString(signature.getBytes()))
                .cbor(cbor)
                .build();
    }

    private LeiosVote unknown(String cbor) {
        return LeiosVote.builder()
                .format(LeiosVoteFormat.UNKNOWN)
                .cbor(cbor)
                .build();
    }
}
