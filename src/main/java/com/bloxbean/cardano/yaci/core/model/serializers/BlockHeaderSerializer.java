package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.ProtocolVersion;
import com.bloxbean.cardano.yaci.core.model.VrfCert;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.List;

public enum BlockHeaderSerializer implements Serializer<BlockHeader> {
    INSTANCE;

    @Override
    public BlockHeader deserialize(byte[] bytes) {
        DataItem di = CborSerializationUtil.deserialize(bytes);

        return deserializeDI(di);
    }

    @Override
    public BlockHeader deserializeDI(DataItem di) {
        byte[] headerBytes = ((ByteString)di).getBytes();
        Array headerArray = (Array)CborSerializationUtil.deserialize(headerBytes);
        return getBlockHeaderFromHeaderArray(headerArray);
    }

    public BlockHeader getBlockHeaderFromHeaderArray(Array headerArray) {
        List<DataItem> headerBodyArr = ((Array) headerArray.getDataItems().get(0)).getDataItems();
        String bodySignature = CborSerializationUtil.toHex(headerArray.getDataItems().get(1));

        HeaderBody.HeaderBodyBuilder headerBodyBuilder = HeaderBody.builder();
        headerBodyBuilder.blockNumber(CborSerializationUtil.toBigInteger(headerBodyArr.get(0)).longValue());
        headerBodyBuilder.slot(CborSerializationUtil.toBigInteger(headerBodyArr.get(1)).longValue());
        headerBodyBuilder.prevHash(CborSerializationUtil.toHex(headerBodyArr.get(2)));
        headerBodyBuilder.issuerVkey(CborSerializationUtil.toHex(headerBodyArr.get(3)));
        headerBodyBuilder.vrfVkey(CborSerializationUtil.toHex(headerBodyArr.get(4)));

        Array nonceVrfArr = (Array) headerBodyArr.get(5);
        headerBodyBuilder.nonceVrf(new VrfCert(CborSerializationUtil.toHex(nonceVrfArr.getDataItems().get(0)), CborSerializationUtil.toHex(nonceVrfArr.getDataItems().get(1))));

        Array leaderVrfArr = (Array) headerBodyArr.get(6);
        headerBodyBuilder.leaderVrf(new VrfCert(CborSerializationUtil.toHex(leaderVrfArr.getDataItems().get(0)), CborSerializationUtil.toHex(leaderVrfArr.getDataItems().get(1))));

        headerBodyBuilder.blockBodySize(CborSerializationUtil.toBigInteger(headerBodyArr.get(7)).longValue());
        headerBodyBuilder.blockBodyHash(CborSerializationUtil.toHex(headerBodyArr.get(8)));

//        headerBody.setOpCertificate() 4 items
        ProtocolVersion protocolVersion = new ProtocolVersion(CborSerializationUtil.toBigInteger(headerBodyArr.get(13)).longValue(),
                CborSerializationUtil.toBigInteger(headerBodyArr.get(14)).longValue());
        headerBodyBuilder.protocolVersion(protocolVersion);

        //Derive blockHash
        String blockHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(CborSerializationUtil.serialize(headerArray)));
        headerBodyBuilder.blockHash(blockHash);

        return new BlockHeader(headerBodyBuilder.build(), bodySignature);
    }
}
