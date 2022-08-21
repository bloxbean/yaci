package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.model.Epoch;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockCons;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.math.BigInteger;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.*;

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

    private ByronEbHead deserializeHeader(Array headerArr) {
        long protocolMagic = toLong(headerArr.getDataItems().get(0));
        String prevBlockId = toHex(headerArr.getDataItems().get(1));

        //String bodyProof = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(CborSerializationUtil.serialize(headerArr.getDataItems().get(2))));
        String bodyProof = HexUtil.encodeHexString(CborSerializationUtil.serialize(headerArr.getDataItems().get(2)));
        ByronBlockCons consensusData = deserializeConsensusData(headerArr.getDataItems().get(3));
        String extraData = HexUtil.encodeHexString(CborSerializationUtil.serialize(headerArr.getDataItems().get(4)));

        return new ByronEbHead(protocolMagic, prevBlockId, bodyProof, consensusData, extraData);
    }

    private ByronBlockCons deserializeConsensusData(DataItem dataItem) {
        Array consArray = (Array) dataItem;
        List<DataItem> consDIs = consArray.getDataItems();

        //slotid
        Array epochArr = (Array)consDIs.get(0);
        long epochNo = toLong(epochArr.getDataItems().get(0));
        long slot = toLong(epochArr.getDataItems().get(1));
        Epoch slotId = new Epoch(epochNo, slot);

        String pubKey = toHex(consDIs.get(1));

        BigInteger difficulty = toBigInteger(((Array)consDIs.get(2)).getDataItems().get(0));
        String blocksig = HexUtil.encodeHexString(CborSerializationUtil.serialize(consDIs.get(3)));

        return new ByronBlockCons(slotId, pubKey, difficulty, blocksig);

    }

    public static void main(String[] args) {
        String blockHex = "";
        ByronEbBlock byronEbBlock = ByronEbBlockSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(blockHex));

        System.out.println(JsonUtil.getPrettyJson(byronEbBlock));
    }
}
