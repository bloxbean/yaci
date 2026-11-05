package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.CborException;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Datum;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.model.serializers.util.WitnessUtil;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.MsgBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2c.LocalChainSyncState;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * Pins what block parsing does today, before ADR 0003 moves any of it onto the shared reader (D8, I11, I12).
 * Inputs are built with CCL's {@link CborSpan} and expected hashes with CCL's Blake2b, so these tests do not rest on
 * the code they pin. Fixture {@code preprod292683}: Babbage envelope (era 6), three transactions, one datum and one
 * redeemer in witness 0, native scripts in witnesses 1 and 2, auxiliary data for transactions 1 and 2, no invalid
 * transactions.
 * Tests named {@code knownDifference} and {@code correction} pin values ADR 0003 decides to change.
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

    /** Table row 1 (I12): era 6 changed to 9. RawBlock.of rejects this; Yaci must keep delivering it. */
    @Test
    void unknownEraIndexGivesAPopulatedBlockWithNullEra() throws Exception {
        for (int era : new int[]{8, 9, 23}) {
            for (Block block : SyncDataIsolationTest.throughBothSyncPaths(withEra(era))) {
                assertThat(block.getEra()).as("era index %d", era).isNull();
                assertThat(block.getTransactionBodies()).hasSize(3);
                assertThat(block.getTransactionWitness()).hasSize(3);
                assertThat(block.getAuxiliaryDataMap()).containsOnlyKeys(1, 2);
                assertThat(block.getHeader().getHeaderBody().getBlockNumber()).isEqualTo(BLOCK_NUMBER);
            }
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

    /** Table row 3. Known difference awaiting Q6: under ADR 0003 this raises onParsingError. */
    @Test
    void knownDifferenceQ6_emptyWitnessArrayIsDeliveredWithNoCountCheck() {
        Block block = BlockSerializer.INSTANCE.deserialize(replacing(block().get(WITNESSES), "80"));
        assertThat(block.getEra()).isEqualTo(Era.Babbage);
        assertThat(block.getTransactionBodies()).hasSize(3);
        assertThat(block.getTransactionWitness()).isEmpty();
    }

    /** Table row 4. Known difference awaiting Q6: under ADR 0003 this raises onParsingError. */
    @Test
    void knownDifferenceQ6_outOfRangeInvalidIndexIsDeliveredWithNoCheck() {
        Block block = BlockSerializer.INSTANCE.deserialize(replacing(block().get(INVALID), "8103"));
        assertThat(block.getTransactionBodies()).hasSize(3);
        assertThat(block.getInvalidTransactions()).containsExactly(3);
    }

    /** Five items with an empty list give an empty list; four items, as to Mary, leave it null. */
    @Test
    void invalidTransactionsAreEmptyWhenPresentAndNullWhenAbsent() {
        assertThat(BlockSerializer.INSTANCE.deserialize(FIXTURE).getInvalidTransactions()).isEmpty();
        CborSpan block = block();
        byte[] fourItems = HexUtil.decodeHexString("820684" + hex(block.get(HEADER)) + hex(block.get(BODIES))
                + hex(block.get(WITNESSES)) + hex(block.get(AUXILIARY)));
        assertThat(BlockSerializer.INSTANCE.deserialize(fourItems).getInvalidTransactions()).isNull();
    }

    // ---- I12: a block that does not fit the newest layout ----

    /** Dijkstra's [header, block_body] fails, and block fetch reports it with the number read from the header. */
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

    // ---- Count mismatches between parsed and raw items ----

    @Test
    void witnessCountMismatchLogsAndKeepsParsedWitnesses() throws Exception {
        Block healthy = BlockSerializer.INSTANCE.deserialize(FIXTURE);
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        try (LogCapture logs = new LogCapture();
             MockedStatic<WitnessUtil> mock = mockStatic(WitnessUtil.class, CALLS_REAL_METHODS)) {
            mock.when(() -> WitnessUtil.getWitnessRawData(any(byte[].class))).thenReturn(Collections.emptyList());
            Block block = BlockSerializer.INSTANCE.deserialize(FIXTURE);
            assertThat(block.getTransactionWitness()).hasSameSizeAs(healthy.getTransactionWitness());
            assertThat(block.getTransactionWitness().get(0).getCbor()).isNull();
            assertThat(logs.at(Level.ERROR)).anyMatch(m -> m.contains("witness set count mismatch"));
        }
    }

    @Test
    void witnessExtractionFailureLogsAndKeepsParsedWitnesses() throws Exception {
        try (LogCapture logs = new LogCapture();
             MockedStatic<WitnessUtil> mock = mockStatic(WitnessUtil.class, CALLS_REAL_METHODS)) {
            mock.when(() -> WitnessUtil.getWitnessRawData(any(byte[].class))).thenThrow(new CborException("injected"));
            assertThat(BlockSerializer.INSTANCE.deserialize(FIXTURE).getTransactionWitness()).hasSize(3);
            assertThat(logs.at(Level.ERROR)).anyMatch(m -> m.contains("Extraction of witness bytes"));
        }
    }

    @Test
    void datumAndRedeemerCountMismatchesLogAndKeepParsedValues() throws Exception {
        Witnesses healthy = BlockSerializer.INSTANCE.deserialize(FIXTURE).getTransactionWitness().get(0);
        try (LogCapture logs = new LogCapture();
             MockedStatic<WitnessUtil> mock = mockStatic(WitnessUtil.class, CALLS_REAL_METHODS)) {
            mock.when(() -> WitnessUtil.getWitnessFields(any(byte[].class))).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                var fields = (Map<BigInteger, byte[]>) invocation.callRealMethod();
                fields.put(BigInteger.valueOf(4), HexUtil.decodeHexString("820000"));
                fields.put(BigInteger.valueOf(5), HexUtil.decodeHexString("80"));
                return fields;
            });
            Witnesses witness = BlockSerializer.INSTANCE.deserialize(FIXTURE).getTransactionWitness().get(0);
            assertThat(witness.getDatums()).hasSameSizeAs(healthy.getDatums());
            assertThat(witness.getRedeemers()).hasSameSizeAs(healthy.getRedeemers());
            assertThat(logs.at(Level.WARN)).anyMatch(m -> m.contains("Datum count mismatch"));
            assertThat(logs.at(Level.WARN)).anyMatch(m -> m.contains("Redeemer count mismatch"));
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
    void auxiliaryCborIsHiddenOnAHashMismatchOrAnIndexPastTheBodies() {
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        // Entries swapped: each is now checked against the other transaction's hash.
        List<Map.Entry<CborSpan, CborSpan>> entries = block().get(AUXILIARY).entries();
        byte[] swapped = CborSpan.of(FIXTURE).replacing(List.of(entries.get(0).getKey(), entries.get(1).getKey()),
                List.of(HexUtil.decodeHexString("02"), HexUtil.decodeHexString("01")));
        Block mismatched = BlockSerializer.INSTANCE.deserialize(swapped);
        assertThat(mismatched.getAuxiliaryDataMap().get(1).getCbor()).isNull();
        assertThat(mismatched.getAuxiliaryDataMap().get(2).getCbor()).isNull();
        assertThat(mismatched.getAuxiliaryDataMap().get(1).getMetadataCbor()).isNotNull();

        Block pastBodies = BlockSerializer.INSTANCE.deserialize(replacing(entries.get(0).getKey(), "09"));
        assertThat(pastBodies.getAuxiliaryDataMap().get(9).getCbor()).isNull();
        assertThat(pastBodies.getAuxiliaryDataMap().get(9).getMetadataCbor()).isNotNull();
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
     * Correction to apply at ADR 0003 M2, with a release note. The block number is re-encoded from {@code 1a0004774b}
     * to {@code 1b000000000004774b}. Yaci hashes a re-encoding of the header and so reports the old hash; Haskell keeps
     * a header's original bytes and hashes those ({@code MemoBytes}, {@code getMemoSafeHash}), giving the second value.
     */
    @Test
    void correctionM2_blockHashIsOfAReEncodingNotOfTheBytesReceived() {
        String source = HexUtil.encodeHexString(FIXTURE);
        assertThat(source).containsOnlyOnce("1a0004774b");
        byte[] bytes = HexUtil.decodeHexString(source.replace("1a0004774b", "1b000000000004774b"));

        Block block = BlockSerializer.INSTANCE.deserialize(bytes);

        assertThat(block.getHeader().getHeaderBody().getBlockNumber()).isEqualTo(BLOCK_NUMBER);
        assertThat(block.getHeader().getHeaderBody().getBlockHash())
                .isEqualTo("1cf993e9b77373d0f78f9cfda42717d0f901d1d8d6569436f79f45efe3c7d248");
        assertThat(blake2b256(CborSpan.of(bytes).get(1).get(HEADER).bytes()))
                .isEqualTo("73ee9b5ef0cffd764ddaea205efde71998f1e9b0bbc635e81c1379ce9ddd03ae");
    }

    // ---- Helpers ----

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

    /** Collects BlockSerializer's log events while open. */
    private static final class LogCapture extends AppenderSkeleton implements AutoCloseable {
        private final Logger logger = Logger.getLogger(BlockSerializer.class);
        private final List<LoggingEvent> events = new ArrayList<>();

        LogCapture() {
            logger.addAppender(this);
        }

        List<String> at(Level level) {
            return events.stream().filter(e -> e.getLevel().equals(level))
                    .map(LoggingEvent::getRenderedMessage).toList();
        }

        @Override
        protected void append(LoggingEvent event) {
            events.add(event);
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }

        @Override
        public void close() {
            logger.removeAppender(this);
        }
    }
}
