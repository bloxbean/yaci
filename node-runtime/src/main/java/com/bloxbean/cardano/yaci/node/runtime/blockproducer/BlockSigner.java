package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.client.crypto.config.CryptoExtConfiguration;
import com.bloxbean.cardano.client.crypto.kes.KesSigner;
import com.bloxbean.cardano.client.crypto.vrf.VrfProver;
import com.bloxbean.cardano.client.crypto.vrf.VrfResult;
import com.bloxbean.cardano.client.crypto.vrf.VrfVerifier;
import com.bloxbean.cardano.client.crypto.vrf.cardano.CardanoVrfInput;

import java.util.Arrays;

/**
 * Stateless helper for VRF proving and KES signing during block production.
 * Uses the CCL crypto-ext APIs.
 */
public class BlockSigner {

    private final VrfProver vrfProver;
    private final VrfVerifier vrfVerifier;
    private final KesSigner kesSigner;

    public BlockSigner() {
        this.vrfProver = CryptoExtConfiguration.INSTANCE.getVrfProver();
        this.vrfVerifier = CryptoExtConfiguration.INSTANCE.getVrfVerifier();
        this.kesSigner = CryptoExtConfiguration.INSTANCE.getKesSigner();
    }

    /**
     * Result of VRF computation.
     *
     * @param output 64-byte VRF output
     * @param proof  80-byte VRF proof
     */
    public record VrfSignResult(byte[] output, byte[] proof) {
    }

    /**
     * Compute VRF proof for a given slot and epoch nonce.
     *
     * @param vrfSkey    64-byte VRF secret key (32-byte seed + 32-byte public key)
     * @param slot       slot number
     * @param epochNonce 32-byte epoch nonce
     * @return VrfSignResult with 64-byte output and 80-byte proof
     */
    public VrfSignResult computeVrf(byte[] vrfSkey, long slot, byte[] epochNonce) {
        // Construct Praos VRF input: blake2b_256(slot_8bytes_BE || epochNonce)
        byte[] alpha = CardanoVrfInput.mkInputVrf(slot, epochNonce);

        // Prove
        byte[] proof = vrfProver.prove(vrfSkey, alpha);

        // Extract VRF vkey from skey (last 32 bytes)
        byte[] vrfVkey = Arrays.copyOfRange(vrfSkey, 32, 64);

        // Verify to get the 64-byte output
        VrfResult result = vrfVerifier.verify(vrfVkey, proof, alpha);
        if (!result.isValid()) {
            throw new IllegalStateException("VRF self-verification failed for slot " + slot);
        }

        return new VrfSignResult(result.getOutput(), proof);
    }

    /**
     * KES-sign a header body.
     *
     * @param kesSkey          608-byte KES secret key
     * @param headerBodyCbor   raw CBOR bytes of the header body array
     * @param currentKesPeriod current KES period (slot / slotsPerKESPeriod)
     * @param opcertKesPeriod  KES period from the operational certificate
     * @return 448-byte KES signature
     */
    public byte[] signHeaderBody(byte[] kesSkey, byte[] headerBodyCbor,
                                 int currentKesPeriod, int opcertKesPeriod) {
        int relativePeriod = currentKesPeriod - opcertKesPeriod;
        if (relativePeriod < 0) {
            throw new IllegalArgumentException(
                    "Current KES period " + currentKesPeriod + " is before opcert KES period " + opcertKesPeriod);
        }
        return kesSigner.sign(kesSkey, headerBodyCbor, relativePeriod);
    }
}
