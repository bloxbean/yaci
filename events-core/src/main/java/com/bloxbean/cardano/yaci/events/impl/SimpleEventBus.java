package com.bloxbean.cardano.yaci.events.impl;

import com.bloxbean.cardano.yaci.events.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple, dependency-free event bus implementation with optional async offload per subscription.
 * 
 * This is the default EventBus implementation for Yaci node runtime. It provides:
 * - Synchronous event delivery by default (runs on publisher thread)
 * - Optional async delivery with per-subscription executor
 * - Bounded queues with configurable overflow strategies
 * - Thread-safe concurrent access
 * - Graceful shutdown with timeout
 * 
 * Design choices:
 * - Uses CopyOnWriteArrayList for subscriber lists (optimized for read-heavy access)
 * - Per-event-type subscription management for efficient routing
 * - Minimal allocations in the hot path
 * - No external dependencies beyond SLF4J
 * 
 * Performance characteristics:
 * - Low latency for synchronous delivery
 * - Predictable memory usage with bounded queues
 * - Scales with number of event types and subscribers
 * 
 * Limitations:
 * - No cross-type event ordering guarantees
 * - No persistence or durability
 * - Single JVM only (no distributed events)
 */
public final class SimpleEventBus implements EventBus {
    private static final Logger log = LoggerFactory.getLogger(SimpleEventBus.class);

    // Map of event type to list of subscriptions (kept sorted by priority, then registration sequence)
    // Using ConcurrentHashMap for thread-safe type lookup
    // Using CopyOnWriteArrayList for snapshot-style iteration during publish
    private final ConcurrentMap<Class<?>, CopyOnWriteArrayList<Sub<?>>> subs = new ConcurrentHashMap<>();
    
    // Global shutdown flag to prevent new operations after close
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private static final class SimpleCtx<E extends Event> implements EventContext<E> {
        private final E event;
        private final EventMetadata metadata;
        SimpleCtx(E event, EventMetadata metadata) { this.event = event; this.metadata = metadata; }
        @Override public E event() { return event; }
        @Override public EventMetadata metadata() { return metadata; }
    }

    private static final class Sub<E extends Event> {
        final EventListener<E> listener;
        final SubscriptionOptions options;
        final Executor executor; // null = sync
        final BlockingQueue<EventContext<E>> queue; // if async
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicInteger delivered = new AtomicInteger();
        final long registrationSeq;
        final int priority;

        Sub(EventListener<E> l, SubscriptionOptions o, long registrationSeq) {
            this.listener = Objects.requireNonNull(l);
            this.options = Objects.requireNonNull(o);
            this.executor = o.executor();
            this.queue = (executor != null) ? new ArrayBlockingQueue<>(Math.max(1, o.bufferSize())) : null;
            this.registrationSeq = registrationSeq;
            this.priority = o.priority();
        }
    }

    // Monotonic sequence to preserve stable order for equal priorities
    private final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();

    @Override
    public <E extends Event> SubscriptionHandle subscribe(Class<E> type, EventListener<E> listener, SubscriptionOptions opts) {
        if (closed.get()) throw new IllegalStateException("EventBus is closed");
        var list = subs.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        var effective = opts != null ? opts : SubscriptionOptions.builder().build();
        var sub = new Sub<>(listener, effective, seq.incrementAndGet());
        // Insert in sorted position: priority asc, then registrationSeq asc (stable)
        int idx = 0;
        for (; idx < list.size(); idx++) {
            Sub<?> ex = list.get(idx);
            if (ex.priority > sub.priority) break;
            if (ex.priority == sub.priority && ex.registrationSeq > sub.registrationSeq) break;
        }
        list.add(idx, sub);
        if (sub.executor != null) startAsyncLoop(type, sub);
        return new SubscriptionHandle() {
            @Override public void close() { sub.active.set(false); list.remove(sub); }
            @Override public boolean isActive() { return sub.active.get(); }
        };
    }

    @Override
    public <E extends Event> void publish(E event, EventMetadata metadata, PublishOptions options) {
        if (event == null) return;
        var list = subs.get(event.getClass());
        if (list == null || list.isEmpty()) return;
        for (Sub<?> raw : list) dispatch(event, metadata, raw);
    }

    private <E extends Event> void dispatch(E event, EventMetadata metadata, Sub<?> raw) {
        @SuppressWarnings("unchecked") Sub<E> s = (Sub<E>) raw;
        if (!s.active.get()) return;
        EventFilter<E> filter = s.options.filter();
        if (filter != null && !filter.test(event, metadata)) return;
        EventContext<E> ctx = new SimpleCtx<>(event, metadata);
        if (s.executor == null) callListener(s, ctx);
        else offerAsync(s, ctx);
    }

    private <E extends Event> void callListener(Sub<E> s, EventContext<E> ctx) {
        try {
            s.listener.onEvent(ctx);
            s.delivered.incrementAndGet();
        } catch (Throwable t) {
            log.error("Listener error for {}: {}", ctx.event().getClass().getSimpleName(), t.toString(), t);
        }
    }

    private final ConcurrentMap<Sub<?>, Future<?>> workers = new ConcurrentHashMap<>();

    private <E extends Event> void startAsyncLoop(Class<E> type, Sub<E> s) {
        Future<?> fut = ((ExecutorService) s.executor).submit(() -> {
            while (s.active.get()) {
                try {
                    EventContext<E> ctx = s.queue.poll(100, TimeUnit.MILLISECONDS);
                    if (ctx == null) continue;
                    callListener(s, ctx);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    log.error("Async loop error for {}: {}", type.getSimpleName(), t.toString(), t);
                }
            }
        });
        workers.put(s, fut);
    }

    private <E extends Event> void offerAsync(Sub<E> s, EventContext<E> ctx) {
        boolean offered;
        try {
            switch (s.options.overflow()) {
                case DROP_LATEST -> offered = s.queue.offer(ctx);
                case DROP_OLDEST -> {
                    s.queue.poll();
                    offered = s.queue.offer(ctx);
                }
                case ERROR -> offered = s.queue.offer(ctx);
                case BLOCK -> offered = s.queue.offer(ctx, 1, TimeUnit.MINUTES);
                default -> offered = s.queue.offer(ctx);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            offered = false;
        }
        if (!offered) {
            if (s.options.overflow() == SubscriptionOptions.Overflow.ERROR) {
                throw new RejectedExecutionException("Event queue full for subscriber");
            } else {
                log.warn("Event dropped due to overflow policy: {}", s.options.overflow());
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        List<Future<?>> fs = new ArrayList<>(workers.values());
        for (var s : subs.values()) s.forEach(sub -> sub.active.set(false));
        for (Future<?> f : fs) {
            try { f.get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        subs.clear();
        workers.clear();
    }
}
