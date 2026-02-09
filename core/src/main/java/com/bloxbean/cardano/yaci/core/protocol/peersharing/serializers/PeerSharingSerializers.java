package com.bloxbean.cardano.yaci.core.protocol.peersharing.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.*;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class PeerSharingSerializers {

    public enum MsgShareRequestSerializer implements Serializer<MsgShareRequest> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgShareRequest message) {
            // CDDL: msgShareRequest = [0, base.word8]
            Array array = new Array();
            array.add(new UnsignedInteger(0)); // Message type
            array.add(new UnsignedInteger(message.getAmount())); // word8 (0-255)

            if (log.isTraceEnabled()) {
                log.trace("MsgShareRequest (serialized): " + HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));
            }

            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgShareRequest deserializeDI(DataItem di) {
            List<DataItem> dataItemList = ((Array) di).getDataItems();
            int key = ((UnsignedInteger) dataItemList.get(0)).getValue().intValue();
            if (key != 0) {
                throw new IllegalStateException("Invalid key. Expected: 0, Found: " + key);
            }

            int amount = ((UnsignedInteger) dataItemList.get(1)).getValue().intValue();
            return new MsgShareRequest(amount);
        }
    }

    public enum MsgSharePeersSerializer implements Serializer<MsgSharePeers> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgSharePeers message) {
            // CDDL: msgSharePeers = [1, peerAddresses]
            Array array = new Array();
            array.add(new UnsignedInteger(1)); // Message type

            Array peerAddressesArray = new Array();
            for (PeerAddress peerAddress : message.getPeerAddresses()) {
                peerAddressesArray.add(serializePeerAddress(peerAddress));
            }
            array.add(peerAddressesArray);

            if (log.isTraceEnabled()) {
                log.trace("MsgSharePeers (serialized): " + HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));
            }

            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgSharePeers deserializeDI(DataItem di) {
            List<DataItem> dataItemList = ((Array) di).getDataItems();
            int key = ((UnsignedInteger) dataItemList.get(0)).getValue().intValue();
            if (key != 1) {
                throw new IllegalStateException("Invalid key. Expected: 1, Found: " + key);
            }

            Array peerAddressesArray = (Array) dataItemList.get(1);
            List<PeerAddress> peerAddresses = new ArrayList<>();

            for (DataItem peerAddressDI : peerAddressesArray.getDataItems()) {
                if (peerAddressDI == Special.BREAK)
                    continue;
                peerAddresses.add(deserializePeerAddress(peerAddressDI));
            }

            return new MsgSharePeers(peerAddresses);
        }

        private Array serializePeerAddress(PeerAddress peerAddress) {
            Array array = new Array();

            try {
                InetAddress inetAddress = InetAddress.getByName(peerAddress.getAddress());
                byte[] addressBytes = inetAddress.getAddress();

                if (peerAddress.getType() == PeerAddressType.IPv4) {
                    // CDDL: [0, base.word32, portNumber] for IPv4
                    array.add(new UnsignedInteger(0));

                    // Convert 4 bytes to word32
                    ByteBuffer buffer = ByteBuffer.wrap(addressBytes).order(ByteOrder.LITTLE_ENDIAN);
                    long ipv4AsLong = buffer.getInt() & 0xFFFFFFFFL; // Convert to unsigned
                    array.add(new UnsignedInteger(ipv4AsLong));

                    array.add(new UnsignedInteger(peerAddress.getPort()));

                } else if (peerAddress.getType() == PeerAddressType.IPv6) {
                    // CDDL: [1, base.word32, base.word32, base.word32, base.word32, portNumber] for IPv6
                    array.add(new UnsignedInteger(1));

                    // Convert 16 bytes to 4 word32s
                    ByteBuffer buffer = ByteBuffer.wrap(addressBytes).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < 4; i++) {
                        long word32 = buffer.getInt() & 0xFFFFFFFFL; // Convert to unsigned
                        array.add(new UnsignedInteger(word32));
                    }

                    array.add(new UnsignedInteger(peerAddress.getPort()));
                }

            } catch (UnknownHostException e) {
                throw new RuntimeException("Invalid IP address: " + peerAddress.getAddress(), e);
            }

            return array;
        }

        private PeerAddress deserializePeerAddress(DataItem di) {
            List<DataItem> dataItemList = ((Array) di).getDataItems();
            int type = ((UnsignedInteger) dataItemList.get(0)).getValue().intValue();

            if (type == 0) { // IPv4
                long ipv4Long = ((UnsignedInteger) dataItemList.get(1)).getValue().longValue();
                int port = ((UnsignedInteger) dataItemList.get(2)).getValue().intValue();

                // Convert word32 back to IPv4 address
                byte[] addressBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) ipv4Long).array();
                try {
                    InetAddress inetAddress = InetAddress.getByAddress(addressBytes);
                    return PeerAddress.ipv4(inetAddress.getHostAddress(), port);
                } catch (UnknownHostException e) {
                    throw new RuntimeException("Invalid IPv4 address", e);
                }

            } else if (type == 1) { // IPv6
                byte[] addressBytes = new byte[16];
                ByteBuffer buffer = ByteBuffer.wrap(addressBytes).order(ByteOrder.LITTLE_ENDIAN);

                // Convert 4 word32s back to 16 bytes
                for (int i = 1; i <= 4; i++) {
                    long word32 = ((UnsignedInteger) dataItemList.get(i)).getValue().longValue();
                    buffer.putInt((int) word32);
                }

                // Handle both V11-12 (8 elements) and V13+ (6 elements) formats
                // V11-12: [type, addr1-4, flowInfo, scopeId, port] - port at index 7
                // V13+:   [type, addr1-4, port] - port at index 5
                int portIndex = dataItemList.size() == 8 ? 7 : 5;
                int port = ((UnsignedInteger) dataItemList.get(portIndex)).getValue().intValue();

                try {
                    InetAddress inetAddress = InetAddress.getByAddress(addressBytes);
                    return PeerAddress.ipv6(inetAddress.getHostAddress(), port);
                } catch (UnknownHostException e) {
                    throw new RuntimeException("Invalid IPv6 address", e);
                }

            } else {
                throw new IllegalArgumentException("Unknown peer address type: " + type);
            }
        }
    }

    public enum MsgDoneSerializer implements Serializer<MsgDone> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgDone message) {
            // CDDL: msgDone = [2]
            Array array = new Array();
            array.add(new UnsignedInteger(2)); // Message type

            if (log.isTraceEnabled()) {
                log.trace("MsgDone (serialized): " + HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));
            }

            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgDone deserializeDI(DataItem di) {
            List<DataItem> dataItemList = ((Array) di).getDataItems();
            int key = ((UnsignedInteger) dataItemList.get(0)).getValue().intValue();
            if (key != 2) {
                throw new IllegalStateException("Invalid key. Expected: 2, Found: " + key);
            }

            return new MsgDone();
        }
    }
}
