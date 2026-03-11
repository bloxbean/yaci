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
 * Bootstrap data provider using the Blockfrost API.
 * Requires a project API key.
 */
public class BlockfrostBootstrapProvider implements BootstrapDataProvider {
    private static final Logger log = LoggerFactory.getLogger(BlockfrostBootstrapProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_PAGE_SIZE = 100;

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    /**
     * @param baseUrl Blockfrost API base URL (e.g. https://cardano-preprod.blockfrost.io/api/v0)
     * @param apiKey  Blockfrost project API key
     */
    public BlockfrostBootstrapProvider(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Create a provider with auto-detected base URL from network name.
     *
     * @param network network name: mainnet, preprod, preview
     * @param apiKey  Blockfrost project API key
     */
    public static BlockfrostBootstrapProvider forNetwork(String network, String apiKey) {
        String baseUrl = switch (network.toLowerCase()) {
            case "mainnet" -> "https://cardano-mainnet.blockfrost.io/api/v0";
            case "preprod" -> "https://cardano-preprod.blockfrost.io/api/v0";
            case "preview" -> "https://cardano-preview.blockfrost.io/api/v0";
            default -> throw new IllegalArgumentException("Unknown network: " + network
                    + ". Use mainnet, preprod, or preview, or provide a custom base URL.");
        };
        return new BlockfrostBootstrapProvider(baseUrl, apiKey);
    }

    @Override
    public BootstrapBlockInfo getLatestBlock() {
        JsonNode json = get("/blocks/latest");
        return parseBlockInfo(json);
    }

    @Override
    public List<BootstrapBlockInfo> getBlocks(long fromBlockNumber, long toBlockNumber) {
        List<BootstrapBlockInfo> blocks = new ArrayList<>();
        for (long n = fromBlockNumber; n <= toBlockNumber; n++) {
            JsonNode json = get("/blocks/" + n);
            blocks.add(parseBlockInfo(json));
        }
        return blocks;
    }

    @Override
    public List<BootstrapUtxo> getUtxosByAddress(String address) {
        return fetchUtxosPaginated("/addresses/" + address + "/utxos");
    }

    @Override
    public List<BootstrapUtxo> getUtxosByStakeAddress(String stakeAddress) {
        return fetchUtxosPaginated("/accounts/" + stakeAddress + "/utxos");
    }

    @Override
    public BootstrapUtxo getUtxo(String txHash, int outputIndex) {
        JsonNode json = get("/txs/" + txHash + "/utxos");
        JsonNode outputs = json.get("outputs");
        if (outputs != null && outputs.isArray()) {
            for (JsonNode out : outputs) {
                int idx = out.get("output_index").asInt();
                if (idx == outputIndex) {
                    return parseUtxo(txHash, out);
                }
            }
        }
        return null;
    }

    private List<BootstrapUtxo> fetchUtxosPaginated(String path) {
        List<BootstrapUtxo> all = new ArrayList<>();
        int page = 1;
        while (true) {
            String url = path + "?count=" + MAX_PAGE_SIZE + "&page=" + page + "&order=asc";
            JsonNode arr = getOrNull(url);
            if (arr == null || !arr.isArray() || arr.isEmpty()) break;
            for (JsonNode item : arr) {
                all.add(parseUtxoFromAddressEndpoint(item));
            }
            if (arr.size() < MAX_PAGE_SIZE) break;
            page++;
        }
        log.info("Blockfrost: fetched {} UTXOs from {}", all.size(), path);
        return all;
    }

    private BootstrapBlockInfo parseBlockInfo(JsonNode json) {
        return new BootstrapBlockInfo(
                json.get("hash").asText(),
                json.get("height").asLong(),
                json.get("slot").asLong(),
                json.has("previous_block") && !json.get("previous_block").isNull()
                        ? json.get("previous_block").asText() : null
        );
    }

    private BootstrapUtxo parseUtxoFromAddressEndpoint(JsonNode item) {
        String txHash = item.get("tx_hash").asText();
        int outputIndex = item.get("output_index").asInt();
        String address = item.get("address").asText();
        return parseUtxoCommon(txHash, outputIndex, address, item);
    }

    private BootstrapUtxo parseUtxo(String txHash, JsonNode out) {
        int outputIndex = out.get("output_index").asInt();
        String address = out.get("address").asText();
        return parseUtxoCommon(txHash, outputIndex, address, out);
    }

    private BootstrapUtxo parseUtxoCommon(String txHash, int outputIndex, String address, JsonNode item) {
        // Parse amounts
        BigInteger lovelace = BigInteger.ZERO;
        List<BootstrapAsset> assets = new ArrayList<>();
        JsonNode amounts = item.get("amount");
        if (amounts != null && amounts.isArray()) {
            for (JsonNode amt : amounts) {
                String unit = amt.get("unit").asText();
                BigInteger quantity = new BigInteger(amt.get("quantity").asText());
                if ("lovelace".equals(unit)) {
                    lovelace = quantity;
                } else {
                    // unit = policyId (56 chars) + assetName (hex)
                    String policyId = unit.substring(0, 56);
                    String assetName = unit.length() > 56 ? unit.substring(56) : "";
                    assets.add(new BootstrapAsset(policyId, assetName, quantity));
                }
            }
        }

        String datumHash = item.has("data_hash") && !item.get("data_hash").isNull()
                ? item.get("data_hash").asText() : null;
        String inlineDatum = item.has("inline_datum") && !item.get("inline_datum").isNull()
                ? item.get("inline_datum").asText() : null;
        String scriptRef = item.has("reference_script_hash") && !item.get("reference_script_hash").isNull()
                ? item.get("reference_script_hash").asText() : null;

        return new BootstrapUtxo(txHash, outputIndex, address, lovelace, assets,
                datumHash, inlineDatum, scriptRef);
    }

    /**
     * Returns null for 404 (not found / no data) instead of throwing.
     * Used for UTXO queries where an address may simply have no UTXOs.
     */
    private JsonNode getOrNull(String path) {
        String url = baseUrl + path;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("project_id", apiKey)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException("Blockfrost API error: HTTP " + response.statusCode()
                        + " for " + url + " - " + response.body());
            }

            return MAPPER.readTree(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Blockfrost API request failed: " + url
                    + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e);
        }
    }

    private JsonNode get(String path) {
        JsonNode result = getOrNull(path);
        if (result == null) {
            throw new RuntimeException("Blockfrost API error: HTTP 404 for " + path);
        }
        return result;
    }
}
