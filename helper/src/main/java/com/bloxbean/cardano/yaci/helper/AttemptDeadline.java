package com.bloxbean.cardano.yaci.helper;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * The deadline of one attempt to get an endorser block's transactions (ADR 0016 D4a), and the cancellation of the
 * certifying block's call it belongs to. Every wait of an attempt goes through {@link #await}, so a deadline, a
 * cancellation (stop, restart, startSync) or the loss of the connection ends it at once.
 */
final class AttemptDeadline {
    private final long deadlineNanos;
    private final CompletableFuture<?> cancelled;

    AttemptDeadline(long deadlineNanos, CompletableFuture<?> cancelled) {
        this.deadlineNanos = deadlineNanos;
        this.cancelled = cancelled;
    }

    long remainingMillis() {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    boolean isExpired() {
        return System.nanoTime() - deadlineNanos >= 0;
    }

    /** @throws TimeoutException if the deadline has passed */
    void check(String what) throws TimeoutException {
        if (isExpired())
            throw new TimeoutException("the deadline passed " + what);
    }

    /**
     * Waits for {@code reply} until the deadline, or until {@code closed} (the connection's end, may be null)
     * completes, whichever comes first.
     *
     * @throws CancellationException if the call was cancelled
     * @throws TimeoutException      if the deadline passed first
     * @throws InterruptedException  if the thread was interrupted
     * @throws Exception             the reply's failure, or the reason the connection ended
     */
    <T> T await(CompletableFuture<T> reply, CompletableFuture<?> closed, String what) throws Exception {
        return await(reply, closed, what, Long.MAX_VALUE);
    }

    /** As {@link #await(CompletableFuture, CompletableFuture, String)}, waiting at most {@code capMillis}. */
    <T> T await(CompletableFuture<T> reply, CompletableFuture<?> closed, String what, long capMillis)
            throws Exception {
        long left = Math.min(deadlineNanos - System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(capMillis));
        CompletableFuture<?> any = closed != null ? CompletableFuture.anyOf(reply, closed, cancelled)
                : CompletableFuture.anyOf(reply, cancelled);
        try {
            any.get(Math.max(left, 0), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new TimeoutException(isExpired() ? "no " + what + " before the deadline"
                    : "no " + what + " within " + capMillis + " ms");
        } catch (ExecutionException e) {
            //reported below
        }
        if (cancelled.isDone())
            throw new CancellationException("cancelled");
        check("before the " + what); //a reply that came too late is not accepted
        if (reply.isDone())
            return join(reply);
        join(closed); //the connection ended first: its reason
        throw new IllegalStateException("The connection was closed before the " + what);
    }

    /**
     * As {@link #await(CompletableFuture, CompletableFuture, String)}, giving up when nothing has been received for
     * {@code stallMillis}: a reply that keeps arriving may take until the deadline, a stuck one ends early.
     *
     * @param lastReceivedNanos the {@link System#nanoTime()} of the last bytes received on the connection; only times
     *                          after this call count
     * @throws TimeoutException if nothing was received for {@code stallMillis}, or the deadline passed
     */
    <T> T awaitWhileReceiving(CompletableFuture<T> reply, CompletableFuture<?> closed, String what, long stallMillis,
                              LongSupplier lastReceivedNanos) throws Exception {
        long stallNanos = TimeUnit.MILLISECONDS.toNanos(stallMillis);
        long since = System.nanoTime();
        while (true) {
            long now = System.nanoTime(); //before the read: bytes recorded after it are never judged by an older time
            long received = lastReceivedNanos.getAsLong();
            if (received - since > 0)
                since = received;
            long quiet = now - since;
            if (quiet >= stallNanos)
                throw new TimeoutException("nothing received for the " + what + " within " + stallMillis + " ms");
            try {
                return await(reply, closed, what, TimeUnit.NANOSECONDS.toMillis(stallNanos - quiet) + 1);
            } catch (TimeoutException e) {
                if (isExpired())
                    throw e;
                //quiet for the rest of the window, unless bytes came meanwhile: look again
            }
        }
    }

    private static <T> T join(CompletableFuture<T> future) throws Exception {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof Exception cause)
                throw cause;
            throw e;
        }
    }
}
