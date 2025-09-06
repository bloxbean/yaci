package com.bloxbean.cardano.yaci.events.api.support;

import com.bloxbean.cardano.yaci.events.api.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;

/** Reflection-based binder for @DomainEventListener methods on provided instances. */
public final class AnnotationListenerRegistrar {
    private AnnotationListenerRegistrar() {}

    public static List<SubscriptionHandle> register(EventBus bus, Object listenerHolder,
                                                    SubscriptionOptions defaults) {
        List<SubscriptionHandle> handles = new ArrayList<>();
        for (Method m : listenerHolder.getClass().getDeclaredMethods()) {
            DomainEventListener ann = m.getAnnotation(DomainEventListener.class);
            if (ann == null) continue;

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
                    if (ctxStyle) {
                        m.invoke(listenerHolder, ctx);
                    } else {
                        m.invoke(listenerHolder, ctx.event());
                    }
                } catch (InvocationTargetException ite) {
                    Throwable target = ite.getTargetException();
                    if (target instanceof Exception ex) throw ex;
                    throw new RuntimeException(target);
                }
            }, opts);
            handles.add(h);
        }
        return handles;
    }

    private static SubscriptionOptions override(SubscriptionOptions base, DomainEventListener ann) {
        SubscriptionOptions.Builder b = SubscriptionOptions.builder()
                .bufferSize(base.bufferSize())
                .overflow(base.overflow())
                .concurrency(ann.concurrency())
                .executor(base.executor())
                .filter(base.filter());
        // async flag is a hint; users can pass an executor in defaults
        return b.build();
    }
}

