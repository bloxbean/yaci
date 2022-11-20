package com.bloxbean.cardano.yaci.core.protocol.handshake.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.*;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class HandshakeSerializers {

    public enum ProposedVersionSerializer implements Serializer<ProposedVersions> {
        INSTANCE;

        @Override
        public byte[] serialize(ProposedVersions proposedVersions) {
            Array array = new Array();
            array.add(new UnsignedInteger(0));
            array.add(VersionTableSerializer.INSTANCE.serializeDI(proposedVersions.getVersionTable()));

            if (log.isDebugEnabled())
                log.debug(HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));
            return CborSerializationUtil.serialize(array);
        }
    }

    public enum VersionTableSerializer implements Serializer<VersionTable> {
        INSTANCE;

        @Override
        public DataItem serializeDI(VersionTable versionTable) {
            co.nstant.in.cbor.model.Map cborMap = new co.nstant.in.cbor.model.Map();
            Map<Long, VersionData> versionDataMap = versionTable.getVersionDataMap();
            versionDataMap.entrySet()
                    .forEach(entry -> {
                        if (entry.getValue() instanceof N2NVersionData) {
                            N2NVersionData versionData = (N2NVersionData) entry.getValue();
                            Array versionDataArray = new Array();
                            versionDataArray.add(new UnsignedInteger(versionData.getNetworkMagic()));
                            versionDataArray.add(versionData.isInitiatorAndResponderDiffusionMode() ? SimpleValue.TRUE : SimpleValue.FALSE);
                            cborMap.put(new UnsignedInteger(entry.getKey()), versionDataArray);
                        } else if (entry.getValue() instanceof N2CVersionData) {
                            cborMap.put(new UnsignedInteger(entry.getKey()), new UnsignedInteger(entry.getValue().getNetworkMagic()));
                        }
                    });

            return cborMap;
        }
    }

    public enum AcceptVersionSerializer implements Serializer<AcceptVersion> {
        INSTANCE;

        @Override
        public AcceptVersion deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> dataItems = array.getDataItems();
            int label = ((UnsignedInteger) dataItems.get(0)).getValue().intValue();

            if (label != 1)
                throw new CborRuntimeException("Invalid label : " + di);

            long versionNumber = ((UnsignedInteger) dataItems.get(1)).getValue().intValue();

            DataItem versionDataDI = dataItems.get(2);
            if (versionDataDI.getMajorType() == MajorType.ARRAY) { //N2N
                Array versionDataArr = (Array) dataItems.get(2);
                long networkMagic = ((UnsignedInteger) versionDataArr.getDataItems().get(0)).getValue().intValue();
                DataItem initiatorAndResponderDiffusionModeDI = versionDataArr.getDataItems().get(1);
                boolean iardm = initiatorAndResponderDiffusionModeDI == SimpleValue.TRUE ? true : false;

                return new AcceptVersion(versionNumber, new N2NVersionData(networkMagic, iardm));
            } else if (versionDataDI.getMajorType() == MajorType.UNSIGNED_INTEGER) { //N2C
                UnsignedInteger networkMagic = (UnsignedInteger) dataItems.get(2); //versiondata == networkmagic

                return new AcceptVersion(versionNumber, new N2CVersionData(networkMagic.getValue().longValue()));
            } else
                throw new CborRuntimeException("Parsing error. Invalid dataitem type : " + versionDataDI);
        }
    }

    public enum ReasonVersionMismatchSerializer implements Serializer<ReasonVersionMismatch> {
        INSTANCE;

        @Override
        public ReasonVersionMismatch deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> dataItems = array.getDataItems();
            int label = ((UnsignedInteger) dataItems.get(0)).getValue().intValue();

            if (label != 0)
                return null;

            Array versionNoArr = (Array) dataItems.get(1);
            List<Long> versionNumbers = new ArrayList<>();

            for (DataItem vnoDI : versionNoArr.getDataItems()) {
                versionNumbers.add(((UnsignedInteger) vnoDI).getValue().longValue());
            }

            return new ReasonVersionMismatch(versionNumbers);
        }

        //TODO -- deserialize not used
    }

    public enum ReasonHandshakeDecodeErrorSerializer implements Serializer<ReasonHandshakeDecodeError> {
        INSTANCE;

        @Override
        public ReasonHandshakeDecodeError deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> dataItems = array.getDataItems();
            int label = ((UnsignedInteger) dataItems.get(0)).getValue().intValue();

            if (label != 1)
                return null;

            long versionNumber = ((UnsignedInteger) dataItems.get(1)).getValue().longValue();
            String str = ((UnicodeString) dataItems.get(2)).getString();

            return new ReasonHandshakeDecodeError(versionNumber, str);
        }

        //TODO -- deserialize not used
    }

    public enum ReasonRefusedSerializer implements Serializer<ReasonRefused> {
        INSTANCE;

        @Override
        public ReasonRefused deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> dataItems = array.getDataItems();
            int label = ((UnsignedInteger) dataItems.get(0)).getValue().intValue();

            if (label != 2)
                return null;

            long versionNumber = ((UnsignedInteger) dataItems.get(1)).getValue().longValue();
            String str = ((UnicodeString) dataItems.get(2)).getString();

            return new ReasonRefused(versionNumber, str);
        }

        //TODO -- deserialize not used
    }


}
