package com.bloxbean.cardano.yaci.node.runtime.utxo;

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
            byte[] raw = AddressUtil.addressToBytes(bech32OrHex);
            if (raw == null || raw.length < 29) return null;
            int header = raw[0] & 0xff;
            int type = (header >> 4) & 0x0f;
            // Shelley address types where first credential is payment cred
            switch (type) {
                case 0: // base addr keyhash/keyhash
                case 1: // base addr scripthash/keyhash
                case 2: // base addr keyhash/scripthash
                case 3: // base addr scripthash/scripthash
                case 4: // pointer addr keyhash
                case 5: // pointer addr scripthash
                case 6: // enterprise addr keyhash
                case 7: // enterprise addr scripthash
                    byte[] cred = new byte[28];
                    System.arraycopy(raw, 1, cred, 0, 28);
                    return cred;
                default:
                    // Byron addr or reward addr (not UTXO) — fallback
                    return null;
            }
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
