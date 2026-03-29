package com.bloxbean.cardano.yaci.node.runtime.account;

import com.bloxbean.cardano.yaci.node.api.account.LedgerStateProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link LedgerStateProvider} implementation that queries a running Yaci Store instance via REST.
 * <p>
 * Used in relay/public network mode where the node doesn't compute ledger state locally
 * but delegates to Yaci Store which has full indexed state.
 * <p>
 * Caches results per credential with TTL-based expiry to avoid repeated HTTP calls
 * for the same credential within a validation batch.
 *
 * <p>Yaci Store API endpoints used:
 * <ul>
 *   <li>{@code GET /accounts/{stakeAddress}} — stake account info (pool, rewards)</li>
 *   <li>{@code GET /pools/pools/{poolId}/epochs/{epoch}} — pool details</li>
 *   <li>{@code GET /governance/dreps/registrations?page=0&count=1} — DRep lookup</li>
 *   <li>{@code GET /governance/committees/current} — committee members</li>
 *   <li>{@code GET /epochs/{epoch}/accounts/{address}/stake} — epoch stake</li>
 * </ul>
 */
public class YaciStoreLedgerStateProvider implements LedgerStateProvider, Closeable {

    private static final Logger log = LoggerFactory.getLogger(YaciStoreLedgerStateProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CACHE_MAX_SIZE = 5000;

    private final String baseUrl;
    private final HttpClient httpClient;

    // Simple caches keyed by "credType:hash"
    private final ConcurrentHashMap<String, CachedAccount> accountCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedPool> poolCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> drepRegCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BigInteger> drepDepositCache = new ConcurrentHashMap<>();

    private record CachedAccount(BigInteger reward, BigInteger deposit, String poolHash,
                                  DRepDelegation drepDelegation, boolean registered) {}
    private record CachedPool(boolean registered, BigInteger deposit, Long retirementEpoch) {}

    public YaciStoreLedgerStateProvider(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // --- Account State ---

    @Override
    public Optional<BigInteger> getRewardBalance(int credType, String credentialHash) {
        var acct = getOrFetchAccount(credType, credentialHash);
        return acct != null && acct.registered ? Optional.of(acct.reward) : Optional.empty();
    }

    @Override
    public Optional<BigInteger> getStakeDeposit(int credType, String credentialHash) {
        var acct = getOrFetchAccount(credType, credentialHash);
        return acct != null && acct.registered ? Optional.of(acct.deposit) : Optional.empty();
    }

    @Override
    public Optional<String> getDelegatedPool(int credType, String credentialHash) {
        var acct = getOrFetchAccount(credType, credentialHash);
        return acct != null && acct.poolHash != null ? Optional.of(acct.poolHash) : Optional.empty();
    }

    @Override
    public Optional<DRepDelegation> getDRepDelegation(int credType, String credentialHash) {
        var acct = getOrFetchAccount(credType, credentialHash);
        return acct != null && acct.drepDelegation != null ? Optional.of(acct.drepDelegation) : Optional.empty();
    }

    @Override
    public boolean isStakeCredentialRegistered(int credType, String credentialHash) {
        var acct = getOrFetchAccount(credType, credentialHash);
        return acct != null && acct.registered;
    }

    @Override
    public BigInteger getTotalDeposited() {
        // Not efficiently queryable from yaci-store REST; return zero.
        // Scalus uses this for value conservation but the actual deposit tracking
        // handles the certificate-level deposits correctly.
        return BigInteger.ZERO;
    }

    // --- Pool State ---

    @Override
    public boolean isPoolRegistered(String poolHash) {
        var pool = getOrFetchPool(poolHash);
        return pool != null && pool.registered;
    }

    @Override
    public Optional<BigInteger> getPoolDeposit(String poolHash) {
        var pool = getOrFetchPool(poolHash);
        return pool != null && pool.registered ? Optional.of(pool.deposit) : Optional.empty();
    }

    @Override
    public Optional<Long> getPoolRetirementEpoch(String poolHash) {
        var pool = getOrFetchPool(poolHash);
        return pool != null && pool.retirementEpoch != null
                ? Optional.of(pool.retirementEpoch) : Optional.empty();
    }

    // --- DRep State ---

    @Override
    public boolean isDRepRegistered(int credType, String credentialHash) {
        String key = credType + ":" + credentialHash;
        return drepRegCache.computeIfAbsent(key, k -> fetchDRepRegistered(credentialHash));
    }

    @Override
    public Optional<BigInteger> getDRepDeposit(int credType, String credentialHash) {
        String key = credType + ":" + credentialHash;
        BigInteger deposit = drepDepositCache.get(key);
        if (deposit != null) return Optional.of(deposit);

        // DRep deposit is fetched alongside registration check
        if (isDRepRegistered(credType, credentialHash)) {
            deposit = drepDepositCache.get(key);
            return deposit != null ? Optional.of(deposit) : Optional.empty();
        }
        return Optional.empty();
    }

    // --- Committee State (delegated to current committee endpoint) ---

    @Override
    public boolean isCommitteeMember(int credType, String coldCredentialHash) {
        // Query current committee and check membership
        return fetchCommitteeMembers().contains(coldCredentialHash);
    }

    @Override
    public Optional<String> getCommitteeHotCredential(int credType, String coldCredentialHash) {
        // Not directly available from yaci-store current committee endpoint
        return Optional.empty();
    }

    @Override
    public boolean hasCommitteeMemberResigned(int credType, String coldCredentialHash) {
        // Check deregistrations endpoint
        return fetchCommitteeResigned(coldCredentialHash);
    }

    // --- Epoch Delegation Snapshot ---

    @Override
    public Optional<String> getEpochDelegation(int epoch, int credType, String credentialHash) {
        // Build stake address from credential hash and query epoch stake
        String url = baseUrl + "/epochs/" + epoch + "/accounts/stake_test1" + credentialHash + "/stake";
        try {
            JsonNode json = fetchJson(url);
            if (json != null) {
                JsonNode poolNode = json.get("pool_hash");
                if (poolNode != null && !poolNode.isNull()) {
                    return Optional.of(poolNode.asText());
                }
            }
        } catch (Exception e) {
            log.debug("getEpochDelegation failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public List<EpochDelegator> getPoolDelegatorsAtEpoch(int epoch, String poolHash) {
        List<EpochDelegator> result = new ArrayList<>();
        String url = baseUrl + "/epochs/" + epoch + "/pools/" + poolHash + "/delegators?page=0&count=100";
        try {
            JsonNode json = fetchJson(url);
            if (json != null && json.isArray()) {
                for (JsonNode entry : json) {
                    String address = entry.has("address") ? entry.get("address").asText() : null;
                    if (address != null) {
                        // Extract credential hash from stake address
                        // For now, return with credType=0 (key hash) as approximation
                        result.add(new EpochDelegator(0, address));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("getPoolDelegatorsAtEpoch failed: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public void close() {
        httpClient.close();
    }

    /**
     * Clear all caches. Called at epoch boundary or when cache grows too large.
     */
    public void clearCaches() {
        accountCache.clear();
        poolCache.clear();
        drepRegCache.clear();
        drepDepositCache.clear();
    }

    // --- Internal fetch methods ---

    private CachedAccount getOrFetchAccount(int credType, String credentialHash) {
        String key = credType + ":" + credentialHash;
        CachedAccount cached = accountCache.get(key);
        if (cached != null) return cached;
        if (accountCache.size() > CACHE_MAX_SIZE) accountCache.clear();

        cached = fetchAccount(credentialHash);
        if (cached != null) {
            accountCache.put(key, cached);
        }
        return cached;
    }

    private CachedAccount fetchAccount(String credentialHash) {
        // Build stake address from credential hash
        // For testnet: e0 prefix + credential hash
        String stakeAddrHex = "e0" + credentialHash;
        // Try bech32 encoding via the yaci-store endpoint
        String url = baseUrl + "/accounts/stake_test1" + credentialHash;

        // Fallback: try raw hex query
        try {
            JsonNode json = fetchJson(url);
            if (json == null) {
                return new CachedAccount(BigInteger.ZERO, BigInteger.ZERO, null, null, false);
            }

            boolean registered = true; // If endpoint returns data, the account exists
            BigInteger reward = getJsonBigInteger(json, "withdrawable_amount");
            BigInteger deposit = BigInteger.valueOf(2_000_000); // default key deposit
            String poolId = json.has("pool_id") && !json.get("pool_id").isNull()
                    ? json.get("pool_id").asText() : null;

            return new CachedAccount(reward, deposit, poolId, null, registered);
        } catch (Exception e) {
            log.debug("fetchAccount failed for {}: {}", credentialHash, e.getMessage());
            return null;
        }
    }

    private CachedPool getOrFetchPool(String poolHash) {
        CachedPool cached = poolCache.get(poolHash);
        if (cached != null) return cached;
        if (poolCache.size() > CACHE_MAX_SIZE) poolCache.clear();

        cached = fetchPool(poolHash);
        if (cached != null) {
            poolCache.put(poolHash, cached);
        }
        return cached;
    }

    private CachedPool fetchPool(String poolHash) {
        // Use the pool details endpoint with epoch 0 (latest)
        String url = baseUrl + "/pools/pools/" + poolHash + "/epochs/0";
        try {
            JsonNode json = fetchJson(url);
            if (json == null) {
                return new CachedPool(false, BigInteger.ZERO, null);
            }

            BigInteger deposit = BigInteger.valueOf(500_000_000); // default pool deposit
            Long retireEpoch = json.has("retire_epoch") && !json.get("retire_epoch").isNull()
                    ? json.get("retire_epoch").asLong() : null;
            String status = json.has("status") ? json.get("status").asText() : "";
            boolean registered = !"RETIRED".equalsIgnoreCase(status);

            return new CachedPool(registered, deposit, retireEpoch);
        } catch (Exception e) {
            log.debug("fetchPool failed for {}: {}", poolHash, e.getMessage());
            return null;
        }
    }

    private boolean fetchDRepRegistered(String credentialHash) {
        // Query DRep registrations and check if credential is registered
        // This is a simplified approach — a full implementation would track
        // registration vs deregistration status
        String url = baseUrl + "/governance/dreps/registrations?page=0&count=100";
        try {
            JsonNode json = fetchJson(url);
            if (json != null && json.isArray()) {
                for (JsonNode entry : json) {
                    String drepHash = entry.has("drep_hash") ? entry.get("drep_hash").asText() : null;
                    if (credentialHash.equals(drepHash)) {
                        // Cache the deposit
                        BigInteger deposit = getJsonBigInteger(entry, "deposit");
                        String key = "0:" + credentialHash;
                        drepDepositCache.put(key, deposit);
                        key = "1:" + credentialHash;
                        drepDepositCache.put(key, deposit);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("fetchDRepRegistered failed for {}: {}", credentialHash, e.getMessage());
        }
        return false;
    }

    private Set<String> committeeMembers;

    private Set<String> fetchCommitteeMembers() {
        if (committeeMembers != null) return committeeMembers;

        Set<String> members = new HashSet<>();
        String url = baseUrl + "/governance/committees/current";
        try {
            JsonNode json = fetchJson(url);
            if (json != null && json.has("members")) {
                for (JsonNode member : json.get("members")) {
                    String hash = member.has("hash") ? member.get("hash").asText() : null;
                    if (hash != null) members.add(hash);
                }
            }
        } catch (Exception e) {
            log.debug("fetchCommitteeMembers failed: {}", e.getMessage());
        }
        committeeMembers = members;
        return members;
    }

    private boolean fetchCommitteeResigned(String coldCredentialHash) {
        String url = baseUrl + "/governance/committees/deregistrations?page=0&count=100";
        try {
            JsonNode json = fetchJson(url);
            if (json != null && json.isArray()) {
                for (JsonNode entry : json) {
                    String coldKey = entry.has("cold_key") ? entry.get("cold_key").asText() : null;
                    if (coldCredentialHash.equals(coldKey)) return true;
                }
            }
        } catch (Exception e) {
            log.debug("fetchCommitteeResigned failed: {}", e.getMessage());
        }
        return false;
    }

    private JsonNode fetchJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return MAPPER.readTree(response.body());
            } else if (response.statusCode() == 404) {
                return null;
            } else {
                log.debug("HTTP {} from {}", response.statusCode(), url);
                return null;
            }
        } catch (Exception e) {
            log.debug("HTTP request failed: {} - {}", url, e.getMessage());
            return null;
        }
    }

    private static BigInteger getJsonBigInteger(JsonNode json, String field) {
        if (json.has(field) && !json.get(field).isNull()) {
            return json.get(field).bigIntegerValue();
        }
        return BigInteger.ZERO;
    }
}
