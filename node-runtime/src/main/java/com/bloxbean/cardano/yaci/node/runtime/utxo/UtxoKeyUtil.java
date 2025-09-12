package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class UtxoKeyUtil {
    private UtxoKeyUtil() {}

    static byte[] outpointKey(String txHashHex, int index) {
        byte[] hash = HexUtil.decodeHexString(txHashHex);
        ByteBuffer bb = ByteBuffer.allocate(hash.length + 2).order(ByteOrder.BIG_ENDIAN);
        bb.put(hash);
        bb.putShort((short) (index & 0xffff));
        return bb.array();
    }

    static byte[] addrHash28(String bech32OrHex) {
        try {
            byte[] raw = AddressUtil.addressToBytes(bech32OrHex);
            return Blake2bUtil.blake2bHash224(raw);
        } catch (Exception e) {
            // Fallback: hash the literal string bytes (should be rare)
            return Blake2bUtil.blake2bHash224(bech32OrHex.getBytes());
        }
    }

    static byte[] addressIndexKey(byte[] addrKey28, long slot, String txHashHex, int index) {
        byte[] hash = HexUtil.decodeHexString(txHashHex);
        ByteBuffer bb = ByteBuffer.allocate(28 + 8 + hash.length + 2).order(ByteOrder.BIG_ENDIAN);
        bb.put(addrKey28);
        bb.putLong(slot);
        bb.put(hash);
        bb.putShort((short) (index & 0xffff));
        return bb.array();
    }

    static byte[] paymentCred28(String bech32OrHex) {
        try {
            // Try as bech32 string first
            Address addr;
            try {
                addr = new Address(bech32OrHex);
            } catch (Exception e) {
                // Fallback to hex-encoded bytes
                byte[] raw;
                try { raw = HexUtil.decodeHexString(bech32OrHex); } catch (Exception ex) { return null; }
                addr = new Address(raw);
            }
            return AddressProvider.getPaymentCredentialHash(addr).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    static byte[] hex28(String hex) {
        if (hex == null) return null;
        byte[] bytes;
        try {
            bytes = HexUtil.decodeHexString(hex);
        } catch (Exception e) {
            return null;
        }
        if (bytes.length == 28) return bytes;
        return null;
    }
}
