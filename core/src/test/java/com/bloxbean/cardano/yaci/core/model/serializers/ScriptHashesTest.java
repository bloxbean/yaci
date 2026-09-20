package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.MsgBlock;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.model.serializers.util.WitnessUtil;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.UtxoByAddressQuery;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ScriptHashesTest {
    private static final String NON_MINIMAL_HASH = "c4a2d94d764194b8fa76e70fb0dd94cd51d034b0a08380e001faf362";
    private static final String DEEP_HASH = "ff3efca65569f6b0b868a3d34abdb1ad8eccf745e0da71fa94fb4f18";
    private static final String[] PLUTUS_HASHES = {
            "bc8f82996834417a91ad5f5bbf06a35e1fdbfe42f21ff91485f443f1",
            "23fce83ba01633b42251eee8a63d82dca216b8b5bfd65a2f13ab3c27",
            "8b8c11dcad0af38c40d742ed155b4c938acc5507a0ecbcfcea36496a"
    };

    @Test
    void originalEncodingDeterminesNativeHash() {
        assertThat(witness("a1018182041801").getNativeScripts().get(0).getHash()).isEqualTo(NON_MINIMAL_HASH);
        assertThat(witness("a10181820401").getNativeScripts().get(0).getHash())
                .isEqualTo("a9640757d14b3a27786d9a90e30b3a001151cb98ab01c02d1f35a000");
        assertThat(witness("bf01d901029f9f041801ffffff").getNativeScripts().get(0).getHash())
                .isEqualTo("9c13e54ffebca02defa7ec8f50a90f76107c89ed83cadc4394104944");
        // An omitted unknown script must not shift subsequent hash assignments.
        assertThat(witness("a1018281186382041801").getNativeScripts())
                .extracting(NativeScript::getHash).containsExactly(NON_MINIMAL_HASH);
        // Match the decoder when a witness field is repeated.
        assertThat(witness("a20181820401018182041801").getNativeScripts())
                .extracting(NativeScript::getHash).containsExactly(NON_MINIMAL_HASH);
    }

    @Test
    void auxiliaryNativeScriptsUseOriginalBytesInBothFormats() {
        for (String hex : List.of("82a08182041801", "d90103a1018182041801")) {
            assertThat(AuxDataSerializer.INSTANCE.deserialize(bytes(hex)).getNativeScripts())
                    .extracting(NativeScript::getHash).containsExactly(NON_MINIMAL_HASH);
        }
    }

    @Test
    void plutusVersionsHashPayloadAndPreserveContentFormats() {
        Witnesses witness = witness("a303814301020306815f4101420203ff078143010203");
        AuxData aux = AuxDataSerializer.INSTANCE.deserialize(bytes("d90103a3029f43010203ff038143010203048143010203"));
        List<PlutusScript> ws = List.of(witness.getPlutusV1Scripts().get(0),
                witness.getPlutusV2Scripts().get(0), witness.getPlutusV3Scripts().get(0));
        List<PlutusScript> as = List.of(aux.getPlutusV1Scripts().get(0),
                aux.getPlutusV2Scripts().get(0), aux.getPlutusV3Scripts().get(0));
        for (int i = 0; i < 3; i++) {
            assertThat(ws.get(i).getHash()).isEqualTo(PLUTUS_HASHES[i]);
            assertThat(as.get(i).getHash()).isEqualTo(PLUTUS_HASHES[i]);
            assertThat(as.get(i).getContent()).isEqualTo("010203");
        }
        assertThat(ws.get(0).getContent()).isEqualTo("43010203");
        assertThat(ws.get(2).getContent()).isEqualTo("43010203");
    }

    @Test
    void nonByteStringPlutusWitnessPreservesLegacyContentAndDoesNotBlockValidSiblings() {
        Witnesses parsed = witness("a3038201430102030682014301020307820143010203");
        List<List<PlutusScript>> versions = List.of(parsed.getPlutusV1Scripts(),
                parsed.getPlutusV2Scripts(), parsed.getPlutusV3Scripts());
        for (int i = 0; i < versions.size(); i++) {
            List<PlutusScript> scripts = versions.get(i);
            assertThat(scripts).hasSize(2);
            assertThat(scripts.get(0).getContent()).isEqualTo("01");
            assertThat(scripts.get(0).getHash()).isNull();
            assertThat(scripts.get(1).getContent()).isEqualTo("43010203");
            assertThat(scripts.get(1).getHash()).isEqualTo(PLUTUS_HASHES[i]);
        }
    }

    @Test
    void nonByteStringAuxiliaryPlutusRetainsExistingContentDecodingRejection() {
        // Unlike witnesses, auxiliary content already required a ByteString before hash enrichment.
        for (String key : List.of("02", "03", "04")) {
            assertThatThrownBy(() -> AuxDataSerializer.INSTANCE.deserialize(bytes("d90103a1" + key + "8101")))
                    .isInstanceOf(ClassCastException.class);
        }
    }

    @Test
    void unmappedPlutusVersionKeepsContentWithoutAHash() {
        // A distinct enum mock exercises an unmapped future version without inventing a wire version.
        PlutusScriptType future = mock(PlutusScriptType.class);
        when(future.toString()).thenReturn("UnmappedPlutusVersion");
        for (boolean wrapped : List.of(false, true)) {
            PlutusScript script = ScriptHashes.plutus(future, new ByteString(bytes("010203")), wrapped);
            assertThat(script.getType()).isSameAs(future);
            assertThat(script.getContent()).isEqualTo(wrapped ? "43010203" : "010203");
            assertThat(script.getHash()).isNull();
        }
    }

    @Test
    void plutusHashingFailureStillDeliversScriptsAndBlockToSyncListener() {
        byte[] blockBytes = CborLoader.getHexBytes("block/preprod286677.txt");
        Block expected = BlockSerializer.INSTANCE.deserialize(blockBytes);
        AtomicReference<Block> delivered = new AtomicReference<>();
        AtomicReference<BlockParseRuntimeException> failure = new AtomicReference<>();
        BlockfetchAgent agent = new BlockfetchAgent();
        agent.addListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) { delivered.set(block); }
            @Override
            public void onParsingError(BlockParseRuntimeException error) { failure.set(error); }
        });
        try (var crypto = mockStatic(Blake2bUtil.class, CALLS_REAL_METHODS)) {
            crypto.when(() -> Blake2bUtil.blake2bHash224(any(byte[].class)))
                    .thenThrow(new IllegalStateException("Injected script hash failure"));
            PlutusScript witnessScript = witness("a1038143010203").getPlutusV1Scripts().get(0);
            PlutusScript auxiliaryScript = AuxDataSerializer.INSTANCE.deserialize(bytes("d90103a1028143010203"))
                    .getPlutusV1Scripts().get(0);
            assertThat(witnessScript.getContent()).isEqualTo("43010203");
            assertThat(auxiliaryScript.getContent()).isEqualTo("010203");
            assertThat(witnessScript.getHash()).isNull();
            assertThat(auxiliaryScript.getHash()).isNull();
            agent.processResponse(new MsgBlock(blockBytes));
        }
        assertThat(failure.get()).isNull();
        assertThat(delivered.get()).isNotNull();
        assertThat(delivered.get().getTransactionBodies()).usingRecursiveComparison()
                .isEqualTo(expected.getTransactionBodies());
        List<PlutusScript> scripts = new ArrayList<>();
        delivered.get().getTransactionWitness().forEach(witness -> scripts.addAll(witness.getPlutusV1Scripts()));
        assertThat(scripts).isNotEmpty();
        assertThat(scripts).allSatisfy(script -> {
            assertThat(script.getContent()).isNotEmpty();
            assertThat(script.getHash()).isNull();
        });
    }

    @Test
    void nativeSourceFailuresDoNotEscapeOrPartiallyAssignHashes() {
        for (String raw : List.of("a0", "a10180", "a10181820401", "a10182820401820501",
                "a10183820401820401820401", "a10181")) {
            Witnesses parsed = WitnessesSerializer.INSTANCE.deserializeDI(
                    CborSerializationUtil.deserializeOne(bytes("a10182820401820401")));
            List<String> content = List.of(parsed.getNativeScripts().get(0).getContent(),
                    parsed.getNativeScripts().get(1).getContent());
            assertThat(ScriptHashes.witness(parsed, bytes(raw))).isSameAs(parsed);
            assertThat(parsed.getNativeScripts()).extracting(NativeScript::getHash).containsOnlyNulls();
            assertThat(parsed.getNativeScripts()).extracting(NativeScript::getContent)
                    .containsExactlyElementsOf(content);
            ScriptHashes.witnessNativeScripts(parsed, null);
            ScriptHashes.witnessNativeScripts(parsed, bytes("81"));
            assertThat(parsed.getNativeScripts()).extracting(NativeScript::getHash).containsOnlyNulls();
        }
        AuxData auxiliary = AuxDataSerializer.INSTANCE.deserializeDI(
                CborSerializationUtil.deserializeOne(bytes("d90103a10181820401")));
        assertThat(ScriptHashes.auxiliary(auxiliary, bytes("d90103a0"))).isSameAs(auxiliary);
        assertThat(auxiliary.getNativeScripts().get(0).getContent()).isNotNull();
        assertThat(auxiliary.getNativeScripts().get(0).getHash()).isNull();
    }

    @Test
    void nativeHashFailureIsIsolatedPerScript() {
        try (var crypto = mockStatic(Blake2bUtil.class, CALLS_REAL_METHODS)) {
            crypto.when(() -> Blake2bUtil.blake2bHash224(any(byte[].class)))
                    .thenThrow(new IllegalStateException("Injected first-script failure"))
                    .thenCallRealMethod();
            Witnesses parsed = witness("a1018282041801820401");
            assertThat(parsed.getNativeScripts().get(0).getHash()).isNull();
            assertThat(parsed.getNativeScripts().get(0).getContent()).isNotNull();
            assertThat(parsed.getNativeScripts().get(1).getHash())
                    .isEqualTo("a9640757d14b3a27786d9a90e30b3a001151cb98ab01c02d1f35a000");
        }
    }

    @Test
    void nativeHashFailuresPreserveStandaloneAndRecoveredBlockData() throws Exception {
        byte[] original = CborLoader.getHexBytes("block/preprod292683.txt");
        CborSlice root = CborSlice.of(original);
        CborSlice firstWitness = CborSlice.arrayItem(original, 1, 2, 0);
        CborSlice auxiliary = CborSlice.arrayItem(original, 1, 3);
        String deepData = "81".repeat(3000) + "00";
        try (var crypto = mockStatic(Blake2bUtil.class, CALLS_REAL_METHODS)) {
            crypto.when(() -> Blake2bUtil.blake2bHash224(any(byte[].class)))
                    .thenThrow(new IllegalStateException("Injected native hashing failure"));
            for (boolean recover : List.of(false, true)) {
                String rawWitness = recover ? "a20181820418010481" + deepData : "a1018182041801";
                String rawAux = "d90103a201818204180100" + (recover ? "a100" + deepData : "a0");
                NativeScript standaloneWitness = witness(rawWitness).getNativeScripts().get(0);
                NativeScript standaloneAux = AuxDataSerializer.INSTANCE.deserialize(bytes(rawAux))
                        .getNativeScripts().get(0);
                assertThat(standaloneWitness.getHash()).isNull();
                assertThat(standaloneAux.getHash()).isNull();
                assertThat(standaloneWitness.getContent()).isNotNull();
                assertThat(standaloneAux.getContent()).isNotNull();
                byte[] modified = root.replacing(List.of(firstWitness, auxiliary),
                        List.of(bytes(rawWitness), bytes("a100" + rawAux)));
                AtomicReference<Block> delivered = new AtomicReference<>();
                AtomicReference<BlockParseRuntimeException> failure = new AtomicReference<>();
                BlockfetchAgent agent = new BlockfetchAgent();
                agent.addListener(new BlockfetchAgentListener() {
                    @Override
                    public void blockFound(Block block) { delivered.set(block); }
                    @Override
                    public void onParsingError(BlockParseRuntimeException error) { failure.set(error); }
                });
                agent.processResponse(new MsgBlock(modified));
                assertThat(failure.get()).isNull();
                Block block = delivered.get();
                assertThat(block).isNotNull();
                assertThat(block.getTransactionWitness().get(0).getNativeScripts().get(0))
                        .usingRecursiveComparison().isEqualTo(standaloneWitness);
                assertThat(block.getAuxiliaryDataMap().get(0).getNativeScripts().get(0))
                        .usingRecursiveComparison().isEqualTo(standaloneAux);
                if (recover) assertThat(block.getAuxiliaryDataMap().get(0).getMetadataParseError()).isNotNull();
            }
        }
    }

    @Test
    void hashesSurviveJsonFailureAndDataRecovery() {
        JsonFactory constrained = JsonFactory.builder().streamWriteConstraints(
                StreamWriteConstraints.builder().maxNestingDepth(1).build()).build();
        NativeScript failed = NativeScriptJson.parse((Array) CborSerializationUtil.deserializeOne(bytes("820180")),
                constrained);
        assertThat(failed.getParseError()).isNotNull();
        Witnesses parsed = witness("a10181820180");
        parsed.getNativeScripts().set(0, failed);
        NativeScript hashed = ScriptHashes.witness(parsed, bytes("a10181820180")).getNativeScripts().get(0);
        assertThat(hashed.getContent()).isNull();
        assertThat(hashed.getHash()).isEqualTo("d441227553a0f1a965fee7d60a0f724b368dd1bddbc208730fccebcf");
        String deepDatum = "81".repeat(3000) + "00";
        assertThat(witness("a20181820418010481" + deepDatum).getNativeScripts().get(0).getHash())
                .isEqualTo(NON_MINIMAL_HASH);
        assertThat(AuxDataSerializer.INSTANCE.deserialize(bytes("d90103a201818204180100a100" + deepDatum))
                .getNativeScripts().get(0).getHash()).isEqualTo(NON_MINIMAL_HASH);
    }

    @Test
    void actualDeepPreprodTransactionHashesOnSmallStack() throws Exception {
        byte[] tx = CborLoader.getHexBytes("script/preprod-f90dce5765108da976abdbb9fc618f9a6ffd9fa4d93b2f288eed1808545424c9.cbor");
        byte[] rawWitness = CborSlice.arrayItem(tx, 1).bytes();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                NativeScript script = WitnessesSerializer.INSTANCE.deserialize(rawWitness).getNativeScripts().get(0);
                assertThat(script.getHash()).isEqualTo(DEEP_HASH);
                assertThat(script.getParseError()).isNull();
                assertThat(script.getContent()).contains("ba386209c0f81f9570b6feb45cedc2649144440157677c720bfd314a");
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "deep-script-hash", 256 * 1024);
        thread.start();
        thread.join(10000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
    }

    @Test
    void preprodBlockScriptsMatchIndependentHashesWithoutCborReturnFlags() throws Exception {
        boolean old = YaciConfig.INSTANCE.isReturnFullTxCbor();
        boolean oldBlock = YaciConfig.INSTANCE.isReturnBlockCbor();
        try {
            YaciConfig.INSTANCE.setReturnFullTxCbor(false);
            YaciConfig.INSTANCE.setReturnBlockCbor(false);
            var expected = new ObjectMapper().readTree(getClass().getResourceAsStream("/script/preprod-hashes.json"));
            for (var fixture : expected) {
                Block block = BlockSerializer.INSTANCE.deserialize(CborLoader.getHexBytes(fixture.get("file").asText()));
                for (var tx : fixture.get("transactions")) {
                    int index = tx.get("index").asInt();
                    assertThat(block.getTransactionBodies().get(index).getTxHash()).isEqualTo(tx.get("txHash").asText());
                    Witnesses witness = block.getTransactionWitness().get(index);
                    List<String> hashes = new ArrayList<>();
                    witness.getNativeScripts().forEach(s -> hashes.add(s.getHash()));
                    witness.getPlutusV1Scripts().forEach(s -> hashes.add(s.getHash()));
                    witness.getPlutusV2Scripts().forEach(s -> hashes.add(s.getHash()));
                    witness.getPlutusV3Scripts().forEach(s -> hashes.add(s.getHash()));
                    List<String> expectedHashes = new ArrayList<>();
                    tx.get("scripts").forEach(s -> expectedHashes.add(s.get("hash").asText()));
                    assertThat(hashes).containsExactlyElementsOf(expectedHashes);
                }
            }
        } finally {
            YaciConfig.INSTANCE.setReturnFullTxCbor(old);
            YaciConfig.INSTANCE.setReturnBlockCbor(oldBlock);
        }
    }

    @Test
    void blockHashesIncludeAuxiliaryScriptsAndSurviveRecovery() throws Exception {
        byte[] original = CborLoader.getHexBytes("block/preprod292683.txt");
        byte[] deepTx = CborLoader.getHexBytes(
                "script/preprod-f90dce5765108da976abdbb9fc618f9a6ffd9fa4d93b2f288eed1808545424c9.cbor");
        byte[] deepWitness = CborSlice.arrayItem(deepTx, 1).bytes();
        for (boolean recover : List.of(false, true)) {
            CborSlice root = CborSlice.of(original);
            CborSlice firstWitness = CborSlice.arrayItem(original, 1, 2, 0);
            CborSlice auxiliary = CborSlice.arrayItem(original, 1, 3);
            String metadata = recover ? "a100" + "81".repeat(3000) + "00" : "a0";
            byte[] modified = root.replacing(List.of(firstWitness, auxiliary),
                    List.of(deepWitness, bytes("a100d90103a200" + metadata + "018182041801")));
            Block block = BlockSerializer.INSTANCE.deserialize(modified);
            assertThat(block.getTransactionWitness().get(0).getNativeScripts().get(0).getHash())
                    .isEqualTo(DEEP_HASH);
            assertThat(block.getAuxiliaryDataMap().get(0).getNativeScripts().get(0).getHash())
                    .isEqualTo(NON_MINIMAL_HASH);
            if (recover) assertThat(block.getAuxiliaryDataMap().get(0).getMetadataParseError()).isNotNull();
        }
    }

    @Test
    void fetchedPreprodTransactionsMatchHashesAndMintPolicies() throws Exception {
        var expected = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/script/preprod-transactions.json"));
        for (var fixture : expected) {
            byte[] tx = CborLoader.getHexBytes(fixture.get("file").asText());
            byte[] body = CborSlice.arrayItem(tx, 0).bytes();
            TransactionBody parsedBody = TransactionBodySerializer.INSTANCE.deserializeDI(
                    CborSerializationUtil.deserializeOne(body), body);
            assertThat(parsedBody.getTxHash()).isEqualTo(fixture.get("txHash").asText());
            Witnesses witness = WitnessesSerializer.INSTANCE.deserialize(CborSlice.arrayItem(tx, 1).bytes());
            List<String> hashes = new ArrayList<>();
            witness.getNativeScripts().forEach(script -> hashes.add(script.getHash()));
            witness.getPlutusV1Scripts().forEach(script -> hashes.add(script.getHash()));
            witness.getPlutusV2Scripts().forEach(script -> hashes.add(script.getHash()));
            witness.getPlutusV3Scripts().forEach(script -> hashes.add(script.getHash()));
            List<String> expectedHashes = new ArrayList<>();
            fixture.get("scripts").forEach(script -> expectedHashes.add(script.get("hash").asText()));
            assertThat(hashes).containsExactlyElementsOf(expectedHashes);
            for (var policy : fixture.get("mintPolicies")) {
                assertThat(hashes).contains(policy.asText());
                assertThat(parsedBody.getMint()).extracting(Amount::getPolicyId).contains(policy.asText());
            }
        }
    }

    @Test
    void actualPreviewReferenceOutputsMatchKoiosAndOriginalCbor() throws Exception {
        var expected = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/script/reference-transactions.json"));
        int referenceCount = 0;
        for (var fixture : expected) {
            byte[] tx = CborLoader.getHexBytes(fixture.get("file").asText());
            byte[] body = CborSlice.arrayItem(tx, 0).bytes();
            TransactionBody parsed = TransactionBodySerializer.INSTANCE.deserializeDI(
                    CborSerializationUtil.deserializeOne(body), body);
            assertThat(parsed.getTxHash()).isEqualTo(fixture.get("txHash").asText());
            assertThat(parsed.getOutputs()).hasSize(fixture.get("outputs").size());
            for (var output : fixture.get("outputs")) {
                int index = output.get("index").asInt();
                TransactionOutput actual = parsed.getOutputs().get(index);
                String context = fixture.get("txHash").asText() + "#" + index;
                if (output.get("hash").isNull()) {
                    assertThat(actual.getScriptHash()).as(context).isNull();
                    assertThat(actual.getScriptRef()).as(context).isNull();
                } else {
                    referenceCount++;
                    assertThat(actual.getScriptHash()).as(context).isEqualTo(output.get("hash").asText());
                    assertThat(actual.getScriptRef()).as(context).isEqualTo(output.get("scriptRef").asText());
                }
            }
        }
        assertThat(referenceCount).isEqualTo(7);
    }

    @Test
    void referenceScriptsHashTheirBodiesAndPreserveOriginalContent() {
        assertThat(referenceOutput("820082041801").getScriptHash()).isEqualTo(NON_MINIMAL_HASH);
        assertThat(referenceOutput("9f009f041801ffff").getScriptHash())
                .isEqualTo("9c13e54ffebca02defa7ec8f50a90f76107c89ed83cadc4394104944");
        for (int version = 1; version <= 3; version++) {
            String ref = "820" + version + "43010203";
            TransactionOutput output = referenceOutput(ref);
            assertThat(output.getScriptRef()).isEqualTo(ref);
            assertThat(output.getScriptHash()).isEqualTo(PLUTUS_HASHES[version - 1]);
            // Hashing does not validate the payload as an executable Plutus program.
            assertThat(referenceOutput("820" + version + "5f4101420203ff").getScriptHash())
                    .isEqualTo(output.getScriptHash());
        }
    }

    @Test
    void malformedOrUnsupportedReferenceScriptsDoNotPreventOutputDelivery() {
        for (String ref : List.of("", "ff", "8201", "a0", "8101", "83014301020300",
                "820443010203", "820043010203", "820180", "82014301020300",
                "82d8000143010203", "8201d80043010203", "8201430102")) {
            TransactionOutput output = referenceOutput(ref);
            assertThat(output.getScriptRef()).isEqualTo(ref);
            assertThat(output.getScriptHash()).isNull();
            assertThat(output.getAmounts()).hasSize(1);
        }
        assertThat(TransactionOutputSerializer.INSTANCE.deserialize(bytes("a10100")).getScriptHash()).isNull();
        assertThat(new TransactionOutput(null, List.of(), null, null, null).getScriptHash()).isNull();
    }

    @Test
    void deepNativeReferenceHashesOriginalBytesWithoutDecodingScriptTree() throws Exception {
        byte[] tx = CborLoader.getHexBytes(
                "script/preprod-f90dce5765108da976abdbb9fc618f9a6ffd9fa4d93b2f288eed1808545424c9.cbor");
        byte[] scripts = WitnessUtil.getWitnessFields(CborSlice.arrayItem(tx, 1).bytes()).get(BigInteger.ONE);
        byte[] nativeScript = CborSlice.arrayItem(scripts, 0).bytes();
        String ref = "8200" + HexUtil.encodeHexString(nativeScript);
        assertThat(referenceOutput(ref).getScriptHash()).isEqualTo(DEEP_HASH);
    }

    @Test
    void localStateUtxosExposeHashInsteadOfReferenceCbor() {
        DataItem response = CborSerializationUtil.deserializeOne(
                bytes("820481a182410000a2010003d81846820143010203"));
        var result = new UtxoByAddressQuery(null).deserializeResult(null, new DataItem[]{response});
        assertThat(result.getUtxoList().get(0).getReferenceScriptHash()).isEqualTo(PLUTUS_HASHES[0]);
    }

    private static TransactionOutput referenceOutput(String ref) {
        ByteString embedded = new ByteString(bytes(ref));
        embedded.setTag(24);
        Map output = new Map()
                .put(new UnsignedInteger(1), new UnsignedInteger(42))
                .put(new UnsignedInteger(3), embedded);
        return TransactionOutputSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(output, false));
    }

    @Test
    void existingConstructionAndDataItemOnlyNativeParsingDoNotInventHashes() {
        assertThat(new NativeScript(0, "{}").getHash()).isNull();
        assertThat(new NativeScript(0, null, "failed").getHash()).isNull();
        assertThat(new PlutusScript(PlutusScriptType.PlutusScriptV1, "00").getHash()).isNull();
        assertThat(WitnessesSerializer.INSTANCE.deserializeDI(CborSerializationUtil.deserializeOne(bytes("a10181820401")))
                .getNativeScripts().get(0).getHash()).isNull();
    }

    private static Witnesses witness(String hex) {
        return WitnessesSerializer.INSTANCE.deserialize(bytes(hex));
    }

    private static byte[] bytes(String hex) {
        return HexUtil.decodeHexString(hex);
    }
}
