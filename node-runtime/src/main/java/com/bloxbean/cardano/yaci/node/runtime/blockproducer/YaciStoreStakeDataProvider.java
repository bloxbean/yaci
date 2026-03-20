package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches stake data from a yaci-store instance via REST API.
 * <p>
 * Endpoints:
 * <ul>
 *   <li>{@code GET {baseUrl}/epochs/{epoch}/pools/{poolHash}/stake} → {@code {"active_stake": N}}</li>
 *   <li>{@code GET {baseUrl}/epochs/{epoch}/total-stake} → {@code {"active_stake": N}}</li>
 * </ul>
 * <p>
 * Uses separate per-epoch caches for pool and total stake (keeps last 3 epochs).
 */
@Slf4j
public class YaciStoreStakeDataProvider implements StakeDataProvider, Closeable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CACHED_EPOCHS = 3;

    private final String baseUrl;
    private final HttpClient httpClient;

    // Separate caches: epoch -> stake value
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<String, BigInteger>> poolStakeCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, BigInteger> totalStakeCache = new ConcurrentHashMap<>();

    public YaciStoreStakeDataProvider(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public BigInteger getPoolStake(String poolHash, int epoch) {
        var epochCache = poolStakeCache.computeIfAbsent(epoch, k -> new ConcurrentHashMap<>());
        BigInteger cached = epochCache.get(poolHash);
        if (cached != null) return cached;

        String url = baseUrl + "/epochs/" + epoch + "/pools/" + poolHash + "/stake";
        BigInteger result = fetchActiveStake(url);
        if (result != null) {
            epochCache.put(poolHash, result);
            evictOldEpochs(epoch);
        }
        return result;
    }

    @Override
    public BigInteger getTotalStake(int epoch) {
        BigInteger cached = totalStakeCache.get(epoch);
        if (cached != null) return cached;

        String url = baseUrl + "/epochs/" + epoch + "/total-stake";
        BigInteger result = fetchActiveStake(url);
        if (result != null) {
            totalStakeCache.put(epoch, result);
            evictOldEpochs(epoch);
        }
        return result;
    }

    @Override
    public void close() {
        httpClient.close();
    }

    private BigInteger fetchActiveStake(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = MAPPER.readTree(response.body());
                JsonNode stakeNode = json.get("active_stake");
                if (stakeNode != null && !stakeNode.isNull()) {
                    return stakeNode.bigIntegerValue();
                }
            } else if (response.statusCode() == 404) {
                log.debug("Stake data not found: {}", url);
            } else {
                log.warn("Unexpected HTTP {} from stake data endpoint: {}", response.statusCode(), url);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch stake data from {}: {}", url, e.getMessage());
        }
        return null;
    }

    private void evictOldEpochs(int currentEpoch) {
        int cutoff = currentEpoch - MAX_CACHED_EPOCHS;
        poolStakeCache.keySet().removeIf(epoch -> epoch < cutoff);
        totalStakeCache.keySet().removeIf(epoch -> epoch < cutoff);
    }
}
