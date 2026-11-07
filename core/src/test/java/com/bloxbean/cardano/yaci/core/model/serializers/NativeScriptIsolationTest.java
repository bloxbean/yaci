package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import co.nstant.in.cbor.model.Special;
import co.nstant.in.cbor.model.UnicodeString;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.NativeScript;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeScriptIsolationTest {
    @Test
    void jsonRepresentationFailureIsReportedPerScript() {
        JsonFactory constrained = JsonFactory.builder().streamWriteConstraints(
                StreamWriteConstraints.builder().maxNestingDepth(2).build()).build();
        NativeScript script = NativeScriptJson.parse(nestedScript(1, 5), constrained);
        assertThat(script.getType()).isEqualTo(1);
        assertThat(script.getContent()).isNull();
        assertThat(script.getParseError()).contains("JSON conversion failed");
        JsonNode json = new ObjectMapper().valueToTree(script);
        assertThat(json.get("parseError").asText()).isEqualTo(script.getParseError());
        assertThat(json.get("content").isNull()).isTrue();
    }

    @Test
    void deeplyNestedScriptsProduceCompleteJsonForAllCompositeTypes() throws Exception {
        for (int type = 1; type <= 3; type++) {
            NativeScript script = WitnessesSerializer.INSTANCE.deserializeNativeScript(nestedScript(type, 1000));
            assertThat(script.getType()).isEqualTo(type);
            assertThat(script.getParseError()).isNull();
            JsonFactory factory = JsonFactory.builder().streamReadConstraints(
                    StreamReadConstraints.builder().maxNestingDepth(10000).build()).build();
            int objects = 0;
            try (var parser = factory.createParser(script.getContent())) {
                while (parser.nextToken() != null) {
                    if (parser.currentToken() == JsonToken.START_OBJECT) {
                        objects++;
                    }
                }
            }
            assertThat(objects).isEqualTo(1001);
        }
    }

    @Test
    void ordinaryNestedScriptsKeepTheirJson() {
        for (int type = 1; type <= 3; type++) {
            NativeScript script = WitnessesSerializer.INSTANCE.deserializeNativeScript(nestedScript(type, 5));
            assertThat(script.getParseError()).isNull();
            assertThat(script.getContent()).contains("keyHash", "scripts");
            JsonNode json = new ObjectMapper().valueToTree(script);
            assertThat(json.has("parseError")).isFalse();
            assertThat(json.get("type").asInt()).isEqualTo(type);
            assertThat(json.get("content").asText()).isEqualTo(script.getContent());
        }
    }

    @Test
    void malformedScriptStillFailsInsteadOfBecomingAPlaceholder() {
        for (Array script : new Array[]{new Array(), new Array().add(new UnicodeString("bad")),
                new Array().add(new UnsignedInteger(0)),
                new Array().add(new UnsignedInteger(1)).add(new UnsignedInteger(0))}) {
            assertThatThrownBy(() -> WitnessesSerializer.INSTANCE.deserializeNativeScript(script))
                    .isInstanceOf(CborRuntimeException.class);
        }
    }

    @Test
    void preservesLegacyPrettyJsonForSmallScripts() throws Exception {
        for (int type = 1; type <= 3; type++) {
            // atLeast has an extra field per node and reaches the formatting budget sooner.
            int[] depths = type == 3 ? new int[]{0, 5, 64} : new int[]{0, 5, 127, 128};
            for (int depth : depths) {
                Array input = nestedScript(type, depth);
                String expected = JsonUtil.getPrettyJson(
                        com.bloxbean.cardano.client.transaction.spec.script.NativeScript.deserialize(input));
                assertThat(WitnessesSerializer.INSTANCE.deserializeNativeScript(input).getContent())
                        .as("type %s depth %s", type, depth).isEqualTo(expected);
            }
        }
        for (int type : new int[]{3, 4, 5}) {
            // The client library limits atLeast's m to int64, as the CDDL does; slots are uint64.
            BigInteger max = type == 3 ? BigInteger.valueOf(Long.MAX_VALUE) : new BigInteger("18446744073709551615");
            Array input = new Array().add(new UnsignedInteger(type)).add(new UnsignedInteger(max));
            if (type == 3) {
                input.add(new Array().add(pubkeyScript()));
            }
            assertThat(WitnessesSerializer.INSTANCE.deserializeNativeScript(input).getContent())
                    .isEqualTo(JsonUtil.getPrettyJson(
                            com.bloxbean.cardano.client.transaction.spec.script.NativeScript.deserialize(input)));
        }
        // atLeast is written without the client library, so an m beyond int64 is still rendered as is.
        Array oversized = new Array().add(new UnsignedInteger(3))
                .add(new UnsignedInteger(new BigInteger("18446744073709551615")))
                .add(new Array().add(pubkeyScript()));
        assertThat(WitnessesSerializer.INSTANCE.deserializeNativeScript(oversized).getContent())
                .contains("\"required\" : 18446744073709551615");
    }

    @Test
    void largeShallowScriptsUseCompactJsonWithoutChangingContent() throws Exception {
        for (int type = 1; type <= 3; type++) {
            Array children = new Array();
            for (int i = 0; i < 1000; i++) {
                children.add(pubkeyScript());
            }
            Array input = new Array().add(new UnsignedInteger(type));
            if (type == 3) {
                input.add(new UnsignedInteger(1));
            }
            input.add(children);
            NativeScript result = NativeScriptJson.parse(input);
            assertThat(result.getParseError()).isNull();
            String legacyJson = JsonUtil.getPrettyJson(
                    com.bloxbean.cardano.client.transaction.spec.script.NativeScript.deserialize(input));
            assertThat(result.getContent()).isEqualTo(
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(legacyJson).toString());
            assertThat(result.getContent()).doesNotContain(" ", "\n", "\r", "\t");
        }
    }

    @Test
    void veryDeepScriptsUseCompactJsonAndRetainEveryNode() throws Exception {
        for (int type = 1; type <= 3; type++) {
            NativeScript result = NativeScriptJson.parse(nestedScript(type, 5400));
            assertThat(result.getParseError()).isNull();
            assertThat(result.getContent()).doesNotContain(" ", "\n", "\r", "\t");
            assertThat(result.getContent().length()).isLessThan(300_000);
            JsonFactory factory = JsonFactory.builder().streamReadConstraints(
                    StreamReadConstraints.builder().maxNestingDepth(20000).build()).build();
            int objects = 0;
            try (var parser = factory.createParser(result.getContent())) {
                while (parser.nextToken() != null) {
                    if (parser.currentToken() == JsonToken.START_OBJECT) {
                        objects++;
                    }
                }
            }
            assertThat(objects).isEqualTo(5401);
        }
    }

    @Test
    void compactOutputStillRejectsMalformedChildrenAfterSizeEstimateStops() {
        Array children = new Array().add(nestedScript(1, 1000)).add(new UnicodeString("bad"));
        Array script = new Array().add(new UnsignedInteger(1)).add(children);
        assertThatThrownBy(() -> NativeScriptJson.parse(script)).isInstanceOf(CborRuntimeException.class);
    }

    /** An unknown type, 7 or more, is kept with a parse error, where it was left out (ADR 0003 D4a). */
    @Test
    void indefiniteAuxiliaryNativeScriptsSkipBreakAndKeepUnknownTypes() {
        Map metadata = new Map().put(new UnsignedInteger(1), new UnsignedInteger(42));
        Array scripts = new Array().add(pubkeyScript()).add(new Array().add(new UnsignedInteger(7)))
                .add(Special.BREAK);
        scripts.setChunked(true);
        Map alonzo = new Map().put(new UnsignedInteger(0), metadata).put(new UnsignedInteger(1), scripts);
        alonzo.setTag(259);
        for (var data : new co.nstant.in.cbor.model.DataItem[]{alonzo, new Array().add(metadata).add(scripts)}) {
            var result = AuxDataSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(data, false));
            assertThat(result.getNativeScripts()).hasSize(2).doesNotContainNull();
            assertThat(result.getNativeScripts().get(0).getContent()).contains("keyHash");
            assertThat(result.getNativeScripts().get(1).getType()).isEqualTo(7);
            assertThat(result.getNativeScripts().get(1).getContent()).isNull();
            assertThat(result.getNativeScripts().get(1).getParseError()).contains("unknown native script type 7");
            assertThat(result.getMetadataJson()).contains("42");
        }
    }

    /**
     * A script of a type Yaci has no JSON for (7 or more), or a script holding one, is kept with no JSON and a parse
     * error, its type and its hash, where it was left out of the tree or of the list.
     */
    @Test
    void anUnknownTypeGivesAParseErrorInsteadOfAnAlteredTreeOrList() {
        String unknown = "8207" + "8200581c" + "00".repeat(28);
        String scripts = "83" + "820181" + unknown
                + HexUtil.encodeHexString(CborSerializationUtil.serialize(pubkeyScript())) + unknown;
        byte[] witnessSet = HexUtil.decodeHexString("a101" + scripts);
        byte[] auxData = HexUtil.decodeHexString("d90103a101" + scripts);
        List<List<NativeScript>> read = List.of(
                WitnessesSerializer.INSTANCE.read(CborSpan.of(witnessSet), Era.Dijkstra, true).getNativeScripts(),
                AuxDataSerializer.INSTANCE.read(CborSpan.of(auxData), Era.Dijkstra, true).getNativeScripts(),
                WitnessesSerializer.INSTANCE.deserialize(witnessSet).getNativeScripts());
        for (List<NativeScript> nativeScripts : read) {
            assertThat(nativeScripts).hasSize(3);
            assertThat(nativeScripts).extracting(NativeScript::getType).containsExactly(1, 0, 7);
            for (int i : new int[]{0, 2}) {
                assertThat(nativeScripts.get(i).getContent()).isNull();
                assertThat(nativeScripts.get(i).getParseError()).contains("unknown native script type 7");
                assertThat(nativeScripts.get(i).getScriptHash()).isNotNull();
            }
            assertThat(nativeScripts.get(1).getContent()).contains("keyHash");
        }
    }

    /**
     * ADR 0003 D4b: Dijkstra's {@code script_require_guard} (type 6) is {@code {"type": "guard", "credential": ...}},
     * the credential as Yaci's {@link Credential} writes it, alone or nested, key or script. The mapping takes no era:
     * in Conway, and through the era-less entry point, the same script gets the same JSON, where M7a kept it with a
     * parse error. That is one of D4b's two listed era-less differences; type 6 is invalid before Dijkstra.
     */
    @Test
    void aGuardScriptHasGuardJsonInAnyEra() throws Exception {
        String keyHash = "11".repeat(28);
        String scriptHash = "22".repeat(28);
        String keyGuard = "8206" + "8200581c" + keyHash;
        String scriptGuard = "8206" + "8201581c" + scriptHash;
        String scripts = "82" + keyGuard + "820182" + scriptGuard
                + HexUtil.encodeHexString(CborSerializationUtil.serialize(pubkeyScript()));
        byte[] witnessSet = HexUtil.decodeHexString("a101" + scripts);
        byte[] auxData = HexUtil.decodeHexString("d90103a101" + scripts);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode keyJson = guardJson(mapper, Credential.fromKeyHash(HexUtil.decodeHexString(keyHash)));
        JsonNode scriptJson = guardJson(mapper, Credential.fromScriptHash(HexUtil.decodeHexString(scriptHash)));
        assertThat(keyJson.get("credential").get("type").asText()).isEqualTo("ADDR_KEYHASH");
        assertThat(scriptJson.get("credential").get("type").asText()).isEqualTo("SCRIPTHASH");
        List<List<NativeScript>> read = new ArrayList<>();
        for (Era era : new Era[]{Era.Dijkstra, Era.Conway}) {
            read.add(WitnessesSerializer.INSTANCE.read(CborSpan.of(witnessSet), era, true).getNativeScripts());
            read.add(AuxDataSerializer.INSTANCE.read(CborSpan.of(auxData), era, true).getNativeScripts());
        }
        read.add(WitnessesSerializer.INSTANCE.deserialize(witnessSet).getNativeScripts());
        for (List<NativeScript> nativeScripts : read) {
            assertThat(nativeScripts).extracting(NativeScript::getType).containsExactly(6, 1);
            assertThat(nativeScripts).extracting(NativeScript::getParseError).containsOnlyNulls();
            assertThat(mapper.readTree(nativeScripts.get(0).getContent())).isEqualTo(keyJson);
            JsonNode all = mapper.readTree(nativeScripts.get(1).getContent());
            assertThat(all.get("type").asText()).isEqualTo("all");
            assertThat(all.get("scripts").get(0)).isEqualTo(scriptJson);
            assertThat(all.get("scripts").get(1).get("keyHash")).isNotNull();
            assertThat(nativeScripts.get(0).getScriptHash()).isNotNull();
        }
    }

    /** A guard without its credential is malformed, as a known script is, and fails its parse. */
    @Test
    void aGuardWithoutACredentialIsMalformed() {
        Array guard = new Array().add(new UnsignedInteger(6));
        assertThatThrownBy(() -> NativeScriptJson.parse(guard)).isInstanceOf(CborRuntimeException.class);
        Array notACredential = new Array().add(new UnsignedInteger(6)).add(new UnsignedInteger(0));
        assertThatThrownBy(() -> NativeScriptJson.parse(notACredential)).isInstanceOf(CborRuntimeException.class);
    }

    private static JsonNode guardJson(ObjectMapper mapper, Credential credential) {
        return mapper.createObjectNode().put("type", "guard").set("credential", mapper.valueToTree(credential));
    }

    @Test
    void auxiliaryDataRetainsMetadataAndOtherScripts() {
        Map metadata = new Map().put(new UnsignedInteger(1), new UnsignedInteger(42));
        Array scripts = new Array().add(nestedScript(1, 1000)).add(pubkeyScript());
        Map alonzo = new Map().put(new UnsignedInteger(0), metadata).put(new UnsignedInteger(1), scripts);
        alonzo.setTag(259);

        for (var data : new co.nstant.in.cbor.model.DataItem[]{alonzo, new Array().add(metadata).add(scripts)}) {
            var result = AuxDataSerializer.INSTANCE.deserializeDI(data);
            assertThat(result.getMetadataJson()).contains("42");
            assertThat(result.getNativeScripts()).hasSize(2);
            assertThat(result.getNativeScripts().get(0).getParseError()).isNull();
            assertThat(result.getNativeScripts().get(1).getContent()).contains("keyHash");
        }
    }

    @Test
    void blockRetainsTransactionsAndWitnessesWithDeepNativeScript() throws Exception {
        byte[] original = CborLoader.getHexBytes("block/preprod292683.txt");
        Block expected = BlockSerializer.INSTANCE.deserialize(original);
        Array root = (Array) CborSerializationUtil.deserializeOne(original);
        Array block = (Array) root.getDataItems().get(1);
        Array witnesses = (Array) block.getDataItems().get(2);
        Map firstWitness = (Map) witnesses.getDataItems().get(0);
        firstWitness.put(new UnsignedInteger(1), new Array().add(nestedScript(1, 1000)).add(pubkeyScript()));

        Block result = BlockSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(root, false));

        assertThat(result.getHeader().getHeaderBody().getBlockHash()).isEqualTo(expected.getHeader().getHeaderBody().getBlockHash());
        assertThat(result.getTransactionBodies()).hasSize(expected.getTransactionBodies().size());
        for (int i = 0; i < expected.getTransactionBodies().size(); i++) {
            assertThat(result.getTransactionBodies().get(i).getTxHash())
                    .isEqualTo(expected.getTransactionBodies().get(i).getTxHash());
        }
        assertThat(result.getTransactionWitness()).hasSize(expected.getTransactionWitness().size());
        var scripts = result.getTransactionWitness().get(0).getNativeScripts();
        assertThat(scripts).hasSize(2);
        assertThat(scripts.get(0).getParseError()).isNull();
        assertThat(scripts.get(1).getContent()).contains("keyHash");
        assertThat(result.getAuxiliaryDataMap().keySet()).isEqualTo(expected.getAuxiliaryDataMap().keySet());
    }

    private Array nestedScript(int type, int depth) {
        Array script = pubkeyScript();
        for (int i = 0; i < depth; i++) {
            Array parent = new Array().add(new UnsignedInteger(type));
            if (type == 3) {
                parent.add(new UnsignedInteger(1));
            }
            script = parent.add(new Array().add(script));
        }
        return script;
    }

    private Array pubkeyScript() {
        return new Array().add(new UnsignedInteger(0)).add(new ByteString(new byte[28]));
    }
}
