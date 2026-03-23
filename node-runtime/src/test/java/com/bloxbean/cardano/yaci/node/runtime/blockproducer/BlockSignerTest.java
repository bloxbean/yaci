package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.BlockProducerKeys;
import com.bloxbean.cardano.client.crypto.config.CryptoExtConfiguration;
import com.bloxbean.cardano.client.crypto.kes.KesVerifier;
import com.bloxbean.cardano.client.crypto.kes.Sum6KesSigner;
import com.bloxbean.cardano.client.crypto.vrf.VrfResult;
import com.bloxbean.cardano.client.crypto.vrf.VrfVerifier;
import com.bloxbean.cardano.client.crypto.vrf.cardano.CardanoVrfInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class BlockSignerTest {

    private static BlockProducerKeys keys;
    private final BlockSigner signer = new BlockSigner();

    @BeforeAll
    static void loadKeys() {
        Path base = Paths.get("src/test/resources/devnet");
        keys = BlockProducerKeys.load(
                base.resolve("vrf.skey"),
                base.resolve("kes.skey"),
                base.resolve("opcert.cert")
        );
    }

    @Test
    void computeVrf_producesValidProof() {
        byte[] epochNonce = Blake2bUtil.blake2bHash256("test-nonce".getBytes());
        long slot = 100;

        BlockSigner.VrfSignResult result = signer.computeVrf(keys.getVrfSkey(), slot, epochNonce);

        assertThat(result.output()).hasSize(64);
        assertThat(result.proof()).hasSize(80);

        // Verify independently
        byte[] vrfVkey = Arrays.copyOfRange(keys.getVrfSkey(), 32, 64);
        byte[] alpha = CardanoVrfInput.mkInputVrf(slot, epochNonce);
        VrfVerifier verifier = CryptoExtConfiguration.INSTANCE.getVrfVerifier();
        VrfResult verified = verifier.verify(vrfVkey, result.proof(), alpha);
        assertTrue(verified.isValid());
        assertArrayEquals(result.output(), verified.getOutput());
    }

    @Test
    void computeVrf_differentSlotsProduceDifferentOutputs() {
        byte[] epochNonce = Blake2bUtil.blake2bHash256("nonce".getBytes());

        BlockSigner.VrfSignResult r1 = signer.computeVrf(keys.getVrfSkey(), 1, epochNonce);
        BlockSigner.VrfSignResult r2 = signer.computeVrf(keys.getVrfSkey(), 2, epochNonce);

        assertThat(r1.output()).isNotEqualTo(r2.output());
        assertThat(r1.proof()).isNotEqualTo(r2.proof());
    }

    @Test
    void signHeaderBody_producesValidSignature() {
        byte[] headerBody = Blake2bUtil.blake2bHash256("header-body-cbor".getBytes());

        byte[] sig = signer.signHeaderBody(keys.getKesSkey(), headerBody, 0,
                (int) keys.getOpCert().getKesPeriod());

        assertThat(sig).hasSize(448);

        // Verify
        Sum6KesSigner kesSigner = new Sum6KesSigner();
        byte[] kesRootVk = kesSigner.deriveVerificationKey(keys.getKesSkey());
        KesVerifier verifier = CryptoExtConfiguration.INSTANCE.getKesVerifier();
        int relativePeriod = 0 - (int) keys.getOpCert().getKesPeriod();
        assertTrue(verifier.verify(sig, headerBody, kesRootVk, relativePeriod));
    }

    @Test
    void signHeaderBody_rejectsInvalidPeriod() {
        byte[] headerBody = new byte[32];

        assertThrows(IllegalArgumentException.class, () ->
                signer.signHeaderBody(keys.getKesSkey(), headerBody, 0, 5));
    }
}
