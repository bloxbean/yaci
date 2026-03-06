package com.bloxbean.cardano.yaci.events.api;

import java.util.List;

/**
 * An event that listeners can reject (veto) by setting rejection state.
 * <p>
 * Since {@link com.bloxbean.cardano.yaci.events.impl.SimpleEventBus} catches listener
 * exceptions, this interface provides a side-channel for listeners to communicate
 * rejection back to the publisher. The publisher calls {@code publish()}, then checks
 * {@code isRejected()} on the event.
 * <p>
 * <b>Thread safety:</b> VetoableEvents are designed for synchronous listener dispatch
 * ({@code async = false}, the default). Using {@code async = true} on a
 * {@link DomainEventListener} for a VetoableEvent is a programming error — the
 * publisher cannot observe rejections from async listeners.
 * <p>
 * <b>Short-circuit convention:</b> Once rejected, subsequent listeners SHOULD check
 * {@code isRejected()} and skip expensive work, but this is a convention, not enforced.
 */
public interface VetoableEvent extends Event {

    /**
     * Reject this event with a source identifier and reason.
     * May be called multiple times by different listeners; all rejections are collected.
     *
     * @param source identifier of the rejecting component (e.g., "default-ledger-rules")
     * @param reason human-readable rejection reason
     */
    void reject(String source, String reason);

    /**
     * @return true if any listener has rejected this event
     */
    boolean isRejected();

    /**
     * @return all rejection reasons collected from listeners (unmodifiable)
     */
    List<Rejection> rejections();

    /**
     * A single rejection entry from a listener.
     */
    record Rejection(String source, String reason) {}
}
