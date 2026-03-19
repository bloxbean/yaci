package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class DevnetBlockBuilderTest {

    private DevnetBlockBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new DevnetBlockBuilder();
    }

    @Test
    void buildGenesisBlock_shouldBeDeserializableByBlockSerializer() {
        var result = builder.buildBlock(0, 0, null, List.of());

        assertNotNull(result.blockCbor());
        assertNotNull(result.blockHash());
        assertThat(result.blockHash()).hasSize(32);
        assertEquals(0, result.blockNumber());
        assertEquals(0, result.slot());

        // Verify BlockSerializer can parse it
        Block block = BlockSerializer.INSTANCE.deserialize(result.blockCbor());
        assertNotNull(block);
        assertNotNull(block.getHeader());
        assertEquals(0, block.getHeader().getHeaderBody().getBlockNumber());
        assertEquals(0, block.getHeader().getHeaderBody().getSlot());
        assertNull(block.getHeader().getHeaderBody().getPrevHash());
        assertThat(block.getTransactionBodies()).isEmpty();
    }

    @Test
    void buildGenesisBlock_wrappedHeaderShouldBeDeserializableByBlockHeaderSerializer() {
        var result = builder.buildBlock(0, 0, null, List.of());

        // Wrapped header is [era, h'<serialized_header>']
        // BlockHeaderSerializer.deserializeDI() expects the ByteString element
        var wrappedDI = CborSerializationUtil.deserializeOne(result.wrappedHeaderCbor());
        Array wrappedArray = (Array) wrappedDI;
        // Element 1 is the ByteString header
        var headerByteString = wrappedArray.getDataItems().get(1);

        BlockHeader header = BlockHeaderSerializer.INSTANCE.deserializeDI(headerByteString);
        assertNotNull(header);
        assertEquals(0, header.getHeaderBody().getBlockNumber());
        assertEquals(0, header.getHeaderBody().getSlot());
        assertNull(header.getHeaderBody().getPrevHash());
    }

    @Test
    void buildBlock_blockHashMatchesHeaderSerializerDerivedHash() {
        var result = builder.buildBlock(1, 100, new byte[32], List.of());

        // Parse using BlockSerializer and check that the derived block hash matches
        Block block = BlockSerializer.INSTANCE.deserialize(result.blockCbor());
        String derivedHash = block.getHeader().getHeaderBody().getBlockHash();
        String expectedHash = HexUtil.encodeHexString(result.blockHash());

        assertEquals(expectedHash, derivedHash);
    }

    @Test
    void buildBlock_chainContinuity() {
        // Build genesis
        var genesis = builder.buildBlock(0, 0, null, List.of());

        // Build block 1 with genesis hash as prevHash
        var block1 = builder.buildBlock(1, 10, genesis.blockHash(), List.of());

        // Parse block 1 and verify prevHash matches genesis block hash
        Block parsed = BlockSerializer.INSTANCE.deserialize(block1.blockCbor());
        String prevHash = parsed.getHeader().getHeaderBody().getPrevHash();
        String genesisHash = HexUtil.encodeHexString(genesis.blockHash());

        assertEquals(genesisHash, prevHash);
    }

    @Test
    void buildBlock_withTransaction() throws Exception {
        // Build a minimal but valid-looking transaction CBOR: [body, witnesses, true, null]
        byte[] txCbor = buildSampleTxCbor();

        var result = builder.buildBlock(1, 50, new byte[32], List.of(txCbor));

        // Verify BlockSerializer can parse it and finds one transaction
        Block block = BlockSerializer.INSTANCE.deserialize(result.blockCbor());
        assertThat(block.getTransactionBodies()).hasSize(1);
    }

    @Test
    void buildBlock_withMultipleTransactions() throws Exception {
        byte[] tx1 = buildSampleTxCbor();
        byte[] tx2 = buildSampleTxCbor();

        var result = builder.buildBlock(2, 100, new byte[32], List.of(tx1, tx2));

        Block block = BlockSerializer.INSTANCE.deserialize(result.blockCbor());
        assertThat(block.getTransactionBodies()).hasSize(2);
    }

    @Test
    void buildBlock_differentBlocksHaveDifferentHashes() {
        var block1 = builder.buildBlock(1, 10, new byte[32], List.of());
        var block2 = builder.buildBlock(2, 20, block1.blockHash(), List.of());

        assertFalse(Arrays.equals(block1.blockHash(), block2.blockHash()));
    }

    @Test
    void buildBlock_conwayEra() {
        var result = builder.buildBlock(0, 0, null, List.of());

        // Verify era tag is 6 (Conway era ID)
        var di = CborSerializationUtil.deserializeOne(result.blockCbor());
        Array arr = (Array) di;
        int era = ((UnsignedInteger) arr.getDataItems().get(0)).getValue().intValue();
        assertEquals(6, era);
    }

    /**
     * Same as extractRawBytesAndCompare but uses a non-canonically encoded transaction
     * (map keys in descending order, which canonical mode will re-sort).
     * This simulates real-world tx submission where wallets produce non-canonical CBOR.
     *
     * The Haskell node's decodeWithBytes captures the bytes AS-IS from the block CBOR stream.
     * If computeBlockBody re-serializes components (canonical mode), but the block CBOR
     * was also serialized canonical, they should still match -- unless the CBOR library
     * produces different canonical forms for nested vs standalone serialization.
     *
     * More critically: if we ever embed raw (non-canonical) tx bytes directly into the
     * block rather than round-tripping through DataItem, this would break.
     */
    @Test
    void extractRawBytesAndCompare_nonCanonicalTx() throws Exception {
        byte[] nonCanonicalTx = buildNonCanonicalTxCbor();
        System.out.println("=== NON-CANONICAL TX TEST ===");
        System.out.println("Non-canonical tx hex: " + HexUtil.encodeHexString(nonCanonicalTx));

        // Show that the tx body bytes change after round-trip through CBOR deserialize + canonical serialize
        var txDI = CborSerializationUtil.deserializeOne(nonCanonicalTx);
        byte[] reserializedTx = CborSerializationUtil.serialize(txDI);
        boolean txRoundTripMatches = Arrays.equals(nonCanonicalTx, reserializedTx);
        System.out.println("Tx bytes match after canonical round-trip: " + txRoundTripMatches);
        if (!txRoundTripMatches) {
            System.out.println("  Original:      " + HexUtil.encodeHexString(nonCanonicalTx));
            System.out.println("  Re-serialized: " + HexUtil.encodeHexString(reserializedTx));
        }

        // Now build a block with this non-canonical tx
        // DevnetBlockBuilder.splitTransaction deserializes the tx and adds DataItems to arrays.
        // Then computeBlockBody serializes each array canonically.
        // The full block is also serialized canonically.
        // So both paths go through canonical serialization and should match.
        extractAndCompareForBlock(List.of(nonCanonicalTx));
    }

    /**
     * Build a non-canonical tx where map keys are in descending order (2, 1, 0 instead of 0, 1, 2).
     * Canonical CBOR requires ascending key order by encoded bytes, so this will differ.
     */
    private byte[] buildNonCanonicalTxCbor() throws Exception {
        // Build tx body with keys in reverse order using non-canonical encoder
        Map txBody = new Map();
        // fee first (key 2) -- reversed order
        txBody.put(new UnsignedInteger(2), new UnsignedInteger(200000));
        // outputs (key 1)
        Array outputs = new Array();
        Map output = new Map();
        output.put(new UnsignedInteger(1), new UnsignedInteger(1000000));
        output.put(new UnsignedInteger(0), new ByteString(new byte[28]));
        outputs.add(output);
        txBody.put(new UnsignedInteger(1), outputs);
        // inputs (key 0)
        Array inputs = new Array();
        Array input = new Array();
        input.add(new ByteString(new byte[32]));
        input.add(new UnsignedInteger(0));
        inputs.add(input);
        txBody.put(new UnsignedInteger(0), inputs);

        Map witnesses = new Map();

        Array tx = new Array();
        tx.add(txBody);
        tx.add(witnesses);
        tx.add(co.nstant.in.cbor.model.SimpleValue.TRUE);
        tx.add(co.nstant.in.cbor.model.SimpleValue.NULL);

        // Serialize WITHOUT canonical mode to preserve insertion order
        return CborSerializationUtil.serialize(tx, false);
    }

    /**
     * Build a minimal sample transaction CBOR: [body_map, witnesses_map, true, null]
     */
    private byte[] buildSampleTxCbor() throws Exception {
        // Minimal tx body: {0: [[h'...', 0]], 1: [{0: h'...', 1: 1000000}], 2: 200000}
        Map txBody = new Map();
        // inputs: [[txhash, index]]
        Array inputs = new Array();
        Array input = new Array();
        input.add(new ByteString(new byte[32])); // tx hash
        input.add(new UnsignedInteger(0));       // index
        inputs.add(input);
        txBody.put(new UnsignedInteger(0), inputs);

        // outputs: [{0: address, 1: amount}]
        Array outputs = new Array();
        Map output = new Map();
        output.put(new UnsignedInteger(0), new ByteString(new byte[28])); // address hash
        output.put(new UnsignedInteger(1), new UnsignedInteger(1000000));
        outputs.add(output);
        txBody.put(new UnsignedInteger(1), outputs);

        // fee
        txBody.put(new UnsignedInteger(2), new UnsignedInteger(200000));

        // witnesses: empty map
        Map witnesses = new Map();

        // Build: [body, witnesses, true, null]
        Array tx = new Array();
        tx.add(txBody);
        tx.add(witnesses);
        tx.add(co.nstant.in.cbor.model.SimpleValue.TRUE);
        tx.add(co.nstant.in.cbor.model.SimpleValue.NULL);

        return CborSerializationUtil.serialize(tx);
    }

    @Test
    void buildBlock_verifyBodyHashComputation() {
        var result = builder.buildBlock(1, 1, new byte[32], List.of());
        
        // Decode block — unwrap tag-24 (wrapCBORinCBOR format)
        Array fullBlock = (Array) CborSerializationUtil.deserializeOne(result.blockCbor());
        Array blockContent = BlockTestUtil.unwrapTag24BlockContent(fullBlock);

        // Get declared body hash from header
        Array headerArray = (Array) blockContent.getDataItems().get(0);
        Array headerBody = (Array) headerArray.getDataItems().get(0);
        byte[] declaredBodyHash = ((ByteString) headerBody.getDataItems().get(7)).getBytes();
        
        // Compute body hash the Alonzo/Conway way: two-level segregated witness hash
        byte[] txBodiesBytes = CborSerializationUtil.serialize(blockContent.getDataItems().get(1));
        byte[] witnessesBytes = CborSerializationUtil.serialize(blockContent.getDataItems().get(2));
        byte[] auxDataBytes = CborSerializationUtil.serialize(blockContent.getDataItems().get(3));
        byte[] invalidTxsBytes = CborSerializationUtil.serialize(blockContent.getDataItems().get(4));

        byte[] h1 = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(txBodiesBytes);
        byte[] h2 = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(witnessesBytes);
        byte[] h3 = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(auxDataBytes);
        byte[] h4 = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(invalidTxsBytes);
        byte[] combined = new byte[128];
        System.arraycopy(h1, 0, combined, 0, 32);
        System.arraycopy(h2, 0, combined, 32, 32);
        System.arraycopy(h3, 0, combined, 64, 32);
        System.arraycopy(h4, 0, combined, 96, 32);
        byte[] computedHash = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(combined);

        System.out.println("Declared body hash: " + HexUtil.encodeHexString(declaredBodyHash));
        System.out.println("Computed body hash: " + HexUtil.encodeHexString(computedHash));

        assertArrayEquals(declaredBodyHash, computedHash, "Body hash should match Alonzo/Conway two-level hash");
    }

    @Test
    void buildBlock_headerBytesConsistentBetweenWrappedHeaderAndFullBlock() {
        var result = builder.buildBlock(1, 1, new byte[32], List.of());

        // Extract header bytes from the wrapped header (ChainSync format)
        Array wrappedHeader = (Array) CborSerializationUtil.deserializeOne(result.wrappedHeaderCbor());
        ByteString headerBS = (ByteString) wrappedHeader.getDataItems().get(1);
        byte[] headerBytesFromWrapped = headerBS.getBytes();

        // Extract header from full block and re-serialize — unwrap tag-24
        Array fullBlock = (Array) CborSerializationUtil.deserializeOne(result.blockCbor());
        Array blockContent = BlockTestUtil.unwrapTag24BlockContent(fullBlock);
        Array headerArrayFromBlock = (Array) blockContent.getDataItems().get(0);
        byte[] headerBytesFromBlock = CborSerializationUtil.serialize(headerArrayFromBlock);

        System.out.println("Header from wrapped: " + HexUtil.encodeHexString(headerBytesFromWrapped));
        System.out.println("Header from block:   " + HexUtil.encodeHexString(headerBytesFromBlock));
        System.out.println("Match: " + java.util.Arrays.equals(headerBytesFromWrapped, headerBytesFromBlock));

        // Block hash should be blake2b_256 of header bytes
        byte[] expectedBlockHash = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(headerBytesFromWrapped);
        byte[] expectedBlockHash2 = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(headerBytesFromBlock);

        System.out.println("Block hash from wrapped header: " + HexUtil.encodeHexString(expectedBlockHash));
        System.out.println("Block hash from block header:   " + HexUtil.encodeHexString(expectedBlockHash2));
        System.out.println("Declared block hash:            " + HexUtil.encodeHexString(result.blockHash()));

        assertArrayEquals(headerBytesFromWrapped, headerBytesFromBlock, "Header bytes should be identical");
        assertArrayEquals(expectedBlockHash, result.blockHash(), "Block hash should match");
    }

    /**
     * Extract raw bytes of each body component directly from the serialized block CBOR
     * (using byte-level offset tracking) and compare them against the standalone
     * serialization that computeBlockBody() uses for the body hash.
     *
     * This detects the Haskell "decodeWithBytes" mismatch: the Haskell node hashes
     * the raw bytes captured during deserialization, not re-serialized bytes.
     * If CborEncoder's canonical mode re-orders map keys or changes encoding during
     * nested vs standalone serialization, this test will catch it.
     */
    @Test
    void extractRawBytesAndCompare() throws Exception {
        // --- Helper: run for both empty block and block with 1 transaction ---
        System.out.println("=== EMPTY BLOCK (no transactions) ===");
        extractAndCompareForBlock(List.of());

        System.out.println("\n=== BLOCK WITH 1 TRANSACTION ===");
        extractAndCompareForBlock(List.of(buildSampleTxCbor()));
    }

    private void extractAndCompareForBlock(List<byte[]> txs) {
        var result = builder.buildBlock(1, 100, new byte[32], txs);
        byte[] blockCbor = result.blockCbor();

        // ---- Step 1: Extract raw bytes from the serialized block CBOR at byte level ----
        // Block structure (direct embedding, no tag-24):
        //   [era, [header, txBodies, witnesses, auxData, invalidTxs]]

        int pos = 0;

        // 1a. Skip outer array header (2-element array)
        pos = skipCborArrayHeader(blockCbor, pos);
        System.out.println("After outer array header, pos=" + pos);

        // 1b. Skip era integer
        pos = skipCborItem(blockCbor, pos);
        System.out.println("After era, pos=" + pos);

        // 1c. The inner content is directly embedded as a 5-element array (no tag-24 wrapping).
        // Work directly within blockCbor from the current position.
        byte[] innerBytes = blockCbor;
        int ipos = pos;

        // 1c. Skip inner array header (5-element array)
        ipos = skipCborArrayHeader(innerBytes, ipos);
        System.out.println("After inner array header, pos=" + ipos);

        // 1d. Skip header (complex nested structure)
        ipos = skipCborItem(innerBytes, ipos);
        System.out.println("After header, pos=" + ipos);

        // 1f. Record txBodies bytes
        int txBodiesStart = ipos;
        ipos = skipCborItem(innerBytes, ipos);
        int txBodiesEnd = ipos;
        byte[] rawTxBodies = Arrays.copyOfRange(innerBytes, txBodiesStart, txBodiesEnd);
        System.out.println("txBodies: offset=" + txBodiesStart + "-" + txBodiesEnd + " (" + rawTxBodies.length + " bytes)");

        // 1g. Record witnesses bytes
        int witnessesStart = ipos;
        ipos = skipCborItem(innerBytes, ipos);
        int witnessesEnd = ipos;
        byte[] rawWitnesses = Arrays.copyOfRange(innerBytes, witnessesStart, witnessesEnd);
        System.out.println("witnesses: offset=" + witnessesStart + "-" + witnessesEnd + " (" + rawWitnesses.length + " bytes)");

        // 1h. Record auxData bytes
        int auxDataStart = ipos;
        ipos = skipCborItem(innerBytes, ipos);
        int auxDataEnd = ipos;
        byte[] rawAuxData = Arrays.copyOfRange(innerBytes, auxDataStart, auxDataEnd);
        System.out.println("auxData: offset=" + auxDataStart + "-" + auxDataEnd + " (" + rawAuxData.length + " bytes)");

        // 1i. Record invalidTxs bytes
        int invalidTxsStart = ipos;
        ipos = skipCborItem(innerBytes, ipos);
        int invalidTxsEnd = ipos;
        byte[] rawInvalidTxs = Arrays.copyOfRange(innerBytes, invalidTxsStart, invalidTxsEnd);
        System.out.println("invalidTxs: offset=" + invalidTxsStart + "-" + invalidTxsEnd + " (" + rawInvalidTxs.length + " bytes)");

        assertEquals(innerBytes.length, ipos, "Should have consumed all inner bytes");

        // ---- Step 2: Get standalone serialization bytes (what computeBlockBody uses for hash) ----
        var body = builder.computeBlockBody(txs);
        byte[] standaloneTxBodies = CborSerializationUtil.serialize(body.txBodiesArray());
        byte[] standaloneWitnesses = CborSerializationUtil.serialize(body.txWitnessesArray());
        byte[] standaloneAuxData = CborSerializationUtil.serialize(body.auxDataMap());
        byte[] standaloneInvalidTxs = CborSerializationUtil.serialize(body.invalidTxsArray());

        // ---- Step 3: Compare ----
        System.out.println("\n--- Comparison ---");
        boolean txBodiesMatch = Arrays.equals(rawTxBodies, standaloneTxBodies);
        boolean witnessesMatch = Arrays.equals(rawWitnesses, standaloneWitnesses);
        boolean auxDataMatch = Arrays.equals(rawAuxData, standaloneAuxData);
        boolean invalidTxsMatch = Arrays.equals(rawInvalidTxs, standaloneInvalidTxs);

        System.out.println("txBodies match:   " + txBodiesMatch);
        if (!txBodiesMatch) {
            System.out.println("  RAW:        " + HexUtil.encodeHexString(rawTxBodies));
            System.out.println("  STANDALONE: " + HexUtil.encodeHexString(standaloneTxBodies));
            printFirstDifference(rawTxBodies, standaloneTxBodies, "txBodies");
        }

        System.out.println("witnesses match:  " + witnessesMatch);
        if (!witnessesMatch) {
            System.out.println("  RAW:        " + HexUtil.encodeHexString(rawWitnesses));
            System.out.println("  STANDALONE: " + HexUtil.encodeHexString(standaloneWitnesses));
            printFirstDifference(rawWitnesses, standaloneWitnesses, "witnesses");
        }

        System.out.println("auxData match:    " + auxDataMatch);
        if (!auxDataMatch) {
            System.out.println("  RAW:        " + HexUtil.encodeHexString(rawAuxData));
            System.out.println("  STANDALONE: " + HexUtil.encodeHexString(standaloneAuxData));
            printFirstDifference(rawAuxData, standaloneAuxData, "auxData");
        }

        System.out.println("invalidTxs match: " + invalidTxsMatch);
        if (!invalidTxsMatch) {
            System.out.println("  RAW:        " + HexUtil.encodeHexString(rawInvalidTxs));
            System.out.println("  STANDALONE: " + HexUtil.encodeHexString(standaloneInvalidTxs));
            printFirstDifference(rawInvalidTxs, standaloneInvalidTxs, "invalidTxs");
        }

        // ---- Step 4: Compute body hashes both ways ----
        byte[] rawH1 = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(rawTxBodies);
        byte[] rawH2 = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(rawWitnesses);
        byte[] rawH3 = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(rawAuxData);
        byte[] rawH4 = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(rawInvalidTxs);
        byte[] rawCombined = new byte[128];
        System.arraycopy(rawH1, 0, rawCombined, 0, 32);
        System.arraycopy(rawH2, 0, rawCombined, 32, 32);
        System.arraycopy(rawH3, 0, rawCombined, 64, 32);
        System.arraycopy(rawH4, 0, rawCombined, 96, 32);
        byte[] rawBodyHash = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(rawCombined);

        byte[] declaredBodyHash = body.bodyHash();

        System.out.println("\nBody hash from raw extracted bytes: " + HexUtil.encodeHexString(rawBodyHash));
        System.out.println("Body hash from standalone (header): " + HexUtil.encodeHexString(declaredBodyHash));
        System.out.println("Body hashes match: " + Arrays.equals(rawBodyHash, declaredBodyHash));

        // ---- Assertions ----
        assertArrayEquals(rawTxBodies, standaloneTxBodies,
                "txBodies: raw bytes from block CBOR must match standalone serialization");
        assertArrayEquals(rawWitnesses, standaloneWitnesses,
                "witnesses: raw bytes from block CBOR must match standalone serialization");
        assertArrayEquals(rawAuxData, standaloneAuxData,
                "auxData: raw bytes from block CBOR must match standalone serialization");
        assertArrayEquals(rawInvalidTxs, standaloneInvalidTxs,
                "invalidTxs: raw bytes from block CBOR must match standalone serialization");
        assertArrayEquals(rawBodyHash, declaredBodyHash,
                "Body hash from raw bytes must match declared body hash");
    }

    private void printFirstDifference(byte[] a, byte[] b, String label) {
        int minLen = Math.min(a.length, b.length);
        for (int i = 0; i < minLen; i++) {
            if (a[i] != b[i]) {
                System.out.printf("  FIRST DIFF at byte %d: raw=0x%02x standalone=0x%02x%n", i, a[i] & 0xFF, b[i] & 0xFF);
                // Print context: 5 bytes before and after
                int ctxStart = Math.max(0, i - 5);
                int ctxEndA = Math.min(a.length, i + 6);
                int ctxEndB = Math.min(b.length, i + 6);
                System.out.println("  RAW context:        " + HexUtil.encodeHexString(Arrays.copyOfRange(a, ctxStart, ctxEndA)));
                System.out.println("  STANDALONE context: " + HexUtil.encodeHexString(Arrays.copyOfRange(b, ctxStart, ctxEndB)));
                return;
            }
        }
        if (a.length != b.length) {
            System.out.printf("  LENGTH DIFF: raw=%d standalone=%d (content matches up to shorter length)%n", a.length, b.length);
        }
    }

    // ---- CBOR byte-level parser methods ----
    // These walk the raw CBOR bytes to determine the byte extent of each data item
    // without deserializing. This mimics what the Haskell "decodeWithBytes" captures.

    /**
     * Extract the payload bytes from a Tag-24 (wrapCBORinCBOR) item at position pos.
     * Format: Tag(24, ByteString(<payload>))
     * Major type 6 (tag), additionalInfo=24 => initial byte 0xd8, next byte 0x18
     * followed immediately by a ByteString (major type 2).
     */
    private byte[] extractTag24Content(byte[] data, int pos) {
        int initialByte = data[pos] & 0xFF;
        int majorType = initialByte >> 5;
        assertEquals(6, majorType, "Expected CBOR tag (major type 6) at pos " + pos + ", got " + majorType);
        int additionalInfo = initialByte & 0x1F;
        // Tag 24 is encoded as 0xd8 0x18 (additionalInfo=24 => 1 extra byte for tag number)
        assertEquals(24, additionalInfo, "Expected tag additionalInfo=24 (1-byte tag number) at pos " + pos);
        int tagNumber = data[pos + 1] & 0xFF;
        assertEquals(24, tagNumber, "Expected tag number 24 (wrapCBORinCBOR) at pos " + pos);
        // Now pos+2 should be a ByteString (major type 2)
        int bsPos = pos + 2;
        int bsInitial = data[bsPos] & 0xFF;
        int bsMajor = bsInitial >> 5;
        assertEquals(2, bsMajor, "Expected ByteString (major type 2) after tag-24 at pos " + bsPos + ", got " + bsMajor);
        int bsAdditional = bsInitial & 0x1F;
        long length = readCborLength(data, bsPos, bsAdditional);
        int headerSize = cborHeaderSize(bsAdditional);
        int contentStart = bsPos + headerSize;
        return Arrays.copyOfRange(data, contentStart, contentStart + (int) length);
    }

    /**
     * Skip an array header and return the position after the header.
     * Does NOT skip the array contents — just the initial byte(s) encoding the array length.
     */
    private int skipCborArrayHeader(byte[] data, int pos) {
        int initialByte = data[pos] & 0xFF;
        int majorType = initialByte >> 5;
        assertEquals(4, majorType, "Expected CBOR array (major type 4) at pos " + pos + ", got " + majorType);

        int additionalInfo = initialByte & 0x1F;
        if (additionalInfo <= 23) {
            return pos + 1; // length fits in the initial byte
        } else if (additionalInfo == 24) {
            return pos + 2; // 1-byte length follows
        } else if (additionalInfo == 25) {
            return pos + 3; // 2-byte length follows
        } else if (additionalInfo == 26) {
            return pos + 5; // 4-byte length follows
        } else if (additionalInfo == 27) {
            return pos + 9; // 8-byte length follows
        } else if (additionalInfo == 31) {
            return pos + 1; // indefinite length
        } else {
            throw new IllegalStateException("Unexpected additional info " + additionalInfo + " at pos " + pos);
        }
    }

    /**
     * Skip a complete CBOR data item (including all nested content) and return
     * the position after the item. This is the core of the byte-level parser.
     */
    private int skipCborItem(byte[] data, int pos) {
        if (pos >= data.length) {
            throw new IllegalStateException("Unexpected end of data at pos " + pos);
        }

        int initialByte = data[pos] & 0xFF;
        int majorType = initialByte >> 5;
        int additionalInfo = initialByte & 0x1F;

        return switch (majorType) {
            case 0, 1 -> // unsigned integer, negative integer
                    skipCborInteger(data, pos, additionalInfo);
            case 2, 3 -> // byte string, text string
                    skipCborString(data, pos, additionalInfo);
            case 4 -> // array
                    skipCborArray(data, pos, additionalInfo);
            case 5 -> // map
                    skipCborMap(data, pos, additionalInfo);
            case 6 -> // tag
                    skipCborTag(data, pos, additionalInfo);
            case 7 -> // simple values, float, break
                    skipCborSimple(data, pos, additionalInfo);
            default -> throw new IllegalStateException("Unknown CBOR major type " + majorType + " at pos " + pos);
        };
    }

    private int skipCborInteger(byte[] data, int pos, int additionalInfo) {
        if (additionalInfo <= 23) return pos + 1;
        if (additionalInfo == 24) return pos + 2;
        if (additionalInfo == 25) return pos + 3;
        if (additionalInfo == 26) return pos + 5;
        if (additionalInfo == 27) return pos + 9;
        throw new IllegalStateException("Invalid integer additional info " + additionalInfo + " at pos " + pos);
    }

    private int skipCborString(byte[] data, int pos, int additionalInfo) {
        if (additionalInfo == 31) {
            // Indefinite-length string: skip chunks until break
            pos++; // skip initial byte
            while ((data[pos] & 0xFF) != 0xFF) {
                pos = skipCborItem(data, pos);
            }
            return pos + 1; // skip break byte
        }
        long length = readCborLength(data, pos, additionalInfo);
        int headerSize = cborHeaderSize(additionalInfo);
        return pos + headerSize + (int) length;
    }

    private int skipCborArray(byte[] data, int pos, int additionalInfo) {
        if (additionalInfo == 31) {
            // Indefinite-length array
            pos++; // skip initial byte
            while ((data[pos] & 0xFF) != 0xFF) {
                pos = skipCborItem(data, pos);
            }
            return pos + 1; // skip break byte
        }
        long count = readCborLength(data, pos, additionalInfo);
        pos += cborHeaderSize(additionalInfo);
        for (long i = 0; i < count; i++) {
            pos = skipCborItem(data, pos);
        }
        return pos;
    }

    private int skipCborMap(byte[] data, int pos, int additionalInfo) {
        if (additionalInfo == 31) {
            // Indefinite-length map
            pos++; // skip initial byte
            while ((data[pos] & 0xFF) != 0xFF) {
                pos = skipCborItem(data, pos); // key
                pos = skipCborItem(data, pos); // value
            }
            return pos + 1; // skip break byte
        }
        long count = readCborLength(data, pos, additionalInfo);
        pos += cborHeaderSize(additionalInfo);
        for (long i = 0; i < count; i++) {
            pos = skipCborItem(data, pos); // key
            pos = skipCborItem(data, pos); // value
        }
        return pos;
    }

    private int skipCborTag(byte[] data, int pos, int additionalInfo) {
        // Tag: header + tag number + one data item
        pos += cborHeaderSize(additionalInfo);
        // Also need to account for extended tag numbers
        if (additionalInfo <= 23) {
            // tag number is in additionalInfo, no extra bytes (already accounted for)
        }
        // But cborHeaderSize already handles this. The tagged content follows.
        return skipCborItem(data, pos);
    }

    private int skipCborSimple(byte[] data, int pos, int additionalInfo) {
        if (additionalInfo <= 23) return pos + 1;  // simple value (false, true, null, undefined, etc.)
        if (additionalInfo == 24) return pos + 2;   // simple value in next byte
        if (additionalInfo == 25) return pos + 3;   // half-precision float
        if (additionalInfo == 26) return pos + 5;   // single-precision float
        if (additionalInfo == 27) return pos + 9;   // double-precision float
        if (additionalInfo == 31) return pos + 1;   // break
        throw new IllegalStateException("Invalid simple additional info " + additionalInfo + " at pos " + pos);
    }

    /**
     * Read the length value from a CBOR header.
     */
    private long readCborLength(byte[] data, int pos, int additionalInfo) {
        if (additionalInfo <= 23) return additionalInfo;
        if (additionalInfo == 24) return data[pos + 1] & 0xFF;
        if (additionalInfo == 25) return ((data[pos + 1] & 0xFFL) << 8) | (data[pos + 2] & 0xFFL);
        if (additionalInfo == 26) return ((data[pos + 1] & 0xFFL) << 24) | ((data[pos + 2] & 0xFFL) << 16)
                | ((data[pos + 3] & 0xFFL) << 8) | (data[pos + 4] & 0xFFL);
        if (additionalInfo == 27) {
            long val = 0;
            for (int i = 1; i <= 8; i++) {
                val = (val << 8) | (data[pos + i] & 0xFFL);
            }
            return val;
        }
        throw new IllegalStateException("Cannot read length for additional info " + additionalInfo);
    }

    /**
     * Return the number of bytes consumed by a CBOR header (initial byte + any length bytes).
     */
    private int cborHeaderSize(int additionalInfo) {
        if (additionalInfo <= 23) return 1;
        if (additionalInfo == 24) return 2;
        if (additionalInfo == 25) return 3;
        if (additionalInfo == 26) return 5;
        if (additionalInfo == 27) return 9;
        throw new IllegalStateException("Cannot compute header size for additional info " + additionalInfo);
    }

    @Test
    void dumpEmptyBlockCborHex() {
        var result = builder.buildBlock(0, 0, null, List.of());
        String blockHex = HexUtil.encodeHexString(result.blockCbor());
        String headerHex = HexUtil.encodeHexString(result.wrappedHeaderCbor());
        System.out.println("EMPTY_BLOCK_CBOR=" + blockHex);
        System.out.println("EMPTY_BLOCK_LENGTH=" + result.blockCbor().length);

        // Dump first 20 bytes to see structure
        System.out.println("FIRST_BYTES=" + blockHex.substring(0, Math.min(80, blockHex.length())));

        // Dump the body component bytes individually
        var body = builder.computeBlockBody(List.of());
        byte[] txBodiesBytes = CborSerializationUtil.serialize(body.txBodiesArray());
        byte[] witnessesBytes = CborSerializationUtil.serialize(body.txWitnessesArray());
        byte[] auxDataBytes = CborSerializationUtil.serialize(body.auxDataMap());
        byte[] invalidTxsBytes = CborSerializationUtil.serialize(body.invalidTxsArray());
        System.out.println("txBodies_hex=" + HexUtil.encodeHexString(txBodiesBytes));
        System.out.println("witnesses_hex=" + HexUtil.encodeHexString(witnessesBytes));
        System.out.println("auxData_hex=" + HexUtil.encodeHexString(auxDataBytes));
        System.out.println("invalidTxs_hex=" + HexUtil.encodeHexString(invalidTxsBytes));
        System.out.println("bodyHash=" + HexUtil.encodeHexString(body.bodyHash()));
        System.out.println("bodySize=" + body.bodySize());
    }

}
