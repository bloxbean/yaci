package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;

/**
 * Tracks epoch nonce state for block production.
 * Evolves incrementally per block, matching Ouroboros Praos (Conway) nonce derivation.
 * <p>
 * State variables:
 * <ul>
 *   <li>{@code epochNonce} — the current epoch's nonce (used for VRF input)</li>
 *   <li>{@code evolvingNonce} — continuously updated with each block's VRF contribution</li>
 *   <li>{@code candidateNonce} — frozen copy of evolvingNonce before stability window</li>
 *   <li>{@code labNonce} — previous block hash from last block (Ledger Adopts Block)</li>
 *   <li>{@code ticknPrevHashNonce} — labNonce carried from previous epoch boundary (for TICKN)</li>
 * </ul>
 */
@Slf4j
public class EpochNonceState {

    private static final int NONCE_LENGTH = 32;
    private static final byte SERIALIZATION_VERSION = 1;

    private byte[] epochNonce;
    private byte[] evolvingNonce;
    private byte[] candidateNonce;
    private byte[] labNonce;           // may be null (no previous block yet)
    private byte[] ticknPrevHashNonce; // may be null (first epoch)
    private int currentEpoch;

    private final long epochLength;
    private final long stabilityWindow;

    /**
     * @param epochLength      slots per epoch
     * @param securityParam    k value
     * @param activeSlotsCoeff f value (e.g. 1.0 for devnet)
     */
    public EpochNonceState(long epochLength, long securityParam, double activeSlotsCoeff) {
        this.epochLength = epochLength;
        // Conway stability window: ceil(4k/f)
        this.stabilityWindow = (long) Math.ceil(4.0 * securityParam / activeSlotsCoeff);
    }

    /**
     * Initialize from genesis: all nonces set to blake2b_256(shelleyGenesisFileBytes).
     *
     * @param shelleyGenesisFileBytes raw bytes of the shelley-genesis.json file
     */
    public void initFromGenesis(byte[] shelleyGenesisFileBytes) {
        byte[] genesisHash = Blake2bUtil.blake2bHash256(shelleyGenesisFileBytes);
        this.epochNonce = genesisHash.clone();
        this.evolvingNonce = genesisHash.clone();
        this.candidateNonce = genesisHash.clone();
        this.labNonce = null;
        this.ticknPrevHashNonce = null;
        this.currentEpoch = 0;
        log.info("Epoch nonce initialized from genesis hash, epoch=0, stabilityWindow={}", stabilityWindow);
    }

    /**
     * Advance epoch if the given slot crosses an epoch boundary.
     * Must be called BEFORE reading epochNonce so that VRF proofs use the correct nonce.
     *
     * @param slot the slot about to be produced
     */
    public void advanceEpochIfNeeded(long slot) {
        int blockEpoch = (int) (slot / epochLength);
        if (blockEpoch > currentEpoch) {
            performTickn();
            currentEpoch = blockEpoch;
            log.info("Epoch transition to epoch {}, new epochNonce={}",
                    currentEpoch, com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(epochNonce));
        }
    }

    /**
     * Called after each block is produced. Evolves the nonce state.
     *
     * @param slot      the slot of the produced block
     * @param prevHash  previous block hash (32 bytes), null for genesis
     * @param vrfOutput 64-byte VRF output from the block's VRF proof
     */
    public void onBlockProduced(long slot, byte[] prevHash, byte[] vrfOutput) {
        // 1. Compute eta: blake2b_256(blake2b_256("N" || vrfOutput))
        byte[] eta = computeEta(vrfOutput);

        // 2. Update evolving nonce: evolvingNonce ⭒ eta
        evolvingNonce = combineNonces(evolvingNonce, eta);

        // 3. Freeze check: if block is before stability window, update candidate
        long firstSlotNextEpoch = ((long) currentEpoch + 1) * epochLength;
        if (slot + stabilityWindow < firstSlotNextEpoch) {
            candidateNonce = evolvingNonce.clone();
        }

        // 4. Update labNonce = prevHash (direct bytes, no re-hashing)
        labNonce = prevHash != null ? prevHash.clone() : null;
    }

    /**
     * TICKN transition: compute new epoch nonce from candidate and ticknPrevHash.
     */
    private void performTickn() {
        // epochNonce = candidateNonce ⭒ ticknPrevHashNonce
        byte[] newEpochNonce = combineNonces(candidateNonce, ticknPrevHashNonce);
        if (newEpochNonce != null) {
            epochNonce = newEpochNonce;
        } else {
            // Both candidateNonce and ticknPrevHashNonce are null (NeutralNonce ⭒ NeutralNonce);
            // keep the previous epochNonce to avoid NPE downstream.
            log.warn("performTickn: combineNonces returned null (both inputs null), keeping previous epochNonce");
        }

        // Carry forward: ticknPrevHashNonce = labNonce from this epoch
        ticknPrevHashNonce = labNonce != null ? labNonce.clone() : null;
    }

    /**
     * Compute eta (VRF nonce contribution) for Praos (Conway): blake2b_256(blake2b_256("N" || vrfOutput))
     */
    static byte[] computeEta(byte[] vrfOutput) {
        byte[] prefixed = new byte[1 + vrfOutput.length];
        prefixed[0] = (byte) 'N';
        System.arraycopy(vrfOutput, 0, prefixed, 1, vrfOutput.length);
        byte[] firstHash = Blake2bUtil.blake2bHash256(prefixed);
        return Blake2bUtil.blake2bHash256(firstHash);
    }

    /**
     * Combine nonces (⭒ operator): if either is null (NeutralNonce), return the other;
     * otherwise blake2b_256(a || b).
     */
    static byte[] combineNonces(byte[] a, byte[] b) {
        if (a == null) return b;
        if (b == null) return a;
        byte[] combined = new byte[a.length + b.length];
        System.arraycopy(a, 0, combined, 0, a.length);
        System.arraycopy(b, 0, combined, a.length, b.length);
        return Blake2bUtil.blake2bHash256(combined);
    }

    public byte[] getEpochNonce() {
        return epochNonce != null ? epochNonce.clone() : null;
    }

    public int getCurrentEpoch() {
        return currentEpoch;
    }

    /**
     * Serialize state to compact binary for persistence.
     * Format: version(1) + epoch(4) + 5 nullable nonces (1-byte flag + 32-byte value each)
     */
    public byte[] serialize() {
        // Max size: 1 + 4 + 5*(1+32) = 170 bytes
        ByteBuffer buf = ByteBuffer.allocate(170);
        buf.put(SERIALIZATION_VERSION);
        buf.putInt(currentEpoch);
        writeNullableNonce(buf, epochNonce);
        writeNullableNonce(buf, evolvingNonce);
        writeNullableNonce(buf, candidateNonce);
        writeNullableNonce(buf, labNonce);
        writeNullableNonce(buf, ticknPrevHashNonce);
        byte[] result = new byte[buf.position()];
        buf.flip();
        buf.get(result);
        return result;
    }

    /**
     * Restore state from serialized bytes.
     */
    public void restore(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        byte version = buf.get();
        if (version != SERIALIZATION_VERSION) {
            throw new IllegalArgumentException("Unsupported nonce state version: " + version);
        }
        currentEpoch = buf.getInt();
        epochNonce = readNullableNonce(buf);
        evolvingNonce = readNullableNonce(buf);
        candidateNonce = readNullableNonce(buf);
        labNonce = readNullableNonce(buf);
        ticknPrevHashNonce = readNullableNonce(buf);
        log.info("Epoch nonce state restored: epoch={}", currentEpoch);
    }

    private static void writeNullableNonce(ByteBuffer buf, byte[] nonce) {
        if (nonce == null) {
            buf.put((byte) 0);
        } else {
            buf.put((byte) 1);
            buf.put(nonce);
        }
    }

    private static byte[] readNullableNonce(ByteBuffer buf) {
        byte flag = buf.get();
        if (flag == 0) return null;
        byte[] nonce = new byte[NONCE_LENGTH];
        buf.get(nonce);
        return nonce;
    }
}
