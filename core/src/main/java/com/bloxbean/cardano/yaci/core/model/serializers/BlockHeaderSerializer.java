package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.List;

public enum BlockHeaderSerializer implements Serializer<BlockHeader> {
    INSTANCE;

    @Override
    public BlockHeader deserialize(byte[] bytes) {
        DataItem di = CborSerializationUtil.deserializeOne(bytes);

        return deserializeDI(di);
    }

    @Override
    public BlockHeader deserializeDI(DataItem di) {
        byte[] headerBytes = ((ByteString)di).getBytes();
        Array headerArray = (Array)CborSerializationUtil.deserializeOne(headerBytes);
        return getBlockHeaderFromHeaderArray(headerArray);
    }

    public BlockHeader getBlockHeaderFromHeaderArray(Array headerArray) {
        //Assumption: Last two header parameters are for protocol version
        List<DataItem> headerBodyArr = ((Array) headerArray.getDataItems().get(0)).getDataItems();
        DataItem protoVersionDI = headerBodyArr.get(headerBodyArr.size() - 1);

        if (protoVersionDI.getMajorType() == MajorType.UNSIGNED_INTEGER) { //pre Babbage
            return preBabbageHeader(headerArray);
        } else {
            return postBabbageHeader(headerArray);
        }
    }

    private BlockHeader postBabbageHeader(Array headerArray) {
        List<DataItem> headerBodyArr = ((Array) headerArray.getDataItems().get(0)).getDataItems();
        String bodySignature = CborSerializationUtil.toHex(headerArray.getDataItems().get(1));

        HeaderBody.HeaderBodyBuilder headerBodyBuilder = HeaderBody.builder();
        headerBodyBuilder.blockNumber(CborSerializationUtil.toBigInteger(headerBodyArr.get(0)).longValue());
        headerBodyBuilder.slot(CborSerializationUtil.toBigInteger(headerBodyArr.get(1)).longValue());

        if (headerBodyArr.get(2) != SimpleValue.NULL) //Required for block = 0
            headerBodyBuilder.prevHash(CborSerializationUtil.toHex(headerBodyArr.get(2)));

        headerBodyBuilder.issuerVkey(CborSerializationUtil.toHex(headerBodyArr.get(3)));
        headerBodyBuilder.vrfVkey(CborSerializationUtil.toHex(headerBodyArr.get(4)));

        Array vrtResultArr = (Array) headerBodyArr.get(5);
        headerBodyBuilder.vrfResult(new VrfCert(CborSerializationUtil.toHex(vrtResultArr.getDataItems().get(0)), CborSerializationUtil.toHex(vrtResultArr.getDataItems().get(1))));

        headerBodyBuilder.blockBodySize(CborSerializationUtil.toBigInteger(headerBodyArr.get(6)).longValue());
        headerBodyBuilder.blockBodyHash(CborSerializationUtil.toHex(headerBodyArr.get(7)));

        List<DataItem> operationalCerts = ((Array) headerBodyArr.get(8)).getDataItems(); //4 items
        headerBodyBuilder.operationalCert(OperationalCert.builder()
                .hotVKey(CborSerializationUtil.toHex(operationalCerts.get(0)))
                .sequenceNumber(CborSerializationUtil.toBigInteger(operationalCerts.get(1)).intValue())
                .kesPeriod(CborSerializationUtil.toBigInteger(operationalCerts.get(2)).intValue())
                .sigma(CborSerializationUtil.toHex(operationalCerts.get(3)))
                .build());

        List<DataItem> protocolVersionArr = ((Array) headerBodyArr.get(9)).getDataItems();
        ProtocolVersion protocolVersion = new ProtocolVersion(CborSerializationUtil.toBigInteger(protocolVersionArr.get(0)).longValue(),
                CborSerializationUtil.toBigInteger(protocolVersionArr.get(1)).longValue());
        headerBodyBuilder.protocolVersion(protocolVersion);

        //Derive blockHash
        String blockHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(CborSerializationUtil.serialize(headerArray)));
        headerBodyBuilder.blockHash(blockHash);

        return new BlockHeader(headerBodyBuilder.build(), bodySignature);
    }

    private BlockHeader preBabbageHeader(Array headerArray) {
        List<DataItem> headerBodyArr = ((Array) headerArray.getDataItems().get(0)).getDataItems();
        String bodySignature = CborSerializationUtil.toHex(headerArray.getDataItems().get(1));

        HeaderBody.HeaderBodyBuilder headerBodyBuilder = HeaderBody.builder();
        headerBodyBuilder.blockNumber(CborSerializationUtil.toBigInteger(headerBodyArr.get(0)).longValue());
        headerBodyBuilder.slot(CborSerializationUtil.toBigInteger(headerBodyArr.get(1)).longValue());
        if (headerBodyArr.get(2) != SimpleValue.NULL) //In Preview network, block 0 starts from Alonzo era
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
