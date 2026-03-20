package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Era;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.TreeMap;

/**
 * Tracks epoch nonce state for block production.
 * Evolves incrementally per block, supporting both TPraos (Shelley-Alonzo) and Praos (Babbage+)
 * nonce derivation.
 * <p>
 * State variables:
 * <ul>
 *   <li>{@code epochNonce} — the current epoch's nonce (used for VRF input)</li>
 *   <li>{@code evolvingNonce} — continuously updated with each block's VRF contribution</li>
 *   <li>{@code candidateNonce} — frozen copy of evolvingNonce before stability window</li>
 *   <li>{@code labNonce} — previous block hash from last block (Ledger Adopts Block)</li>
 *   <li>{@code ticknPrevHashNonce} — labNonce carried from previous epoch boundary (for TICKN)</li>
 * </ul>
 * <p>
 * Includes a checkpoint ring buffer for rollback recovery. Checkpoints are stored per-slot
 * and allow restoring state to any recent slot within the buffer (up to k+40 entries).
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
    private final long preConwayStabilityWindow;  // floor(3k/f) for Shelley-Babbage
    private final long conwayStabilityWindow;     // ceil(4k/f) for Conway+
    private final long byronSlotsPerEpoch;

    // First absolute slot of the Shelley (or later) era. 0 means no Byron era (devnet/preview).
    // Set lazily from era tracking data persisted in ChainState.
    private long shelleyStartSlot = 0;
    private boolean shelleyStartSlotSet = false;

    // Checkpoint ring buffer for rollback recovery
    private final TreeMap<Long, byte[]> checkpoints = new TreeMap<>(); // slot -> serialized state
    private static final int MAX_CHECKPOINTS = 2200; // slightly more than k (2160)

    /**
     * @param epochLength      slots per epoch
     * @param securityParam    k value
     * @param activeSlotsCoeff f value (e.g. 1.0 for devnet, 0.05 for mainnet)
     */
    public EpochNonceState(long epochLength, long securityParam, double activeSlotsCoeff) {
        this(epochLength, securityParam, activeSlotsCoeff, Constants.BYRON_SLOTS_PER_EPOCH);
    }

    /**
     * @param epochLength         slots per epoch
     * @param securityParam       k value
     * @param activeSlotsCoeff    f value (e.g. 1.0 for devnet, 0.05 for mainnet)
     * @param byronSlotsPerEpoch  Byron era slots per epoch (k * 10 from Byron genesis)
     */
    public EpochNonceState(long epochLength, long securityParam, double activeSlotsCoeff, long byronSlotsPerEpoch) {
        this.epochLength = epochLength;
        this.preConwayStabilityWindow = (long) Math.floor(3.0 * securityParam / activeSlotsCoeff);
        this.conwayStabilityWindow = (long) Math.ceil(4.0 * securityParam / activeSlotsCoeff);
        this.byronSlotsPerEpoch = byronSlotsPerEpoch;
    }

    /**
     * Set the first absolute slot of the Shelley (or first non-Byron) era.
     * Only sets once — subsequent calls are ignored. Slot 0 is valid (networks with no Byron era).
     */
    public void setShelleyStartSlot(long slot) {
        if (!shelleyStartSlotSet) {
            this.shelleyStartSlot = (slot / byronSlotsPerEpoch) * byronSlotsPerEpoch;
            this.shelleyStartSlotSet = true;
            log.info("Shelley start slot set: {} (from block at slot {})", this.shelleyStartSlot, slot);
        }
    }

    public boolean isShelleyStartSlotSet() {
        return shelleyStartSlotSet;
    }

    public long getShelleyStartSlot() {
        return shelleyStartSlot;
    }

    /**
     * Compute the epoch number for a given absolute slot, accounting for Byron era offset.
     * For networks with no Byron era (shelleyStartSlot == 0), this is simply slot / epochLength.
     */
    public int epochForSlot(long slot) {
        if (shelleyStartSlot <= 0) return (int) (slot / epochLength);
        long shelleyStartEpoch = shelleyStartSlot / byronSlotsPerEpoch;
        return (int) (shelleyStartEpoch + (slot - shelleyStartSlot) / epochLength);
    }

    /**
     * Compute the first absolute slot of the given epoch, accounting for Byron era offset.
     * For networks with no Byron era (shelleyStartSlot == 0), this is simply epoch * epochLength.
     */
    public long firstSlotOfEpoch(int epoch) {
        if (shelleyStartSlot <= 0) return (long) epoch * epochLength;
        long shelleyStartEpoch = shelleyStartSlot / byronSlotsPerEpoch;
        return shelleyStartSlot + (epoch - shelleyStartEpoch) * epochLength;
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
        this.currentEpoch = epochForSlot(shelleyStartSlot);
        log.info("Epoch nonce initialized from genesis hash, epoch={}, preConwayStabilityWindow={}, conwayStabilityWindow={}",
                currentEpoch, preConwayStabilityWindow, conwayStabilityWindow);
    }

    /**
     * Initialize from a pre-computed genesis hash (32 bytes).
     * Used when the hash is provided via config to avoid file-byte sensitivity.
     *
     * @param genesisHash 32-byte blake2b-256 hash of the shelley genesis file
     */
    public void initFromGenesisHash(byte[] genesisHash) {
        this.epochNonce = genesisHash.clone();
        this.evolvingNonce = genesisHash.clone();
        this.candidateNonce = genesisHash.clone();
        this.labNonce = null;
        this.ticknPrevHashNonce = null;
        this.currentEpoch = epochForSlot(shelleyStartSlot);
        log.info("Epoch nonce initialized from pre-computed genesis hash, epoch={}, preConwayStabilityWindow={}, conwayStabilityWindow={}",
                currentEpoch, preConwayStabilityWindow, conwayStabilityWindow);
    }

    /**
     * Seed the nonce state from an external source (e.g. config, yaci-store, cardano-cli).
     * Used for bootstrap on public networks where we know the epoch nonce but haven't synced
     * the full chain.
     *
     * @param epoch the epoch number for this nonce
     * @param nonce the 32-byte epoch nonce
     */
    public void seedFromExternal(int epoch, byte[] nonce) {
        this.currentEpoch = epoch;
        this.epochNonce = nonce.clone();
        this.evolvingNonce = nonce.clone();
        this.candidateNonce = nonce.clone();
        this.labNonce = null;
        this.ticknPrevHashNonce = null;
        log.info("Epoch nonce seeded from external source: epoch={}, nonce={}",
                epoch, com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(nonce));
    }

    /**
     * Advance epoch if the given slot crosses an epoch boundary.
     * Must be called BEFORE reading epochNonce so that VRF proofs use the correct nonce.
     *
     * @param slot the slot about to be produced/observed
     */
    public void advanceEpochIfNeeded(long slot) {
        int blockEpoch = epochForSlot(slot);
        if (blockEpoch > currentEpoch) {
            performTickn();
            currentEpoch = blockEpoch;
            log.info("Epoch transition to epoch {}, new epochNonce={}",
                    currentEpoch, com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(epochNonce));
        }
    }

    /**
     * Called after each block is produced by THIS node. Evolves the nonce state
     * using Praos (Conway) eta derivation.
     * <p>
     * Kept for backward compatibility with devnet block production where all blocks are Conway.
     *
     * @param slot      the slot of the produced block
     * @param prevHash  previous block hash (32 bytes), null for genesis
     * @param vrfOutput 64-byte VRF output from the block's VRF proof
     */
    public void onBlockProduced(long slot, byte[] prevHash, byte[] vrfOutput) {
        // Devnet always produces Conway blocks, so use Praos eta derivation
        byte[] eta = computeEtaPraos(vrfOutput);
        evolveState(slot, prevHash, eta, conwayStabilityWindow);
    }

    /**
     * Called when observing a synced block from the network. Evolves the nonce state
     * using era-appropriate eta derivation.
     *
     * @param slot      the slot of the observed block
     * @param prevHash  previous block hash (32 bytes), null for genesis
     * @param vrfOutput VRF output bytes from the block header
     * @param era       the era of the block (determines TPraos vs Praos derivation)
     */
    public void onBlockObserved(long slot, byte[] prevHash, byte[] vrfOutput, Era era) {
        byte[] eta = computeEta(vrfOutput, era);
        long stabilityWindow = getStabilityWindow(era);
        evolveState(slot, prevHash, eta, stabilityWindow);
    }

    /**
     * Common nonce evolution logic shared by onBlockProduced and onBlockObserved.
     */
    private void evolveState(long slot, byte[] prevHash, byte[] eta, long stabilityWindow) {
        // 1. Update evolving nonce: evolvingNonce ⭒ eta
        evolvingNonce = combineNonces(evolvingNonce, eta);

        // 2. Freeze check: if block is before stability window, update candidate
        long firstSlotNextEpoch = firstSlotOfEpoch(currentEpoch + 1);
        if (slot + stabilityWindow < firstSlotNextEpoch) {
            candidateNonce = evolvingNonce.clone();
        }

        // 3. Update labNonce = prevHash (direct bytes, no re-hashing)
        labNonce = prevHash != null ? prevHash.clone() : null;
    }

    /**
     * Save a checkpoint at the given slot for rollback recovery.
     */
    public void saveCheckpoint(long slot) {
        saveCheckpoint(slot, serialize());
    }

    /**
     * Save a checkpoint using pre-serialized bytes (avoids redundant serialization
     * when the caller also needs the serialized form for persistence).
     */
    public void saveCheckpoint(long slot, byte[] serialized) {
        checkpoints.put(slot, serialized);
        while (checkpoints.size() > MAX_CHECKPOINTS) {
            checkpoints.pollFirstEntry();
        }
    }

    /**
     * Roll back nonce state to the most recent checkpoint at or before the target slot.
     *
     * @param slot the target rollback slot
     * @return true if a checkpoint was found and restored, false otherwise
     */
    public boolean rollbackTo(long slot) {
        var entry = checkpoints.floorEntry(slot);
        if (entry == null) return false;

        restore(entry.getValue());
        // Remove all checkpoints after rollback point
        checkpoints.tailMap(slot, false).clear();
        return true;
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
            log.warn("performTickn: combineNonces returned null (both inputs null), keeping previous epochNonce");
        }

        // Carry forward: ticknPrevHashNonce = labNonce from this epoch
        ticknPrevHashNonce = labNonce != null ? labNonce.clone() : null;
    }

    /**
     * Get the appropriate stability window for the given era.
     * <ul>
     *   <li>Conway+ (era value >= 7): ceil(4k/f)</li>
     *   <li>Pre-Conway (Shelley-Babbage): floor(3k/f)</li>
     * </ul>
     * Note: Era.Conway.getValue() == 7 in yaci's EraUtil mapping.
     */
    long getStabilityWindow(Era era) {
        if (era != null && era.getValue() >= Era.Conway.getValue()) {
            return conwayStabilityWindow;
        }
        return preConwayStabilityWindow;
    }

    /**
     * Compute eta (VRF nonce contribution) with era-appropriate derivation.
     *
     * @param vrfOutput the VRF output bytes
     * @param era       the block's era
     * @return 32-byte eta value
     */
    static byte[] computeEta(byte[] vrfOutput, Era era) {
        if (era != null && era.getValue() >= Era.Babbage.getValue()) {
            // Praos (Babbage+): Blake2b_256(Blake2b_256("N" || vrfOutput))
            return computeEtaPraos(vrfOutput);
        } else {
            // TPraos (Shelley-Alonzo): Blake2b_256(vrfOutput)
            return computeEtaTPraos(vrfOutput);
        }
    }

    /**
     * Praos eta derivation (Babbage+): blake2b_256(blake2b_256("N" || vrfOutput))
     */
    static byte[] computeEtaPraos(byte[] vrfOutput) {
        byte[] prefixed = new byte[1 + vrfOutput.length];
        prefixed[0] = (byte) 'N';
        System.arraycopy(vrfOutput, 0, prefixed, 1, vrfOutput.length);
        byte[] firstHash = Blake2bUtil.blake2bHash256(prefixed);
        return Blake2bUtil.blake2bHash256(firstHash);
    }

    /**
     * TPraos eta derivation (Shelley-Alonzo): blake2b_256(vrfOutput)
     */
    static byte[] computeEtaTPraos(byte[] vrfOutput) {
        return Blake2bUtil.blake2bHash256(vrfOutput);
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

    public byte[] getEvolvingNonce() {
        return evolvingNonce != null ? evolvingNonce.clone() : null;
    }

    public byte[] getCandidateNonce() {
        return candidateNonce != null ? candidateNonce.clone() : null;
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
