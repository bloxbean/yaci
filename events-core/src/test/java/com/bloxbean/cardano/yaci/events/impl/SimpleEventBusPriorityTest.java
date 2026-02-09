package com.bloxbean.cardano.yaci.events.impl;

import com.bloxbean.cardano.yaci.events.api.*;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleEventBusPriorityTest {

    static record TestEv(String name) implements Event {}

    @Test
    void respectsGlobalPriorityAcrossManualSubscribers() {
        SimpleEventBus bus = new SimpleEventBus();
        List<String> calls = new CopyOnWriteArrayList<>();

        SubscriptionOptions p10 = SubscriptionOptions.builder().priority(10).build();
        SubscriptionOptions p5 = SubscriptionOptions.builder().priority(5).build();
        SubscriptionOptions p7 = SubscriptionOptions.builder().priority(7).build();

        bus.subscribe(TestEv.class, ctx -> calls.add("p10"), p10);
        bus.subscribe(TestEv.class, ctx -> calls.add("p5"), p5);
        bus.subscribe(TestEv.class, ctx -> calls.add("p7"), p7);

        bus.publish(new TestEv("x"), EventMetadata.builder().build(), PublishOptions.builder().build());

        assertThat(calls).containsExactly("p5", "p7", "p10");
    }

    @Test
    void tieBreaksByRegistrationOrderWhenEqualPriority() {
        SimpleEventBus bus = new SimpleEventBus();
        List<String> calls = new ArrayList<>();

        SubscriptionOptions p0 = SubscriptionOptions.builder().priority(0).build();

        bus.subscribe(TestEv.class, ctx -> calls.add("A"), p0);
        bus.subscribe(TestEv.class, ctx -> calls.add("B"), p0);
        bus.subscribe(TestEv.class, ctx -> calls.add("C"), p0);

        bus.publish(new TestEv("y"), EventMetadata.builder().build(), PublishOptions.builder().build());

        assertThat(calls).containsExactly("A", "B", "C");
    }

    public static final class AListeners {
        final List<String> calls;
        AListeners(List<String> calls) { this.calls = calls; }
        @DomainEventListener(order = 10)
        public void onHigh(TestEv ev) { calls.add("A-10"); }
        @DomainEventListener(order = 5)
        public void onLow(TestEv ev) { calls.add("A-5"); }
    }

    public static final class BListeners {
        final List<String> calls;
        BListeners(List<String> calls) { this.calls = calls; }
        @DomainEventListener(order = 7)
        public void onMid(TestEv ev) { calls.add("B-7"); }
    }

    @Test
    void annotationOrderMapsToGlobalPriorityAcrossClasses() {
        SimpleEventBus bus = new SimpleEventBus();
        List<String> calls = new ArrayList<>();

        SubscriptionOptions defaults = SubscriptionOptions.builder().build();
        // Register in reverse order to ensure registration order doesn't dominate
        AnnotationListenerRegistrar.register(bus, new BListeners(calls), defaults);
        AnnotationListenerRegistrar.register(bus, new AListeners(calls), defaults);

        bus.publish(new TestEv("z"), EventMetadata.builder().build(), PublishOptions.builder().build());

        assertThat(calls).containsExactly("A-5", "B-7", "A-10");
    }
}
