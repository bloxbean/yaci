package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlockCons;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toHex;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toLong;

public enum ByronEbBlockSerializer implements Serializer<ByronEbBlock> {
    INSTANCE;

    @Override
    public ByronEbBlock deserializeDI(DataItem di) {
        Array array = (Array) di;
        int eraValue = ((UnsignedInteger)array.getDataItems().get(0)).getValue().intValue();
        Era era = EraUtil.getEra(eraValue);
        if (era != Era.Byron && eraValue != 1)
            throw new IllegalArgumentException("Not a Byron Eb block");

        Array mainBlkArray = (Array) array.getDataItems().get(1);

        //header
        Array headerArr = (Array) mainBlkArray.getDataItems().get(0);
        ByronEbHead header = deserializeHeader(headerArr);
        //TODO -- Other fields

        ByronEbBlock block = ByronEbBlock.builder()
                .header(header)
                .build();

        return block;
    }

    public ByronEbHead deserializeHeader(Array headerArr) {
        long protocolMagic = toLong(headerArr.getDataItems().get(0));
        String prevBlockId = toHex(headerArr.getDataItems().get(1));

        //String bodyProof = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(CborSerializationUtil.serialize(headerArr.getDataItems().get(2))));
        String bodyProof = HexUtil.encodeHexString(CborSerializationUtil.serialize(headerArr.getDataItems().get(2)));
        ByronEbBlockCons consensusData = deserializeConsensusData(headerArr.getDataItems().get(3));
        String extraData = HexUtil.encodeHexString(CborSerializationUtil.serialize(headerArr.getDataItems().get(4)));

        //Calculate block hash
        Array blockHashArray = new Array();
        // hash expects to have a prefix for the type of block
        blockHashArray.add(new UnsignedInteger(0)); //For Eb block
        blockHashArray.add(headerArr);
        String blockHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(CborSerializationUtil.serialize(blockHashArray)));

        return new ByronEbHead(protocolMagic, prevBlockId, bodyProof, consensusData, extraData, blockHash);
    }

    private ByronEbBlockCons deserializeConsensusData(DataItem dataItem) {
        Array consArray = (Array) dataItem;
        List<DataItem> consDIs = consArray.getDataItems();

        long epochId = toLong(consDIs.get(0));
        Array difficultyArr = (Array) consDIs.get(1);
        long[] difficulty = new long[difficultyArr.getDataItems().size()];
        for (int i = 0; i < difficultyArr.getDataItems().size(); i++) {
            difficulty[i] = toLong(difficultyArr.getDataItems().get(i));
        }

        return new ByronEbBlockCons(epochId, difficulty);
    }

    public static void main(String[] args) {
        String blockHex = "";
        ByronEbBlock byronEbBlock = ByronEbBlockSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(blockHex));

        System.out.println(JsonUtil.getPrettyJson(byronEbBlock));
    }
}
