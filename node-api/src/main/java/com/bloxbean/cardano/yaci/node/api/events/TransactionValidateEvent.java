package com.bloxbean.cardano.yaci.node.api.events;

import com.bloxbean.cardano.yaci.events.api.VetoableEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Published before a transaction is admitted to the mempool.
 * Listeners validate the transaction and call {@link #reject(String, String)} if invalid.
 * After {@code eventBus.publish()}, the submitter checks {@link #isRejected()}.
 * <p>
 * Listener ordering convention:
 * <ul>
 *   <li>0-49: Pre-checks (size limits, rate limiting)</li>
 *   <li>50-99: Pre-validation hooks (whitelist/blacklist)</li>
 *   <li>100: Default ledger-rules validator (built-in)</li>
 *   <li>101-199: Post-validation hooks</li>
 *   <li>200+: Custom policy rules</li>
 * </ul>
 * <p>
 * Thread safety: This event is designed for synchronous listener dispatch only.
 * The internal {@code ArrayList} is not synchronized; this is safe because
 * {@code SimpleEventBus} dispatches sync listeners sequentially on the publisher thread.
 */
public final class TransactionValidateEvent implements VetoableEvent {

    private final byte[] txCbor;
    private final String txHash;
    private final String origin;
    private final List<Rejection> rejections = new ArrayList<>();

    /**
     * @param txCbor raw CBOR bytes of the transaction
     * @param txHash hex-encoded transaction hash (for logging convenience)
     * @param origin submission path identifier ("rest-api", "txsubmission", etc.)
     */
    public TransactionValidateEvent(byte[] txCbor, String txHash, String origin) {
        this.txCbor = txCbor;
        this.txHash = txHash;
        this.origin = origin;
    }

    public byte[] txCbor() { return txCbor; }
    public String txHash() { return txHash; }
    public String origin() { return origin; }

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
