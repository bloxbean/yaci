package com.bloxbean.cardano.yaci.core.protocol.leios;

import java.util.Arrays;
import java.util.Objects;

public final class LeiosPoint {
    public static final int EB_HASH_LENGTH = 32;

    private final long slot;
    private final byte[] ebHash;

    public LeiosPoint(long slot, byte[] ebHash) {
        if (slot < 0) {
            throw new IllegalArgumentException("slot must be non-negative");
        }
        Objects.requireNonNull(ebHash, "ebHash");
        if (ebHash.length != EB_HASH_LENGTH) {
            throw new IllegalArgumentException("ebHash must be " + EB_HASH_LENGTH + " bytes");
        }

        this.slot = slot;
        this.ebHash = Arrays.copyOf(ebHash, ebHash.length);
    }

    public long getSlot() {
        return slot;
    }

    public byte[] getEbHash() {
        return Arrays.copyOf(ebHash, ebHash.length);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LeiosPoint that)) {
            return false;
        }
        return slot == that.slot && Arrays.equals(ebHash, that.ebHash);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(slot);
        result = 31 * result + Arrays.hashCode(ebHash);
        return result;
    }

    @Override
    public String toString() {
        return "LeiosPoint{" +
                "slot=" + slot +
                ", ebHash=" + Arrays.toString(ebHash) +
                '}';
    }
}
