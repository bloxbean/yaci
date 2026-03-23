package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import co.nstant.in.cbor.model.*;
import co.nstant.in.cbor.model.Map;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.BlockProducerKeys;
import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.crypto.config.CryptoExtConfiguration;
import com.bloxbean.cardano.client.crypto.kes.KesVerifier;
import com.bloxbean.cardano.client.crypto.kes.Sum6KesSigner;
import com.bloxbean.cardano.client.crypto.vrf.VrfResult;
import com.bloxbean.cardano.client.crypto.vrf.VrfVerifier;
import com.bloxbean.cardano.client.crypto.vrf.cardano.CardanoVrfInput;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive end-to-end test that simulates exactly what a Haskell cardano-node
 * does when validating a block produced by Yaci's SignedBlockBuilder.
 * <p>
 * This test independently verifies every cryptographic and structural property of the
 * block CBOR output, following the same steps as the Haskell node's block validation:
 * <ol>
 *   <li>Block structure: [era, 24(h'inner')] tag-24 wrapping</li>
 *   <li>Block content: [header, txBodies, witnesses, auxData, invalidTxs]</li>
 *   <li>Block hash: blake2b_256(serialized header array)</li>
 *   <li>Body hash: two-level Alonzo/Conway segregated witness hash</li>
 *   <li>VRF proof verification</li>
 *   <li>KES signature verification</li>
 *   <li>Protocol version check</li>
 *   <li>Wrapped header format check</li>
 * </ol>
 */
class HaskellCompatibilityTest {

    // Genesis parameters from shelley-genesis.json
    private static final long EPOCH_LENGTH = 600;
    private static final long SECURITY_PARAM = 100;
    private static final double ACTIVE_SLOTS_COEFF = 1.0;
    private static final long SLOTS_PER_KES_PERIOD = 129600;
    private static final long MAX_KES_EVOLUTIONS = 60;

    private static BlockProducerKeys keys;
    private static byte[] genesisBytes;
    private static byte[] genesisEpochNonce;

    @BeforeAll
    static void setUp() throws Exception {
        Path base = Paths.get("src/test/resources/devnet");
        keys = BlockProducerKeys.load(
                base.resolve("vrf.skey"),
                base.resolve("kes.skey"),
                base.resolve("opcert.cert")
        );
        genesisBytes = Files.readAllBytes(base.resolve("shelley-genesis.json"));

        // Compute the initial epoch nonce: blake2b_256(shelleyGenesisFileBytes)
        genesisEpochNonce = Blake2bUtil.blake2bHash256(genesisBytes);
    }

    /**
     * Create a fresh SignedBlockBuilder with a clean EpochNonceState initialized from genesis.
     */
    private static SignedBlockBuilder createFreshBuilder() {
        EpochNonceState freshNonce = new EpochNonceState(EPOCH_LENGTH, SECURITY_PARAM, ACTIVE_SLOTS_COEFF);
        freshNonce.initFromGenesis(genesisBytes);
        return new SignedBlockBuilder(keys, SLOTS_PER_KES_PERIOD, MAX_KES_EVOLUTIONS, freshNonce, null);
    }

    @Test
    void fullHaskellBlockValidation() {
        // =====================================================================
        // STEP 1: Build a signed block
        // =====================================================================
        SignedBlockBuilder builder = createFreshBuilder();

        // Capture the epoch nonce BEFORE building (buildBlock evolves it internally)
        byte[] epochNonce = genesisEpochNonce.clone();

        long blockNumber = 1;
        long slot = 10;
        byte[] prevHash = new byte[32]; // zero-filled for first block after genesis

        DevnetBlockBuilder.BlockBuildResult result = builder.buildBlock(blockNumber, slot, prevHash, List.of());

        assertNotNull(result, "Block build result must not be null");
        assertNotNull(result.blockCbor(), "Block CBOR must not be null");
        assertNotNull(result.wrappedHeaderCbor(), "Wrapped header CBOR must not be null");
        assertThat(result.blockHash()).hasSize(32);
        assertEquals(blockNumber, result.blockNumber());
        assertEquals(slot, result.slot());

        System.out.println("=== STEP 1 PASSED: Block built successfully ===");
        System.out.println("  blockNumber=" + result.blockNumber() + ", slot=" + result.slot());
        System.out.println("  blockHash=" + HexUtil.encodeHexString(result.blockHash()));
        System.out.println("  blockCbor length=" + result.blockCbor().length);
        System.out.println("  wrappedHeaderCbor length=" + result.wrappedHeaderCbor().length);

        // =====================================================================
        // STEP 2: Verify block structure [blockType, [block_content...]]
        // blockType=7 for Conway (BlockType numbering: Byron EBB=0, Main=1, ..., Conway=7)
        // =====================================================================
        DataItem blockDI = CborSerializationUtil.deserializeOne(result.blockCbor());
        assertInstanceOf(Array.class, blockDI, "Top-level block CBOR must be an array");
        Array blockArray = (Array) blockDI;
        assertEquals(2, blockArray.getDataItems().size(), "Block array must have 2 elements: [blockType, content]");

        // Element 0: block type (BlockType numbering for BlockFetch)
        DataItem eraItem = blockArray.getDataItems().get(0);
        assertInstanceOf(UnsignedInteger.class, eraItem, "First element must be era integer");
        long eraId = ((UnsignedInteger) eraItem).getValue().longValueExact();
        assertEquals(7, eraId, "Era ID must be 7 (Conway BlockType for BlockFetch)");

        // Element 1: block content array (directly embedded, no tag-24 wrapping)
        DataItem innerItem = blockArray.getDataItems().get(1);
        assertInstanceOf(Array.class, innerItem, "Second element must be an Array (block content)");
        Array blockContent = (Array) innerItem;
        assertEquals(5, blockContent.getDataItems().size(),
                "Block content must have 5 elements: [header, txBodies, witnesses, auxData, invalidTxs]");

        System.out.println("=== STEP 2 PASSED: Block structure [blockType=7, [content...]] verified ===");
        System.out.println("  Inner content has " + blockContent.getDataItems().size() + " elements");

        // Extract the header array: [headerBody, kesSig]
        DataItem headerDI = blockContent.getDataItems().get(0);
        assertInstanceOf(Array.class, headerDI, "Header must be an array");
        Array header = (Array) headerDI;
        assertEquals(2, header.getDataItems().size(), "Header must have 2 elements: [headerBody, kesSig]");

        Array headerBody = (Array) header.getDataItems().get(0);
        byte[] kesSigFromBlock = ((ByteString) header.getDataItems().get(1)).getBytes();

        // =====================================================================
        // STEP 3: Verify block hash = blake2b_256(serialized header array)
        // =====================================================================
        byte[] headerArrayBytes = CborSerializationUtil.serialize(header);
        byte[] computedBlockHash = Blake2bUtil.blake2bHash256(headerArrayBytes);

        System.out.println("=== STEP 3: Block hash verification ===");
        System.out.println("  serialized header array length=" + headerArrayBytes.length);
        System.out.println("  computed blockHash=" + HexUtil.encodeHexString(computedBlockHash));
        System.out.println("  declared blockHash=" + HexUtil.encodeHexString(result.blockHash()));

        assertArrayEquals(computedBlockHash, result.blockHash(),
                "Block hash must equal blake2b_256(serialized header array [headerBody, kesSig])");
        System.out.println("=== STEP 3 PASSED: Block hash verified ===");

        // =====================================================================
        // STEP 4: Verify body hash (two-level Alonzo/Conway segregated witness)
        // =====================================================================
        System.out.println("=== STEP 4: Body hash verification (two-level) ===");

        // Extract the four body components from block content
        DataItem txBodiesDI = blockContent.getDataItems().get(1);
        DataItem txWitnessesDI = blockContent.getDataItems().get(2);
        DataItem auxDataDI = blockContent.getDataItems().get(3);
        DataItem invalidTxsDI = blockContent.getDataItems().get(4);

        // Re-serialize each component individually
        byte[] txBodiesBytes = CborSerializationUtil.serialize(txBodiesDI);
        byte[] txWitnessesBytes = CborSerializationUtil.serialize(txWitnessesDI);
        byte[] auxDataBytes = CborSerializationUtil.serialize(auxDataDI);
        byte[] invalidTxsBytes = CborSerializationUtil.serialize(invalidTxsDI);

        // Compute h1=hash(txBodies), h2=hash(witnesses), h3=hash(auxData), h4=hash(invalidTxs)
        byte[] h1 = Blake2bUtil.blake2bHash256(txBodiesBytes);
        byte[] h2 = Blake2bUtil.blake2bHash256(txWitnessesBytes);
        byte[] h3 = Blake2bUtil.blake2bHash256(auxDataBytes);
        byte[] h4 = Blake2bUtil.blake2bHash256(invalidTxsBytes);

        // Compute bodyHash = hash(h1 || h2 || h3 || h4)
        byte[] combined = new byte[128];
        System.arraycopy(h1, 0, combined, 0, 32);
        System.arraycopy(h2, 0, combined, 32, 32);
        System.arraycopy(h3, 0, combined, 64, 32);
        System.arraycopy(h4, 0, combined, 96, 32);
        byte[] computedBodyHash = Blake2bUtil.blake2bHash256(combined);

        // Extract declared body hash from header body field 7
        byte[] declaredBodyHash = ((ByteString) headerBody.getDataItems().get(7)).getBytes();

        System.out.println("  h1(txBodies)    = " + HexUtil.encodeHexString(h1));
        System.out.println("  h2(txWitnesses) = " + HexUtil.encodeHexString(h2));
        System.out.println("  h3(auxData)     = " + HexUtil.encodeHexString(h3));
        System.out.println("  h4(invalidTxs)  = " + HexUtil.encodeHexString(h4));
        System.out.println("  computed bodyHash = " + HexUtil.encodeHexString(computedBodyHash));
        System.out.println("  declared bodyHash = " + HexUtil.encodeHexString(declaredBodyHash));

        assertArrayEquals(computedBodyHash, declaredBodyHash,
                "Computed two-level body hash must match header body's declared body hash (index 7)");
        System.out.println("=== STEP 4 PASSED: Body hash verified ===");

        // =====================================================================
        // STEP 5: Verify VRF proof
        // =====================================================================
        System.out.println("=== STEP 5: VRF proof verification ===");

        // Extract vrfVkey (index 4) from header body
        byte[] vrfVkeyFromBlock = ((ByteString) headerBody.getDataItems().get(4)).getBytes();
        assertThat(vrfVkeyFromBlock).hasSize(32);

        // Extract vrfResult (index 5): [output(64), proof(80)]
        Array vrfResultArray = (Array) headerBody.getDataItems().get(5);
        assertEquals(2, vrfResultArray.getDataItems().size(), "VRF result must have 2 elements: [output, proof]");
        byte[] vrfOutputFromBlock = ((ByteString) vrfResultArray.getDataItems().get(0)).getBytes();
        byte[] vrfProofFromBlock = ((ByteString) vrfResultArray.getDataItems().get(1)).getBytes();
        assertThat(vrfOutputFromBlock).hasSize(64);
        assertThat(vrfProofFromBlock).hasSize(80);

        // Compute the VRF alpha input: blake2b_256(slot_8BE || epochNonce)
        byte[] vrfAlpha = CardanoVrfInput.mkInputVrf(slot, epochNonce);

        System.out.println("  vrfVkey         = " + HexUtil.encodeHexString(vrfVkeyFromBlock));
        System.out.println("  vrfOutput len   = " + vrfOutputFromBlock.length);
        System.out.println("  vrfProof len    = " + vrfProofFromBlock.length);
        System.out.println("  epochNonce      = " + HexUtil.encodeHexString(epochNonce));
        System.out.println("  vrfAlpha        = " + HexUtil.encodeHexString(vrfAlpha));

        // Verify the VRF proof against the vkey and alpha
        VrfVerifier vrfVerifier = CryptoExtConfiguration.INSTANCE.getVrfVerifier();
        VrfResult vrfVerified = vrfVerifier.verify(vrfVkeyFromBlock, vrfProofFromBlock, vrfAlpha);

        assertTrue(vrfVerified.isValid(), "VRF proof must verify against the reconstructed alpha input");
        assertArrayEquals(vrfOutputFromBlock, vrfVerified.getOutput(),
                "VRF output from block must match verified output");

        // Also verify the vrfVkey matches what we derive from the VRF secret key
        byte[] expectedVrfVkey = Arrays.copyOfRange(keys.getVrfSkey(), 32, 64);
        assertArrayEquals(expectedVrfVkey, vrfVkeyFromBlock,
                "VRF vkey in block must match last 32 bytes of VRF skey");

        System.out.println("=== STEP 5 PASSED: VRF proof verified, output matches ===");

        // =====================================================================
        // STEP 6: Verify KES signature
        // =====================================================================
        System.out.println("=== STEP 6: KES signature verification ===");

        // Verify the KES signature length
        assertThat(kesSigFromBlock).hasSize(448);

        // Serialize the header body to get the signed bytes (same as Haskell getSignableRepresentation)
        byte[] reserializedHeaderBodyBytes = CborSerializationUtil.serialize(headerBody);

        // Derive the KES root verification key from the KES secret key
        Sum6KesSigner signer = new Sum6KesSigner();
        byte[] kesRootVk = signer.deriveVerificationKey(keys.getKesSkey());

        // Compute the KES period
        int kesPeriod = (int) (slot / SLOTS_PER_KES_PERIOD);
        int opcertKesPeriod = (int) keys.getOpCert().getKesPeriod();
        int relativePeriod = kesPeriod - opcertKesPeriod;

        System.out.println("  KES sig length  = " + kesSigFromBlock.length);
        System.out.println("  KES root vk     = " + HexUtil.encodeHexString(kesRootVk));
        System.out.println("  headerBody CBOR len = " + reserializedHeaderBodyBytes.length);
        System.out.println("  kesPeriod       = " + kesPeriod);
        System.out.println("  opcertKesPeriod = " + opcertKesPeriod);
        System.out.println("  relativePeriod  = " + relativePeriod);

        // Verify KES signature using the re-serialized header body bytes
        // This is exactly what the Haskell node does: parse -> extract header body -> serialize -> verify
        KesVerifier kesVerifier = CryptoExtConfiguration.INSTANCE.getKesVerifier();
        boolean kesValid = kesVerifier.verify(kesSigFromBlock, reserializedHeaderBodyBytes,
                kesRootVk, relativePeriod);

        if (!kesValid) {
            System.err.println("  FAILED: KES signature verification failed!");
            System.err.println("  This means re-serialized header body bytes differ from what was signed,");
            System.err.println("  or the KES key/period is incorrect.");
            System.err.println("  headerBody CBOR hex = " + HexUtil.encodeHexString(reserializedHeaderBodyBytes));
        }
        assertTrue(kesValid,
                "KES signature must verify against re-serialized header body bytes " +
                "(simulates Haskell getSignableRepresentation)");

        // Also verify that the KES vkey from the operational cert matches the derived root vk
        byte[] opcertKesVkey = ((ByteString) ((Array) headerBody.getDataItems().get(8))
                .getDataItems().get(0)).getBytes();
        assertArrayEquals(kesRootVk, opcertKesVkey,
                "OpCert KES vkey must match root vk derived from KES skey");

        System.out.println("=== STEP 6 PASSED: KES signature verified ===");

        // =====================================================================
        // STEP 7: Verify protocol version in header body (index 9) matches [10, 2]
        // =====================================================================
        System.out.println("=== STEP 7: Protocol version verification ===");

        assertEquals(10, headerBody.getDataItems().size(), "Header body must have 10 fields");

        Array protoVersion = (Array) headerBody.getDataItems().get(9);
        assertEquals(2, protoVersion.getDataItems().size(), "Protocol version must have 2 elements: [major, minor]");
        long major = ((UnsignedInteger) protoVersion.getDataItems().get(0)).getValue().longValueExact();
        long minor = ((UnsignedInteger) protoVersion.getDataItems().get(1)).getValue().longValueExact();

        System.out.println("  protocolVersion = [" + major + ", " + minor + "]");
        assertEquals(10, major, "Protocol major version must be 10");
        assertEquals(2, minor, "Protocol minor version must be 2");

        System.out.println("=== STEP 7 PASSED: Protocol version [10, 2] verified ===");

        // =====================================================================
        // STEP 8: Verify wrapped header format [era, 24(h'<serialized_header_array>')]
        // =====================================================================
        System.out.println("=== STEP 8: Wrapped header format verification ===");

        DataItem wrappedDI = CborSerializationUtil.deserializeOne(result.wrappedHeaderCbor());
        assertInstanceOf(Array.class, wrappedDI, "Wrapped header must be an array");
        Array wrappedArray = (Array) wrappedDI;
        assertEquals(2, wrappedArray.getDataItems().size(),
                "Wrapped header must have 2 elements: [era, 24(h'...')]");

        // Element 0: era ID
        long wrappedEraId = ((UnsignedInteger) wrappedArray.getDataItems().get(0)).getValue().longValueExact();
        assertEquals(6, wrappedEraId, "Wrapped header era must be 6 (Conway)");

        // Element 1: tag-24 wrapped ByteString containing the serialized header array
        DataItem wrappedInner = wrappedArray.getDataItems().get(1);
        assertInstanceOf(ByteString.class, wrappedInner, "Wrapped header inner must be a ByteString");
        ByteString wrappedInnerBS = (ByteString) wrappedInner;
        assertEquals(24L, wrappedInnerBS.getTag().getValue(), "Wrapped header must have tag 24");

        // The inner bytes should be the serialized header array [headerBody, kesSig]
        byte[] wrappedHeaderInnerBytes = wrappedInnerBS.getBytes();

        // Verify the inner bytes match the header array serialized from the block content
        // (This ensures the wrapped header and the block's header are consistent)
        assertArrayEquals(headerArrayBytes, wrappedHeaderInnerBytes,
                "Wrapped header inner bytes must match the serialized header array from the block");

        // Also verify that the block hash can be computed from the wrapped header
        byte[] blockHashFromWrapped = Blake2bUtil.blake2bHash256(wrappedHeaderInnerBytes);
        assertArrayEquals(result.blockHash(), blockHashFromWrapped,
                "Block hash computed from wrapped header must match declared block hash");

        System.out.println("  Wrapped header inner bytes length = " + wrappedHeaderInnerBytes.length);
        System.out.println("  Matches block's header array bytes: true");
        System.out.println("  Block hash from wrapped header matches: true");
        System.out.println("=== STEP 8 PASSED: Wrapped header format verified ===");

        // =====================================================================
        // BONUS: Verify OpCert cold key signature
        // =====================================================================
        System.out.println("=== BONUS: OpCert cold key signature verification ===");

        Array opCertArray = (Array) headerBody.getDataItems().get(8);
        byte[] opcertKesVkeyFull = ((ByteString) opCertArray.getDataItems().get(0)).getBytes();
        long opcertCounter = ((UnsignedInteger) opCertArray.getDataItems().get(1)).getValue().longValueExact();
        long opcertPeriod = ((UnsignedInteger) opCertArray.getDataItems().get(2)).getValue().longValueExact();
        byte[] opcertColdSig = ((ByteString) opCertArray.getDataItems().get(3)).getBytes();

        // issuerVkey (cold vkey) is field 3 of the header body
        byte[] issuerVkeyFromBlock = ((ByteString) headerBody.getDataItems().get(3)).getBytes();

        System.out.println("  opcertKesVkey   = " + HexUtil.encodeHexString(opcertKesVkeyFull));
        System.out.println("  opcertCounter   = " + opcertCounter);
        System.out.println("  opcertPeriod    = " + opcertPeriod);
        System.out.println("  issuerVkey      = " + HexUtil.encodeHexString(issuerVkeyFromBlock));
        System.out.println("  coldSig length  = " + opcertColdSig.length);

        // The cold key signs: kesVkey(32) || counter(8, big-endian) || kesPeriod(8, big-endian)
        byte[] signedData = new byte[32 + 8 + 8];
        System.arraycopy(opcertKesVkeyFull, 0, signedData, 0, 32);
        for (int i = 7; i >= 0; i--) {
            signedData[32 + (7 - i)] = (byte) (opcertCounter >>> (i * 8));
        }
        for (int i = 7; i >= 0; i--) {
            signedData[40 + (7 - i)] = (byte) (opcertPeriod >>> (i * 8));
        }

        SigningProvider ed25519 = CryptoConfiguration.INSTANCE.getSigningProvider();
        boolean opCertValid = ed25519.verify(opcertColdSig, signedData, issuerVkeyFromBlock);
        assertTrue(opCertValid,
                "OpCert cold key signature must verify over kesVkey || counter(8BE) || kesPeriod(8BE)");

        System.out.println("=== BONUS PASSED: OpCert cold key signature verified ===");

        // =====================================================================
        // SUMMARY
        // =====================================================================
        System.out.println();
        System.out.println("========================================");
        System.out.println("  ALL 8 HASKELL VALIDATION STEPS PASSED");
        System.out.println("========================================");
        System.out.println("  1. Block built successfully");
        System.out.println("  2. Block structure [era=6, 24(h'...')] verified");
        System.out.println("  3. Block hash = blake2b_256(header array) verified");
        System.out.println("  4. Body hash (two-level segregated witness) verified");
        System.out.println("  5. VRF proof verified");
        System.out.println("  6. KES signature verified");
        System.out.println("  7. Protocol version [10, 2] verified");
        System.out.println("  8. Wrapped header format verified");
        System.out.println("  +  OpCert cold key signature verified");
    }

    /**
     * Test that a block with transactions also passes all validation steps.
     * This catches potential issues with body hash computation when transactions are present.
     */
    @Test
    void fullHaskellBlockValidation_withTransactions() throws Exception {
        SignedBlockBuilder builder = createFreshBuilder();
        byte[] epochNonce = genesisEpochNonce.clone();

        // Build a simple transaction CBOR: [body, witnesses, is_valid, aux_data]
        // Use a minimal valid tx structure
        byte[] fakeTxCbor = buildMinimalTxCbor();

        long blockNumber = 1;
        long slot = 5;
        byte[] prevHash = new byte[32];

        DevnetBlockBuilder.BlockBuildResult result = builder.buildBlock(blockNumber, slot, prevHash,
                List.of(fakeTxCbor));

        assertNotNull(result);
        System.out.println("Block with 1 transaction built, blockHash=" + HexUtil.encodeHexString(result.blockHash()));

        // Parse and verify block structure
        DataItem blockDI = CborSerializationUtil.deserializeOne(result.blockCbor());
        Array blockArray = (Array) blockDI;
        Array blockContent = BlockTestUtil.unwrapTag24BlockContent(blockArray);
        assertEquals(5, blockContent.getDataItems().size());

        Array header = (Array) blockContent.getDataItems().get(0);
        Array headerBody = (Array) header.getDataItems().get(0);

        // Verify the txBodies array has 1 element
        DataItem txBodiesDI = blockContent.getDataItems().get(1);
        assertInstanceOf(Array.class, txBodiesDI);
        Array txBodies = (Array) txBodiesDI;
        assertEquals(1, txBodies.getDataItems().size(), "txBodies should have 1 transaction body");

        // Verify body hash (two-level)
        byte[] txBodiesBytes = CborSerializationUtil.serialize(blockContent.getDataItems().get(1));
        byte[] txWitnessesBytes = CborSerializationUtil.serialize(blockContent.getDataItems().get(2));
        byte[] auxDataBytes = CborSerializationUtil.serialize(blockContent.getDataItems().get(3));
        byte[] invalidTxsBytes = CborSerializationUtil.serialize(blockContent.getDataItems().get(4));

        byte[] h1 = Blake2bUtil.blake2bHash256(txBodiesBytes);
        byte[] h2 = Blake2bUtil.blake2bHash256(txWitnessesBytes);
        byte[] h3 = Blake2bUtil.blake2bHash256(auxDataBytes);
        byte[] h4 = Blake2bUtil.blake2bHash256(invalidTxsBytes);

        byte[] combined = new byte[128];
        System.arraycopy(h1, 0, combined, 0, 32);
        System.arraycopy(h2, 0, combined, 32, 32);
        System.arraycopy(h3, 0, combined, 64, 32);
        System.arraycopy(h4, 0, combined, 96, 32);
        byte[] computedBodyHash = Blake2bUtil.blake2bHash256(combined);

        byte[] declaredBodyHash = ((ByteString) headerBody.getDataItems().get(7)).getBytes();
        assertArrayEquals(computedBodyHash, declaredBodyHash,
                "Body hash must match for block with transactions");

        // Verify block hash
        byte[] headerArrayBytes = CborSerializationUtil.serialize(header);
        byte[] computedBlockHash = Blake2bUtil.blake2bHash256(headerArrayBytes);
        assertArrayEquals(computedBlockHash, result.blockHash(),
                "Block hash must match for block with transactions");

        // Verify VRF proof
        byte[] vrfVkeyFromBlock = ((ByteString) headerBody.getDataItems().get(4)).getBytes();
        Array vrfResultArray = (Array) headerBody.getDataItems().get(5);
        byte[] vrfProofFromBlock = ((ByteString) vrfResultArray.getDataItems().get(1)).getBytes();
        byte[] vrfAlpha = CardanoVrfInput.mkInputVrf(slot, epochNonce);
        VrfVerifier vrfVerifier = CryptoExtConfiguration.INSTANCE.getVrfVerifier();
        VrfResult vrfVerified = vrfVerifier.verify(vrfVkeyFromBlock, vrfProofFromBlock, vrfAlpha);
        assertTrue(vrfVerified.isValid(), "VRF proof must verify for block with transactions");

        // Verify KES signature
        byte[] kesSig = ((ByteString) header.getDataItems().get(1)).getBytes();
        byte[] headerBodyCbor = CborSerializationUtil.serialize(headerBody);
        Sum6KesSigner kesSigner = new Sum6KesSigner();
        byte[] kesRootVk = kesSigner.deriveVerificationKey(keys.getKesSkey());
        int kesPeriod = (int) (slot / SLOTS_PER_KES_PERIOD);
        int opcertKesPeriod = (int) keys.getOpCert().getKesPeriod();
        KesVerifier kesVerifier = CryptoExtConfiguration.INSTANCE.getKesVerifier();
        boolean kesValid = kesVerifier.verify(kesSig, headerBodyCbor, kesRootVk, kesPeriod - opcertKesPeriod);
        assertTrue(kesValid, "KES signature must verify for block with transactions");

        System.out.println("=== Block with transactions: ALL validations passed ===");
    }

    /**
     * Verify that producing multiple consecutive blocks maintains hash chain integrity
     * and all blocks individually pass validation.
     */
    @Test
    void multiBlockChainValidation() {
        SignedBlockBuilder builder = createFreshBuilder();

        byte[] prevHash = null;
        List<byte[]> blockHashes = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            long blockNum = i;
            long slot = i * 2L;

            DevnetBlockBuilder.BlockBuildResult result = builder.buildBlock(blockNum, slot, prevHash, List.of());
            assertNotNull(result);

            // Verify block hash
            DataItem blockDI = CborSerializationUtil.deserializeOne(result.blockCbor());
            Array blockContent = BlockTestUtil.unwrapTag24BlockContent((Array) blockDI);
            Array header = (Array) blockContent.getDataItems().get(0);
            byte[] headerArrayBytes = CborSerializationUtil.serialize(header);
            byte[] computedBlockHash = Blake2bUtil.blake2bHash256(headerArrayBytes);
            assertArrayEquals(computedBlockHash, result.blockHash(),
                    "Block hash must verify for block #" + i);

            // Verify prevHash in header body matches what we passed
            Array headerBody = (Array) header.getDataItems().get(0);
            DataItem prevHashItem = headerBody.getDataItems().get(2);
            if (prevHash == null) {
                assertTrue(prevHashItem instanceof SimpleValue,
                        "First block's prevHash must be null");
            } else {
                byte[] prevHashFromBlock = ((ByteString) prevHashItem).getBytes();
                assertArrayEquals(prevHash, prevHashFromBlock,
                        "Block #" + i + " prevHash must match previous block's hash");
            }

            // Verify body hash
            byte[] txBodiesBytes = CborSerializationUtil.serialize(blockContent.getDataItems().get(1));
            byte[] txWitnessesBytes = CborSerializationUtil.serialize(blockContent.getDataItems().get(2));
            byte[] auxDataBytes = CborSerializationUtil.serialize(blockContent.getDataItems().get(3));
            byte[] invalidTxsBytes = CborSerializationUtil.serialize(blockContent.getDataItems().get(4));
            byte[] h1 = Blake2bUtil.blake2bHash256(txBodiesBytes);
            byte[] h2 = Blake2bUtil.blake2bHash256(txWitnessesBytes);
            byte[] h3 = Blake2bUtil.blake2bHash256(auxDataBytes);
            byte[] h4 = Blake2bUtil.blake2bHash256(invalidTxsBytes);
            byte[] comb = new byte[128];
            System.arraycopy(h1, 0, comb, 0, 32);
            System.arraycopy(h2, 0, comb, 32, 32);
            System.arraycopy(h3, 0, comb, 64, 32);
            System.arraycopy(h4, 0, comb, 96, 32);
            byte[] bodyHash = Blake2bUtil.blake2bHash256(comb);
            byte[] declaredBodyHash = ((ByteString) headerBody.getDataItems().get(7)).getBytes();
            assertArrayEquals(bodyHash, declaredBodyHash, "Body hash must verify for block #" + i);

            blockHashes.add(result.blockHash());
            prevHash = result.blockHash();
        }

        // Verify all block hashes are unique
        Set<String> uniqueHashes = new HashSet<>();
        for (byte[] hash : blockHashes) {
            uniqueHashes.add(HexUtil.encodeHexString(hash));
        }
        assertEquals(5, uniqueHashes.size(), "All 5 block hashes must be unique");

        System.out.println("=== Multi-block chain: 5 consecutive blocks verified ===");
    }

    /**
     * Build a minimal but structurally valid transaction CBOR.
     * Structure: [body, witnesses, is_valid, aux_data]
     * where body is a map with a single input and output.
     */
    private byte[] buildMinimalTxCbor() {
        // tx body: {0: [input], 1: [output], 2: fee}
        Map txBody = new Map();
        // Inputs: array of [txHash, index]
        Array inputs = new Array();
        Array input = new Array();
        input.add(new ByteString(new byte[32])); // dummy tx hash
        input.add(new UnsignedInteger(0));
        inputs.add(input);
        txBody.put(new UnsignedInteger(0), inputs);
        // Outputs: array of [address, amount]
        Array outputs = new Array();
        Array output = new Array();
        output.add(new ByteString(new byte[57])); // dummy address
        output.add(new UnsignedInteger(1000000));  // 1 ADA
        outputs.add(output);
        txBody.put(new UnsignedInteger(1), outputs);
        // Fee
        txBody.put(new UnsignedInteger(2), new UnsignedInteger(200000));

        // witnesses: empty map
        Map witnesses = new Map();

        // is_valid: true
        SimpleValue isValid = SimpleValue.TRUE;

        // aux_data: null
        SimpleValue auxData = SimpleValue.NULL;

        // Complete tx: [body, witnesses, is_valid, aux_data]
        Array tx = new Array();
        tx.add(txBody);
        tx.add(witnesses);
        tx.add(isValid);
        tx.add(auxData);

        return CborSerializationUtil.serialize(tx);
    }
}
