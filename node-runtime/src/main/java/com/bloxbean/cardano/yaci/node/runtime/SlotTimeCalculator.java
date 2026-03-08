package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.node.runtime.chain.DirectRocksDBChainState;
import lombok.extern.slf4j.Slf4j;

/**
 * Era-aware slot-to-unix-time converter.
 * <p>
 * Uses the same formula as yaci-store's EraService:
 * <ul>
 *   <li>Byron: {@code networkStartTime + slot * byronSlotDuration}</li>
 *   <li>Shelley+: {@code shelleyEraStartTime + (slot - firstShelleySlot) * shelleySlotLength}</li>
 * </ul>
 * where {@code shelleyEraStartTime = networkStartTime + firstShelleySlot * byronSlotDuration}.
 * <p>
 * When {@code firstNonByronSlot == 0} (devnet/preview), the formula degenerates to:
 * {@code networkStartTime + slot * shelleySlotLength}
 */
@Slf4j
public class SlotTimeCalculator {

    private final long networkStartTimeSec;
    private final long byronSlotDurationSec;
    private final double shelleySlotLengthSec;
    private final ChainState chainState;
    private long firstNonByronSlot = -1;

    public SlotTimeCalculator(long networkStartTimeSec, long byronSlotDurationSec,
                              double shelleySlotLengthSec, ChainState chainState) {
        this.networkStartTimeSec = networkStartTimeSec;
        this.byronSlotDurationSec = byronSlotDurationSec;
        this.shelleySlotLengthSec = shelleySlotLengthSec;
        this.chainState = chainState;
    }

    /**
     * Convert a slot number to a Unix timestamp (seconds since epoch).
     *
     * @param slot the slot number
     * @return Unix timestamp in seconds
     */
    public long slotToUnixTime(long slot) {
        long firstShelley = resolveFirstNonByronSlot();
        if (firstShelley < 0 || slot < firstShelley) {
            // Byron era or first non-Byron slot not yet known
            return networkStartTimeSec + slot * byronSlotDurationSec;
        } else {
            // Shelley+ era
            long shelleyStartTime = networkStartTimeSec + firstShelley * byronSlotDurationSec;
            return shelleyStartTime + Math.round((slot - firstShelley) * shelleySlotLengthSec);
        }
    }

    /**
     * Set the first non-Byron slot directly (for testing or devnet initialization).
     */
    void setFirstNonByronSlot(long slot) {
        this.firstNonByronSlot = slot;
    }

    /**
     * Invalidate the cached first-non-Byron slot so it will be re-read from storage
     * on the next call. Should be called after snapshot restore.
     */
    public void invalidateCache() {
        this.firstNonByronSlot = -1;
    }

    private long resolveFirstNonByronSlot() {
        if (firstNonByronSlot >= 0) return firstNonByronSlot;
        if (chainState instanceof DirectRocksDBChainState rocksState) {
            var opt = rocksState.getFirstNonByronEraStartSlot();
            if (opt.isPresent()) {
                firstNonByronSlot = opt.getAsLong();
                return firstNonByronSlot;
            }
        }
        return -1;
    }
}
