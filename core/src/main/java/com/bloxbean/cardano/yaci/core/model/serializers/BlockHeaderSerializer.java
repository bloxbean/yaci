package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosAnnouncement;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.List;

@Slf4j
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
        List<DataItem> headerBodyArr = ((Array) headerArray.getDataItems().get(0)).getDataItems();

        if (isPostBabbageHeader(headerBodyArr)) {
            return postBabbageHeader(headerArray);
        } else {
            return preBabbageHeader(headerArray);
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
        readLeiosCertified(headerBodyArr).ifPresent(headerBodyBuilder::leiosCertified);
        readLeiosAnnouncement(headerBodyArr).ifPresent(headerBodyBuilder::leiosAnnouncement);

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

        //Operational Certificate 4 items
        headerBodyBuilder.operationalCert(OperationalCert.builder()
                .hotVKey(CborSerializationUtil.toHex(headerBodyArr.get(9)))
                .sequenceNumber(CborSerializationUtil.toBigInteger(headerBodyArr.get(10)).intValue())
                .kesPeriod(CborSerializationUtil.toBigInteger(headerBodyArr.get(11)).intValue())
                .sigma(CborSerializationUtil.toHex(headerBodyArr.get(12)))
                .build());

        ProtocolVersion protocolVersion = new ProtocolVersion(CborSerializationUtil.toBigInteger(headerBodyArr.get(13)).longValue(),
                CborSerializationUtil.toBigInteger(headerBodyArr.get(14)).longValue());
        headerBodyBuilder.protocolVersion(protocolVersion);

        //Derive blockHash
        String blockHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(CborSerializationUtil.serialize(headerArray)));
        headerBodyBuilder.blockHash(blockHash);

        return new BlockHeader(headerBodyBuilder.build(), bodySignature);
    }

    private boolean isPostBabbageHeader(List<DataItem> headerBodyArr) {
        return headerBodyArr.size() > 8 && headerBodyArr.get(8) instanceof Array;
    }

    private java.util.Optional<LeiosAnnouncement> readLeiosAnnouncement(List<DataItem> headerBodyArr) {
        if (headerBodyArr.size() <= 10) {
            return java.util.Optional.empty();
        }

        DataItem item;
        if (isCborBoolean(headerBodyArr.get(10))) {
            if (headerBodyArr.size() <= 11) {
                return java.util.Optional.empty();
            }
            item = headerBodyArr.get(11);
        } else {
            item = headerBodyArr.get(10);
        }
        if (item == SimpleValue.NULL) {
            return java.util.Optional.empty();
        }
        if (!(item instanceof Array announcementArr)) {
            if (log.isDebugEnabled()) {
                log.debug("Ignoring unsupported Leios announcement header extension shape: {}",
                        item != null ? item.getMajorType() : null);
            }
            return java.util.Optional.empty();
        }

        List<DataItem> items = announcementArr.getDataItems().stream()
                .filter(dataItem -> dataItem != SimpleValue.BREAK)
                .toList();
        if (items.size() != 2 || !(items.get(0) instanceof ByteString ebHash)
                || !(items.get(1) instanceof UnsignedInteger ebSize)) {
            if (log.isDebugEnabled()) {
                log.debug("Ignoring unsupported Leios announcement header extension shape");
            }
            return java.util.Optional.empty();
        }

        if (ebHash.getBytes().length != 32) {
            if (log.isDebugEnabled()) {
                log.debug("Ignoring Leios announcement with EB hash length {}", ebHash.getBytes().length);
            }
            return java.util.Optional.empty();
        }

        BigInteger size = ebSize.getValue();
        if (size.signum() < 0 || size.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            if (log.isDebugEnabled()) {
                log.debug("Ignoring Leios announcement with EB size outside signed long range");
            }
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(LeiosAnnouncement.builder()
                .ebHash(HexUtil.encodeHexString(ebHash.getBytes()))
                .ebSize(size.longValue())
                .build());
    }

    private java.util.Optional<Boolean> readLeiosCertified(List<DataItem> headerBodyArr) {
        if (headerBodyArr.size() <= 10) {
            return java.util.Optional.empty();
        }

        DataItem item = headerBodyArr.get(10);
        if (item == SimpleValue.TRUE) {
            return java.util.Optional.of(Boolean.TRUE);
        }
        if (item == SimpleValue.FALSE) {
            return java.util.Optional.of(Boolean.FALSE);
        }
        return java.util.Optional.empty();
    }

    private boolean isCborBoolean(DataItem dataItem) {
        return dataItem == SimpleValue.TRUE || dataItem == SimpleValue.FALSE;
    }
}
