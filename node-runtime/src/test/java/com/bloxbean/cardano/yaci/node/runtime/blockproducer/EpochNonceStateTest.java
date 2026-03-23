package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class EpochNonceStateTest {

    private static final long EPOCH_LENGTH = 600;
    private static final long SECURITY_PARAM = 100;
    private static final double ACTIVE_SLOTS_COEFF = 1.0;

    private EpochNonceState state;

    @BeforeEach
    void setUp() {
        state = new EpochNonceState(EPOCH_LENGTH, SECURITY_PARAM, ACTIVE_SLOTS_COEFF);
    }

    @Test
    void initFromGenesis_setsAllNoncesToGenesisHash() {
        byte[] genesisBytes = "test-genesis-content".getBytes();
        byte[] expectedHash = Blake2bUtil.blake2bHash256(genesisBytes);

        state.initFromGenesis(genesisBytes);

        assertArrayEquals(expectedHash, state.getEpochNonce());
        assertEquals(0, state.getCurrentEpoch());
    }

    @Test
    void onBlockProduced_evolvesNonce() {
        state.initFromGenesis("genesis".getBytes());
        byte[] initialNonce = state.getEpochNonce().clone();

        byte[] prevHash = Blake2bUtil.blake2bHash256("prevblock".getBytes());
        byte[] vrfOutput = new byte[64]; // 64-byte VRF output
        vrfOutput[0] = 0x42;

        state.onBlockProduced(1, prevHash, vrfOutput);

        // Epoch nonce should not change within the same epoch
        assertArrayEquals(initialNonce, state.getEpochNonce());
        assertEquals(0, state.getCurrentEpoch());
    }

    @Test
    void advanceEpochIfNeeded_epochBoundary_performsTickn() {
        state.initFromGenesis("genesis".getBytes());
        byte[] epochZeroNonce = state.getEpochNonce().clone();

        // Produce a block in epoch 0
        byte[] vrfOutput1 = new byte[64];
        vrfOutput1[0] = 0x01;
        state.onBlockProduced(0, null, vrfOutput1);

        // Advance epoch before producing block in epoch 1
        state.advanceEpochIfNeeded(EPOCH_LENGTH);

        // Epoch should have transitioned
        assertEquals(1, state.getCurrentEpoch());
        // Epoch nonce should have changed (TICKN applied)
        assertFalse(java.util.Arrays.equals(epochZeroNonce, state.getEpochNonce()),
                "Epoch nonce should change after TICKN transition");

        // Now produce the block in epoch 1
        byte[] prevHash2 = Blake2bUtil.blake2bHash256("block1".getBytes());
        byte[] vrfOutput2 = new byte[64];
        vrfOutput2[0] = 0x02;
        state.onBlockProduced(EPOCH_LENGTH, prevHash2, vrfOutput2);
    }

    @Test
    void serializeDeserialize_roundtrip() {
        state.initFromGenesis("genesis-roundtrip".getBytes());

        // Produce a block to evolve state
        byte[] prevHash = Blake2bUtil.blake2bHash256("some-block".getBytes());
        byte[] vrfOutput = new byte[64];
        vrfOutput[0] = 0x77;
        state.onBlockProduced(5, prevHash, vrfOutput);

        // Serialize
        byte[] serialized = state.serialize();
        assertThat(serialized).isNotNull();
        assertThat(serialized.length).isGreaterThan(0).isLessThanOrEqualTo(170);

        // Deserialize into a new instance
        EpochNonceState restored = new EpochNonceState(EPOCH_LENGTH, SECURITY_PARAM, ACTIVE_SLOTS_COEFF);
        restored.restore(serialized);

        assertArrayEquals(state.getEpochNonce(), restored.getEpochNonce());
        assertEquals(state.getCurrentEpoch(), restored.getCurrentEpoch());
    }

    @Test
    void serializeDeserialize_withNullNonces() {
        // Init from genesis — labNonce and ticknPrevHashNonce are null initially
        state.initFromGenesis("genesis-nulls".getBytes());

        byte[] serialized = state.serialize();
        EpochNonceState restored = new EpochNonceState(EPOCH_LENGTH, SECURITY_PARAM, ACTIVE_SLOTS_COEFF);
        restored.restore(serialized);

        assertArrayEquals(state.getEpochNonce(), restored.getEpochNonce());
        assertEquals(0, restored.getCurrentEpoch());
    }

    @Test
    void combineNonces_nullHandling() {
        byte[] a = Blake2bUtil.blake2bHash256("a".getBytes());
        byte[] b = Blake2bUtil.blake2bHash256("b".getBytes());

        // null ⭒ x = x
        assertArrayEquals(b, EpochNonceState.combineNonces(null, b));
        // x ⭒ null = x
        assertArrayEquals(a, EpochNonceState.combineNonces(a, null));
        // null ⭒ null = null
        assertNull(EpochNonceState.combineNonces(null, null));
        // a ⭒ b should be blake2b(a || b)
        byte[] combined = new byte[a.length + b.length];
        System.arraycopy(a, 0, combined, 0, a.length);
        System.arraycopy(b, 0, combined, a.length, b.length);
        assertArrayEquals(Blake2bUtil.blake2bHash256(combined), EpochNonceState.combineNonces(a, b));
    }

    @Test
    void computeEta_matchesPraosFormula() {
        byte[] vrfOutput = new byte[64];
        vrfOutput[0] = 0x42;

        byte[] eta = EpochNonceState.computeEtaPraos(vrfOutput);
        assertThat(eta).hasSize(32);

        // Verify: blake2b_256(blake2b_256("N" || vrfOutput))
        byte[] prefixed = new byte[1 + vrfOutput.length];
        prefixed[0] = (byte) 'N';
        System.arraycopy(vrfOutput, 0, prefixed, 1, vrfOutput.length);
        byte[] firstHash = Blake2bUtil.blake2bHash256(prefixed);
        byte[] expected = Blake2bUtil.blake2bHash256(firstHash);
        assertArrayEquals(expected, eta);
    }

    @Test
    void multipleEpochTransitions_nonceEvolves() {
        state.initFromGenesis("genesis-multi".getBytes());

        byte[] vrfOutput = new byte[64];
        vrfOutput[0] = 0x11;

        // Produce blocks across 3 epochs
        for (int epoch = 0; epoch < 3; epoch++) {
            long slot = epoch * EPOCH_LENGTH + 10;
            byte[] prevHash = Blake2bUtil.blake2bHash256(("block-" + epoch).getBytes());
            state.advanceEpochIfNeeded(slot);
            state.onBlockProduced(slot, prevHash, vrfOutput);
        }

        assertEquals(2, state.getCurrentEpoch());
        assertThat(state.getEpochNonce()).hasSize(32);
    }

    @Test
    void epochForSlot_withByronOffset_preprod() {
        var s = new EpochNonceState(432000, 2160, 0.05);
        s.setShelleyStartSlot(86400);  // preprod: 4 Byron epochs * 21600
        assertEquals(4, s.epochForSlot(86400));    // first Shelley slot
        assertEquals(4, s.epochForSlot(518399));   // last slot of epoch 4
        assertEquals(5, s.epochForSlot(518400));   // first slot of epoch 5
    }

    @Test
    void firstSlotOfEpoch_withByronOffset_preprod() {
        var s = new EpochNonceState(432000, 2160, 0.05);
        s.setShelleyStartSlot(86400);
        assertEquals(86400, s.firstSlotOfEpoch(4));
        assertEquals(518400, s.firstSlotOfEpoch(5));
    }

    @Test
    void epochForSlot_noByron_unchanged() {
        var s = new EpochNonceState(432000, 2160, 0.05);
        // shelleyStartSlot=0 (default), formula unchanged
        assertEquals(0, s.epochForSlot(0));
        assertEquals(1, s.epochForSlot(432000));
    }

    @Test
    void setShelleyStartSlot_roundsDownToByronBoundary() {
        var s = new EpochNonceState(432000, 2160, 0.05);
        // Pass a non-aligned slot (e.g. 86401 instead of 86400)
        s.setShelleyStartSlot(86401);
        // Should round down to 86400 (4 * 21600)
        assertEquals(86400, s.getShelleyStartSlot());
        // Epoch calculations should still be correct
        assertEquals(4, s.epochForSlot(86400));
        assertEquals(4, s.epochForSlot(518399));
        assertEquals(5, s.epochForSlot(518400));
    }

    @Test
    void setShelleyStartSlot_exactBoundaryUnchanged() {
        var s = new EpochNonceState(432000, 2160, 0.05);
        s.setShelleyStartSlot(86400);
        assertEquals(86400, s.getShelleyStartSlot());
    }

    @Test
    void setShelleyStartSlot_slot0_setsOnceAndPreventsReSet() {
        // Preview/Sanchonet: no Byron era, first block at slot 0
        var s = new EpochNonceState(86400, 432, 0.05);
        assertFalse(s.isShelleyStartSlotSet());

        // Set from first block at slot 0
        s.setShelleyStartSlot(0);
        assertTrue(s.isShelleyStartSlotSet());
        assertEquals(0, s.getShelleyStartSlot());

        // Subsequent calls should be no-ops (even with slot > 21600)
        s.setShelleyStartSlot(21600);
        assertEquals(0, s.getShelleyStartSlot());
        s.setShelleyStartSlot(86400);
        assertEquals(0, s.getShelleyStartSlot());

        // Epoch calculations should remain correct
        assertEquals(0, s.epochForSlot(0));
        assertEquals(0, s.epochForSlot(86399));
        assertEquals(1, s.epochForSlot(86400));
    }

    @Test
    void setShelleyStartSlot_preprod_setsOnceFromNonZeroSlot() {
        var s = new EpochNonceState(432000, 2160, 0.05);
        assertFalse(s.isShelleyStartSlotSet());

        s.setShelleyStartSlot(86400);
        assertTrue(s.isShelleyStartSlotSet());
        assertEquals(86400, s.getShelleyStartSlot());

        // Subsequent calls should be no-ops
        s.setShelleyStartSlot(518400);
        assertEquals(86400, s.getShelleyStartSlot());
    }

    @Test
    void epochForSlot_withByronOffset_mainnet() {
        var s = new EpochNonceState(432000, 2160, 0.05);
        s.setShelleyStartSlot(4492800);  // mainnet: 208 Byron epochs * 21600
        assertEquals(208, s.epochForSlot(4492800));
    }

    @Test
    void initFromGenesisHash_setsCorrectEpoch_withByronOffset() {
        var s = new EpochNonceState(432000, 2160, 0.05);
        s.setShelleyStartSlot(86400); // preprod
        byte[] hash = Blake2bUtil.blake2bHash256("genesis".getBytes());
        s.initFromGenesisHash(hash);
        assertEquals(4, s.getCurrentEpoch());
    }

    @Test
    void epochForSlot_withCustomByronK_preview() {
        // Preview-like network: Byron k=432, byronSlotsPerEpoch=4320, Shelley epochLength=86400
        var s = new EpochNonceState(86400, 432, 0.05, 4320);
        // Suppose 10 Byron epochs before Shelley: 10 * 4320 = 43200
        s.setShelleyStartSlot(43200);
        assertEquals(43200, s.getShelleyStartSlot());
        assertEquals(10, s.epochForSlot(43200));   // first Shelley slot
        assertEquals(10, s.epochForSlot(129599));  // last slot of epoch 10
        assertEquals(11, s.epochForSlot(129600));  // first slot of epoch 11
        assertEquals(43200, s.firstSlotOfEpoch(10));
        assertEquals(129600, s.firstSlotOfEpoch(11));
    }

    @Test
    void setShelleyStartSlot_roundsToCustomByronEpochBoundary() {
        // Byron k=432, byronSlotsPerEpoch=4320
        var s = new EpochNonceState(86400, 432, 0.05, 4320);
        // Pass a non-aligned slot (e.g. 43201 instead of 43200)
        s.setShelleyStartSlot(43201);
        // Should round down to 43200 (10 * 4320)
        assertEquals(43200, s.getShelleyStartSlot());
    }

    @Test
    void stabilityWindow_freezesCandidateNonce() {
        // With ACTIVE_SLOTS_COEFF=1.0, stabilityWindow = ceil(4*100/1.0) = 400
        state.initFromGenesis("genesis-stability".getBytes());

        byte[] vrfOutput = new byte[64];
        vrfOutput[0] = 0x33;

        // Produce a block early in epoch (before stability window)
        // firstSlotNextEpoch = 600, slot + 400 < 600 → slot < 200
        byte[] prevHash1 = Blake2bUtil.blake2bHash256("early".getBytes());
        state.onBlockProduced(50, prevHash1, vrfOutput);

        // Produce a block late in epoch (within stability window)
        // slot + 400 >= 600 → slot >= 200
        byte[] prevHash2 = Blake2bUtil.blake2bHash256("late".getBytes());
        state.onBlockProduced(300, prevHash2, vrfOutput);

        // Trigger epoch transition to use the candidate nonce
        byte[] prevHash3 = Blake2bUtil.blake2bHash256("epoch1".getBytes());
        state.advanceEpochIfNeeded(EPOCH_LENGTH + 1);
        state.onBlockProduced(EPOCH_LENGTH + 1, prevHash3, vrfOutput);

        assertEquals(1, state.getCurrentEpoch());
        assertThat(state.getEpochNonce()).hasSize(32);
    }
}
