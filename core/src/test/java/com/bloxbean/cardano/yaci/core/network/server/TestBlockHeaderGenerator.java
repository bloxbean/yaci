package com.bloxbean.cardano.yaci.core.network.server;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

/**
 * Utility to generate proper CBOR-encoded block headers for testing
 */
public class TestBlockHeaderGenerator {

    /**
     * Generate a proper CBOR-encoded Shelley-style block header for testing
     */
    public static byte[] generateShelleyBlockHeader(long blockNumber, long slot,
                                                   String prevHashHex, String blockHashHex) {
        try {
            // Create the header body array
            Array headerBody = new Array();

            // Block number
            headerBody.add(new UnsignedInteger(blockNumber));

            // Slot
            headerBody.add(new UnsignedInteger(slot));

            // Previous hash (null for genesis)
            if (prevHashHex != null && !prevHashHex.equals("5f20df933584822601f9e3f8c024eb5eb252fe8cefb24d1317dc3d432e940ebb")) {
                headerBody.add(new ByteString(HexUtil.decodeHexString(prevHashHex)));
            } else {
                headerBody.add(SimpleValue.NULL);
            }

            // Issuer VKey (32 bytes)
            headerBody.add(new ByteString(generateTestBytes(32)));

            // VRF VKey (32 bytes)
            headerBody.add(new ByteString(generateTestBytes(32)));

            // VRF result (nonce VRF for pre-Babbage)
            Array nonceVrf = new Array();
            nonceVrf.add(new ByteString(generateTestBytes(32))); // VRF output
            nonceVrf.add(new ByteString(generateTestBytes(80))); // VRF proof
            headerBody.add(nonceVrf);

            // Leader VRF (for pre-Babbage)
            Array leaderVrf = new Array();
            leaderVrf.add(new ByteString(generateTestBytes(32))); // VRF output
            leaderVrf.add(new ByteString(generateTestBytes(80))); // VRF proof
            headerBody.add(leaderVrf);

            // Block body size
            headerBody.add(new UnsignedInteger(1000));

            // Block body hash
            headerBody.add(new ByteString(generateTestBytes(32)));

            // Operational cert hot VKey (32 bytes)
            headerBody.add(new ByteString(generateTestBytes(32)));

            // Operational cert sequence number
            headerBody.add(new UnsignedInteger(1));

            // Operational cert KES period
            headerBody.add(new UnsignedInteger(100));

            // Operational cert sigma (signature)
            headerBody.add(new ByteString(generateTestBytes(64)));

            // Protocol version major
            headerBody.add(new UnsignedInteger(8));

            // Protocol version minor
            headerBody.add(new UnsignedInteger(0));

            // Create the full header array [header_body, body_signature]
            Array fullHeader = new Array();
            fullHeader.add(headerBody);
            fullHeader.add(new ByteString(generateTestBytes(64))); // Body signature

            // Serialize to CBOR
            return CborSerializationUtil.serialize(fullHeader);

        } catch (Exception e) {
            throw new RuntimeException("Failed to generate test block header", e);
        }
    }

    /**
     * Generate test bytes of specified length
     */
    private static byte[] generateTestBytes(int length) {
        byte[] bytes = new byte[length];
        // Fill with predictable test data
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i % 256);
        }
        return bytes;
    }

    /**
     * Generate a Byron-style block header for testing
     */
    public static byte[] generateByronBlockHeader(long blockNumber, long slot,
                                                 String prevHashHex, String blockHashHex) {
        try {
            // Byron block structure is more complex, for now return a simple mock
            // In a real implementation, this would create a proper Byron block header

            // Create a simple array to represent Byron block structure
            Array byronBlock = new Array();

            // Era indicator (0 for Byron)
            byronBlock.add(new UnsignedInteger(0));

            // Block array [header, body]
            Array blockArray = new Array();

            // Byron header (simplified)
            Array byronHeader = new Array();
            byronHeader.add(new UnsignedInteger(blockNumber));
            byronHeader.add(new UnsignedInteger(slot));
            if (prevHashHex != null && !prevHashHex.equals("5f20df933584822601f9e3f8c024eb5eb252fe8cefb24d1317dc3d432e940ebb")) {
                byronHeader.add(new ByteString(HexUtil.decodeHexString(prevHashHex)));
            } else {
                byronHeader.add(SimpleValue.NULL);
            }

            blockArray.add(byronHeader);
            blockArray.add(new Array()); // Empty body for simplicity

            byronBlock.add(blockArray);

            return CborSerializationUtil.serialize(byronBlock);

        } catch (Exception e) {
            throw new RuntimeException("Failed to generate Byron test block header", e);
        }
    }
}
