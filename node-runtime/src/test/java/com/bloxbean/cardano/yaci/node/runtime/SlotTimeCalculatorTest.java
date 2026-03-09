package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.node.runtime.chain.InMemoryChainState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SlotTimeCalculator using known Cardano network data.
 * Test values sourced from yaci-store's EraServiceTest and EraServiceSlotFromTimeTest.
 */
class SlotTimeCalculatorTest {

    // --- Preprod ---
    // startTime=1654041600, byronSlotDuration=20s, shelleySlotLength=1s, firstNonByronSlot=86400

    @Test
    void preprod_byronSlot() {
        var calc = new SlotTimeCalculator(1654041600L, 20, 1.0, new InMemoryChainState());
        // Set firstNonByronSlot via calculation — for preprod it's 86400
        // Since InMemoryChainState has no era data, Byron formula applies: startTime + slot * 20
        // Byron slot 23761 → 1654041600 + 23761 * 20 = 1654041600 + 475220 = 1654516820
        long time = calc.slotToUnixTime(23761);
        assertThat(time).isEqualTo(1654516820L);
    }

    @Test
    void preprod_shelleySlot() {
        // Use a calculator with a pre-resolved firstNonByronSlot
        var calc = new PreprodCalculator();
        // Shelley slot 87460
        // shelleyStartTime = 1654041600 + 86400 * 20 = 1654041600 + 1728000 = 1655769600
        // time = 1655769600 + (87460 - 86400) * 1 = 1655769600 + 1060 = 1655770660
        long time = calc.slotToUnixTime(87460);
        assertThat(time).isEqualTo(1655770660L);
    }

    @Test
    void preprod_shelleySlot_large() {
        var calc = new PreprodCalculator();
        // Shelley slot 14621989
        // shelleyStartTime = 1655769600
        // time = 1655769600 + (14621989 - 86400) = 1655769600 + 14535589 = 1670305189
        long time = calc.slotToUnixTime(14621989);
        assertThat(time).isEqualTo(1670305189L);
    }

    // --- Preview (all Shelley+, firstNonByronSlot=0) ---
    // startTime=1666656000, firstNonByronSlot=0, shelleySlotLength=1s

    @Test
    void preview_slot_420() {
        var calc = new PreviewCalculator();
        // time = 1666656000 + 420 * 1 = 1666656420
        long time = calc.slotToUnixTime(420);
        assertThat(time).isEqualTo(1666656420L);
    }

    @Test
    void preview_slot_259180() {
        var calc = new PreviewCalculator();
        long time = calc.slotToUnixTime(259180);
        assertThat(time).isEqualTo(1666915180L);
    }

    @Test
    void preview_slot_264811() {
        var calc = new PreviewCalculator();
        long time = calc.slotToUnixTime(264811);
        assertThat(time).isEqualTo(1666920811L);
    }

    // --- Mainnet ---
    // startTime=1506203091, byronSlotDuration=20s, shelleySlotLength=1s, firstNonByronSlot=4492800

    @Test
    void mainnet_byronSlot0() {
        var calc = new MainnetCalculator();
        long time = calc.slotToUnixTime(0);
        assertThat(time).isEqualTo(1506203091L);
    }

    @Test
    void mainnet_byronSlot_2591343() {
        // Byron: time = 1506203091 + 2591343 * 20 = 1506203091 + 51826860 = 1558029951
        var calc = new MainnetCalculator();
        long time = calc.slotToUnixTime(2591343);
        assertThat(time).isEqualTo(1558029951L);
    }

    @Test
    void mainnet_shelleySlot_roundTrip() {
        var calc = new MainnetCalculator();
        // From yaci-store test: slot 170350961 → Oct 31 2025 21:27:32 SGT
        // shelleyStartTime = 1506203091 + 4492800 * 20 = 1506203091 + 89856000 = 1596059091
        // time = 1596059091 + (170350961 - 4492800) * 1 = 1596059091 + 165858161 = 1761917252
        long time = calc.slotToUnixTime(170350961);
        assertThat(time).isEqualTo(1761917252L);
    }

    // --- Devnet (firstNonByronSlot=0, fractional slot length) ---

    @Test
    void devnet_fractionalSlotLength() {
        // startTime=1700000000, slotLength=0.2s, firstNonByronSlot=0
        var calc = new DevnetCalculator(1700000000L, 0.2);
        // slot 100 → 1700000000 + 100 * 0.2 = 1700000000 + 20 = 1700000020
        long time = calc.slotToUnixTime(100);
        assertThat(time).isEqualTo(1700000020L);
    }

    @Test
    void devnet_slotLength_1sec() {
        var calc = new DevnetCalculator(1700000000L, 1.0);
        long time = calc.slotToUnixTime(500);
        assertThat(time).isEqualTo(1700000500L);
    }

    // --- Edge cases ---

    @Test
    void firstNonByronSlotNotResolved_fallsBackToByronFormula() {
        // InMemoryChainState has no era data, so resolveFirstNonByronSlot returns -1
        var calc = new SlotTimeCalculator(1654041600L, 20, 1.0, new InMemoryChainState());
        long time = calc.slotToUnixTime(100);
        // Falls back to Byron: 1654041600 + 100 * 20 = 1654043600
        assertThat(time).isEqualTo(1654043600L);
    }

    @Test
    void firstNonByronSlotIsZero_usesShelleyFormula() {
        var calc = new PreviewCalculator();
        // firstNonByronSlot=0 → shelleyStartTime = startTime + 0 = startTime
        // time = startTime + slot * shelleySlotLength
        long time = calc.slotToUnixTime(0);
        assertThat(time).isEqualTo(1666656000L);
    }

    // --- Helper calculator subclasses with pre-resolved firstNonByronSlot ---

    private static class PreprodCalculator extends SlotTimeCalculator {
        PreprodCalculator() {
            super(1654041600L, 20, 1.0, new InMemoryChainState());
            setFirstNonByronSlot(86400);
        }
    }

    private static class PreviewCalculator extends SlotTimeCalculator {
        PreviewCalculator() {
            super(1666656000L, 20, 1.0, new InMemoryChainState());
            setFirstNonByronSlot(0);
        }
    }

    private static class MainnetCalculator extends SlotTimeCalculator {
        MainnetCalculator() {
            super(1506203091L, 20, 1.0, new InMemoryChainState());
            setFirstNonByronSlot(4492800);
        }
    }

    private static class DevnetCalculator extends SlotTimeCalculator {
        DevnetCalculator(long startTime, double slotLength) {
            super(startTime, 20, slotLength, new InMemoryChainState());
            setFirstNonByronSlot(0);
        }
    }
}
