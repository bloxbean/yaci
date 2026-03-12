package com.bloxbean.cardano.yaci.node.runtime.appmsg;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;

import java.util.List;

/**
 * Interface for app-layer message mempool with topic-awareness and TTL.
 */
public interface AppMessageMemPool {
    boolean addMessage(AppMessage message);
    AppMessage getMessage(byte[] messageId);
    List<AppMessage> getMessages(int maxCount);
    List<AppMessage> getMessagesForTopic(String topicId, int maxCount);
    boolean contains(byte[] messageId);
    /**
     * Get all messages for a topic (no limit).
     */
    default List<AppMessage> getMessagesForTopic(String topicId) {
        return getMessagesForTopic(topicId, Integer.MAX_VALUE);
    }

    /**
     * Remove a specific message by ID.
     * @return true if the message was removed
     */
    boolean removeMessage(byte[] messageId);

    int removeExpired(long currentTimeSeconds);
    int size();
    void clear();
}
