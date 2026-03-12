package com.bloxbean.cardano.yaci.node.api.events;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.events.api.VetoableEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Published before an app message is included in an app block.
 * Listeners can veto to reject the message from the block.
 * <p>
 * Listener ordering convention (same as TransactionValidateEvent):
 * <ul>
 *   <li>0-49: Pre-checks (size limits, rate limiting)</li>
 *   <li>50-99: Pre-validation hooks (whitelist/blacklist)</li>
 *   <li>100: Default app data validator</li>
 *   <li>101-199: Post-validation hooks</li>
 *   <li>200+: Custom policy rules</li>
 * </ul>
 */
public final class AppDataValidateEvent implements VetoableEvent {

    private final AppMessage message;
    private final String topicId;
    private final long currentBlockNumber;
    private final List<Rejection> rejections = new ArrayList<>();

    public AppDataValidateEvent(AppMessage message, String topicId, long currentBlockNumber) {
        this.message = message;
        this.topicId = topicId;
        this.currentBlockNumber = currentBlockNumber;
    }

    public AppMessage message() { return message; }
    public String topicId() { return topicId; }
    public long currentBlockNumber() { return currentBlockNumber; }

    @Override
    public void reject(String source, String reason) {
        rejections.add(new Rejection(source, reason));
    }

    @Override
    public boolean isRejected() {
        return !rejections.isEmpty();
    }

    @Override
    public List<Rejection> rejections() {
        return Collections.unmodifiableList(rejections);
    }
}
