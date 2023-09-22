package com.bloxbean.cardano.yaci.core.protocol.handshake.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.*;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.Map;

//TODO -- Explore option to split N2N and N2C serializers
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
                            versionDataArray.add(versionData.getInitiatorAndResponderDiffusionMode() ? SimpleValue.TRUE : SimpleValue.FALSE);

                            //TODO -- check with existing node versions
                            versionDataArray.add(versionData.getPeerSharing() == null? new UnsignedInteger(0) : new UnsignedInteger(versionData.getPeerSharing()));
                            versionDataArray.add(versionData.getQuery() ? SimpleValue.TRUE : SimpleValue.FALSE);
                            cborMap.put(new UnsignedInteger(entry.getKey()), versionDataArray);
                        } else if (entry.getValue() instanceof OldN2CVersionData) {
                            cborMap.put(new UnsignedInteger(entry.getKey()), new UnsignedInteger(entry.getValue().getNetworkMagic()));
                        } else if (entry.getValue() instanceof N2CVersionData) {
                            N2CVersionData versionData = (N2CVersionData) entry.getValue();
                            Array versionDataArray = new Array();
                            versionDataArray.add(new UnsignedInteger(versionData.getNetworkMagic()));
                            versionDataArray.add(versionData.isQuery() ? SimpleValue.TRUE : SimpleValue.FALSE);
                            cborMap.put(new UnsignedInteger(entry.getKey()), versionDataArray);
                        }
                    });

            return cborMap;
        }

        @Override
        public VersionTable deserializeDI(DataItem versionTableDI) {
            co.nstant.in.cbor.model.Map cborMap = (co.nstant.in.cbor.model.Map) versionTableDI;
            Map<Long, VersionData> versionDataMap = new HashMap<>();
            cborMap.getKeys()
                    .forEach(key -> {
                        DataItem value = cborMap.get(key);
                        if (value.getMajorType() == MajorType.UNSIGNED_INTEGER) { //Old N2C
                            versionDataMap.put(((UnsignedInteger) key).getValue().longValue(),
                                    new OldN2CVersionData(((UnsignedInteger) value).getValue().intValue()));
                        } else if (value.getMajorType() == MajorType.ARRAY) {
                            long versionNumber = ((UnsignedInteger) key).getValue().longValue();
                            if (versionNumber > 32000) { //N2C
                                Array versionDataArray = (Array) value;
                                long networkMagic = ((UnsignedInteger) versionDataArray.getDataItems().get(0)).getValue().intValue();
                                Boolean query = versionDataArray.getDataItems().get(1) == SimpleValue.TRUE ? Boolean.TRUE : Boolean.FALSE;

                                versionDataMap.put(versionNumber, new N2CVersionData(networkMagic, query));
                            } else { //N2N
                                Array versionDataArray = (Array) value;
                                if (versionDataArray.getDataItems().size() == 2) { //N2N 1,2,3,4,5,6,7,8,9,10
                                    long networkMagic = ((UnsignedInteger) versionDataArray.getDataItems().get(0)).getValue().intValue();

                                    DataItem initiatorAndResponderDiffusionModeDI = versionDataArray.getDataItems().get(1);
                                    boolean iardm = initiatorAndResponderDiffusionModeDI == SimpleValue.TRUE ? true : false;

                                    versionDataMap.put(versionNumber, new N2NVersionData(networkMagic, iardm));
                                } else if (versionDataArray.getDataItems().size() == 4) { //N2N 11,12
                                    long networkMagic = ((UnsignedInteger) versionDataArray.getDataItems().get(0)).getValue().intValue();

                                    DataItem initiatorAndResponderDiffusionModeDI = versionDataArray.getDataItems().get(1);
                                    boolean iardm = initiatorAndResponderDiffusionModeDI == SimpleValue.TRUE ? true : false;

                                    int peerSharing = ((UnsignedInteger) versionDataArray.getDataItems().get(2)).getValue().intValue();
                                    Boolean query = versionDataArray.getDataItems().get(3) == SimpleValue.TRUE ? Boolean.TRUE : Boolean.FALSE;

                                    versionDataMap.put(versionNumber, new N2NVersionData(networkMagic, iardm, peerSharing, query));
                                }
                            }
                        }
                    });

            return new VersionTable(versionDataMap);
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

                return new AcceptVersion(versionNumber, new OldN2CVersionData(networkMagic.getValue().longValue()));
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

    public enum QueryReplySerializer implements Serializer<VersionTable> {
        INSTANCE;

        @Override
        public VersionTable deserializeDI(DataItem versionTableDI) {
            return VersionTableSerializer.INSTANCE.deserializeDI(versionTableDI);
        }
    }
}
