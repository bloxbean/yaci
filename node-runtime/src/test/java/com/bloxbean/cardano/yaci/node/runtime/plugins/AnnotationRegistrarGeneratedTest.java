package com.bloxbean.cardano.yaci.node.runtime.plugins;

import com.bloxbean.cardano.yaci.events.api.*;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationRegistrarGeneratedTest {

    @Test
    void registrarUsesGeneratedBindingsWhenAvailable() {
        // given
        LoggingPlugin plugin = new LoggingPlugin();
        DummyBus bus = new DummyBus();
        SubscriptionOptions defaults = SubscriptionOptions.builder().build();

        // when
        List<SubscriptionHandle> handles = AnnotationListenerRegistrar.register(bus, plugin, defaults);

        // then
        assertThat(handles).hasSizeGreaterThanOrEqualTo(4); // 4 annotated methods in LoggingPlugin
        assertThat(bus.subscriptionCount()).isGreaterThanOrEqualTo(4);

        // Cleanup
        handles.forEach(SubscriptionHandle::close);
        assertThat(handles.stream().allMatch(h -> !((DummyHandle) h).active)).isTrue();
    }

    // Minimal in-memory EventBus for test
    static final class DummyBus implements EventBus {
        final Map<Class<?>, List<EventListener<?>>> listeners = new ConcurrentHashMap<>();
        final AtomicInteger subs = new AtomicInteger();

        @Override
        public <E extends Event> SubscriptionHandle subscribe(Class<E> type, EventListener<E> listener, SubscriptionOptions options) {
            listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
            subs.incrementAndGet();
            return new DummyHandle();
        }

        @Override
        public <E extends Event> void publish(E event, EventMetadata metadata, PublishOptions options) {
            // Not needed for this test
        }

        @Override
        public void close() { listeners.clear(); }

        int subscriptionCount() { return subs.get(); }
    }

    static final class DummyHandle implements SubscriptionHandle {
        volatile boolean active = true;
        @Override public void close() { active = false; }
        @Override public boolean isActive() { return active; }
    }
}

