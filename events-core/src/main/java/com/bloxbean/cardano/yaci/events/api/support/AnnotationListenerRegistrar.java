package com.bloxbean.cardano.yaci.events.api.support;

import com.bloxbean.cardano.yaci.events.api.*;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.*;

/** Registrar for @DomainEventListener methods supporting build-time and reflection fallback. */
public final class AnnotationListenerRegistrar {
    private AnnotationListenerRegistrar() {}

    public static List<SubscriptionHandle> register(EventBus bus, Object listenerHolder,
                                                    SubscriptionOptions defaults) {
        List<SubscriptionHandle> generated = tryGenerated(bus, listenerHolder, defaults);
        if (!generated.isEmpty()) return generated;
        return reflectivelyRegister(bus, listenerHolder, defaults);
    }

    private static List<SubscriptionHandle> tryGenerated(EventBus bus, Object listenerHolder, SubscriptionOptions defaults) {
        ServiceLoader<DomainEventBindings> loader = ServiceLoader.load(DomainEventBindings.class,
                listenerHolder.getClass().getClassLoader());
        List<SubscriptionHandle> handles = new ArrayList<>();
        for (DomainEventBindings b : loader) {
            if (b.targetType().isAssignableFrom(listenerHolder.getClass())) {
                handles.addAll(b.register(bus, listenerHolder, defaults));
            }
        }
        return handles;
    }

    private static List<SubscriptionHandle> reflectivelyRegister(EventBus bus, Object listenerHolder, SubscriptionOptions defaults) {
        List<Method> methods = new ArrayList<>();
        for (Method m : listenerHolder.getClass().getDeclaredMethods()) {
            if (m.getAnnotation(DomainEventListener.class) != null) methods.add(m);
        }
        methods.sort(Comparator.comparingInt(m -> m.getAnnotation(DomainEventListener.class).order()));

        List<SubscriptionHandle> handles = new ArrayList<>();
        for (Method m : methods) {
            DomainEventListener ann = m.getAnnotation(DomainEventListener.class);
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 1)
                throw new IllegalArgumentException("@DomainEventListener methods must have exactly one parameter: " + m);

            boolean ctxStyle = EventContext.class.isAssignableFrom(params[0]);
            Class<?> eventType;
            if (ctxStyle) {
                if (!(m.getGenericParameterTypes()[0] instanceof ParameterizedType pt))
                    throw new IllegalArgumentException("EventContext parameter must be parameterized: " + m);
                eventType = (Class<?>) pt.getActualTypeArguments()[0];
            } else {
                eventType = params[0];
            }

            m.setAccessible(true);
            SubscriptionOptions opts = override(defaults, ann);

            @SuppressWarnings("unchecked")
            Class<? extends Event> et = (Class<? extends Event>) eventType;
            SubscriptionHandle h = bus.subscribe(et, ctx -> {
                try {
                    if (ctxStyle) m.invoke(listenerHolder, ctx); else m.invoke(listenerHolder, ctx.event());
                } catch (ReflectiveOperationException roe) {
                    Throwable cause = (roe instanceof java.lang.reflect.InvocationTargetException ite) ? ite.getTargetException() : roe;
                    if (cause instanceof RuntimeException re) throw re;
                    if (cause instanceof Error err) throw err;
                    throw new RuntimeException(cause);
                }
            }, opts);
            handles.add(h);
        }
        return handles;
    }

    private static SubscriptionOptions override(SubscriptionOptions base, DomainEventListener ann) {
        boolean async = ann.async();
        // Only set an executor when async=true; prefer caller-provided, else default virtual threads
        var exec = async ? (base.executor() != null ? base.executor() : EventsExecutors.virtual()) : null;

        SubscriptionOptions.Builder b = SubscriptionOptions.builder()
                .bufferSize(base.bufferSize())
                .overflow(base.overflow())
                .executor(exec)
                .filter(base.filter())
                // Map annotation order to global priority for cross-class ordering
                .priority(ann.order());
        return b.build();
    }
}
