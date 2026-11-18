package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.RawOutput;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.NativeScript;
import com.bloxbean.cardano.yaci.core.model.PlutusScript;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Script and inline datum hashes are the ledger's, taken from the bytes received through CCL's reader. Expected hashes
 * of the real fixtures under {@code script/} were computed independently of Yaci and CCL, and checked against Koios
 * (see {@code script/README.md}; fixtures from bloxbean/yaci#202). Synthetic cases use Bouncy Castle's Blake2b.
 */
class SourceHashesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADDRESS = "00" + "581d60" + "00".repeat(28);
    private static final String COIN = "01" + "1a000f4240";

    @Test
    void witnessScriptsInPreprodBlocksHaveTheirIndependentHashes() throws IOException {
        int checked = 0;
        for (JsonNode file : json("script/preprod-hashes.json")) {
            Block block = BlockSerializer.INSTANCE.deserialize(CborLoader.getHexBytes(file.get("file").asText()));
            for (JsonNode tx : file.get("transactions")) {
                int index = tx.get("index").asInt();
                assertThat(block.getTransactionBodies().get(index).getTxHash()).isEqualTo(tx.get("txHash").asText());
                checked += assertScripts(block.getTransactionWitness().get(index), tx.get("scripts"));
            }
        }
        assertThat(checked).isEqualTo(22);
    }

    @Test
    void witnessScriptsInPreprodTransactionsHaveTheirIndependentHashesAndPolicies() throws Exception {
        // f90dce… holds a native script nested thousands of arrays deep (yaci#185): read it on a small stack.
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                int checked = 0;
                for (JsonNode tx : json("script/preprod-transactions.json")) {
                    CborSpan txSpan = CborSpan.of(CborLoader.getHexBytes(tx.get("file").asText()));
                    TransactionBody body = TransactionBodySerializer.INSTANCE.read(txSpan.get(0).bytes(), Era.Conway);
                    assertThat(body.getTxHash()).isEqualTo(tx.get("txHash").asText());
                    Witnesses witnesses = WitnessesSerializer.INSTANCE.deserialize(txSpan.get(1).bytes());
                    checked += assertScripts(witnesses, tx.get("scripts"));
                    List<String> hashes = new ArrayList<>();
                    witnesses.getNativeScripts().forEach(script -> hashes.add(script.getScriptHash()));
                    Stream.of(witnesses.getPlutusV1Scripts(), witnesses.getPlutusV2Scripts(),
                                    witnesses.getPlutusV3Scripts())
                            .forEach(scripts -> hashes.addAll(plutusHashes(scripts)));
                    for (JsonNode policy : tx.get("mintPolicies"))
                        assertThat(hashes).contains(policy.asText());
                }
                assertThat(checked).isEqualTo(8);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "small-stack", 256 * 1024);
        thread.start();
        thread.join();
        if (failure.get() != null)
            throw new AssertionError(failure.get());
    }

    @Test
    void referenceScriptsInPreviewOutputsHaveTheirIndependentHashes() throws IOException {
        int checked = 0;
        for (JsonNode tx : json("script/reference-transactions.json")) {
            CborSpan txSpan = CborSpan.of(CborLoader.getHexBytes(tx.get("file").asText()));
            TransactionBody body = TransactionBodySerializer.INSTANCE.read(txSpan.get(0).bytes(), Era.Conway);
            assertThat(body.getTxHash()).isEqualTo(tx.get("txHash").asText());
            for (JsonNode expected : tx.get("outputs")) {
                TransactionOutput output = body.getOutputs().get(expected.get("index").asInt());
                assertThat(output.getReferenceScriptHash()).isEqualTo(text(expected, "hash"));
                assertThat(output.getScriptRef()).isEqualTo(text(expected, "scriptRef"));
                if (output.getReferenceScriptHash() != null)
                    checked++;
            }
        }
        assertThat(checked).isEqualTo(7);
    }

    @Test
    void everyInlineDatumInTheBlockFixturesHasTheHashOfItsBytes() throws IOException {
        int checked = 0;
        for (String file : blockFixtures()) {
            Block block;
            try {
                block = BlockSerializer.INSTANCE.deserialize(CborLoader.getHexBytes(file));
            } catch (RuntimeException e) {
                continue; // fixtures of other tests: Byron, Dijkstra or deliberately broken blocks
            }
            for (TransactionBody body : block.getTransactionBodies()) {
                List<TransactionOutput> outputs = new ArrayList<>(body.getOutputs());
                if (body.getCollateralReturn() != null)
                    outputs.add(body.getCollateralReturn());
                for (TransactionOutput output : outputs) {
                    if (output.getInlineDatum() == null) {
                        assertThat(output.getInlineDatumHash()).isNull();
                        continue;
                    }
                    assertThat(output.getInlineDatumHash())
                            .isEqualTo(blake2b(32, HexUtil.decodeHexString(output.getInlineDatum())));
                    checked++;
                }
            }
        }
        assertThat(checked).isPositive();
    }

    @Test
    void inlineDatumHashIsOfTheBytesReceivedNotOfAReEncoding() {
        // 1800 is 0 in a non-minimal head; a re-encoding would write 00 and hash that.
        TransactionOutput output = output("a3" + ADDRESS + COIN + "02" + "8201" + "d818" + "42" + "1800");
        assertThat(output.getInlineDatum()).isEqualTo("1800");
        assertThat(output.getInlineDatumHash()).isEqualTo(blake2b(32, h("1800")))
                .isNotEqualTo(blake2b(32, h("00")));
        assertThat(output.getDatumHash()).isNull();
    }

    @Test
    void outputWithADatumHashHasNoInlineDatumHash() {
        String hash = "ab".repeat(32);
        TransactionOutput output = output("a3" + ADDRESS + COIN + "02" + "8200" + "5820" + hash);
        assertThat(output.getDatumHash()).isEqualTo(hash);
        assertThat(output.getInlineDatumHash()).isNull();
        assertThat(output.getReferenceScriptHash()).isNull();
    }

    @Test
    void plutusHashCoversThePayloadSoChunkingDoesNotChangeIt() {
        String expected = blake2b(28, h("02010203"));
        String definite = output("a3" + ADDRESS + COIN + "03" + "d818" + "46" + "820243010203")
                .getReferenceScriptHash();
        String chunked = output("a3" + ADDRESS + COIN + "03" + "d818" + "49" + "82025f4201024103ff")
                .getReferenceScriptHash();
        assertThat(definite).isEqualTo(expected);
        assertThat(chunked).isEqualTo(expected);

        // The witness set keeps each script's encoding as content, and hashes the same payload.
        Witnesses witnesses = WitnessesSerializer.INSTANCE.deserialize(h("a2" + "06" + "8143010203" + "07" + "815f4201024103ff"));
        assertThat(witnesses.getPlutusV2Scripts()).extracting(PlutusScript::getContent).containsExactly("43010203");
        assertThat(witnesses.getPlutusV3Scripts()).extracting(PlutusScript::getContent).containsExactly("5f4201024103ff");
        assertThat(witnesses.getPlutusV2Scripts().get(0).getScriptHash()).isEqualTo(blake2b(28, h("02010203")));
        assertThat(witnesses.getPlutusV3Scripts().get(0).getScriptHash()).isEqualTo(blake2b(28, h("03010203")));
    }

    @Test
    void nativeHashIsOfTheBytesReceivedNotOfAReEncoding() {
        // [1, []] (all of nothing) with its type in a non-minimal head.
        String script = "821801" + "80";
        String expected = blake2b(28, h("00" + script));
        assertThat(expected).isNotEqualTo(blake2b(28, h("00" + "820180")));

        Witnesses witnesses = WitnessesSerializer.INSTANCE.deserialize(h("a1" + "01" + "81" + script));
        assertThat(witnesses.getNativeScripts().get(0).getScriptHash()).isEqualTo(expected);
        assertThat(output("a3" + ADDRESS + COIN + "03" + "d818" + "46" + "8200" + script).getReferenceScriptHash())
                .isEqualTo(expected);
        assertThat(AuxDataSerializer.INSTANCE.deserialize(h("d90103a2" + "00a0" + "01" + "81" + script))
                .getNativeScripts().get(0).getScriptHash()).isEqualTo(expected);
    }

    @Test
    void auxDataPlutusScriptsAreHashed() {
        // Alonzo aux data: {0: {}, 2: [h'010203'], 3: [h'010203'], 4: [h'010203']}
        var aux = AuxDataSerializer.INSTANCE.deserialize(
                h("d90103a4" + "00a0" + "02" + "8143010203" + "03" + "8143010203" + "04" + "8143010203"));
        assertThat(aux.getPlutusV1Scripts().get(0).getScriptHash()).isEqualTo(blake2b(28, h("01010203")));
        assertThat(aux.getPlutusV2Scripts().get(0).getScriptHash()).isEqualTo(blake2b(28, h("02010203")));
        assertThat(aux.getPlutusV3Scripts().get(0).getScriptHash()).isEqualTo(blake2b(28, h("03010203")));
    }

    @Test
    void referenceBytesThatAreNotAScriptKeepTheOutputWithoutAHash() {
        // [9, h'00']: no script type 9. The bytes are kept as received; only the hash is missing.
        TransactionOutput output = output("a3" + ADDRESS + COIN + "03" + "d818" + "44" + "82094100");
        assertThat(output.getScriptRef()).isEqualTo("82094100");
        assertThat(output.getReferenceScriptHash()).isNull();
        assertThat(output.getAmounts()).isNotEmpty();
    }

    @SuppressWarnings("removal")
    @Test
    void aDecodedOutputGetsTheSameHashesAsItsBytes() {
        // Both hashes cover bytes inside a tag 24, which re-encoding the output keeps.
        String bytes = "a4" + ADDRESS + COIN + "02" + "8201" + "d818" + "42" + "1800"
                + "03" + "d818" + "46" + "8200" + "821801" + "80";
        TransactionOutput fromBytes = output(bytes);
        TransactionOutput decoded = TransactionOutputSerializer.INSTANCE
                .deserializeDI(CborSerializationUtil.deserializeOne(h(bytes)));
        assertThat(decoded.getInlineDatumHash()).isEqualTo(fromBytes.getInlineDatumHash()).isNotNull();
        assertThat(decoded.getReferenceScriptHash()).isEqualTo(fromBytes.getReferenceScriptHash()).isNotNull();
    }

    @Test
    void legacyConstructorsLeaveTheHashesNull() {
        assertThat(new TransactionOutput("addr", List.of(), null, "00", "8200").getInlineDatumHash()).isNull();
        assertThat(new TransactionOutput("addr", List.of(), null, "00", "8200").getReferenceScriptHash()).isNull();
        assertThat(new PlutusScript(null, "4100").getScriptHash()).isNull();
    }

    /** Checks the witness set's scripts of each version, in order, against {version, hash} entries. */
    private static int assertScripts(Witnesses witnesses, JsonNode expected) {
        List<List<String>> actual = List.of(
                witnesses.getNativeScripts().stream().map(NativeScript::getScriptHash).toList(),
                plutusHashes(witnesses.getPlutusV1Scripts()),
                plutusHashes(witnesses.getPlutusV2Scripts()),
                plutusHashes(witnesses.getPlutusV3Scripts()));
        List<List<String>> wanted = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (JsonNode script : expected)
            wanted.get(script.get("version").asInt()).add(script.get("hash").asText());
        assertThat(actual).isEqualTo(wanted);
        return expected.size();
    }

    private static List<String> plutusHashes(List<PlutusScript> scripts) {
        return scripts == null ? List.of() : scripts.stream().map(PlutusScript::getScriptHash).toList();
    }

    private static TransactionOutput output(String hex) {
        return TransactionOutputSerializer.INSTANCE.read(RawOutput.tolerant(CborSpan.of(h(hex))));
    }

    private static List<String> blockFixtures() throws IOException {
        try (Stream<java.nio.file.Path> files = java.nio.file.Files.list(java.nio.file.Path.of(
                SourceHashesTest.class.getClassLoader().getResource("block").getPath()))) {
            return files.map(path -> "block/" + path.getFileName()).filter(name -> name.endsWith(".txt")).sorted()
                    .toList();
        }
    }

    private static JsonNode json(String resource) throws IOException {
        try (InputStream in = SourceHashesTest.class.getClassLoader().getResourceAsStream(resource)) {
            return JSON.readTree(in);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String blake2b(int size, byte[] bytes) {
        Blake2bDigest digest = new Blake2bDigest(size * 8);
        digest.update(bytes, 0, bytes.length);
        byte[] out = new byte[size];
        digest.doFinal(out, 0);
        return HexUtil.encodeHexString(out);
    }

    private static byte[] h(String hex) {
        return HexUtil.decodeHexString(hex);
    }
}
