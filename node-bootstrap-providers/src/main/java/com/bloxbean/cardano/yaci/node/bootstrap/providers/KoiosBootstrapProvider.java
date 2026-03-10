package com.bloxbean.cardano.yaci.node.bootstrap.providers;

import com.bloxbean.cardano.yaci.node.api.bootstrap.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Bootstrap data provider using the Koios API.
 * No API key required (rate-limited).
 */
public class KoiosBootstrapProvider implements BootstrapDataProvider {
    private static final Logger log = LoggerFactory.getLogger(KoiosBootstrapProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient;

    /**
     * @param baseUrl Koios API base URL (e.g. https://preprod.koios.rest/api/v1)
     */
    public KoiosBootstrapProvider(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Create a provider with auto-detected base URL from network name.
     *
     * @param network network name: mainnet, preprod, preview
     */
    public static KoiosBootstrapProvider forNetwork(String network) {
        String baseUrl = switch (network.toLowerCase()) {
            case "mainnet" -> "https://api.koios.rest/api/v1";
            case "preprod" -> "https://preprod.koios.rest/api/v1";
            case "preview" -> "https://preview.koios.rest/api/v1";
            default -> throw new IllegalArgumentException("Unknown network: " + network
                    + ". Use mainnet, preprod, or preview, or provide a custom base URL.");
        };
        return new KoiosBootstrapProvider(baseUrl);
    }

    @Override
    public BootstrapBlockInfo getLatestBlock() {
        JsonNode arr = get("/tip");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new RuntimeException("Koios /tip returned empty response");
        }
        JsonNode tip = arr.get(0);
        return new BootstrapBlockInfo(
                tip.get("hash").asText(),
                tip.get("block_no").asLong(),
                tip.get("abs_slot").asLong(),
                tip.has("prev_hash") && !tip.get("prev_hash").isNull()
                        ? tip.get("prev_hash").asText() : null
        );
    }

    @Override
    public List<BootstrapBlockInfo> getBlocks(long fromBlockNumber, long toBlockNumber) {
        List<BootstrapBlockInfo> blocks = new ArrayList<>();
        // Koios block_info accepts block hashes; use blocks endpoint with height filter instead
        for (long n = fromBlockNumber; n <= toBlockNumber; n++) {
            JsonNode arr = get("/blocks?block_height=eq." + n + "&limit=1");
            if (arr.isArray() && !arr.isEmpty()) {
                JsonNode b = arr.get(0);
                blocks.add(new BootstrapBlockInfo(
                        b.get("hash").asText(),
                        b.get("block_height").asLong(),
                        b.get("abs_slot").asLong(),
                        b.has("prev_hash") && !b.get("prev_hash").isNull()
                                ? b.get("prev_hash").asText() : null
                ));
            }
        }
        return blocks;
    }

    @Override
    public List<BootstrapUtxo> getUtxosByAddress(String address) {
        String body = "{\"_addresses\":[\"" + address + "\"]}";
        return fetchUtxosPaginatedPost("/address_utxos", body);
    }

    @Override
    public List<BootstrapUtxo> getUtxosByStakeAddress(String stakeAddress) {
        String body = "{\"_stake_addresses\":[\"" + stakeAddress + "\"]}";
        return fetchUtxosPaginatedPost("/account_utxos", body);
    }

    @Override
    public BootstrapUtxo getUtxo(String txHash, int outputIndex) {
        // Koios tx_utxos POST endpoint
        String body = "{\"_tx_hashes\":[\"" + txHash + "\"]}";
        JsonNode arr = post("/tx_utxos", body);
        if (arr.isArray()) {
            for (JsonNode tx : arr) {
                JsonNode outputs = tx.get("outputs");
                if (outputs != null && outputs.isArray()) {
                    for (JsonNode out : outputs) {
                        int idx = out.get("tx_index").asInt();
                        if (idx == outputIndex) {
                            return parseKoiosUtxo(txHash, out);
                        }
                    }
                }
            }
        }
        return null;
    }

    private List<BootstrapUtxo> fetchUtxosPaginatedPost(String path, String body) {
        List<BootstrapUtxo> all = new ArrayList<>();
        int offset = 0;
        int limit = 1000;
        while (true) {
            String url = path + "?limit=" + limit + "&offset=" + offset;
            JsonNode arr = post(url, body);
            if (!arr.isArray() || arr.isEmpty()) break;
            for (JsonNode item : arr) {
                all.add(parseKoiosAddressUtxo(item));
            }
            if (arr.size() < limit) break;
            offset += limit;
        }
        log.info("Koios: fetched {} UTXOs from {}", all.size(), path);
        return all;
    }

    private BootstrapUtxo parseKoiosAddressUtxo(JsonNode item) {
        String txHash = item.get("tx_hash").asText();
        int outputIndex = item.get("tx_index").asInt();
        String address = item.get("address").asText();
        BigInteger lovelace = new BigInteger(item.get("value").asText());

        List<BootstrapAsset> assets = parseKoiosAssets(item.get("asset_list"));

        String datumHash = item.has("datum_hash") && !item.get("datum_hash").isNull()
                ? item.get("datum_hash").asText() : null;
        String inlineDatum = item.has("inline_datum") && !item.get("inline_datum").isNull()
                ? extractCborFromDatum(item.get("inline_datum")) : null;
        String scriptRef = item.has("reference_script") && !item.get("reference_script").isNull()
                ? extractCborFromScript(item.get("reference_script")) : null;

        return new BootstrapUtxo(txHash, outputIndex, address, lovelace, assets,
                datumHash, inlineDatum, scriptRef);
    }

    private BootstrapUtxo parseKoiosUtxo(String txHash, JsonNode out) {
        int outputIndex = out.get("tx_index").asInt();
        String address = out.get("payment_addr").isObject()
                ? out.get("payment_addr").get("bech32").asText()
                : out.get("payment_addr").asText();
        BigInteger lovelace = new BigInteger(out.get("value").asText());

        List<BootstrapAsset> assets = parseKoiosAssets(out.get("asset_list"));

        String datumHash = out.has("datum_hash") && !out.get("datum_hash").isNull()
                ? out.get("datum_hash").asText() : null;
        String inlineDatum = out.has("inline_datum") && !out.get("inline_datum").isNull()
                ? extractCborFromDatum(out.get("inline_datum")) : null;
        String scriptRef = out.has("reference_script") && !out.get("reference_script").isNull()
                ? extractCborFromScript(out.get("reference_script")) : null;

        return new BootstrapUtxo(txHash, outputIndex, address, lovelace, assets,
                datumHash, inlineDatum, scriptRef);
    }

    private List<BootstrapAsset> parseKoiosAssets(JsonNode assetList) {
        List<BootstrapAsset> assets = new ArrayList<>();
        if (assetList != null && assetList.isArray()) {
            for (JsonNode a : assetList) {
                String policyId = a.get("policy_id").asText();
                String assetName = a.has("asset_name") && !a.get("asset_name").isNull()
                        ? a.get("asset_name").asText() : "";
                BigInteger quantity = new BigInteger(a.get("quantity").asText());
                assets.add(new BootstrapAsset(policyId, assetName, quantity));
            }
        }
        return assets;
    }

    private String extractCborFromDatum(JsonNode datumNode) {
        if (datumNode.isObject() && datumNode.has("bytes")) {
            return datumNode.get("bytes").asText();
        }
        if (datumNode.isTextual()) {
            return datumNode.asText();
        }
        return null;
    }

    private String extractCborFromScript(JsonNode scriptNode) {
        if (scriptNode.isObject() && scriptNode.has("bytes")) {
            return scriptNode.get("bytes").asText();
        }
        if (scriptNode.isTextual()) {
            return scriptNode.asText();
        }
        return null;
    }

    private JsonNode get(String path) {
        String url = baseUrl + path;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Koios API error: HTTP " + response.statusCode()
                        + " for " + url + " - " + response.body());
            }

            return MAPPER.readTree(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Koios API request failed: " + url
                    + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e);
        }
    }

    private JsonNode post(String path, String body) {
        String url = baseUrl + path;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Koios API error: HTTP " + response.statusCode()
                        + " for POST " + url + " - " + response.body());
            }

            return MAPPER.readTree(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Koios API request failed: POST " + url
                    + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e);
        }
    }
}
