package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import co.nstant.in.cbor.model.Special;
import co.nstant.in.cbor.model.UnicodeString;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.NativeScript;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
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
            Array input = new Array().add(new UnsignedInteger(type))
                    .add(new UnsignedInteger(new BigInteger("18446744073709551615")));
            if (type == 3) {
                input.add(new Array().add(pubkeyScript()));
            }
            assertThat(WitnessesSerializer.INSTANCE.deserializeNativeScript(input).getContent())
                    .isEqualTo(JsonUtil.getPrettyJson(
                            com.bloxbean.cardano.client.transaction.spec.script.NativeScript.deserialize(input)));
        }
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

    @Test
    void indefiniteAuxiliaryNativeScriptsSkipBreakAndUnknownTypes() {
        Map metadata = new Map().put(new UnsignedInteger(1), new UnsignedInteger(42));
        Array scripts = new Array().add(pubkeyScript()).add(new Array().add(new UnsignedInteger(6)))
                .add(Special.BREAK);
        scripts.setChunked(true);
        Map alonzo = new Map().put(new UnsignedInteger(0), metadata).put(new UnsignedInteger(1), scripts);
        alonzo.setTag(259);
        for (var data : new co.nstant.in.cbor.model.DataItem[]{alonzo, new Array().add(metadata).add(scripts)}) {
            var result = AuxDataSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(data, false));
            assertThat(result.getNativeScripts()).hasSize(1).doesNotContainNull();
            assertThat(result.getNativeScripts().get(0).getContent()).contains("keyHash");
            assertThat(result.getMetadataJson()).contains("42");
        }
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
