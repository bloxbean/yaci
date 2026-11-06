package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Datum;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.MsgBlock;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers.MsgBlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2c.LocalChainSyncState;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.RollForwardSerializer;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins what block parsing does, from before ADR 0003 moved it onto the shared reader (D8, I11, I12).
 * Inputs are built with CCL's {@link CborSpan} and expected hashes with CCL's Blake2b, so these tests do not rest on
 * the code they pin. Fixture {@code preprod292683}: Babbage envelope (era 6), three transactions, one datum and one
 * redeemer in witness 0, native scripts in witnesses 1 and 2, auxiliary data for transactions 1 and 2, no invalid
 * transactions.
 * Tests named {@code differenceQ6} pin what ADR 0003 M2 narrowed, and {@code correction} what it corrected.
 */
class BlockParsingPinTest {
    private static final byte[] FIXTURE = CborLoader.getHexBytes("block/preprod292683.txt");
    private static final long BLOCK_NUMBER = 292683;
    private static final int HEADER = 0;
    private static final int BODIES = 1;
    private static final int WITNESSES = 2;
    private static final int AUXILIARY = 3;
    private static final int INVALID = 4;

    private final boolean originalBlockCbor = YaciConfig.INSTANCE.isReturnBlockCbor();
    private final boolean originalBodyCbor = YaciConfig.INSTANCE.isReturnTxBodyCbor();
    private final boolean originalFullTx = YaciConfig.INSTANCE.isReturnFullTxCbor();

    @AfterEach
    void restoreConfig() {
        YaciConfig.INSTANCE.setReturnBlockCbor(originalBlockCbor);
        YaciConfig.INSTANCE.setReturnTxBodyCbor(originalBodyCbor);
        YaciConfig.INSTANCE.setReturnFullTxCbor(originalFullTx);
    }

    // ---- ADR 0003 D8's four inputs ----

    /**
     * Table row 1 (I12, re-pinned by ADR 0003 D4a): an unknown index reads in the newest layout, Dijkstra's.
     * RawBlock.of rejects it; Yaci delivers a Dijkstra-shaped block, here a real one with one transaction, with a null
     * era.
     */
    @Test
    void unknownEraIndexGivesAPopulatedBlockWithNullEra() throws Exception {
        byte[] dijkstra = CborLoader.getHexBytes("block/dijkstra/musashi-dijkstra2.txt");
        assertThat(dijkstra[1]).isEqualTo((byte) 8);
        for (int era : new int[]{9, 23}) {
            byte[] bytes = dijkstra.clone();
            bytes[1] = (byte) era;
            for (Block block : SyncDataIsolationTest.throughBothSyncPaths(bytes)) {
                assertThat(block.getEra()).as("era index %d", era).isNull();
                assertThat(block.getTransactionBodies()).hasSize(1);
                assertThat(block.getTransactionWitness()).hasSize(1);
                assertThat(block.getInvalidTransactions()).isEmpty();
                assertThat(block.getHeader().getHeaderBody().getBlockNumber()).isEqualTo(4277);
            }
        }
    }

    /**
     * I12 re-pinned by ADR 0003 D4a: a Conway-shaped block at index 8 (Dijkstra) or at an unknown index, which reads
     * in Dijkstra's layout, raises onParsingError, where an unknown index was delivered with a null era.
     */
    @Test
    void conwayShapedBlockAtDijkstraOrUnknownIndexRaisesParsingError() {
        for (int era : new int[]{8, 9, 23}) {
            assertThat(parsingError(withEra(era)).getBlockNumber()).as("era index %d", era).isEqualTo(BLOCK_NUMBER);
        }
    }

    /** Table row 2: a top-level {@code 00} appended; the first value is the block. */
    @Test
    void trailingTopLevelValuesAreIgnored() throws Exception {
        byte[] bytes = Arrays.copyOf(FIXTURE, FIXTURE.length + 1);
        for (Block block : SyncDataIsolationTest.throughBothSyncPaths(bytes)) {
            assertThat(block.getEra()).isEqualTo(Era.Babbage);
            assertThat(block).usingRecursiveComparison().isEqualTo(BlockSerializer.INSTANCE.deserialize(FIXTURE));
        }
    }

    /** Trailing values are decoded and must be well-formed. ADR 0003 D8's CborSpan.at stops reading before them. */
    @Test
    void malformedTrailingBytesFailTheBlock() {
        byte[] bytes = Arrays.copyOf(FIXTURE, FIXTURE.length + 1);
        bytes[FIXTURE.length] = (byte) 0x81;
        assertThatThrownBy(() -> BlockSerializer.INSTANCE.deserialize(bytes))
                .isInstanceOf(com.bloxbean.cardano.yaci.core.exception.CborRuntimeException.class);
    }

    /** Block CBOR is the whole input, trailing values included, not only the block's own span. */
    @Test
    void blockCborKeepsTrailingValues() {
        YaciConfig.INSTANCE.setReturnBlockCbor(true);
        byte[] bytes = Arrays.copyOf(FIXTURE, FIXTURE.length + 1);
        assertThat(BlockSerializer.INSTANCE.deserialize(bytes).getCbor()).isEqualTo(HexUtil.encodeHexString(bytes));
    }

    /** Table row 3. Q6: this was delivered with three bodies and no witnesses. */
    @Test
    void differenceQ6_emptyWitnessArrayRaisesParsingError() {
        assertThat(parsingError(replacing(block().get(WITNESSES), "80")).getBlockNumber()).isEqualTo(BLOCK_NUMBER);
    }

    /** Table row 4. Q6: this was delivered with invalid transaction 3 of 3. */
    @Test
    void differenceQ6_outOfRangeInvalidIndexRaisesParsingError() {
        assertThat(parsingError(replacing(block().get(INVALID), "8103")).getBlockNumber()).isEqualTo(BLOCK_NUMBER);
    }

    /** Q6: all of these were delivered. The reader rejects each one. */
    @Test
    void differenceQ6_blockShapesTheReaderRejectsRaiseParsingError() {
        CborSpan block = block();
        String header = hex(block.get(HEADER));
        String flatHeader = flatHeader();
        String bodies = hex(block.get(BODIES));
        String witnesses = hex(block.get(WITNESSES));
        String auxiliary = hex(block.get(AUXILIARY));
        String invalid = hex(block.get(INVALID));
        String parts = bodies + witnesses + auxiliary;
        String[] shapes = {
                // The wrong layout for the era, known or not
                "820684" + header + parts,
                "820585" + header + parts + invalid,
                "820485" + flatHeader + parts + invalid,
                "820584" + flatHeader + parts,
                "820984" + header + parts,
                "820985" + flatHeader + parts + invalid,
                // Extra items in the block and the envelope
                "820686" + header + parts + invalid + "80",
                "830685" + header + parts + invalid + "00",
                // Not an array where the bodies are: the old extractor read any head as a length
                "820685" + header + "00" + "80" + "a0" + "80",
                // A tag on the era index and the parts that were read through one
                "82" + "d90102" + "06" + hex(block),
                "820685" + header + bodies + "d90102" + witnesses + auxiliary + invalid,
                "820685" + header + bodies + witnesses + "d90102" + auxiliary + invalid,
                "820685" + header + parts + "d90102" + invalid,
        };
        for (String shape : shapes) parsingError(HexUtil.decodeHexString(shape));
    }

    /** Q6: all of these were delivered. Counts and indexes are the reader's checks from ADR 0001 D6. */
    @Test
    void differenceQ6_badCountsAndIndexesRaiseParsingError() {
        CborSpan block = block();
        List<Map.Entry<CborSpan, CborSpan>> aux = block.get(AUXILIARY).entries();
        String oneMoreWitness = "84" + hex(block.get(WITNESSES)).substring(2) + "a0";
        for (byte[] input : List.of(
                replacing(block.get(WITNESSES), oneMoreWitness),
                replacing(aux.get(0).getKey(), "03"),
                replacing(aux.get(0).getKey(), "d9010201"),
                replacing(block.get(INVALID), "820100"),
                replacing(block.get(INVALID), "820000"))) {
            assertThat(parsingError(input).getBlockNumber()).isEqualTo(BLOCK_NUMBER);
        }
    }

    /** Q6: a repeated aux data index is rejected from Conway, and takes the last value before it (ADR 0001 D6). */
    @Test
    void differenceQ6_repeatedAuxiliaryIndexRaisesParsingErrorFromConway() {
        List<Map.Entry<CborSpan, CborSpan>> aux = block().get(AUXILIARY).entries();
        byte[] repeated = replacing(aux.get(1).getKey(), hex(aux.get(0).getKey()));

        assertThat(BlockSerializer.INSTANCE.deserialize(repeated).getAuxiliaryDataMap()).containsOnlyKeys(1);
        byte[] conway = repeated.clone();
        conway[1] = 7;
        assertThat(parsingError(conway).getBlockNumber()).isEqualTo(BLOCK_NUMBER);
    }

    /** Q6: extra items in the header's records. The header does not fit a layout, so it gives no number. */
    @Test
    void differenceQ6_extraItemsInAHeaderRaiseParsingError() {
        CborSpan header = block().get(HEADER);
        CborSpan body = header.get(0);
        for (CborSpan array : List.of(header, body, body.get(5), body.get(8), body.get(9))) {
            assertThat(parsingError(replacing(array, withExtraItem(array))).getBlockNumber()).isNull();
        }
    }

    /** Not a difference: tags on a header and its records are looked through, as they were. */
    @Test
    void tagsInAHeaderAreLookedThrough() {
        CborSpan header = block().get(HEADER);
        CborSpan body = header.get(0);
        BlockHeader healthy = BlockSerializer.INSTANCE.deserialize(FIXTURE).getHeader();
        for (CborSpan item : List.of(body, body.get(0), body.get(2), body.get(5), body.get(8), body.get(9))) {
            HeaderBody parsed = BlockSerializer.INSTANCE.deserialize(replacing(item, "d90102" + hex(item)))
                    .getHeader().getHeaderBody();
            assertThat(parsed).usingRecursiveComparison().ignoringFields("blockHash")
                    .isEqualTo(healthy.getHeaderBody());
        }
    }

    /** Not a difference: rejected before and now. The tree was accepted, and the old extractor misread these heads. */
    @Test
    void tagsTheOldExtractorCouldNotReadStayRejected() {
        CborSpan block = block();
        String header = hex(block.get(HEADER));
        String rest = hex(block.get(WITNESSES)) + hex(block.get(AUXILIARY)) + hex(block.get(INVALID));
        for (String shape : new String[]{"d90102" + "8206" + hex(block), "8206" + "d90102" + hex(block),
                "820685" + header + "d90102" + hex(block.get(BODIES)) + rest}) {
            parsingError(HexUtil.decodeHexString(shape));
        }
    }

    /**
     * A difference on the era only: an index past int was truncated to a known one, and is an unknown one now, read in
     * Dijkstra's layout (D4a): a Dijkstra-shaped block is delivered, and this Babbage-shaped one is a parse error.
     */
    @Test
    void eraIndexPastIntIsAnUnknownEraHere() {
        CborSpan dijkstra = CborSpan.of(CborLoader.getHexBytes("block/dijkstra/musashi-dijkstra2.txt")).get(1);
        Block block = BlockSerializer.INSTANCE.deserialize(
                HexUtil.decodeHexString("821b0000000100000008" + hex(dijkstra)));
        assertThat(block.getEra()).isNull();
        assertThat(block.getTransactionBodies()).hasSize(1);
        assertThat(parsingError(HexUtil.decodeHexString("821b0000000100000006" + hex(block())))
                .getBlockNumber()).isEqualTo(BLOCK_NUMBER);
    }

    /** Q6: an index of 0 or 1 is Byron. The agents read those with the Byron serializers before reaching this class. */
    @Test
    void differenceQ6_byronIndexIsNotReadByThisClass() {
        for (int era : new int[]{0, 1}) {
            assertThatThrownBy(() -> BlockSerializer.INSTANCE.deserialize(withEra(era)))
                    .isInstanceOf(com.bloxbean.cardano.yaci.core.exception.CborRuntimeException.class);
        }
    }

    /** The message serializers take tag 24 off, so it never reaches this class, which rejected it and still does. */
    @Test
    void aBlockInTag24IsNotRead() {
        byte[] wrapped = CborSerializationUtil.serialize(new ByteString(FIXTURE));
        String tagged = "d818" + HexUtil.encodeHexString(wrapped);
        assertThatThrownBy(() -> BlockSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(tagged)))
                .isInstanceOf(com.bloxbean.cardano.yaci.core.exception.CborRuntimeException.class);
        assertThat(MsgBlockSerializer.INSTANCE.deserialize(HexUtil.decodeHexString("8204" + tagged)).getBytes())
                .isEqualTo(FIXTURE);
    }

    /** Five items with an empty list give an empty list; four items, as to Mary, leave it null. */
    @Test
    void invalidTransactionsAreEmptyWhenPresentAndNullWhenAbsent() {
        assertThat(BlockSerializer.INSTANCE.deserialize(FIXTURE).getInvalidTransactions()).isEmpty();
        CborSpan block = block();
        byte[] mary = HexUtil.decodeHexString("820484" + flatHeader() + hex(block.get(BODIES))
                + hex(block.get(WITNESSES)) + hex(block.get(AUXILIARY)));
        assertThat(BlockSerializer.INSTANCE.deserialize(mary).getEra()).isEqualTo(Era.Mary);
        assertThat(BlockSerializer.INSTANCE.deserialize(mary).getInvalidTransactions()).isNull();
    }

    // ---- I12: a block that does not fit the newest layout ----

    /** A [header, block_body] whose body is not Dijkstra's fails, and block fetch reports the header's number. */
    @Test
    void twoItemBlockRaisesParsingErrorWithItsBlockNumber() {
        CborSpan block = block();
        String oneTx = "8184" + hex(block.get(BODIES).get(0)) + hex(block.get(WITNESSES).get(0)) + "f5f6";
        for (String blockBody : new String[]{"80", "a0", hex(block.get(BODIES)), oneTx}) {
            byte[] bytes = HexUtil.decodeHexString("8209" + "82" + hex(block.get(HEADER)) + blockBody);
            assertThatThrownBy(() -> BlockSerializer.INSTANCE.deserialize(bytes)).isInstanceOf(RuntimeException.class);
            AgentResult result = throughAgent(bytes);
            assertThat(result.blocks).isEmpty();
            assertThat(result.errors).hasSize(1);
            assertThat(result.errors.get(0).getBlockNumber()).isEqualTo(BLOCK_NUMBER);
            assertThat(result.errors.get(0).getBlockCbor()).isEqualTo(bytes);
        }
    }

    /** Local chain sync has no onParsingError: the state logs the failure and returns no message. */
    @Test
    void twoItemBlockInLocalChainSyncIsLoggedAndGivesNoMessage() {
        CborSpan block = block();
        String wrapped = hex(block.get(HEADER)) + "80";
        int length = 3 + wrapped.length() / 2;
        String content = "820982" + wrapped;
        byte[] message = HexUtil.decodeHexString("8302" + "d818" + "59" + String.format("%04x", length) + content
                + "8282" + "01" + "5820" + "00".repeat(32) + "01");
        assertThat(LocalChainSyncState.Idle.handleInbound(message)).isNull();
    }

    // ---- onParsingError ----

    /** Era and header are read as a prefix, so a block damaged after its header still reports its number. */
    @Test
    void parsingErrorCarriesBlockNumberWhenTheHeaderCanBeRead() {
        byte[] truncated = Arrays.copyOf(FIXTURE, FIXTURE.length - 10);
        AgentResult result = throughAgent(truncated);
        assertThat(result.blocks).isEmpty();
        assertThat(result.errors).hasSize(1);
        assertThat(result.errors.get(0).getBlockNumber()).isEqualTo(BLOCK_NUMBER);
        assertThat(result.errors.get(0).getBlockCbor()).isEqualTo(truncated);
    }

    @Test
    void parsingErrorHasNoBlockNumberWhenTheHeaderCannotBeRead() {
        for (String input : new String[]{"", "8207", "820700", "82078100", "8207818100"}) {
            AgentResult result = throughAgent(HexUtil.decodeHexString(input));
            assertThat(result.blocks).as(input).isEmpty();
            assertThat(result.errors).as(input).hasSize(1);
            assertThat(result.errors.get(0).getBlockNumber()).as(input).isNull();
        }
    }

    @Test
    void parsingErrorDoesNotStopTheNextBlock() {
        BlockfetchAgent agent = new BlockfetchAgent();
        AgentResult result = listen(agent);
        agent.processResponse(new MsgBlock(Arrays.copyOf(FIXTURE, FIXTURE.length - 10)));
        agent.processResponse(new MsgBlock(FIXTURE));
        assertThat(result.errors).hasSize(1);
        assertThat(result.blocks).hasSize(1);
    }

    // ---- I11: what a bad witness or auxiliary data item does ----

    /** Only stack exhaustion and JSON failure are isolated: CBOR and hash stay, JSON goes, the block is delivered. */
    @Test
    void deepDatumAndMetadataAreIsolatedAndTheBlockIsDelivered() throws Exception {
        String deepDatum = "81".repeat(8000) + "00";
        String deepMetadata = "a100".repeat(3000) + "00";
        CborSpan witness0 = block().get(WITNESSES).get(0);
        CborSpan datum = field(witness0, 4).get(0);
        CborSpan aux1 = block().get(AUXILIARY).entries().get(0).getValue();
        CborSpan metadata = field(aux1.untag(), 0);
        byte[] bytes = CborSpan.of(FIXTURE).replacing(List.of(datum, metadata),
                List.of(HexUtil.decodeHexString(deepDatum), HexUtil.decodeHexString(deepMetadata)));

        Block healthy = BlockSerializer.INSTANCE.deserialize(FIXTURE);
        for (Block block : SyncDataIsolationTest.throughBothSyncPaths(bytes)) {
            Datum parsed = block.getTransactionWitness().get(0).getDatums().get(0);
            assertThat(parsed.getCbor()).isEqualTo(deepDatum);
            assertThat(parsed.getHash()).isEqualTo(blake2b256(HexUtil.decodeHexString(deepDatum)));
            assertThat(parsed.getJson()).isNull();
            assertThat(parsed.getParseError()).isNotNull();

            AuxData aux = block.getAuxiliaryDataMap().get(1);
            assertThat(aux.getMetadataCbor()).isEqualTo(deepMetadata);
            assertThat(aux.getMetadataJson()).isNull();
            assertThat(aux.getMetadataParseError()).isNotNull();

            assertThat(block.getTransactionBodies()).usingRecursiveComparison()
                    .isEqualTo(healthy.getTransactionBodies());
            assertThat(block.getTransactionWitness().get(1)).usingRecursiveComparison()
                    .isEqualTo(healthy.getTransactionWitness().get(1));
            assertThat(block.getAuxiliaryDataMap().get(2)).usingRecursiveComparison()
                    .isEqualTo(healthy.getAuxiliaryDataMap().get(2));
        }
    }

    /**
     * ADR 0003 D8 and I11 say a witness or auxiliary data item that fails to parse does not fail its block. That holds
     * only for the test above: a structural failure does fail the block, and block fetch reports it.
     */
    @Test
    void structurallyBadWitnessFailsTheBlock() {
        String[] witnesses = {"00", "a10000", "a10481f5", "a1018100", "a1058100"};
        for (String witness : witnesses) {
            byte[] bytes = replacing(block().get(WITNESSES).get(0), witness);
            assertThatThrownBy(() -> BlockSerializer.INSTANCE.deserialize(bytes)).as(witness).isNotNull();
            AgentResult result = throughAgent(bytes);
            assertThat(result.blocks).as(witness).isEmpty();
            assertThat(result.errors.get(0).getBlockNumber()).as(witness).isEqualTo(BLOCK_NUMBER);
        }
    }

    @Test
    void structurallyBadAuxiliaryDataFailsTheBlock() {
        String[] auxiliaries = {"80", "8100", "d90103a10000", "d90103a1018100", "d90103a1028100", "a100f5"};
        for (String auxiliary : auxiliaries) {
            byte[] bytes = replacing(block().get(AUXILIARY).entries().get(0).getValue(), auxiliary);
            assertThatThrownBy(() -> BlockSerializer.INSTANCE.deserialize(bytes)).as(auxiliary).isNotNull();
            AgentResult result = throughAgent(bytes);
            assertThat(result.blocks).as(auxiliary).isEmpty();
            assertThat(result.errors.get(0).getBlockNumber()).as(auxiliary).isEqualTo(BLOCK_NUMBER);
        }
    }

    /** Shapes that do not fail at all: auxiliary data read as nothing, a text datum read as bytes, an unknown key. */
    @Test
    void wrongShapesTheSerializersDoNotLookAtAreDelivered() {
        for (String auxiliary : new String[]{"00", "6161"}) {
            Block block = BlockSerializer.INSTANCE.deserialize(
                    replacing(block().get(AUXILIARY).entries().get(0).getValue(), auxiliary));
            AuxData aux = block.getAuxiliaryDataMap().get(1);
            assertThat(aux.getMetadataCbor()).as(auxiliary).isNull();
            assertThat(aux.getNativeScripts()).as(auxiliary).isNull();
        }
        Witnesses datumText = BlockSerializer.INSTANCE.deserialize(
                replacing(block().get(WITNESSES).get(0), "a104816161")).getTransactionWitness().get(0);
        assertThat(datumText.getDatums()).hasSize(1);
        assertThat(datumText.getDatums().get(0).getCbor()).isEqualTo("6161");
        assertThat(datumText.getDatums().get(0).getJson()).contains("\"bytes\"");
        Witnesses unknownKey = BlockSerializer.INSTANCE.deserialize(
                replacing(block().get(WITNESSES).get(0), "a11863f5")).getTransactionWitness().get(0);
        assertThat(unknownKey.getVkeyWitnesses()).isEmpty();
        Witnesses scriptInt = BlockSerializer.INSTANCE.deserialize(
                replacing(block().get(WITNESSES).get(0), "a1038100")).getTransactionWitness().get(0);
        assertThat(scriptInt.getPlutusV1Scripts()).hasSize(1);
    }

    /** Metadata with an indefinite array is delivered with no JSON, as cbor-java's decoder made it fail. */
    @Test
    void indefiniteArrayInMetadataHasNoJson() {
        String metadata = "a1009f01ff";
        AuxData aux = BlockSerializer.INSTANCE.deserialize(
                replacing(block().get(AUXILIARY).entries().get(0).getValue(), metadata)).getAuxiliaryDataMap().get(1);
        assertThat(aux.getMetadataCbor()).isEqualTo(metadata);
        assertThat(aux.getMetadataJson()).isNull();
        assertThat(aux.getMetadataParseError()).contains("MetadataDeSerializationException");
    }

    // ---- Count mismatches between parsed and raw items ----

    /**
     * ADR 0003 D8's "a count mismatch between parsed and raw items logs and continues" has nothing to apply to from M4:
     * a witness set is read once, from its source bytes, so no parsed and raw items are counted against each other.
     */
    @Test
    void eachWitnessSetIsReadOnceFromItsSourceBytes() {
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        Block block = BlockSerializer.INSTANCE.deserialize(FIXTURE);
        List<CborSpan> sets = block().get(WITNESSES).items();
        assertThat(block.getTransactionWitness()).hasSameSizeAs(sets);
        for (int i = 0; i < sets.size(); i++) {
            Witnesses expected = WitnessesSerializer.INSTANCE.deserialize(sets.get(i).bytes()).toBuilder()
                    .cbor(hex(sets.get(i))).build();
            assertThat(block.getTransactionWitness().get(i)).usingRecursiveComparison().isEqualTo(expected);
        }
    }

    // ---- Source bytes and hashes ----

    @Test
    void datumAndRedeemerBytesAndHashesComeFromTheBlock() {
        CborSpan witness = block().get(WITNESSES).get(0);
        CborSpan datum = field(witness, 4).get(0);
        CborSpan redeemer = field(witness, 5).get(0);
        Witnesses parsed = BlockSerializer.INSTANCE.deserialize(FIXTURE).getTransactionWitness().get(0);

        assertThat(parsed.getDatums().get(0).getCbor()).isEqualTo(hex(datum));
        assertThat(parsed.getDatums().get(0).getHash()).isEqualTo(blake2b256(datum.bytes()));
        assertThat(parsed.getRedeemers().get(0).getCbor()).isEqualTo(hex(redeemer));
        assertThat(parsed.getRedeemers().get(0).getData().getCbor()).isEqualTo(hex(redeemer.get(2)));
        assertThat(parsed.getRedeemers().get(0).getData().getHash()).isEqualTo(blake2b256(redeemer.get(2).bytes()));
    }

    /** Non-minimal encodings keep their own bytes and hash: {@code 1800} is not re-encoded as {@code 00}. */
    @Test
    void nonMinimalDataKeepsItsOwnBytesAndHash() {
        byte[] data = HexUtil.decodeHexString("1800");
        // Array form [[tag, index, data, units]], then Conway's map form {[tag, index]: [data, units]}.
        for (String redeemers : new String[]{"81" + "84000018" + "00820a14", "a1" + "820000" + "821800820a14"}) {
            String witness = "a2" + "0481" + "1800" + "05" + redeemers;
            Witnesses parsed = BlockSerializer.INSTANCE.deserialize(replacing(block().get(WITNESSES).get(0), witness))
                    .getTransactionWitness().get(0);
            assertThat(parsed.getDatums().get(0).getCbor()).isEqualTo("1800");
            assertThat(parsed.getDatums().get(0).getHash()).isEqualTo(blake2b256(data));
            Redeemer redeemer = parsed.getRedeemers().get(0);
            assertThat(redeemer.getData().getCbor()).isEqualTo("1800");
            assertThat(redeemer.getData().getHash()).isEqualTo(blake2b256(data));
        }
    }

    /**
     * The duplicate rules. A datum array keeps every entry, equal or not. A repeated witness key takes its last value.
     * A repeated key in a Conway redeemer map keeps its first position and takes the last value, in the source bytes of
     * that value. ADR 0003 D8 calls this "the datum duplicate rule"; no rule about duplicate datums themselves exists.
     */
    @Test
    void duplicateRules() {
        Witnesses datums = BlockSerializer.INSTANCE.deserialize(
                replacing(block().get(WITNESSES).get(0), "a104820000")).getTransactionWitness().get(0);
        assertThat(datums.getDatums()).hasSize(2);

        Witnesses repeatedKey = BlockSerializer.INSTANCE.deserialize(
                replacing(block().get(WITNESSES).get(0), "a2048100048101")).getTransactionWitness().get(0);
        assertThat(repeatedKey.getDatums()).hasSize(1);
        assertThat(repeatedKey.getDatums().get(0).getCbor()).isEqualTo("01");

        // {[0, 0]: [1, units], [1, 1]: [2, units], [0, 0]: [1800, units]}, the second [0, 0] encoded non-minimally
        String map = "a3" + "820000" + "8201820a14" + "820101" + "8202820a14" + "82180000" + "821800820a14";
        Witnesses redeemers = BlockSerializer.INSTANCE.deserialize(
                replacing(block().get(WITNESSES).get(0), "a105" + map)).getTransactionWitness().get(0);
        assertThat(redeemers.getRedeemers()).hasSize(2);
        assertThat(redeemers.getRedeemers().get(0).getIndex()).isEqualTo(0);
        assertThat(redeemers.getRedeemers().get(0).getData().getCbor()).isEqualTo("1800");
        assertThat(redeemers.getRedeemers().get(0).getData().getHash())
                .isEqualTo(blake2b256(HexUtil.decodeHexString("1800")));
        assertThat(redeemers.getRedeemers().get(1).getIndex()).isEqualTo(1);
    }

    /** Transaction ids are the hash of each body as received. */
    @Test
    void transactionHashComesFromTheBody() {
        List<CborSpan> bodies = block().get(BODIES).items();
        Block block = BlockSerializer.INSTANCE.deserialize(FIXTURE);
        for (int i = 0; i < bodies.size(); i++) {
            assertThat(block.getTransactionBodies().get(i).getTxHash()).isEqualTo(blake2b256(bodies.get(i).bytes()));
        }
    }

    // ---- Auxiliary data CBOR ----

    @Test
    void auxiliaryCborIsExposedWhenItMatchesTheBodysHash() {
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        Block block = BlockSerializer.INSTANCE.deserialize(FIXTURE);
        List<Map.Entry<CborSpan, CborSpan>> entries = block().get(AUXILIARY).entries();
        assertThat(block.getAuxiliaryDataMap().get(1).getCbor()).isEqualTo(hex(entries.get(0).getValue()));
        assertThat(block.getAuxiliaryDataMap().get(2).getCbor()).isEqualTo(hex(entries.get(1).getValue()));
        assertThat(block.getTransactionBodies().get(1).getAuxiliaryDataHash())
                .isEqualTo(blake2b256(entries.get(0).getValue().bytes()));
    }

    @Test
    void auxiliaryCborIsHiddenOnAHashMismatch() {
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        // Entries swapped: each is now checked against the other transaction's hash.
        List<Map.Entry<CborSpan, CborSpan>> entries = block().get(AUXILIARY).entries();
        byte[] swapped = CborSpan.of(FIXTURE).replacing(List.of(entries.get(0).getKey(), entries.get(1).getKey()),
                List.of(HexUtil.decodeHexString("02"), HexUtil.decodeHexString("01")));
        Block mismatched = BlockSerializer.INSTANCE.deserialize(swapped);
        assertThat(mismatched.getAuxiliaryDataMap().get(1).getCbor()).isNull();
        assertThat(mismatched.getAuxiliaryDataMap().get(2).getCbor()).isNull();
        assertThat(mismatched.getAuxiliaryDataMap().get(1).getMetadataCbor()).isNotNull();
    }

    /** ADR 0003 D8 says "only if it matches": a body with no auxiliary data hash has nothing to match, so it shows. */
    @Test
    void auxiliaryCborIsExposedWhenTheBodyHasNoHash() {
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        Map.Entry<CborSpan, CborSpan> entry = block().get(AUXILIARY).entries().get(0);
        Block block = BlockSerializer.INSTANCE.deserialize(replacing(entry.getKey(), "00"));
        assertThat(block.getTransactionBodies().get(0).getAuxiliaryDataHash()).isNull();
        assertThat(block.getAuxiliaryDataMap().get(0).getCbor()).isEqualTo(hex(entry.getValue()));
    }

    // ---- YaciConfig ----

    @Test
    void noCborIsReturnedByDefault() {
        YaciConfig.INSTANCE.setReturnBlockCbor(false);
        YaciConfig.INSTANCE.setReturnTxBodyCbor(false);
        YaciConfig.INSTANCE.setReturnFullTxCbor(false);
        assertCbor(BlockSerializer.INSTANCE.deserialize(FIXTURE), false, false, false);
    }

    @Test
    void returnBlockCborSetsOnlyTheBlock() {
        YaciConfig.INSTANCE.setReturnBlockCbor(true);
        YaciConfig.INSTANCE.setReturnTxBodyCbor(false);
        YaciConfig.INSTANCE.setReturnFullTxCbor(false);
        assertCbor(BlockSerializer.INSTANCE.deserialize(FIXTURE), true, false, false);
    }

    @Test
    void returnTxBodyCborSetsOnlyTheBodies() {
        YaciConfig.INSTANCE.setReturnBlockCbor(false);
        YaciConfig.INSTANCE.setReturnTxBodyCbor(true);
        YaciConfig.INSTANCE.setReturnFullTxCbor(false);
        assertCbor(BlockSerializer.INSTANCE.deserialize(FIXTURE), false, true, false);
    }

    @Test
    void returnFullTxCborSetsBodiesWitnessesAndAuxiliaryData() {
        YaciConfig.INSTANCE.setReturnBlockCbor(false);
        YaciConfig.INSTANCE.setReturnTxBodyCbor(false);
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        assertCbor(BlockSerializer.INSTANCE.deserialize(FIXTURE), false, true, true);
    }

    private static void assertCbor(Block block, boolean blockCbor, boolean bodies, boolean witnessesAndAux) {
        assertThat(block.getCbor()).isEqualTo(blockCbor ? HexUtil.encodeHexString(FIXTURE) : null);
        for (int i = 0; i < 3; i++) {
            assertThat(block.getTransactionBodies().get(i).getCbor())
                    .isEqualTo(bodies ? hex(block().get(BODIES).get(i)) : null);
            assertThat(block.getTransactionWitness().get(i).getCbor())
                    .isEqualTo(witnessesAndAux ? hex(block().get(WITNESSES).get(i)) : null);
        }
        assertThat(block.getAuxiliaryDataMap().get(1).getCbor())
                .isEqualTo(witnessesAndAux ? hex(block().get(AUXILIARY).entries().get(0).getValue()) : null);
    }

    // ---- HeaderBody.blockHash ----

    /** On a minimally encoded header, the re-encoding and the received bytes agree. */
    @Test
    void blockHashOfAMinimalHeaderIsTheHashOfItsBytes() {
        assertThat(BlockSerializer.INSTANCE.deserialize(FIXTURE).getHeader().getHeaderBody().getBlockHash())
                .isEqualTo("1cf993e9b77373d0f78f9cfda42717d0f901d1d8d6569436f79f45efe3c7d248")
                .isEqualTo(blake2b256(block().get(HEADER).bytes()));
    }

    /**
     * Correction made at ADR 0003 M2, with a release note. The block number is re-encoded from {@code 1a0004774b} to
     * {@code 1b000000000004774b}. Yaci hashed a re-encoding of the header and so reported the fixture's own hash,
     * {@code 1cf993e9...d248}. Haskell keeps a header's original bytes and hashes those ({@code MemoBytes},
     * {@code getMemoSafeHash}), giving this value. Both sync paths read it from the bytes received.
     */
    @Test
    void correctionM2_blockHashIsOfTheBytesReceivedNotOfAReEncoding() {
        String source = HexUtil.encodeHexString(FIXTURE);
        assertThat(source).containsOnlyOnce("1a0004774b");
        byte[] bytes = HexUtil.decodeHexString(source.replace("1a0004774b", "1b000000000004774b"));
        String expected = "73ee9b5ef0cffd764ddaea205efde71998f1e9b0bbc635e81c1379ce9ddd03ae";

        Block block = BlockSerializer.INSTANCE.deserialize(bytes);

        assertThat(block.getHeader().getHeaderBody().getBlockNumber()).isEqualTo(BLOCK_NUMBER);
        assertThat(block.getHeader().getHeaderBody().getBlockHash()).isEqualTo(expected);
        byte[] header = CborSpan.of(bytes).get(1).get(HEADER).bytes();
        assertThat(blake2b256(header)).isEqualTo(expected);
        // As chain sync receives it: a byte string holding the header, read by the same mapper.
        assertThat(BlockHeaderSerializer.INSTANCE.deserializeDI(new ByteString(header)).getHeaderBody().getBlockHash())
                .isEqualTo(expected);
        assertThat(blockHeaderThroughChainSync(header).getHeaderBody().getBlockHash()).isEqualTo(expected);
    }

    /** Agrees with what block fetch gives on a real header, so chain sync's points still match. */
    @Test
    void chainSyncAndBlockFetchGiveTheSameBlockHash() {
        BlockHeader fetched = BlockSerializer.INSTANCE.deserialize(FIXTURE).getHeader();
        assertThat(blockHeaderThroughChainSync(block().get(HEADER).bytes())).usingRecursiveComparison()
                .isEqualTo(fetched);
    }

    /** A caller holding only a decoded array has no original bytes, so its encoding is what is hashed. */
    @Test
    void headerFromADecodedArrayHashesItsEncoding() {
        Array tree = (Array) CborSerializationUtil.deserializeOne(block().get(HEADER).bytes());
        assertThat(BlockHeaderSerializer.INSTANCE.getBlockHeaderFromHeaderArray(tree)).usingRecursiveComparison()
                .isEqualTo(BlockSerializer.INSTANCE.deserialize(FIXTURE).getHeader());
    }

    /** Both header layouts map every field, with the numbers as the old reads gave them: low bits, bignums included. */
    @Test
    void headerNumbersAreItsLowBits() {
        CborSpan body = block().get(HEADER).get(0);
        byte[] bytes = CborSpan.of(FIXTURE).replacing(List.of(body.get(0), body.get(8).get(1), body.get(8).get(2)),
                List.of(HexUtil.decodeHexString("c249010000000000000005"),
                        HexUtil.decodeHexString("1b0000000100000007"), HexUtil.decodeHexString("d81808")));
        HeaderBody parsed = BlockSerializer.INSTANCE.deserialize(bytes).getHeader().getHeaderBody();
        assertThat(parsed.getBlockNumber()).isEqualTo(5);
        assertThat(parsed.getOperationalCert().getSequenceNumber()).isEqualTo(7);
        assertThat(parsed.getOperationalCert().getKesPeriod()).isEqualTo(8);
    }

    @Test
    void flatHeaderFillsItsTwoVrfResults() {
        CborSpan vrf = block().get(HEADER).get(0).get(5);
        HeaderBody parsed = BlockHeaderSerializer.INSTANCE.deserializeDI(
                new ByteString(HexUtil.decodeHexString(flatHeader()))).getHeaderBody();
        assertThat(parsed.getNonceVrf().get_1()).isEqualTo(HexUtil.encodeHexString(vrf.get(0).byteString()));
        assertThat(parsed.getLeaderVrf().get_2()).isEqualTo(HexUtil.encodeHexString(vrf.get(1).byteString()));
        assertThat(parsed.getVrfResult()).isNull();
        assertThat(parsed.getBlockNumber()).isEqualTo(BLOCK_NUMBER);
    }

    /** An era index past int fails as it did, whichever way its bits read. */
    @Test
    void eraIndexPastIntFailsTheAgentsRead() {
        for (String index : new String[]{"1a80000000", "1b0000000100000006", "1bffffffffffffffff"}) {
            assertThatThrownBy(() -> EraUtil.getEraValue(HexUtil.decodeHexString("82" + index + "80")))
                    .as(index).isInstanceOf(ArithmeticException.class);
        }
    }

    // ---- Helpers ----

    private static BlockParseRuntimeException parsingError(byte[] bytes) {
        assertThatThrownBy(() -> BlockSerializer.INSTANCE.deserialize(bytes))
                .isInstanceOf(com.bloxbean.cardano.yaci.core.exception.CborRuntimeException.class);
        AgentResult result = throughAgent(bytes);
        assertThat(result.blocks).isEmpty();
        assertThat(result.errors).hasSize(1);
        assertThat(result.errors.get(0).getBlockCbor()).isEqualTo(bytes);
        return result.errors.get(0);
    }

    /** The fixture's header in the flat layout, as to Alonzo: two VRF results, its other records in line. */
    private static String flatHeader() {
        CborSpan header = block().get(HEADER);
        CborSpan body = header.get(0);
        StringBuilder flat = new StringBuilder("8f");
        for (int i = 0; i < 5; i++) flat.append(hex(body.get(i)));
        flat.append(hex(body.get(5))).append(hex(body.get(5))).append(hex(body.get(6))).append(hex(body.get(7)));
        body.get(8).items().forEach(item -> flat.append(hex(item)));
        body.get(9).items().forEach(item -> flat.append(hex(item)));
        return "82" + flat + hex(header.get(1));
    }

    /** A definite array of fewer than 23 items, with an empty array added. */
    private static String withExtraItem(CborSpan array) {
        String encoded = hex(array);
        return String.format("%02x", array.bytes()[0] + 1 & 0xff) + encoded.substring(2) + "80";
    }

    private static BlockHeader blockHeaderThroughChainSync(byte[] header) {
        String wrapped = HexUtil.encodeHexString(CborSerializationUtil.serialize(new ByteString(header)));
        byte[] message = HexUtil.decodeHexString("8302" + "8206" + "d818" + wrapped
                + "8282" + "01" + "5820" + "00".repeat(32) + "01");
        return RollForwardSerializer.INSTANCE.deserialize(message).getBlockHeader();
    }

    private static byte[] withEra(int era) {
        byte[] bytes = FIXTURE.clone();
        assertThat(bytes[1]).isEqualTo((byte) 6);
        bytes[1] = (byte) era;
        return bytes;
    }

    private static CborSpan block() {
        return CborSpan.of(FIXTURE).get(1);
    }

    private static byte[] replacing(CborSpan target, String replacement) {
        return CborSpan.of(FIXTURE).replacing(List.of(target), List.of(HexUtil.decodeHexString(replacement)));
    }

    private static CborSpan field(CborSpan map, long key) {
        return map.field(key).orElseThrow();
    }

    private static String hex(CborSpan span) {
        return HexUtil.encodeHexString(span.bytes());
    }

    private static String blake2b256(byte[] bytes) {
        return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(bytes));
    }

    private static AgentResult throughAgent(byte[] bytes) {
        BlockfetchAgent agent = new BlockfetchAgent();
        AgentResult result = listen(agent);
        agent.processResponse(new MsgBlock(bytes));
        return result;
    }

    private static AgentResult listen(BlockfetchAgent agent) {
        AgentResult result = new AgentResult();
        agent.addListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) { result.blocks.add(block); }
            @Override
            public void onParsingError(BlockParseRuntimeException error) { result.errors.add(error); }
        });
        return result;
    }

    private static final class AgentResult {
        final List<Block> blocks = new ArrayList<>();
        final List<BlockParseRuntimeException> errors = new ArrayList<>();
    }
}
