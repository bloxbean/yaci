package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.model.EbAnnouncement;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The endorser block announcements of recently seen blocks, by block hash (ADR 0016 D4, D4b). A certifying block
 * finds its parent's announcement here through {@code prevHash}. Owned by a {@link BlockSync} or
 * {@link BlockRangeSync} instance, so it lives across restarts and halts.
 */
final class EbAnnouncementCache {
    static final int CAPACITY = 4_096;

    /** A block's slot and its announcement (null if it announced none). The EB's slot is this block's slot. */
    record Announced(long slot, EbAnnouncement announcement) {
    }

    private final Map<String, Announced> entries = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Announced> eldest) {
            return size() > CAPACITY;
        }
    };

    synchronized void put(String blockHash, long slot, EbAnnouncement announcement) {
        if (blockHash != null)
            entries.put(blockHash, new Announced(slot, announcement));
    }

    synchronized Announced get(String blockHash) {
        return blockHash != null ? entries.get(blockHash) : null;
    }

    synchronized int size() {
        return entries.size();
    }
}
