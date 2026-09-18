package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.Special;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadata;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Datum;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatumMetadataIsolationTest {
    @Test
    void datumFailureRetainsCborAndHashForListsConstructorsAndNestedMapValues() throws Exception {
        for (boolean indefinite : new boolean[]{false, true}) {
            for (int kind = 0; kind < 3; kind++) {
                DataItem data = nestedList(10000, indefinite);
                if (kind == 1) {
                    data.setTag(121); // Constructor 0, whose fields contain the deeply nested list.
                } else if (kind == 2) {
                    data = new Map().put(new UnsignedInteger(0), data);
                }
                byte[] cbor = CborSerializationUtil.serialize(data, false);
                Datum result = Datum.from(data);
                assertThat(result.getCbor()).isEqualTo(HexUtil.encodeHexString(cbor));
                assertThat(result.getHash()).isEqualTo(Datum.cborToHash(cbor));
                assertThat(result.getJson()).isNull();
                assertThat(result.getParseError()).contains("Datum JSON conversion");
            }
        }
    }

    @Test
    void ordinaryDatumJsonCborAndConstructorsAreUnchanged() throws Exception {
        for (String hex : new String[]{"00", "182a", "42abcd", "8201820203", "a101820203", "d8798100"}) {
            DataItem input = CborSerializationUtil.deserializeOne(HexUtil.decodeHexString(hex));
            Datum result = Datum.from(input);
            assertThat(result.getJson()).isEqualTo(JsonUtil.getPrettyJson(PlutusData.deserialize(input)));
            assertThat(result.getCbor()).isEqualTo(hex);
            assertThat(result.getHash()).isEqualTo(Datum.cborToHash(HexUtil.decodeHexString(hex)));
            assertThat(result.getParseError()).isNull();
            assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("parseError");
        }
        assertThat(new Datum("hash", "00", "json").getParseError()).isNull();
        assertThat(Datum.from(null)).isNull();
        assertThatThrownBy(() -> Datum.from(Special.BREAK)).isInstanceOf(CborDeserializationException.class);
    }

    @Test
    void metadataFailureKeepsCborAndOtherScriptsInEveryAuxiliaryFormat() {
        for (boolean indefinite : new boolean[]{false, true}) {
            Map metadata = new Map().put(new UnsignedInteger(7), nestedList(10000, indefinite));
            Array scripts = new Array().add(nativeScript());
            Map alonzo = new Map().put(new UnsignedInteger(0), metadata).put(new UnsignedInteger(1), scripts);
            alonzo.setTag(259);
            for (DataItem input : new DataItem[]{metadata, new Array().add(metadata).add(scripts), alonzo}) {
                AuxData result = AuxDataSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(input, false));
                assertThat(result.getMetadataCbor()).isEqualTo(
                        HexUtil.encodeHexString(CborSerializationUtil.serialize(metadata, false)));
                assertThat(result.getMetadataJson()).isNull();
                assertThat(result.getMetadataParseError()).contains("Metadata JSON conversion");
                if (input != metadata) {
                    assertThat(result.getNativeScripts()).hasSize(1);
                    assertThat(result.getNativeScripts().get(0).getParseError()).isNull();
                }
            }
        }
    }

    @Test
    void ordinaryMetadataKeepsItsExistingCanonicalCborAndJson() throws Exception {
        // Unsorted source keys: ordinary metadata must retain the previous canonical encoding.
        Map metadata = new Map().put(new UnsignedInteger(9), new UnsignedInteger(42))
                .put(new UnsignedInteger(1), new Array().add(new UnsignedInteger(5)));
        byte[] expected = CBORMetadata.deserialize(metadata).serialize();
        AuxData result = AuxDataSerializer.INSTANCE.deserializeDI(metadata);
        assertThat(result.getMetadataCbor()).isEqualTo(HexUtil.encodeHexString(expected));
        assertThat(result.getMetadataJson()).isEqualTo(MetadataToJsonNoSchemaConverter.cborBytesToJson(expected));
        assertThat(result.getMetadataParseError()).isNull();
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("metadataParseError");
        assertThat(new AuxData("cbor", "json", null, null, null, null).getMetadataParseError()).isNull();
        assertThat(new AuxData("aux", "cbor", "json", null, null, null, null).getCbor()).isEqualTo("aux");
    }

    @Test
    void jsonOnlyMetadataFailureRetainsCbor() {
        // The current display converter rejects BREAK in indefinite lists; its CBOR is still valid.
        Map metadata = new Map().put(new UnsignedInteger(1), nestedList(1, true));
        AuxData result = AuxDataSerializer.INSTANCE.deserializeDI(metadata);
        assertThat(result.getMetadataCbor()).isNotEmpty();
        assertThat(result.getMetadataJson()).isNull();
        assertThat(result.getMetadataParseError()).contains("Metadata JSON conversion failed");
    }

    @Test
    void redeemerRepresentationFailureKeepsExecutionUnitsAndCbor() {
        Array input = new Array().add(new UnsignedInteger(0)).add(new UnsignedInteger(2))
                .add(nestedList(10000, false))
                .add(new Array().add(new UnsignedInteger(10)).add(new UnsignedInteger(20)));
        Redeemer result = Redeemer.deserializePreConway(input);
        assertThat(result.getIndex()).isEqualTo(2);
        assertThat(result.getData().getParseError()).isNotNull();
        assertThat(result.getData().getCbor()).isNotEmpty();
        assertThat(result.getExUnits().getMem()).isEqualTo(10);
        assertThat(result.getExUnits().getSteps()).isEqualTo(20);
        assertThat(result.getCbor()).isEqualTo(HexUtil.encodeHexString(CborSerializationUtil.serialize(input, false)));
    }

    @Test
    void fullBlockSurvivesDeepDatumAndMetadataAndRetainsUnrelatedData() throws Exception {
        byte[] original = CborLoader.getHexBytes("block/preprod292683.txt");
        Block expected = BlockSerializer.INSTANCE.deserialize(original);
        Array root = (Array) CborSerializationUtil.deserializeOne(original);
        Array block = (Array) root.getDataItems().get(1);
        Map witness = (Map) ((Array) block.getDataItems().get(2)).getDataItems().get(0);
        witness.put(new UnsignedInteger(4), new Array().add(nestedList(10000, false)));
        Map metadata = new Map().put(new UnsignedInteger(0), nestedList(10000, false));
        ((Map) block.getDataItems().get(3)).put(new UnsignedInteger(0), metadata);

        Block result = BlockSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(root, false));
        assertThat(result.getHeader().getHeaderBody().getBlockHash())
                .isEqualTo(expected.getHeader().getHeaderBody().getBlockHash());
        assertThat(result.getTransactionBodies()).hasSize(expected.getTransactionBodies().size());
        for (int i = 0; i < expected.getTransactionBodies().size(); i++) {
            assertThat(result.getTransactionBodies().get(i).getTxHash())
                    .isEqualTo(expected.getTransactionBodies().get(i).getTxHash());
        }
        assertThat(result.getTransactionWitness()).hasSize(expected.getTransactionWitness().size());
        Datum datum = result.getTransactionWitness().get(0).getDatums().get(0);
        assertThat(datum.getJson()).isNull();
        assertThat(datum.getParseError()).isNotNull();
        assertThat(datum.getHash()).isEqualTo(Datum.cborToHash(HexUtil.decodeHexString(datum.getCbor())));
        assertThat(result.getAuxiliaryDataMap().get(0).getMetadataCbor()).isNotEmpty();
        assertThat(result.getAuxiliaryDataMap().get(0).getMetadataParseError()).isNotNull();
    }

    @Test
    void fullBlocksKeepOriginalCborForDeepMapsConstructorsAndComplexKeys() throws Exception {
        byte[] original = CborLoader.getHexBytes("block/preprod292683.txt");
        Block expected = BlockSerializer.INSTANCE.deserialize(original);
        boolean fullTx = YaciConfig.INSTANCE.isReturnFullTxCbor();
        boolean fullBlock = YaciConfig.INSTANCE.isReturnBlockCbor();
        String deepKey = "81".repeat(2000) + "00";
        String[] datumCases = {
                "a100".repeat(5000) + "00",
                "bf00".repeat(5000) + "00" + "ff".repeat(5000),
                "d87981".repeat(5000) + "00",
                "d8799f".repeat(5000) + "00" + "ff".repeat(5000),
                "81a100d87981".repeat(2000) + "00",
                "a1" + deepKey + "00"
        };
        String metadataHex = "a100" + "a10081".repeat(2000) + "00";
        try {
            for (boolean full : new boolean[]{false, true}) {
                YaciConfig.INSTANCE.setReturnFullTxCbor(full);
                YaciConfig.INSTANCE.setReturnBlockCbor(full);
                for (String datumHex : datumCases) {
                    byte[] bytes = blockWithData(original, datumHex, metadataHex);
                    Block result = BlockSerializer.INSTANCE.deserialize(bytes);
                    assertThat(result.getHeader().getHeaderBody().getBlockHash())
                            .isEqualTo(expected.getHeader().getHeaderBody().getBlockHash());
                    assertThat(result.getTransactionBodies()).hasSize(expected.getTransactionBodies().size());
                    for (int i = 0; i < expected.getTransactionBodies().size(); i++) {
                        assertThat(result.getTransactionBodies().get(i).getTxHash())
                                .isEqualTo(expected.getTransactionBodies().get(i).getTxHash());
                    }
                    Datum datum = result.getTransactionWitness().get(0).getDatums().get(0);
                    assertThat(datum.getCbor()).isEqualTo(datumHex);
                    assertThat(datum.getHash()).isEqualTo(Datum.cborToHash(HexUtil.decodeHexString(datumHex)));
                    assertThat(datum.getJson()).isNull();
                    assertThat(datum.getParseError()).isNotNull();
                    AuxData auxiliary = result.getAuxiliaryDataMap().get(0);
                    assertThat(auxiliary.getMetadataCbor()).isEqualTo(metadataHex);
                    assertThat(auxiliary.getMetadataJson()).isNull();
                    assertThat(auxiliary.getMetadataParseError()).isNotNull();
                    assertThat(result.getCbor()).isEqualTo(full ? HexUtil.encodeHexString(bytes) : null);
                    if (full) {
                        List<CborSlice> body = CborSlice.of(bytes).items(MajorType.ARRAY).get(1)
                                .items(MajorType.ARRAY);
                        assertThat(result.getTransactionWitness().get(0).getCbor()).isEqualTo(
                                HexUtil.encodeHexString(body.get(2).items(MajorType.ARRAY).get(0).bytes()));
                    }
                }
            }
        } finally {
            YaciConfig.INSTANCE.setReturnFullTxCbor(fullTx);
            YaciConfig.INSTANCE.setReturnBlockCbor(fullBlock);
        }
    }

    @Test
    void directByteEntriesRetainSourceDataAndOtherFields() throws Exception {
        String datum = "a11800".repeat(3000) + "1800"; // Deliberately non-minimal integer encoding.
        String script = "8200581c" + "00".repeat(28);
        String witness = "a20181" + script + "0481" + datum;
        var result = WitnessesSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(witness));
        assertThat(result.getDatums().get(0).getCbor()).isEqualTo(datum);
        assertThat(result.getDatums().get(0).getHash()).isEqualTo(Datum.cborToHash(HexUtil.decodeHexString(datum)));
        assertThat(result.getDatums().get(0).getParseError()).isNotNull();
        assertThat(result.getNativeScripts()).hasSize(1);
        assertThat(result.getNativeScripts().get(0).getParseError()).isNull();
        String metadata = "a11800" + datum;
        for (String aux : new String[]{metadata, "82" + metadata + "81" + script,
                "d90103a200" + metadata + "0181" + script}) {
            AuxData parsed = AuxDataSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(aux));
            assertThat(parsed.getMetadataCbor()).isEqualTo(metadata);
            assertThat(parsed.getMetadataJson()).isNull();
            assertThat(parsed.getMetadataParseError()).isNotNull();
            if (!aux.equals(metadata)) assertThat(parsed.getNativeScripts()).hasSize(1);
        }
        assertThatThrownBy(() -> Datum.fromCbor(HexUtil.decodeHexString("a100")))
                .isInstanceOf(CborException.class);
    }

    @Test
    void bothRedeemerFormatsKeepTheirDataAndExecutionUnitsAfterOverflow() {
        String datum = "d87981".repeat(3000) + "00";
        String units = "820a14";
        String oldRedeemer = "840002" + datum + units;
        for (String redeemers : new String[]{"81" + oldRedeemer, "a182000282" + datum + units}) {
            var witness = WitnessesSerializer.INSTANCE.deserialize(HexUtil.decodeHexString("a105" + redeemers));
            assertThat(witness.getRedeemers()).hasSize(1);
            Redeemer redeemer = witness.getRedeemers().get(0);
            assertThat(redeemer.getIndex()).isEqualTo(2);
            assertThat(redeemer.getExUnits().getMem()).isEqualTo(10);
            assertThat(redeemer.getExUnits().getSteps()).isEqualTo(20);
            assertThat(redeemer.getData().getCbor()).isEqualTo(datum);
            assertThat(redeemer.getData().getJson()).isNull();
            assertThat(redeemer.getData().getParseError()).isNotNull();
            assertThat(redeemer.getCbor()).isEqualTo(oldRedeemer);
        }
    }

    @Test
    void fallbackPreservesFullTransactionWitnessAndAuxiliaryBytes() throws Exception {
        boolean fullTx = YaciConfig.INSTANCE.isReturnFullTxCbor();
        try {
            YaciConfig.INSTANCE.setReturnFullTxCbor(true);
            String datum = "a100".repeat(5000) + "00";
            String metadata = "a100" + datum;
            byte[] bytes = blockWithData(CborLoader.getHexBytes("block/preprod292683.txt"), datum, metadata);
            CborSlice root = CborSlice.of(bytes);
            List<CborSlice> body = root.items(MajorType.ARRAY).get(1).items(MajorType.ARRAY);
            CborSlice tx = body.get(1).items(MajorType.ARRAY).get(0);
            Map txData = (Map) CborSerializationUtil.deserializeOne(tx.bytes());
            txData.put(new UnsignedInteger(7), new ByteString(HexUtil.decodeHexString(
                    Datum.cborToHash(HexUtil.decodeHexString(metadata)))));
            byte[] txBytes = CborSerializationUtil.serialize(txData, false);
            bytes = root.replacing(Arrays.asList(tx), Arrays.asList(txBytes));

            Block result = BlockSerializer.INSTANCE.deserialize(bytes);
            body = CborSlice.of(bytes).items(MajorType.ARRAY).get(1).items(MajorType.ARRAY);
            List<CborSlice> txs = body.get(1).items(MajorType.ARRAY);
            List<CborSlice> witnesses = body.get(2).items(MajorType.ARRAY);
            for (int i = 0; i < txs.size(); i++) {
                assertThat(result.getTransactionBodies().get(i).getCbor())
                        .isEqualTo(HexUtil.encodeHexString(txs.get(i).bytes()));
                assertThat(result.getTransactionBodies().get(i).getTxHash())
                        .isEqualTo(Datum.cborToHash(txs.get(i).bytes()));
            }
            for (int i = 0; i < witnesses.size(); i++) {
                assertThat(result.getTransactionWitness().get(i).getCbor())
                        .isEqualTo(HexUtil.encodeHexString(witnesses.get(i).bytes()));
            }
            assertThat(result.getAuxiliaryDataMap().get(0).getCbor()).isEqualTo(metadata);
        } finally {
            YaciConfig.INSTANCE.setReturnFullTxCbor(fullTx);
        }
    }

    @Test
    void isolatedDatumLeavesHealthySiblingsIntactAndDoesNotHideMalformedFields() throws Exception {
        String deep = "a100".repeat(5000) + "00";
        // Indefinite witness map and tagged, indefinite datum set with a healthy sibling.
        var witness = WitnessesSerializer.INSTANCE.deserialize(
                HexUtil.decodeHexString("bf04d901029f" + deep + "182affff"));
        assertThat(witness.getDatums()).hasSize(2);
        assertThat(witness.getDatums().get(0).getCbor()).isEqualTo(deep);
        assertThat(witness.getDatums().get(0).getJson()).isNull();
        assertThat(witness.getDatums().get(1).getCbor()).isEqualTo("182a");
        assertThat(witness.getDatums().get(1).getJson()).isEqualTo(Datum.fromCbor(
                HexUtil.decodeHexString("182a")).getJson());
        assertThat(witness.getDatums().get(1).getParseError()).isNull();
        assertThatThrownBy(() -> WitnessesSerializer.INSTANCE.deserialize(
                HexUtil.decodeHexString("a20481" + deep + "0100"))).isInstanceOf(ClassCastException.class);
        assertThatThrownBy(() -> WitnessesSerializer.INSTANCE.deserialize(
                HexUtil.decodeHexString("a10482" + deep))).isInstanceOf(Exception.class);
    }

    @Test
    void fullBlockRecoveryRejectsMalformedFramingInsideDeepData() throws Exception {
        byte[] original = CborLoader.getHexBytes("block/preprod292683.txt");
        String expectedHash = BlockSerializer.INSTANCE.deserialize(original)
                .getHeader().getHeaderBody().getBlockHash();
        String deepPrefix = "a100".repeat(5000);
        // Reach the recovery path before the decoder sees the corruption. A byte boundary must
        // still be trustworthy: depth is recoverable, but these malformed CBOR encodings are not.
        String[] corruptValues = {
                "1e",                 // Reserved additional-information value.
                "ff",                 // BREAK cannot close a definite map's missing value.
                "bf00ff",             // Indefinite map ends after a key without a value.
                "5f6100ff",           // Text chunk inside an indefinite byte string.
                "7f5f40ffff",         // Nested indefinite byte string inside a text string.
                "9bffffffffffffffff", // Array length cannot fit in the source buffer.
                "5b7fffffffffffffff"  // Claimed byte-string payload is absent.
        };
        for (String corrupt : corruptValues) {
            for (boolean inMetadata : new boolean[]{false, true}) {
                byte[] bytes = blockWithData(original,
                        inMetadata ? "00" : deepPrefix + corrupt,
                        inMetadata ? "a100" + deepPrefix + corrupt : "a10000");
                assertThatThrownBy(() -> BlockSerializer.INSTANCE.deserialize(bytes))
                        .as("corrupt=%s, metadata=%s", corrupt, inMetadata)
                        .isInstanceOf(Exception.class); // In particular, no escaping StackOverflowError.
                // A rejected block must not leave mutable decoder state that corrupts the next parse.
                assertThat(BlockSerializer.INSTANCE.deserialize(original).getHeader().getHeaderBody().getBlockHash())
                        .isEqualTo(expectedHash);
            }
        }
    }

    @Test
    void fullBlockRecoveryRejectsTruncationAndMalformedTrailingBytes() throws Exception {
        byte[] original = CborLoader.getHexBytes("block/preprod292683.txt");
        byte[] deepBlock = blockWithData(original, "a100".repeat(5000) + "00", "a10000");
        byte[] trailingValue = Arrays.copyOf(deepBlock, deepBlock.length + 1);
        trailingValue[deepBlock.length] = (byte) 0x81; // A truncated second CBOR value.
        for (byte[] malformed : new byte[][]{
                Arrays.copyOf(deepBlock, deepBlock.length - 1),
                Arrays.copyOf(deepBlock, deepBlock.length / 2), trailingValue}) {
            assertThatThrownBy(() -> BlockSerializer.INSTANCE.deserialize(malformed))
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    void fullBlockRecoveryDoesNotHideInvalidScriptsExecutionUnitsOrSiblingDatums() throws Exception {
        byte[] original = CborLoader.getHexBytes("block/preprod292683.txt");
        String deep = "a100".repeat(5000) + "00";
        byte[] deepBlock = blockWithData(original, deep, "a10000");
        CborSlice root = CborSlice.of(deepBlock);
        CborSlice witness = root.items(MajorType.ARRAY).get(1).items(MajorType.ARRAY)
                .get(2).items(MajorType.ARRAY).get(0);
        // Each witness contains a deep datum to trigger recovery and a separate semantic error.
        // All three have valid CBOR boundaries, so rejection must come from the existing serializers.
        String[] invalidWitnesses = {
                "a20481" + deep + "018180",       // Native script is [], missing its type.
                "a20481" + deep + "05818400000000", // Execution units are an integer, not [mem, steps].
                "a10482" + deep + "f5"            // Boolean is CBOR, but is not valid Plutus data.
        };
        for (String invalid : invalidWitnesses) {
            byte[] bytes = root.replacing(Arrays.asList(witness), Arrays.asList(HexUtil.decodeHexString(invalid)));
            assertThatThrownBy(() -> BlockSerializer.INSTANCE.deserialize(bytes))
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    void emptyByteInputsRetainTheirPreviousNullResult() {
        assertThat(WitnessesSerializer.INSTANCE.deserialize(new byte[0])).isNull();
        assertThat(AuxDataSerializer.INSTANCE.deserialize(new byte[0])).isNull();
    }

    @Test
    void blockRecoveryAcceptsValidTrailingValuesLikeTheNormalParser() throws Exception {
        byte[] original = CborLoader.getHexBytes("block/preprod292683.txt");
        String datum = "a100".repeat(5000) + "00";
        byte[] deepBlock = blockWithData(original, datum, "a10000");
        for (byte[] first : new byte[][]{original, deepBlock}) {
            byte[] repeated = new byte[first.length * 2];
            System.arraycopy(first, 0, repeated, 0, first.length);
            System.arraycopy(first, 0, repeated, first.length, first.length);
            Block result = BlockSerializer.INSTANCE.deserialize(repeated);
            assertThat(result.getHeader().getHeaderBody().getBlockHash())
                    .isEqualTo(BlockSerializer.INSTANCE.deserialize(first).getHeader().getHeaderBody().getBlockHash());
            if (first == deepBlock) {
                assertThat(result.getTransactionWitness().get(0).getDatums().get(0).getCbor()).isEqualTo(datum);
            }
        }
        // This historical fixture contains two complete blocks in its byte buffer.
        byte[] duplicateFixture = CborLoader.getHexBytes("block/preprod286677.txt");
        var fallback = BlockSerializer.class.getDeclaredMethod("deserializeWithIsolatedData", byte[].class);
        fallback.setAccessible(true);
        Block isolated = (Block) fallback.invoke(BlockSerializer.INSTANCE, (Object) duplicateFixture);
        assertThat(new ObjectMapper().writeValueAsString(isolated)).isEqualTo(
                new ObjectMapper().writeValueAsString(BlockSerializer.INSTANCE.deserialize(duplicateFixture)));
    }

    @Test
    void healthyConwayRedeemerKeepsLegacyCborWhenAnotherDatumFails() throws Exception {
        String chunks = "5f5840" + "ab".repeat(64) + "46" + "cd".repeat(6) + "ff";
        String data = "d8799f" + chunks + "ff";
        String witness = "a205a182000082" + data + "821903e81a000f4240048100";
        byte[] original = CborLoader.getHexBytes("block/preprod292683.txt");
        CborSlice root = CborSlice.of(original);
        CborSlice witnessSlot = root.items(MajorType.ARRAY).get(1).items(MajorType.ARRAY)
                .get(2).items(MajorType.ARRAY).get(0);
        byte[] healthy = root.replacing(Arrays.asList(witnessSlot),
                Arrays.asList(HexUtil.decodeHexString(witness)));
        byte[] failed = root.replacing(Arrays.asList(witnessSlot), Arrays.asList(HexUtil.decodeHexString(
                witness.substring(0, witness.length() - 2) + "a100".repeat(5000) + "00")));
        Redeemer expected = BlockSerializer.INSTANCE.deserialize(healthy)
                .getTransactionWitness().get(0).getRedeemers().get(0);
        Redeemer actual = BlockSerializer.INSTANCE.deserialize(failed)
                .getTransactionWitness().get(0).getRedeemers().get(0);
        assertThat(actual.getCbor()).hasSize(88 * 2).isEqualTo(expected.getCbor());
        assertThat(actual.getData().getCbor()).isEqualTo(data);
        assertThat(actual.getData().getHash()).isEqualTo(Datum.cborToHash(HexUtil.decodeHexString(data)));
        assertThat(actual.getData().getJson()).isEqualTo(expected.getData().getJson());
        assertThat(actual.getData().getParseError()).isNull();
    }

    static byte[] blockWithData(byte[] original, String datum, String metadata) throws Exception {
        Array root = (Array) CborSerializationUtil.deserializeOne(original);
        Array body = (Array) root.getDataItems().get(1);
        ((Map) ((Array) body.getDataItems().get(2)).getDataItems().get(0))
                .put(new UnsignedInteger(4), new Array().add(new UnsignedInteger(0)));
        ((Map) body.getDataItems().get(3)).put(new UnsignedInteger(0), new Map());
        CborSlice source = CborSlice.of(CborSerializationUtil.serialize(root, false));
        List<CborSlice> fields = source.items(MajorType.ARRAY).get(1).items(MajorType.ARRAY);
        List<CborSlice> witness = fields.get(2).items(MajorType.ARRAY).get(0).items(MajorType.MAP);
        CborSlice datumSlot = null;
        for (int i = 0; i < witness.size(); i += 2) {
            if (DataItemIsolation.unsigned(witness.get(i)) == 4) {
                datumSlot = witness.get(i + 1).items(MajorType.ARRAY).get(0);
            }
        }
        List<CborSlice> auxiliary = fields.get(3).items(MajorType.MAP);
        CborSlice metadataSlot = null;
        for (int i = 0; i < auxiliary.size(); i += 2) {
            if (DataItemIsolation.unsigned(auxiliary.get(i)) == 0) metadataSlot = auxiliary.get(i + 1);
        }
        return source.replacing(Arrays.asList(datumSlot, metadataSlot),
                Arrays.asList(HexUtil.decodeHexString(datum), HexUtil.decodeHexString(metadata)));
    }

    private static DataItem nestedList(int depth, boolean indefinite) {
        DataItem item = new UnsignedInteger(0);
        for (int i = 0; i < depth; i++) {
            Array array = new Array().add(item);
            if (indefinite) {
                array.setChunked(true);
                array.add(Special.BREAK);
            }
            item = array;
        }
        return item;
    }

    private static Array nativeScript() {
        return new Array().add(new UnsignedInteger(0)).add(new ByteString(new byte[28]));
    }
}
