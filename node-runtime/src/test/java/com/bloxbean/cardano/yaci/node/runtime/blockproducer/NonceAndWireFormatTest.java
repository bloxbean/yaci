package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.BlockProducerKeys;
import com.bloxbean.cardano.client.crypto.vrf.cardano.CardanoVrfInput;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.MsgBlock;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers.MsgBlockSerializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies:
 * 1. Epoch nonce derivation from the ACTUAL devnet shelley-genesis.json matches blake2b_256(file_bytes).
 * 2. After block 0 at slot 0, the epoch nonce does NOT change (only evolving nonce is updated).
 * 3. Block 1 uses the same epoch nonce, and we print the VRF input (alpha), proof, and output.
 * 4. MsgBlock wire format bytes vs raw blockCbor bytes are dumped for comparison.
 */
class NonceAndWireFormatTest {

    // Genesis params from node-app/config/network/devnet/shelley-genesis.json
    private static final long EPOCH_LENGTH = 600;
    private static final long SECURITY_PARAM = 100;
    private static final double ACTIVE_SLOTS_COEFF = 1.0;
    private static final long SLOTS_PER_KES_PERIOD = 129600;
    private static final long MAX_KES_EVOLUTIONS = 60;

    // Path to the ACTUAL genesis file used by node-app
    private static final Path ACTUAL_GENESIS_PATH =
            Paths.get("../node-app/config/network/devnet/shelley-genesis.json");

    private static BlockProducerKeys keys;
    private static byte[] genesisFileBytes;
    private static byte[] genesisHash;

    @BeforeAll
    static void setUp() throws Exception {
        // Load keys from test resources
        Path base = Paths.get("src/test/resources/devnet");
        keys = BlockProducerKeys.load(
                base.resolve("vrf.skey"),
                base.resolve("kes.skey"),
                base.resolve("opcert.cert")
        );

        // Read the ACTUAL genesis file from node-app config
        genesisFileBytes = Files.readAllBytes(ACTUAL_GENESIS_PATH);
        genesisHash = Blake2bUtil.blake2bHash256(genesisFileBytes);

        System.out.println("============================================================");
        System.out.println("  SETUP: Genesis file loaded");
        System.out.println("============================================================");
        System.out.println("  Genesis file: " + ACTUAL_GENESIS_PATH.toAbsolutePath());
        System.out.println("  Genesis file size: " + genesisFileBytes.length + " bytes");
        System.out.println("  blake2b_256(file_bytes) = " + HexUtil.encodeHexString(genesisHash));
        System.out.println("  ^ This is the initial epoch nonce for epoch 0");
        System.out.println();
    }

    @Test
    void verifyEpochNonceDerivationAndEvolution() {
        System.out.println("============================================================");
        System.out.println("  TEST 1: Epoch Nonce Derivation and Evolution");
        System.out.println("============================================================");
        System.out.println();

        // =====================================================================
        // STEP 1: Verify EpochNonceState.initFromGenesis uses blake2b_256(file_bytes)
        // =====================================================================
        EpochNonceState nonceState = new EpochNonceState(EPOCH_LENGTH, SECURITY_PARAM, ACTIVE_SLOTS_COEFF);
        nonceState.initFromGenesis(genesisFileBytes);

        byte[] initialEpochNonce = nonceState.getEpochNonce();

        System.out.println("STEP 1: Verify epoch nonce = blake2b_256(genesis_file_bytes)");
        System.out.println("  Expected (blake2b_256): " + HexUtil.encodeHexString(genesisHash));
        System.out.println("  Actual (from state):    " + HexUtil.encodeHexString(initialEpochNonce));

        assertArrayEquals(genesisHash, initialEpochNonce,
                "Initial epoch nonce must equal blake2b_256(shelley-genesis.json bytes)");
        System.out.println("  MATCH: epoch nonce == blake2b_256(genesis file bytes)");
        System.out.println();

        // =====================================================================
        // STEP 2: Build block 0 at slot 0, capture epoch nonce before and after
        // =====================================================================
        SignedBlockBuilder builder = new SignedBlockBuilder(keys, SLOTS_PER_KES_PERIOD, MAX_KES_EVOLUTIONS,
                nonceState, null);

        byte[] epochNonceBeforeBlock0 = nonceState.getEpochNonce().clone();

        System.out.println("STEP 2: Build block 0 at slot 0 (genesis block)");
        System.out.println("  Epoch nonce BEFORE buildBlock(0, 0, null, []): " +
                HexUtil.encodeHexString(epochNonceBeforeBlock0));

        DevnetBlockBuilder.BlockBuildResult block0 = builder.buildBlock(0, 0, null, List.of());

        byte[] epochNonceAfterBlock0 = nonceState.getEpochNonce();

        System.out.println("  Block 0 hash: " + HexUtil.encodeHexString(block0.blockHash()));
        System.out.println("  Epoch nonce AFTER  buildBlock(0, 0, null, []): " +
                HexUtil.encodeHexString(epochNonceAfterBlock0));

        // Verify epoch nonce did NOT change after block 0
        assertArrayEquals(epochNonceBeforeBlock0, epochNonceAfterBlock0,
                "Epoch nonce must NOT change after building block 0 at slot 0 " +
                        "(onBlockProduced only updates evolvingNonce, not epochNonce within same epoch)");

        System.out.println("  VERIFIED: epoch nonce unchanged after block 0");
        System.out.println();

        // =====================================================================
        // STEP 3: Build block 1 at slot 1 using prevHash from block 0
        // =====================================================================
        byte[] epochNonceBeforeBlock1 = nonceState.getEpochNonce().clone();

        System.out.println("STEP 3: Build block 1 at slot 1");
        System.out.println("  prevHash (from block 0): " + HexUtil.encodeHexString(block0.blockHash()));
        System.out.println("  Epoch nonce BEFORE buildBlock(1, 1, prevHash, []): " +
                HexUtil.encodeHexString(epochNonceBeforeBlock1));

        // Verify block 1 uses the SAME epoch nonce as block 0
        assertArrayEquals(initialEpochNonce, epochNonceBeforeBlock1,
                "Block 1 must use the same epoch nonce as block 0 (still epoch 0)");

        DevnetBlockBuilder.BlockBuildResult block1 = builder.buildBlock(1, 1, block0.blockHash(), List.of());

        byte[] epochNonceAfterBlock1 = nonceState.getEpochNonce();

        System.out.println("  Block 1 hash: " + HexUtil.encodeHexString(block1.blockHash()));
        System.out.println("  Epoch nonce AFTER  buildBlock(1, 1, ...): " +
                HexUtil.encodeHexString(epochNonceAfterBlock1));

        // Still same epoch (both slots 0 and 1 are in epoch 0)
        assertArrayEquals(initialEpochNonce, epochNonceAfterBlock1,
                "Epoch nonce must STILL not change after block 1 at slot 1 (same epoch 0)");
        System.out.println("  VERIFIED: epoch nonce still unchanged after block 1 (same epoch)");
        System.out.println();

        // =====================================================================
        // STEP 4: Print the VRF input (alpha) used for block 1
        // =====================================================================
        System.out.println("STEP 4: VRF input computation for block 1");

        long block1Slot = 1;
        byte[] epochNonceForBlock1 = initialEpochNonce; // same as genesis hash

        // Manually compute what CardanoVrfInput.mkInputVrf does:
        // alpha = blake2b_256(slot_8bytes_BE || epochNonce_32bytes)
        byte[] slotAndNonce = new byte[8 + 32];
        ByteBuffer.wrap(slotAndNonce).putLong(block1Slot);
        System.arraycopy(epochNonceForBlock1, 0, slotAndNonce, 8, 32);

        System.out.println("  slot_8bytes_BE:    " + HexUtil.encodeHexString(Arrays.copyOf(slotAndNonce, 8)));
        System.out.println("  epochNonce:        " + HexUtil.encodeHexString(epochNonceForBlock1));
        System.out.println("  raw input (40 bytes): slot_8BE || epochNonce");
        System.out.println("    hex: " + HexUtil.encodeHexString(slotAndNonce));

        byte[] alpha = CardanoVrfInput.mkInputVrf(block1Slot, epochNonceForBlock1);
        System.out.println("  alpha = blake2b_256(slot_8BE || epochNonce):");
        System.out.println("    " + HexUtil.encodeHexString(alpha));
        System.out.println();

        // =====================================================================
        // STEP 5: Extract VRF proof and output from block 1's header
        // =====================================================================
        System.out.println("STEP 5: VRF proof and output from block 1 header");

        DataItem blockDI = CborSerializationUtil.deserializeOne(block1.blockCbor());
        Array blockArray = (Array) blockDI;
        Array blockContent = BlockTestUtil.unwrapTag24BlockContent(blockArray);
        Array header = (Array) blockContent.getDataItems().get(0);
        Array headerBody = (Array) header.getDataItems().get(0);

        // Field 5: vrfResult [output(64), proof(80)]
        Array vrfResult = (Array) headerBody.getDataItems().get(5);
        byte[] vrfOutput = ((ByteString) vrfResult.getDataItems().get(0)).getBytes();
        byte[] vrfProof = ((ByteString) vrfResult.getDataItems().get(1)).getBytes();

        System.out.println("  VRF output (64 bytes): " + HexUtil.encodeHexString(vrfOutput));
        System.out.println("  VRF proof  (80 bytes): " + HexUtil.encodeHexString(vrfProof));
        assertThat(vrfOutput).hasSize(64);
        assertThat(vrfProof).hasSize(80);
        System.out.println();

        // =====================================================================
        // SUMMARY TABLE
        // =====================================================================
        System.out.println("============================================================");
        System.out.println("  SUMMARY: Epoch Nonce State Across Blocks 0 and 1");
        System.out.println("============================================================");
        System.out.println();
        System.out.println("  Genesis hash (= initial epoch nonce): " + HexUtil.encodeHexString(genesisHash));
        System.out.println();
        System.out.println("  Block 0 (slot=0, prevHash=null):");
        System.out.println("    epoch nonce used: " + HexUtil.encodeHexString(epochNonceBeforeBlock0));
        System.out.println("    block hash:       " + HexUtil.encodeHexString(block0.blockHash()));
        System.out.println();
        System.out.println("  Block 1 (slot=1, prevHash=block0.hash):");
        System.out.println("    epoch nonce used: " + HexUtil.encodeHexString(epochNonceBeforeBlock1));
        System.out.println("    VRF alpha input:  " + HexUtil.encodeHexString(alpha));
        System.out.println("    VRF output:       " + HexUtil.encodeHexString(vrfOutput));
        System.out.println("    VRF proof:        " + HexUtil.encodeHexString(vrfProof));
        System.out.println("    block hash:       " + HexUtil.encodeHexString(block1.blockHash()));
        System.out.println();
        System.out.println("  Epoch nonce SAME for both blocks? " +
                Arrays.equals(epochNonceBeforeBlock0, epochNonceBeforeBlock1));
        System.out.println();
    }

    @Test
    void dumpMsgBlockAndStoredBlockBytes() {
        System.out.println("============================================================");
        System.out.println("  TEST 2: MsgBlock Wire Format vs Stored Block Bytes");
        System.out.println("============================================================");
        System.out.println();

        // Build block 0 with fresh state
        EpochNonceState nonceState = new EpochNonceState(EPOCH_LENGTH, SECURITY_PARAM, ACTIVE_SLOTS_COEFF);
        nonceState.initFromGenesis(genesisFileBytes);

        SignedBlockBuilder builder = new SignedBlockBuilder(keys, SLOTS_PER_KES_PERIOD, MAX_KES_EVOLUTIONS,
                nonceState, null);

        DevnetBlockBuilder.BlockBuildResult block0 = builder.buildBlock(0, 0, null, List.of());

        byte[] blockCbor = block0.blockCbor();
        byte[] blockHash = block0.blockHash();

        // =====================================================================
        // STEP 1: Wrap in MsgBlock
        // =====================================================================
        System.out.println("STEP 1: Serialize block 0 as MsgBlock");

        MsgBlock msgBlock = new MsgBlock(blockCbor);
        byte[] msgBlockWire = MsgBlockSerializer.INSTANCE.serialize(msgBlock);

        System.out.println("  blockCbor length:     " + blockCbor.length + " bytes");
        System.out.println("  msgBlockWire length:  " + msgBlockWire.length + " bytes");
        System.out.println("  overhead (wire - block): " + (msgBlockWire.length - blockCbor.length) + " bytes");
        System.out.println();

        // =====================================================================
        // STEP 2: Print first 30 bytes of MsgBlock wire
        // =====================================================================
        int dumpSize = Math.min(30, msgBlockWire.length);
        System.out.println("STEP 2: First " + dumpSize + " bytes of MsgBlock wire format:");
        System.out.println("  hex: " + HexUtil.encodeHexString(Arrays.copyOf(msgBlockWire, dumpSize)));
        printAnnotatedBytes("  ", msgBlockWire, dumpSize);
        System.out.println();

        // =====================================================================
        // STEP 3: Print first 30 bytes of stored blockCbor
        // =====================================================================
        dumpSize = Math.min(30, blockCbor.length);
        System.out.println("STEP 3: First " + dumpSize + " bytes of stored blockCbor:");
        System.out.println("  hex: " + HexUtil.encodeHexString(Arrays.copyOf(blockCbor, dumpSize)));
        printAnnotatedBytes("  ", blockCbor, dumpSize);
        System.out.println();

        // =====================================================================
        // STEP 4: Print block hash
        // =====================================================================
        System.out.println("STEP 4: Block hash");
        System.out.println("  block hash: " + HexUtil.encodeHexString(blockHash));
        System.out.println();

        // =====================================================================
        // STEP 5: Verify MsgBlock round-trip
        // =====================================================================
        System.out.println("STEP 5: MsgBlock round-trip verification");
        MsgBlock deserialized = MsgBlockSerializer.INSTANCE.deserialize(msgBlockWire);
        assertNotNull(deserialized);
        assertArrayEquals(blockCbor, deserialized.getBytes(),
                "MsgBlock round-trip must preserve block bytes");
        System.out.println("  Round-trip: PASSED (deserialized bytes == original blockCbor)");
        System.out.println();

        // =====================================================================
        // STEP 6: Verify wire format structure
        // =====================================================================
        System.out.println("STEP 6: Wire format structure verification");

        // MsgBlock wire: [4, tag24(bytestring)]
        assertEquals((byte) 0x82, msgBlockWire[0], "MsgBlock wire[0] must be 0x82 (array of 2)");
        assertEquals((byte) 0x04, msgBlockWire[1], "MsgBlock wire[1] must be 0x04 (MsgBlock type = 4)");
        assertEquals((byte) 0xd8, msgBlockWire[2], "MsgBlock wire[2] must be 0xd8 (tag follows)");
        assertEquals((byte) 0x18, msgBlockWire[3], "MsgBlock wire[3] must be 0x18 (tag 24)");

        // Stored block: [6, [header, ...]]
        assertEquals((byte) 0x82, blockCbor[0], "blockCbor[0] must be 0x82 (array of 2)");
        assertEquals((byte) 0x06, blockCbor[1], "blockCbor[1] must be 0x06 (era 6 = Conway)");
        assertEquals((byte) 0x85, blockCbor[2], "blockCbor[2] must be 0x85 (array of 5 = block content)");

        System.out.println("  MsgBlock wire: [0x82=arr(2), 0x04=uint(4), 0xd8 0x18=tag(24), ...]");
        System.out.println("  blockCbor:     [0x82=arr(2), 0x06=uint(6), 0x85=arr(5), ...]");
        System.out.println("  Structure: VERIFIED");
        System.out.println();

        // =====================================================================
        // SUMMARY
        // =====================================================================
        System.out.println("============================================================");
        System.out.println("  SUMMARY: Wire Format");
        System.out.println("============================================================");
        System.out.println();
        System.out.println("  MsgBlock wire:  82 04 d8 18 <bstr_header> <stored_block_bytes>");
        System.out.println("  Stored block:   82 06 85 ... [header, txBodies, witnesses, auxData, invalidTxs]");
        System.out.println();
        System.out.println("  The MsgBlock serializer wraps the entire stored block CBOR");
        System.out.println("  inside a tag-24 ByteString. The Haskell node extracts the");
        System.out.println("  ByteString payload which is exactly the stored block CBOR.");
        System.out.println();
        System.out.println("  Block 0 hash: " + HexUtil.encodeHexString(blockHash));
        System.out.println("  Block 0 blockCbor[0..29]: " + HexUtil.encodeHexString(Arrays.copyOf(blockCbor, Math.min(30, blockCbor.length))));
        System.out.println("  MsgBlock wire[0..29]:     " + HexUtil.encodeHexString(Arrays.copyOf(msgBlockWire, Math.min(30, msgBlockWire.length))));
        System.out.println();
    }

    /**
     * Print annotated hex bytes with CBOR type hints for the first N bytes.
     */
    private void printAnnotatedBytes(String indent, byte[] data, int maxBytes) {
        int limit = Math.min(maxBytes, data.length);
        StringBuilder hex = new StringBuilder(indent + "raw: ");
        StringBuilder ann = new StringBuilder(indent + "ann: ");

        for (int i = 0; i < limit; i++) {
            int b = data[i] & 0xFF;
            hex.append(String.format("%02x ", b));

            String label;
            int majorType = (b >> 5) & 0x07;
            int addInfo = b & 0x1F;

            if (majorType == 0 && addInfo < 24) {
                label = "u" + addInfo;
            } else if (majorType == 2) {
                label = "bs";
            } else if (majorType == 4 && addInfo < 24) {
                label = "a" + addInfo;
            } else if (majorType == 5 && addInfo < 24) {
                label = "m" + addInfo;
            } else if (majorType == 6) {
                label = "tg";
            } else if (majorType == 7 && addInfo == 22) {
                label = "nl";
            } else {
                label = String.format("%02x", b);
            }

            // Pad to 3 chars to align with hex
            while (label.length() < 3) label += " ";
            ann.append(label);
        }

        System.out.println(hex.toString().trim());
        System.out.println(ann.toString().trim());
    }
}
