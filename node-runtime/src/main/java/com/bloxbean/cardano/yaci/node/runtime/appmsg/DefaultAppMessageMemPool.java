package com.bloxbean.cardano.yaci.node.runtime.appmsg;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Default app-message mempool using LinkedHashMap for insertion-order iteration.
 * Dedup by messageId, TTL eviction on expiresAt.
 */
@Slf4j
public class DefaultAppMessageMemPool implements AppMessageMemPool {

    private final LinkedHashMap<String, AppMessage> messages;
    private final int maxSize;

    public DefaultAppMessageMemPool(int maxSize) {
        this.messages = new LinkedHashMap<>();
        this.maxSize = maxSize;
    }

    @Override
    public synchronized boolean addMessage(AppMessage message) {
        String idHex = HexUtil.encodeHexString(message.getMessageId());
        if (messages.containsKey(idHex)) {
            log.debug("Duplicate message ignored: {}", idHex);
            return false;
        }

        // Evict oldest if at capacity
        if (messages.size() >= maxSize) {
            var it = messages.entrySet().iterator();
            if (it.hasNext()) {
                var oldest = it.next();
                it.remove();
                log.debug("Evicted oldest message: {}", oldest.getKey());
            }
        }

        messages.put(idHex, message);
        log.debug("Message added to mempool: {} (size: {})", idHex, messages.size());
        return true;
    }

    @Override
    public synchronized AppMessage getMessage(byte[] messageId) {
        return messages.get(HexUtil.encodeHexString(messageId));
    }

    @Override
    public synchronized List<AppMessage> getMessages(int maxCount) {
        return messages.values().stream()
                .limit(maxCount)
                .collect(Collectors.toList());
    }

    @Override
    public synchronized List<AppMessage> getMessagesForTopic(String topicId, int maxCount) {
        return messages.values().stream()
                .filter(m -> topicId.equals(m.getTopicId()))
                .limit(maxCount)
                .collect(Collectors.toList());
    }

    @Override
    public synchronized boolean removeMessage(byte[] messageId) {
        return messages.remove(HexUtil.encodeHexString(messageId)) != null;
    }

    @Override
    public synchronized boolean contains(byte[] messageId) {
        return messages.containsKey(HexUtil.encodeHexString(messageId));
    }

    @Override
    public synchronized int removeExpired(long currentTimeSeconds) {
        int removed = 0;
        var it = messages.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            long expiresAt = entry.getValue().getExpiresAt();
            if (expiresAt > 0 && expiresAt <= currentTimeSeconds) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Removed {} expired messages, pool size: {}", removed, messages.size());
        }
        return removed;
    }

    @Override
    public synchronized int size() {
        return messages.size();
    }

    @Override
    public synchronized void clear() {
        messages.clear();
    }
}
