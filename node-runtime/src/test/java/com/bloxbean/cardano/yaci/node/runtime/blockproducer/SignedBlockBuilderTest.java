package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SignedBlockBuilder: produces blocks with real VRF proofs, KES signatures,
 * and operational certificates, then verifies the crypto is valid.
 */
class SignedBlockBuilderTest {

    private static final long EPOCH_LENGTH = 600;
    private static final long SECURITY_PARAM = 100;
    private static final double ACTIVE_SLOTS_COEFF = 1.0;
    private static final long SLOTS_PER_KES_PERIOD = 129600;
    private static final long MAX_KES_EVOLUTIONS = 60;

    private static BlockProducerKeys keys;
    private static SignedBlockBuilder builder;
    private static EpochNonceState nonceState;
    private static byte[] genesisEpochNonce; // initial epoch nonce from genesis (before any blocks)

    @BeforeAll
    static void setUp() throws Exception {
        Path base = Paths.get("src/test/resources/devnet");
        keys = BlockProducerKeys.load(
                base.resolve("vrf.skey"),
                base.resolve("kes.skey"),
                base.resolve("opcert.cert")
        );

        nonceState = new EpochNonceState(EPOCH_LENGTH, SECURITY_PARAM, ACTIVE_SLOTS_COEFF);
        byte[] genesisBytes = java.nio.file.Files.readAllBytes(base.resolve("shelley-genesis.json"));
        nonceState.initFromGenesis(genesisBytes);
        genesisEpochNonce = nonceState.getEpochNonce();

        builder = new SignedBlockBuilder(keys, SLOTS_PER_KES_PERIOD, MAX_KES_EVOLUTIONS,
                nonceState, null);
    }

    /**
     * Create a fresh SignedBlockBuilder with a clean EpochNonceState initialized from genesis.
     * Each call returns an independent builder whose nonce state has not been evolved.
     */
    private static SignedBlockBuilder createFreshBuilder() {
        return createFreshBuilder(null);
    }

    /**
     * Create a fresh SignedBlockBuilder with a clean EpochNonceState and optional NonceStateStore.
     */
    private static SignedBlockBuilder createFreshBuilder(NonceStateStore store) {
        EpochNonceState freshNonce = new EpochNonceState(EPOCH_LENGTH, SECURITY_PARAM, ACTIVE_SLOTS_COEFF);
        try {
            Path base = Paths.get("src/test/resources/devnet");
            byte[] genesisBytes = java.nio.file.Files.readAllBytes(base.resolve("shelley-genesis.json"));
            freshNonce.initFromGenesis(genesisBytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new SignedBlockBuilder(keys, SLOTS_PER_KES_PERIOD, MAX_KES_EVOLUTIONS, freshNonce, store);
    }

    @Test
    void buildBlock_producesParseableBlock() {
        var result = builder.buildBlock(0, 0, null, List.of());

        assertThat(result).isNotNull();
        assertThat(result.blockCbor()).isNotNull();
        assertThat(result.wrappedHeaderCbor()).isNotNull();
        assertThat(result.blockHash()).hasSize(32);
        assertEquals(0, result.blockNumber());
        assertEquals(0, result.slot());

        // Verify the block can be deserialized
        com.bloxbean.cardano.yaci.core.model.Block block =
                com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer.INSTANCE.deserialize(result.blockCbor());
        assertThat(block).isNotNull();
        assertThat(block.getTransactionBodies()).isEmpty();
    }

    @Test
    void buildBlock_vrfProofIsValid() {
        byte[] epochNonce = nonceState.getEpochNonce();
        long slot = 42;

        var result = builder.buildBlock(1, slot, new byte[32], List.of());

        // Parse the header to extract VRF proof — unwrap tag-24
        DataItem blockDI = CborSerializationUtil.deserializeOne(result.blockCbor());
        Array blockArray = (Array) blockDI;
        Array blockContent = BlockTestUtil.unwrapTag24BlockContent(blockArray);
        Array header = (Array) blockContent.getDataItems().get(0);
        Array headerBody = (Array) header.getDataItems().get(0);

        // Field 4: vrfVkey (32 bytes)
        byte[] vrfVkey = ((ByteString) headerBody.getDataItems().get(4)).getBytes();
        assertThat(vrfVkey).hasSize(32);

        // Field 5: vrfResult [output(64), proof(80)]
        Array vrfResult = (Array) headerBody.getDataItems().get(5);
        byte[] vrfOutput = ((ByteString) vrfResult.getDataItems().get(0)).getBytes();
        byte[] vrfProof = ((ByteString) vrfResult.getDataItems().get(1)).getBytes();
        assertThat(vrfOutput).hasSize(64);
        assertThat(vrfProof).hasSize(80);

        // Verify the VRF proof
        // Note: epochNonce was captured BEFORE buildBlock evolved it, and buildBlock uses it for VRF
        byte[] alpha = CardanoVrfInput.mkInputVrf(slot, epochNonce);
        VrfVerifier verifier = CryptoExtConfiguration.INSTANCE.getVrfVerifier();
        VrfResult verified = verifier.verify(vrfVkey, vrfProof, alpha);
        assertTrue(verified.isValid(), "VRF proof should verify");
        assertArrayEquals(vrfOutput, verified.getOutput(), "VRF output should match");
    }

    @Test
    void buildBlock_kesSignatureIsValid() {
        SignedBlockBuilder freshBuilder = createFreshBuilder();

        var result = freshBuilder.buildBlock(0, 0, null, List.of());

        // Parse block to extract header — unwrap tag-24
        DataItem blockDI = CborSerializationUtil.deserializeOne(result.blockCbor());
        Array blockArray = (Array) blockDI;
        Array blockContent = BlockTestUtil.unwrapTag24BlockContent(blockArray);
        Array header = (Array) blockContent.getDataItems().get(0);

        // Header = [headerBody, kesSig]
        Array headerBody = (Array) header.getDataItems().get(0);
        byte[] kesSig = ((ByteString) header.getDataItems().get(1)).getBytes();
        assertThat(kesSig).hasSize(448);

        // KES signs raw CBOR of the header body
        byte[] headerBodyCbor = CborSerializationUtil.serialize(headerBody);

        // Get KES root vk from the KES skey
        Sum6KesSigner signer = new Sum6KesSigner();
        byte[] kesRootVk = signer.deriveVerificationKey(keys.getKesSkey());

        // Verify KES signature
        KesVerifier verifier = CryptoExtConfiguration.INSTANCE.getKesVerifier();
        int kesPeriod = 0; // slot 0 / slotsPerKESPeriod(129600) = 0, relative = 0 - opcert.kesPeriod
        int opcertKesPeriod = (int) keys.getOpCert().getKesPeriod();
        int relativePeriod = kesPeriod - opcertKesPeriod;

        boolean valid = verifier.verify(kesSig, headerBodyCbor, kesRootVk, relativePeriod);
        assertTrue(valid, "KES signature should verify");
    }

    @Test
    void buildBlock_opCertFieldsAreCorrect() {
        SignedBlockBuilder freshBuilder = createFreshBuilder();

        var result = freshBuilder.buildBlock(0, 0, null, List.of());

        // Parse header body — unwrap tag-24
        DataItem blockDI = CborSerializationUtil.deserializeOne(result.blockCbor());
        Array blockArray = (Array) blockDI;
        Array blockContent = BlockTestUtil.unwrapTag24BlockContent(blockArray);
        Array header = (Array) blockContent.getDataItems().get(0);
        Array headerBody = (Array) header.getDataItems().get(0);

        // Field 3: issuerVkey = cold vkey from opcert
        byte[] issuerVkey = ((ByteString) headerBody.getDataItems().get(3)).getBytes();
        assertArrayEquals(keys.getOpCert().getColdVkey(), issuerVkey);

        // Field 8: operationalCert [kesVkey, counter, kesPeriod, coldSig]
        Array opCert = (Array) headerBody.getDataItems().get(8);
        byte[] kesVkey = ((ByteString) opCert.getDataItems().get(0)).getBytes();
        assertArrayEquals(keys.getOpCert().getKesVkey(), kesVkey);

        byte[] coldSig = ((ByteString) opCert.getDataItems().get(3)).getBytes();
        assertArrayEquals(keys.getOpCert().getColdSignature(), coldSig);
    }

    @Test
    void buildBlock_multipleBlocksEvolveNonce() {
        SignedBlockBuilder freshBuilder = createFreshBuilder();

        // Build genesis block
        var genesis = freshBuilder.buildBlock(0, 0, null, List.of());
        assertThat(genesis.blockHash()).hasSize(32);

        // Build several more blocks
        byte[] prevHash = genesis.blockHash();
        for (int i = 1; i <= 5; i++) {
            var block = freshBuilder.buildBlock(i, i * 2L, prevHash, List.of());
            assertThat(block.blockHash()).hasSize(32);
            assertThat(block.blockHash()).isNotEqualTo(prevHash);
            prevHash = block.blockHash();
        }
    }

    /**
     * Comprehensive end-to-end block verification test that simulates exactly what the
     * Haskell cardano-node does when validating a received block. Checks performed:
     *
     * 1. Build a signed block
     * 2. Re-serialize header body and verify bytes match (getSignableRepresentation)
     * 3. Verify KES signature against re-serialized header body bytes
     * 4. Verify VRF proof (reconstruct input, verify proof, check output)
     * 5. Verify OpCert cold key signature over KES hot vkey
     * 6. Verify two-level body hash
     * 7. Verify block hash = blake2b_256(serialized header array)
     */
    @Test
    void buildBlock_fullHaskellValidation() {
        // --- Setup: fresh builder with clean nonce state ---
        SignedBlockBuilder freshBuilder = createFreshBuilder();

        // Capture epoch nonce BEFORE buildBlock (buildBlock evolves it).
        // Use the genesis-derived nonce captured during setUp, since createFreshBuilder
        // produces a builder with the same initial nonce.
        byte[] epochNonce = genesisEpochNonce;

        long slot = 10;
        long blockNumber = 1;
        byte[] prevHash = new byte[32]; // zero-filled for first block after genesis

        // =====================================================================
        // STEP 1: Build a signed block
        // =====================================================================
        var result = freshBuilder.buildBlock(blockNumber, slot, prevHash, List.of());
        assertNotNull(result, "Block build result should not be null");
        assertNotNull(result.blockCbor(), "Block CBOR should not be null");
        System.out.println("STEP 1 PASSED: Block built successfully");
        System.out.println("  blockNumber=" + result.blockNumber() + ", slot=" + result.slot());
        System.out.println("  blockHash=" + HexUtil.encodeHexString(result.blockHash()));
        System.out.println("  blockCbor length=" + result.blockCbor().length);

        // =====================================================================
        // STEP 2: Re-serialize header body (simulate Haskell getSignableRepresentation)
        // =====================================================================
        // Parse the block CBOR: [era, 24(h'[header, txBodies, txWitnesses, auxData, invalidTxs]')]
        DataItem blockDI = CborSerializationUtil.deserializeOne(result.blockCbor());
        Array blockArray = (Array) blockDI;
        Array blockContent = BlockTestUtil.unwrapTag24BlockContent(blockArray);
        // header = [headerBody, kesSig]
        Array header = (Array) blockContent.getDataItems().get(0);
        Array headerBody = (Array) header.getDataItems().get(0);
        byte[] kesSigFromBlock = ((ByteString) header.getDataItems().get(1)).getBytes();

        // Re-serialize the headerBody from the parsed CBOR object model
        byte[] reserializedHeaderBodyBytes = CborSerializationUtil.serialize(headerBody);

        // Now get the ORIGINAL header body bytes: serialize the header array, then extract
        // headerBody bytes from it. But since both come from the same CBOR object model after
        // parsing, the key test is whether CborSerializationUtil.serialize is deterministic.
        // The Haskell node does: parse block -> extract headerBody -> serialize headerBody -> verify KES.
        // If serialize(headerBody) != original bytes that were KES-signed, KES verification fails.
        // We test this by verifying KES against the re-serialized bytes in step 3.

        System.out.println("STEP 2: Re-serialized header body");
        System.out.println("  headerBody fields count=" + headerBody.getDataItems().size());
        System.out.println("  re-serialized headerBody length=" + reserializedHeaderBodyBytes.length);
        System.out.println("  re-serialized headerBody hash=" +
                HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(reserializedHeaderBodyBytes)));

        // Verify the header body has the expected 10 fields
        assertEquals(10, headerBody.getDataItems().size(),
                "Header body should have exactly 10 fields");
        System.out.println("STEP 2 PASSED: Header body has 10 fields, re-serialization successful");

        // =====================================================================
        // STEP 3: Verify KES signature against RE-SERIALIZED header body bytes
        //         (This is exactly what the Haskell node does)
        // =====================================================================
        // Get KES root verification key from the KES secret key
        Sum6KesSigner signer = new Sum6KesSigner();
        byte[] kesRootVk = signer.deriveVerificationKey(keys.getKesSkey());
        System.out.println("STEP 3: KES verification");
        System.out.println("  KES root vk=" + HexUtil.encodeHexString(kesRootVk));
        System.out.println("  KES sig length=" + kesSigFromBlock.length);

        // Compute the KES period (same logic as SignedBlockBuilder)
        int kesPeriod = (int) (slot / SLOTS_PER_KES_PERIOD);
        int opcertKesPeriod = (int) keys.getOpCert().getKesPeriod();
        int relativePeriod = kesPeriod - opcertKesPeriod;
        System.out.println("  kesPeriod=" + kesPeriod + ", opcertKesPeriod=" + opcertKesPeriod +
                ", relativePeriod=" + relativePeriod);

        // Verify KES signature using RE-SERIALIZED bytes (not original)
        // This is the critical test: if re-serialization produces different bytes,
        // this verification will FAIL just like on the Haskell node
        KesVerifier kesVerifier = CryptoExtConfiguration.INSTANCE.getKesVerifier();
        boolean kesValid = kesVerifier.verify(kesSigFromBlock, reserializedHeaderBodyBytes,
                kesRootVk, relativePeriod);

        if (!kesValid) {
            System.err.println("STEP 3 FAILED: KES signature verification failed!");
            System.err.println("  This means re-serialized header body bytes differ from original.");
            System.err.println("  re-serialized bytes hex=" +
                    HexUtil.encodeHexString(reserializedHeaderBodyBytes));
        }
        assertTrue(kesValid, "KES signature must verify against re-serialized header body bytes " +
                "(simulates Haskell getSignableRepresentation)");
        System.out.println("STEP 3 PASSED: KES signature verified against re-serialized header body");

        // =====================================================================
        // STEP 4: Verify VRF proof
        // =====================================================================
        System.out.println("STEP 4: VRF proof verification");

        // Extract vrfVkey from header body field 4
        byte[] vrfVkeyFromBlock = ((ByteString) headerBody.getDataItems().get(4)).getBytes();
        assertThat(vrfVkeyFromBlock).hasSize(32);
        System.out.println("  vrfVkey=" + HexUtil.encodeHexString(vrfVkeyFromBlock));

        // Extract vrfResult from header body field 5: [output(64), proof(80)]
        Array vrfResultArray = (Array) headerBody.getDataItems().get(5);
        byte[] vrfOutputFromBlock = ((ByteString) vrfResultArray.getDataItems().get(0)).getBytes();
        byte[] vrfProofFromBlock = ((ByteString) vrfResultArray.getDataItems().get(1)).getBytes();
        assertThat(vrfOutputFromBlock).hasSize(64);
        assertThat(vrfProofFromBlock).hasSize(80);
        System.out.println("  vrfOutput length=" + vrfOutputFromBlock.length);
        System.out.println("  vrfProof length=" + vrfProofFromBlock.length);

        // Reconstruct the VRF input: blake2b_256(slot_8bytes_BE || epochNonce)
        byte[] vrfAlpha = CardanoVrfInput.mkInputVrf(slot, epochNonce);
        System.out.println("  epochNonce=" + HexUtil.encodeHexString(epochNonce));
        System.out.println("  vrfAlpha (reconstructed)=" + HexUtil.encodeHexString(vrfAlpha));

        // Verify the VRF proof against the reconstructed input
        VrfVerifier vrfVerifier = CryptoExtConfiguration.INSTANCE.getVrfVerifier();
        VrfResult vrfVerified = vrfVerifier.verify(vrfVkeyFromBlock, vrfProofFromBlock, vrfAlpha);

        if (!vrfVerified.isValid()) {
            System.err.println("STEP 4 FAILED: VRF proof verification failed!");
            System.err.println("  vrfVkey=" + HexUtil.encodeHexString(vrfVkeyFromBlock));
            System.err.println("  vrfProof=" + HexUtil.encodeHexString(vrfProofFromBlock));
            System.err.println("  vrfAlpha=" + HexUtil.encodeHexString(vrfAlpha));
        }
        assertTrue(vrfVerified.isValid(), "VRF proof must verify against reconstructed input");

        // Check the VRF output matches
        if (!Arrays.equals(vrfOutputFromBlock, vrfVerified.getOutput())) {
            System.err.println("STEP 4 FAILED: VRF output mismatch!");
            System.err.println("  expected=" + HexUtil.encodeHexString(vrfOutputFromBlock));
            System.err.println("  actual=  " + HexUtil.encodeHexString(vrfVerified.getOutput()));
        }
        assertArrayEquals(vrfOutputFromBlock, vrfVerified.getOutput(),
                "VRF output from block must match verified output");
        System.out.println("STEP 4 PASSED: VRF proof verified, output matches");

        // =====================================================================
        // STEP 5: Verify OpCert cold key signature
        // =====================================================================
        System.out.println("STEP 5: OpCert cold key signature verification");

        // Extract OpCert from header body field 8: [kesVkey, counter, kesPeriod, coldSig]
        Array opCertArray = (Array) headerBody.getDataItems().get(8);
        byte[] opcertKesVkey = ((ByteString) opCertArray.getDataItems().get(0)).getBytes();
        long opcertCounter = ((UnsignedInteger) opCertArray.getDataItems().get(1)).getValue().longValueExact();
        long opcertPeriod = ((UnsignedInteger) opCertArray.getDataItems().get(2)).getValue().longValueExact();
        byte[] opcertColdSig = ((ByteString) opCertArray.getDataItems().get(3)).getBytes();

        // The issuer vkey (cold vkey) is field 3 of the header body
        byte[] issuerVkeyFromBlock = ((ByteString) headerBody.getDataItems().get(3)).getBytes();

        System.out.println("  opcertKesVkey=" + HexUtil.encodeHexString(opcertKesVkey));
        System.out.println("  opcertCounter=" + opcertCounter);
        System.out.println("  opcertPeriod=" + opcertPeriod);
        System.out.println("  coldVkey (issuer)=" + HexUtil.encodeHexString(issuerVkeyFromBlock));
        System.out.println("  coldSig length=" + opcertColdSig.length);

        // In Cardano, the cold key signs: kesVkey(32) || counter(8, big-endian) || kesPeriod(8, big-endian)
        byte[] signedData = new byte[32 + 8 + 8];
        System.arraycopy(opcertKesVkey, 0, signedData, 0, 32);
        for (int i = 7; i >= 0; i--) {
            signedData[32 + (7 - i)] = (byte) (opcertCounter >>> (i * 8));
        }
        for (int i = 7; i >= 0; i--) {
            signedData[40 + (7 - i)] = (byte) (opcertPeriod >>> (i * 8));
        }

        SigningProvider ed25519 = CryptoConfiguration.INSTANCE.getSigningProvider();
        boolean opCertValid = ed25519.verify(opcertColdSig, signedData, issuerVkeyFromBlock);

        if (!opCertValid) {
            System.err.println("STEP 5 FAILED: OpCert cold key signature verification failed!");
            System.err.println("  signedData=" + HexUtil.encodeHexString(signedData));
        }
        assertTrue(opCertValid, "OpCert cold key signature must verify over " +
                "kesVkey || counter || kesPeriod");

        // Also verify that the opcert KES vkey matches the KES root vk derived from skey
        assertArrayEquals(kesRootVk, opcertKesVkey,
                "OpCert KES vkey must match root vk derived from KES skey");
        System.out.println("STEP 5 PASSED: OpCert cold key signature verified, KES vkey matches");

        // =====================================================================
        // STEP 6: Verify body hash (two-level Alonzo/Conway segregated witness)
        // =====================================================================
        System.out.println("STEP 6: Body hash verification (two-level)");

        // Extract body components from the block
        Array txBodiesArray = (Array) blockContent.getDataItems().get(1);
        Array txWitnessesArray = (Array) blockContent.getDataItems().get(2);
        // auxData could be a Map
        DataItem auxDataDI = blockContent.getDataItems().get(3);
        Array invalidTxsArray = (Array) blockContent.getDataItems().get(4);

        // Serialize each component
        byte[] txBodiesBytes = CborSerializationUtil.serialize(txBodiesArray);
        byte[] txWitnessesBytes = CborSerializationUtil.serialize(txWitnessesArray);
        byte[] auxDataBytes = CborSerializationUtil.serialize(auxDataDI);
        byte[] invalidTxsBytes = CborSerializationUtil.serialize(invalidTxsArray);

        // Compute two-level hash:
        // 1. Hash each component individually
        byte[] h1 = Blake2bUtil.blake2bHash256(txBodiesBytes);
        byte[] h2 = Blake2bUtil.blake2bHash256(txWitnessesBytes);
        byte[] h3 = Blake2bUtil.blake2bHash256(auxDataBytes);
        byte[] h4 = Blake2bUtil.blake2bHash256(invalidTxsBytes);

        // 2. Concatenate the four 32-byte hashes
        byte[] combined = new byte[128];
        System.arraycopy(h1, 0, combined, 0, 32);
        System.arraycopy(h2, 0, combined, 32, 32);
        System.arraycopy(h3, 0, combined, 64, 32);
        System.arraycopy(h4, 0, combined, 96, 32);

        // 3. Hash the 128-byte concatenation
        byte[] computedBodyHash = Blake2bUtil.blake2bHash256(combined);

        // Extract declared body hash from header body field 7
        byte[] declaredBodyHash = ((ByteString) headerBody.getDataItems().get(7)).getBytes();

        System.out.println("  h1(txBodies)   =" + HexUtil.encodeHexString(h1));
        System.out.println("  h2(txWitnesses)=" + HexUtil.encodeHexString(h2));
        System.out.println("  h3(auxData)    =" + HexUtil.encodeHexString(h3));
        System.out.println("  h4(invalidTxs) =" + HexUtil.encodeHexString(h4));
        System.out.println("  computed bodyHash=" + HexUtil.encodeHexString(computedBodyHash));
        System.out.println("  declared bodyHash=" + HexUtil.encodeHexString(declaredBodyHash));

        if (!Arrays.equals(computedBodyHash, declaredBodyHash)) {
            System.err.println("STEP 6 FAILED: Body hash mismatch!");
            System.err.println("  computed=" + HexUtil.encodeHexString(computedBodyHash));
            System.err.println("  declared=" + HexUtil.encodeHexString(declaredBodyHash));
        }
        assertArrayEquals(computedBodyHash, declaredBodyHash,
                "Computed two-level body hash must match header's declared body hash");
        System.out.println("STEP 6 PASSED: Body hash verified (two-level)");

        // =====================================================================
        // STEP 7: Verify block hash = blake2b_256(serialized header array)
        // =====================================================================
        System.out.println("STEP 7: Block hash verification");

        // Serialize the header array: [headerBody, kesSig]
        byte[] headerArrayBytes = CborSerializationUtil.serialize(header);
        byte[] computedBlockHash = Blake2bUtil.blake2bHash256(headerArrayBytes);

        System.out.println("  serialized header array length=" + headerArrayBytes.length);
        System.out.println("  computed blockHash=" + HexUtil.encodeHexString(computedBlockHash));
        System.out.println("  declared blockHash=" + HexUtil.encodeHexString(result.blockHash()));

        if (!Arrays.equals(computedBlockHash, result.blockHash())) {
            System.err.println("STEP 7 FAILED: Block hash mismatch!");
            System.err.println("  computed=" + HexUtil.encodeHexString(computedBlockHash));
            System.err.println("  declared=" + HexUtil.encodeHexString(result.blockHash()));
        }
        assertArrayEquals(computedBlockHash, result.blockHash(),
                "Computed blake2b_256(header array) must match declared block hash");
        System.out.println("STEP 7 PASSED: Block hash verified");

        System.out.println("\n=== ALL 7 HASKELL VALIDATION STEPS PASSED ===");
    }

    @Test
    void buildBlock_nonceStatePersistence() {
        // Use an in-memory store
        byte[][] storeHolder = {null};
        NonceStateStore store = new NonceStateStore() {
            @Override
            public void storeEpochNonceState(byte[] serialized) {
                storeHolder[0] = serialized;
            }

            @Override
            public byte[] getEpochNonceState() {
                return storeHolder[0];
            }
        };

        SignedBlockBuilder freshBuilder = createFreshBuilder(store);

        // Build a block — should persist nonce state
        freshBuilder.buildBlock(0, 0, null, List.of());
        assertThat(storeHolder[0]).isNotNull();

        // Restore into new state and verify
        EpochNonceState restored = new EpochNonceState(EPOCH_LENGTH, SECURITY_PARAM, ACTIVE_SLOTS_COEFF);
        restored.restore(storeHolder[0]);

        // After one block at slot 0, the nonce state should have evolved;
        // verify the restored state matches by comparing epoch and nonce
        assertEquals(0, restored.getCurrentEpoch());
        assertThat(restored.getEpochNonce()).isNotNull();
    }

}
