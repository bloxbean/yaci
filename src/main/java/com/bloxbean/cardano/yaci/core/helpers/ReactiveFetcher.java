package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.helpers.api.Fetcher;
import reactor.core.publisher.MonoSink;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class ReactiveFetcher<T> implements Fetcher<T> {
    private Map<Object, MonoSink> monoSinkMap = new ConcurrentHashMap<>();

    protected void storeMonoSinkReference(Object key, MonoSink monoSink) {
        monoSinkMap.put(key, monoSink);
        monoSink.onDispose(() -> monoSinkMap.remove(key));
    }

    protected void applyMonoSuccess(Object key, Object result) {
        MonoSink monoSink = monoSinkMap.get(key);
        if (monoSink != null)
            monoSink.success(result);
    }
}
