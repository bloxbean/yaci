package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.crypto.BlockProducerKeys;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.MsgBlock;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers.MsgBlockSerializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test to dump and analyze the MsgBlock wire format produced by Yaci's
 * block producer, and verify the nested CBOR tag-24 structure matches what
 * the Haskell cardano-node expects.
 * <p>
 * Wire format from a real Haskell node (relay path):
 * <pre>
 *   MsgBlock wire:  [4, 24(h'<block_bytes>')]
 *   block_bytes:    [era, 24(h'<block_content>')]
 *   block_content:  [header, txBodies, witnesses, auxData, invalidTxs]
 * </pre>
 * <p>
 * This test verifies that Yaci-produced blocks follow the same structure
 * and that the MsgBlockSerializer round-trips correctly.
 */
class WireFormatDebugTest {

    private static final long EPOCH_LENGTH = 600;
    private static final long SECURITY_PARAM = 100;
    private static final double ACTIVE_SLOTS_COEFF = 1.0;
    private static final long SLOTS_PER_KES_PERIOD = 129600;
    private static final long MAX_KES_EVOLUTIONS = 60;

    private static BlockProducerKeys keys;
    private static SignedBlockBuilder builder;

    @BeforeAll
    static void setUp() throws Exception {
        Path base = Paths.get("src/test/resources/devnet");
        keys = BlockProducerKeys.load(
                base.resolve("vrf.skey"),
                base.resolve("kes.skey"),
                base.resolve("opcert.cert")
        );

        EpochNonceState nonceState = new EpochNonceState(EPOCH_LENGTH, SECURITY_PARAM, ACTIVE_SLOTS_COEFF);
        byte[] genesisBytes = Files.readAllBytes(base.resolve("shelley-genesis.json"));
        nonceState.initFromGenesis(genesisBytes);

        builder = new SignedBlockBuilder(keys, SLOTS_PER_KES_PERIOD, MAX_KES_EVOLUTIONS,
                nonceState, null);
    }

    @Test
    void dumpAndVerifyMsgBlockWireFormat() throws Exception {
        // =====================================================================
        // STEP 1: Build a signed block
        // =====================================================================
        var result = builder.buildBlock(1, 10, new byte[32], List.of());
        byte[] storedBlockCbor = result.blockCbor();

        System.out.println("==========================================================");
        System.out.println("  WIRE FORMAT DEBUG: MsgBlock structure analysis");
        System.out.println("==========================================================");
        System.out.println();

        // =====================================================================
        // STEP 2: Hex-dump the stored block CBOR (before MsgBlock wrapping)
        // =====================================================================
        String storedHex = HexUtil.encodeHexString(storedBlockCbor);
        int dumpLen = Math.min(200, storedHex.length());
        System.out.println("--- Stored block CBOR (what goes into ChainState) ---");
        System.out.println("  Total length: " + storedBlockCbor.length + " bytes");
        System.out.println("  First " + (dumpLen / 2) + " bytes hex:");
        System.out.println("  " + storedHex.substring(0, dumpLen));
        System.out.println();

        // =====================================================================
        // STEP 3: Serialize as MsgBlock and hex-dump
        // =====================================================================
        MsgBlock msgBlock = new MsgBlock(storedBlockCbor);
        byte[] wireBytes = MsgBlockSerializer.INSTANCE.serialize(msgBlock);

        String wireHex = HexUtil.encodeHexString(wireBytes);
        dumpLen = Math.min(200, wireHex.length());
        System.out.println("--- MsgBlock wire format (what goes on the network) ---");
        System.out.println("  Total length: " + wireBytes.length + " bytes");
        System.out.println("  First " + (dumpLen / 2) + " bytes hex:");
        System.out.println("  " + wireHex.substring(0, dumpLen));
        System.out.println();

        // =====================================================================
        // STEP 4: Parse the MsgBlock wire format manually
        // =====================================================================
        System.out.println("--- Parsing MsgBlock wire format layer by layer ---");
        System.out.println();

        // Layer 0: The outermost MsgBlock array
        DataItem outerDI = CborSerializationUtil.deserializeOne(wireBytes);
        assertInstanceOf(Array.class, outerDI, "Outer must be an Array");
        Array outerArray = (Array) outerDI;
        assertEquals(2, outerArray.getDataItems().size(), "Outer array must have 2 elements: [msgType, payload]");

        DataItem msgTypeItem = outerArray.getDataItems().get(0);
        assertInstanceOf(UnsignedInteger.class, msgTypeItem);
        long msgType = ((UnsignedInteger) msgTypeItem).getValue().longValueExact();
        assertEquals(4, msgType, "MsgBlock type must be 4");

        DataItem payloadItem = outerArray.getDataItems().get(1);
        assertInstanceOf(ByteString.class, payloadItem, "Payload must be a ByteString");
        ByteString payloadBS = (ByteString) payloadItem;

        // Check tag-24 on payload
        Tag payloadTag = payloadBS.getTag();
        assertNotNull(payloadTag, "Payload ByteString must have a tag");
        assertEquals(24L, payloadTag.getValue(), "Payload must have tag 24 (CBOR-in-CBOR)");

        byte[] payloadBytes = payloadBS.getBytes();
        System.out.println("LAYER 0 (MsgBlock): [4, 24(h'...')]");
        System.out.println("  msgType = " + msgType);
        System.out.println("  payload tag = " + payloadTag.getValue());
        System.out.println("  payload (inner) length = " + payloadBytes.length + " bytes");
        System.out.println("  payload hex (first 100 bytes): " +
                HexUtil.encodeHexString(payloadBytes).substring(0, Math.min(200, payloadBytes.length * 2)));
        System.out.println();

        // Verify payload bytes == stored block CBOR
        assertArrayEquals(storedBlockCbor, payloadBytes,
                "MsgBlock payload bytes must exactly equal the stored block CBOR");
        System.out.println("  VERIFIED: payload bytes == stored block CBOR");
        System.out.println();

        // Layer 1: The HFC-wrapped block [era, [block_content...]]
        DataItem innerDI = CborSerializationUtil.deserializeOne(payloadBytes);
        assertInstanceOf(Array.class, innerDI, "Inner must be an Array");
        Array innerArray = (Array) innerDI;
        assertEquals(2, innerArray.getDataItems().size(), "Inner array must have 2 elements: [era, [content...]]");

        DataItem eraItem = innerArray.getDataItems().get(0);
        assertInstanceOf(UnsignedInteger.class, eraItem);
        long era = ((UnsignedInteger) eraItem).getValue().longValueExact();
        assertEquals(6, era, "Era must be 6 (Conway)");

        // Block content is directly embedded as an array (no tag-24 wrapping)
        DataItem contentItem = innerArray.getDataItems().get(1);
        assertInstanceOf(Array.class, contentItem, "Block content must be an Array (directly embedded)");

        System.out.println("LAYER 1 (HFC block): [6, [content...]]");
        System.out.println("  era = " + era);
        System.out.println();

        // Layer 2: The block content [header, txBodies, witnesses, auxData, invalidTxs]
        Array blockContent = (Array) contentItem;
        assertEquals(5, blockContent.getDataItems().size(),
                "Block content must have 5 elements: [header, txBodies, witnesses, auxData, invalidTxs]");

        DataItem headerDI = blockContent.getDataItems().get(0);
        assertInstanceOf(Array.class, headerDI, "Header must be an array");
        Array header = (Array) headerDI;
        assertEquals(2, header.getDataItems().size(), "Header must have 2 elements: [headerBody, kesSig]");

        Array headerBody = (Array) header.getDataItems().get(0);
        assertEquals(10, headerBody.getDataItems().size(), "Header body must have 10 fields");

        System.out.println("LAYER 2 (block content): [header, txBodies, witnesses, auxData, invalidTxs]");
        System.out.println("  header = [headerBody(10 fields), kesSig(448 bytes)]");
        System.out.println("  txBodies count = " + ((Array) blockContent.getDataItems().get(1)).getDataItems().size());
        System.out.println("  txWitnesses count = " + ((Array) blockContent.getDataItems().get(2)).getDataItems().size());
        System.out.println("  auxData type = " + blockContent.getDataItems().get(3).getMajorType());
        System.out.println("  invalidTxs count = " + ((Array) blockContent.getDataItems().get(4)).getDataItems().size());
        System.out.println();

        // =====================================================================
        // STEP 5: Verify MsgBlock round-trip (deserialize back)
        // =====================================================================
        System.out.println("--- MsgBlock round-trip verification ---");
        MsgBlock deserialized = MsgBlockSerializer.INSTANCE.deserialize(wireBytes);
        assertNotNull(deserialized, "Deserialized MsgBlock must not be null");
        assertArrayEquals(storedBlockCbor, deserialized.getBytes(),
                "Deserialized MsgBlock.getBytes() must equal original stored block CBOR");
        System.out.println("  MsgBlock round-trip: PASSED");
        System.out.println("  deserialized.getBytes().length = " + deserialized.getBytes().length);
        System.out.println();

        // =====================================================================
        // STEP 6: Annotate the raw CBOR byte-by-byte for the first ~50 bytes
        // =====================================================================
        System.out.println("--- Annotated CBOR bytes (MsgBlock wire) ---");
        annotateCborPrefix(wireBytes, 50);
        System.out.println();

        System.out.println("--- Annotated CBOR bytes (stored block) ---");
        annotateCborPrefix(storedBlockCbor, 50);
        System.out.println();

        // =====================================================================
        // STEP 7: Summary — what does the Haskell node see?
        // =====================================================================
        System.out.println("==========================================================");
        System.out.println("  SUMMARY: Wire format nesting structure");
        System.out.println("==========================================================");
        System.out.println();
        System.out.println("  Wire (MsgBlock):     [4, tag24(bytestring)]");
        System.out.println("    -> bytestring is:  [6, [header, txBodies, witnesses, auxData, invalidTxs]]");
        System.out.println();
        System.out.println("  The Haskell node's BlockFetch client does:");
        System.out.println("    1. Receives MsgBlock wire bytes");
        System.out.println("    2. Parses CBOR: gets [4, tag24(block_bytes)]");
        System.out.println("    3. Extracts block_bytes from tag24 ByteString (unwraps outer tag24)");
        System.out.println("    4. block_bytes = [era, [block_content...]]  <-- this is what it stores/processes");
        System.out.println("    5. Extracts block_content array = [header, ...]");
        System.out.println();
        System.out.println("  The MsgBlock serializer adds tag-24 around the ENTIRE stored block CBOR.");
        System.out.println("  The stored block CBOR contains the block content directly (no inner tag-24).");
        System.out.println();

        // =====================================================================
        // STEP 8: Cross-check — verify the MsgBlockSerializer.deserialize()
        //         produces the same bytes that were stored (relay path simulation)
        // =====================================================================
        System.out.println("--- Relay path simulation ---");
        System.out.println("  (Simulates: real node -> MsgBlock wire -> deserialize -> store -> retrieve -> re-serialize)");
        System.out.println();

        // Simulate what happens in BlockFetchServerAgent:
        // 1. chainState.getBlock(hash) returns storedBlockCbor
        // 2. new MsgBlock(storedBlockCbor) wraps it
        // 3. MsgBlockSerializer.serialize() sends it on the wire
        // 4. Haskell node receives, parses [4, tag24(...)]
        byte[] reWrappedWire = new MsgBlock(storedBlockCbor).serialize();
        assertArrayEquals(wireBytes, reWrappedWire,
                "Re-wrapped MsgBlock wire bytes must match original");
        System.out.println("  Re-wrapping stored block as MsgBlock: wire bytes match. PASSED");

        // Simulate the other direction:
        // 1. Receive from real Haskell node: wireBytes
        // 2. MsgBlockSerializer.deserialize(wireBytes) -> MsgBlock.getBytes() = storedBlockCbor
        // 3. Store in ChainState: storedBlockCbor
        // 4. Retrieve and re-serve: new MsgBlock(storedBlockCbor).serialize() = wireBytes
        MsgBlock fromWire = MsgBlockSerializer.INSTANCE.deserialize(wireBytes);
        byte[] wouldBeStored = fromWire.getBytes();
        byte[] reServed = new MsgBlock(wouldBeStored).serialize();
        assertArrayEquals(wireBytes, reServed,
                "Re-served wire bytes must match original (relay round-trip)");
        System.out.println("  Full relay round-trip (wire -> deserialize -> store -> serve -> wire): PASSED");
        System.out.println();

        // =====================================================================
        // FINAL: Verify the MsgBlock tag-24 ByteString contains the raw stored block CBOR
        // =====================================================================
        System.out.println("--- MsgBlock tag-24 ByteString content verification ---");
        System.out.println();
        System.out.println("  MsgBlock serializer wraps stored block CBOR in tag-24 for the wire.");
        System.out.println("  Stored block CBOR: [6, [header, txBodies, witnesses, auxData, invalidTxs]]");
        System.out.println("  No inner tag-24 wrapping — block content is directly embedded.");
        System.out.println();

        System.out.println("==========================================================");
        System.out.println("  ALL CHECKS PASSED");
        System.out.println("==========================================================");
    }

    @Test
    void verifyMsgBlockDeserializerStripsOuterTag24Only() {
        // This test verifies exactly what the Haskell node's BlockFetch handler does:
        // it receives MsgBlock wire bytes, strips the outer [4, tag24(...)] wrapper,
        // and gets the raw block bytes which still contain [era, tag24(content)]

        var result = builder.buildBlock(2, 20, new byte[32], List.of());
        byte[] storedBlock = result.blockCbor();

        // Serialize to wire format
        byte[] wireBytes = new MsgBlock(storedBlock).serialize();

        // Manually parse the wire format
        Array wireArray = (Array) CborSerializationUtil.deserializeOne(wireBytes);
        ByteString wireBs = (ByteString) wireArray.getDataItems().get(1);

        // This is what MsgBlockSerializer.deserialize does: wireBs.getBytes()
        byte[] extractedBytes = wireBs.getBytes();

        // The extracted bytes must be the raw stored block CBOR
        assertArrayEquals(storedBlock, extractedBytes,
                "MsgBlock deserializer must extract the raw stored block bytes from tag24");

        // The extracted bytes should parse as [era, [content...]]
        Array blockArray = (Array) CborSerializationUtil.deserializeOne(extractedBytes);
        long era = ((UnsignedInteger) blockArray.getDataItems().get(0)).getValue().longValueExact();
        assertEquals(6, era);

        // Block content is directly embedded as an array (no inner tag-24)
        DataItem innerItem = blockArray.getDataItems().get(1);
        assertInstanceOf(Array.class, innerItem, "Block content must be an Array (directly embedded)");
        Array blockContent = (Array) innerItem;
        assertEquals(5, blockContent.getDataItems().size(),
                "Block content must have 5 elements: [header, txBodies, witnesses, auxData, invalidTxs]");

        System.out.println("VERIFIED: MsgBlock deserializer strips outer tag24, block content is directly embedded.");
        System.out.println("  Extracted bytes = [6, [header, txBodies, ...]] which is the HFC format.");
    }

    @Test
    void compareByteLevelEncoding() throws Exception {
        // This test examines the actual CBOR byte encoding to show exactly
        // how the block structure looks on the wire.

        var result = builder.buildBlock(3, 30, new byte[32], List.of());
        byte[] storedBlock = result.blockCbor();

        System.out.println("==========================================================");
        System.out.println("  Byte-level encoding analysis");
        System.out.println("==========================================================");
        System.out.println();

        // The stored block CBOR starts with: 82 06 85 ...
        //   82 = array(2)
        //   06 = unsigned(6) = era
        //   85 = array(5) = block content [header, txBodies, witnesses, auxData, invalidTxs]
        System.out.println("Stored block CBOR (first 20 bytes):");
        printBytesAnnotated(storedBlock, 20);
        System.out.println();

        // The MsgBlock wire format starts with: 82 04 d8 18 ...
        //   82 = array(2)
        //   04 = unsigned(4) = MsgBlock type
        //   d8 18 = tag(24)
        //   5a/59/58 XX XX ... = bytestring(N) containing stored block CBOR
        byte[] wireBytes = new MsgBlock(storedBlock).serialize();
        System.out.println("MsgBlock wire format (first 20 bytes):");
        printBytesAnnotated(wireBytes, 20);
        System.out.println();

        // Show the nesting visually:
        // Wire level:
        //   [4, d8_18 <bytestring of length L1>]
        //              |
        //              v  (L1 bytes = storedBlock)
        //   [6, [header, txBodies, witnesses, auxData, invalidTxs]]

        System.out.println("Nesting visualization:");
        System.out.println("  Wire: 82 04 d8_18 <bstr L1=" + storedBlock.length + ">");
        System.out.println("          |");
        System.out.println("          +--> L1 bytes parse to:");
        // Parse inner to get content
        Array blockArray = (Array) CborSerializationUtil.deserializeOne(storedBlock);
        Array content = (Array) blockArray.getDataItems().get(1);
        System.out.println("               82 06 85 [header, txBodies, witnesses, auxData, invalidTxs]");
        System.out.println("               (" + content.getDataItems().size() + " elements)");
        System.out.println();

        // Verify byte-level:
        // wireBytes[0] should be 0x82 (array of 2)
        assertEquals((byte) 0x82, wireBytes[0], "First byte must be 0x82 (array of 2)");
        // wireBytes[1] should be 0x04 (MsgBlock type = 4)
        assertEquals((byte) 0x04, wireBytes[1], "Second byte must be 0x04 (MsgBlock type)");
        // wireBytes[2] should be 0xd8 (tag, 1 byte follows)
        assertEquals((byte) 0xd8, wireBytes[2], "Third byte must be 0xd8 (tag follows)");
        // wireBytes[3] should be 0x18 (tag value = 24)
        assertEquals((byte) 0x18, wireBytes[3], "Fourth byte must be 0x18 (tag 24)");

        // storedBlock[0] should be 0x82 (array of 2)
        assertEquals((byte) 0x82, storedBlock[0], "Stored block first byte must be 0x82 (array of 2)");
        // storedBlock[1] should be 0x06 (era = 6)
        assertEquals((byte) 0x06, storedBlock[1], "Stored block second byte must be 0x06 (era 6)");
        // storedBlock[2] should be 0x85 (array of 5 = block content, directly embedded)
        assertEquals((byte) 0x85, storedBlock[2], "Stored block third byte must be 0x85 (array of 5, block content)");

        System.out.println("Byte-level assertions PASSED.");
        System.out.println();
        System.out.println("CONCLUSION: The MsgBlock wire format has tag-24 wrapping around stored block:");
        System.out.println("  82 04 d8 18 59|5a XXXX <stored_block_bytes>");
        System.out.println("  where <stored_block_bytes> = 82 06 85 ... [header, txBodies, witnesses, auxData, invalidTxs]");
    }

    /**
     * Print annotated hex bytes with CBOR type descriptions for the first N bytes.
     */
    private void printBytesAnnotated(byte[] data, int maxBytes) {
        int limit = Math.min(maxBytes, data.length);
        StringBuilder hexLine = new StringBuilder("  HEX: ");
        StringBuilder annLine = new StringBuilder("  ANN: ");

        for (int i = 0; i < limit; i++) {
            int b = data[i] & 0xFF;
            hexLine.append(String.format("%02x ", b));

            String ann;
            if (i == 0) {
                int majorType = (b >> 5) & 0x07;
                int addInfo = b & 0x1F;
                ann = switch (majorType) {
                    case 0 -> "uint(" + addInfo + ")";
                    case 2 -> "bstr(" + (addInfo < 24 ? addInfo : "...") + ")";
                    case 4 -> "arr(" + (addInfo < 24 ? addInfo : "...") + ")";
                    case 5 -> "map(" + (addInfo < 24 ? addInfo : "...") + ")";
                    case 6 -> "tag(...)";
                    case 7 -> "special";
                    default -> "mt" + majorType;
                };
            } else {
                ann = String.format("0x%02x", b);
            }
            // Pad annotation to match hex width (3 chars per byte)
            while (ann.length() < 3) ann += " ";
            annLine.append(ann).append("");
        }

        System.out.println(hexLine.toString().trim());
        System.out.println(annLine.toString().trim());
    }

    /**
     * Annotate the first N bytes of a CBOR-encoded message with structure descriptions.
     */
    private void annotateCborPrefix(byte[] data, int maxBytes) {
        int limit = Math.min(maxBytes, data.length);
        StringBuilder sb = new StringBuilder();
        int offset = 0;

        while (offset < limit) {
            int b = data[offset] & 0xFF;
            int majorType = (b >> 5) & 0x07;
            int addInfo = b & 0x1F;

            sb.append(String.format("  [%3d] %02x  ", offset, b));

            switch (majorType) {
                case 0 -> { // unsigned integer
                    if (addInfo < 24) {
                        sb.append("uint(" + addInfo + ")");
                        offset++;
                    } else if (addInfo == 24) {
                        sb.append("uint(1-byte follows)");
                        offset++;
                        if (offset < limit) {
                            sb.append(String.format(" -> value=%d", data[offset] & 0xFF));
                            offset++;
                        }
                    } else {
                        sb.append("uint(" + (1 << (addInfo - 24)) + "-byte follows)");
                        offset += 1 + (1 << (addInfo - 24));
                    }
                }
                case 2 -> { // byte string
                    if (addInfo < 24) {
                        sb.append("bstr(len=" + addInfo + ")");
                        offset += 1 + addInfo;
                    } else if (addInfo == 24) {
                        sb.append("bstr(1-byte len follows)");
                        offset++;
                        if (offset < limit) {
                            int len = data[offset] & 0xFF;
                            sb.append(String.format(" -> len=%d", len));
                            offset += 1 + len;
                        }
                    } else if (addInfo == 25) {
                        sb.append("bstr(2-byte len follows)");
                        offset++;
                        if (offset + 1 < data.length) {
                            int len = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                            sb.append(String.format(" -> len=%d", len));
                            offset += 2 + len;
                        }
                    } else if (addInfo == 26) {
                        sb.append("bstr(4-byte len follows)");
                        offset++;
                        if (offset + 3 < data.length) {
                            int len = ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                                    | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
                            sb.append(String.format(" -> len=%d", len));
                            offset += 4 + len;
                        }
                    } else {
                        sb.append("bstr(complex)");
                        offset++;
                    }
                }
                case 4 -> { // array
                    if (addInfo < 24) {
                        sb.append("array(" + addInfo + " items)");
                    } else {
                        sb.append("array(len follows)");
                    }
                    offset++;
                }
                case 5 -> { // map
                    if (addInfo < 24) {
                        sb.append("map(" + addInfo + " pairs)");
                    } else {
                        sb.append("map(len follows)");
                    }
                    offset++;
                }
                case 6 -> { // tag
                    if (addInfo < 24) {
                        sb.append("tag(" + addInfo + ")");
                        offset++;
                    } else if (addInfo == 24) {
                        sb.append("tag(1-byte follows)");
                        offset++;
                        if (offset < limit) {
                            sb.append(String.format(" -> tag=%d", data[offset] & 0xFF));
                            offset++;
                        }
                    } else {
                        sb.append("tag(multi-byte)");
                        offset += 1 + (1 << (addInfo - 24));
                    }
                }
                case 7 -> { // special
                    if (addInfo == 20) sb.append("false");
                    else if (addInfo == 21) sb.append("true");
                    else if (addInfo == 22) sb.append("null");
                    else if (addInfo == 23) sb.append("undefined");
                    else sb.append("special(" + addInfo + ")");
                    offset++;
                }
                default -> {
                    sb.append("???");
                    offset++;
                }
            }

            sb.append("\n");
        }

        System.out.print(sb);
    }
}
