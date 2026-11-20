package com.bloxbean.cardano.yaci.core.protocol.leios;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class LeiosTxBitmap {
    public static final int TXS_PER_WINDOW = 64;
    public static final int MAX_WINDOW_INDEX = 0xFFFF;

    private final Map<Integer, Long> windows;

    public LeiosTxBitmap(Map<Integer, Long> windows) {
        Objects.requireNonNull(windows, "windows");

        TreeMap<Integer, Long> sorted = new TreeMap<>();
        windows.forEach((window, mask) -> {
            validateWindow(window);
            if (mask != 0L) {
                sorted.put(window, mask);
            }
        });

        this.windows = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    public static LeiosTxBitmap empty() {
        return new LeiosTxBitmap(Map.of());
    }

    public static LeiosTxBitmap firstN(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
        if (count == 0) {
            return empty();
        }

        Map<Integer, Long> windows = new LinkedHashMap<>();
        int remaining = count;
        int window = 0;
        while (remaining > 0) {
            validateWindow(window);
            int inWindow = Math.min(TXS_PER_WINDOW, remaining);
            windows.put(window, firstBits(inWindow));
            remaining -= inWindow;
            window++;
        }

        return new LeiosTxBitmap(windows);
    }

    public static LeiosTxBitmap fromIndices(int... indices) {
        Objects.requireNonNull(indices, "indices");
        Map<Integer, Long> windows = new LinkedHashMap<>();
        for (int index : indices) {
            addIndex(windows, index);
        }
        return new LeiosTxBitmap(windows);
    }

    public static LeiosTxBitmap fromIndices(Collection<Integer> indices) {
        Objects.requireNonNull(indices, "indices");
        Map<Integer, Long> windows = new LinkedHashMap<>();
        for (Integer index : indices) {
            if (index == null) {
                throw new IllegalArgumentException("index cannot be null");
            }
            addIndex(windows, index);
        }
        return new LeiosTxBitmap(windows);
    }

    public Map<Integer, Long> getWindows() {
        return windows;
    }

    public boolean isEmpty() {
        return windows.isEmpty();
    }

    public Long getMask(int window) {
        validateWindow(window);
        return windows.get(window);
    }

    private static void addIndex(Map<Integer, Long> windows, int index) {
        if (index < 0) {
            throw new IllegalArgumentException("transaction index must be non-negative");
        }
        int window = index / TXS_PER_WINDOW;
        validateWindow(window);

        int offset = index % TXS_PER_WINDOW;
        long bit = 1L << (TXS_PER_WINDOW - 1 - offset);
        windows.merge(window, bit, (left, right) -> left | right);
    }

    private static long firstBits(int count) {
        if (count < 0 || count > TXS_PER_WINDOW) {
            throw new IllegalArgumentException("count must be between 0 and " + TXS_PER_WINDOW);
        }
        if (count == 0) {
            return 0L;
        }
        if (count == TXS_PER_WINDOW) {
            return -1L;
        }
        return -1L << (TXS_PER_WINDOW - count);
    }

    private static void validateWindow(int window) {
        if (window < 0 || window > MAX_WINDOW_INDEX) {
            throw new IllegalArgumentException("window must be between 0 and " + MAX_WINDOW_INDEX);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LeiosTxBitmap that)) {
            return false;
        }
        return windows.equals(that.windows);
    }

    @Override
    public int hashCode() {
        return windows.hashCode();
    }

    @Override
    public String toString() {
        return "LeiosTxBitmap{" +
                "windows=" + windows +
                '}';
    }
}
