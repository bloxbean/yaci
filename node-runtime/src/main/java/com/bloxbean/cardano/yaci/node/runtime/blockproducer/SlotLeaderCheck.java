package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.client.crypto.vrf.cardano.CardanoLeaderCheck;

import java.math.BigDecimal;

/**
 * Slot leader eligibility check for Ouroboros Praos.
 * Wraps VRF computation and threshold comparison.
 */
public class SlotLeaderCheck {

    private final byte[] vrfSkey;
    private final BigDecimal activeSlotCoeff;
    private final BlockSigner blockSigner;

    /**
     * @param vrfSkey          64-byte VRF secret key
     * @param activeSlotCoeff  active slot coefficient (e.g. 0.05 for mainnet)
     * @param blockSigner      shared BlockSigner instance
     */
    public SlotLeaderCheck(byte[] vrfSkey, BigDecimal activeSlotCoeff, BlockSigner blockSigner) {
        this.vrfSkey = vrfSkey;
        this.activeSlotCoeff = activeSlotCoeff;
        this.blockSigner = blockSigner;
    }

    /**
     * Check whether we are the slot leader for the given slot.
     * If eligible, returns the VRF result (to be reused in block building).
     * If not eligible, returns null.
     *
     * @param slot       the slot to check
     * @param epochNonce the current epoch nonce (32 bytes)
     * @param sigma      the pool's relative stake (0..1)
     * @return VrfSignResult if eligible, null if not
     */
    public BlockSigner.VrfSignResult checkAndProve(long slot, byte[] epochNonce, BigDecimal sigma) {
        BlockSigner.VrfSignResult vrfResult = blockSigner.computeVrf(vrfSkey, slot, epochNonce);
        byte[] leaderValue = CardanoLeaderCheck.vrfLeaderValue(vrfResult.output());
        boolean isLeader = CardanoLeaderCheck.checkLeaderValue(leaderValue, sigma, activeSlotCoeff);
        return isLeader ? vrfResult : null;
    }
}
