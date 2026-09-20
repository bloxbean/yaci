package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.MajorType;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Datum;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.model.serializers.util.WitnessUtil;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.MsgBlock;
import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/** Offline regressions for exact source bytes and nonblocking optional witness enrichment. */
class RawWitnessExtractionTest {
    private static final String DATA = "d8799f18005f41ff4100ffff";
    private static final String REDEEMER = "840000" + DATA + "820a14";
    private static final String WITNESS = "a204d9010281" + DATA + "0581" + REDEEMER;
    private final boolean originalFullTx = YaciConfig.INSTANCE.isReturnFullTxCbor();
    private final boolean originalFullBlock = YaciConfig.INSTANCE.isReturnBlockCbor();

    /** Restore singleton configuration so these cases do not change other tests' behavior. */
    @AfterEach
    void restoreConfig() {
        YaciConfig.INSTANCE.setReturnFullTxCbor(originalFullTx);
        YaciConfig.INSTANCE.setReturnBlockCbor(originalFullBlock);
    }

    /** Replay every named issue block through both sync paths with independently recorded byte offsets/hashes. */
    @Test
    void namedPreprodBlocksRetainEverySourceDatumAndRedeemerIncludingLaterWitnesses() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode fixtures = mapper.readTree(getClass().getResourceAsStream("/block/raw-witness-expectations.json"));
        for (JsonNode fixture : fixtures) {
            byte[] bytes = CborLoader.getHexBytes("block/preprod" + fixture.get("block").asInt() + ".txt");
            for (boolean fullTx : new boolean[]{false, true}) {
                for (boolean fullBlock : new boolean[]{false, true}) {
                    YaciConfig.INSTANCE.setReturnFullTxCbor(fullTx);
                    YaciConfig.INSTANCE.setReturnBlockCbor(fullBlock);
                    for (Block block : SyncDataIsolationTest.throughBothSyncPaths(bytes)) {
                        assertThat(block.getHeader().getHeaderBody().getBlockNumber())
                                .isEqualTo(fixture.get("block").asLong());
                        assertThat(block.getHeader().getHeaderBody().getBlockHash())
                                .isEqualTo(fixture.get("blockHash").asText());
                        assertThat(block.getTransactionBodies()).hasSize(fixture.get("transactions").asInt());
                        assertThat(block.getTransactionWitness()).hasSize(fixture.get("witnesses").asInt());
                        assertThat(fixture.get("transactionHashes").size())
                                .isEqualTo(block.getTransactionBodies().size());
                        for (int i = 0; i < block.getTransactionBodies().size(); i++) {
                            assertThat(block.getTransactionBodies().get(i).getTxHash())
                                    .as("block %s transaction %s hash", fixture.get("block"), i)
                                    .isEqualTo(fixture.get("transactionHashes").get(i).asText());
                        }
                        assertThat(fixture.get("witnessCounts").size())
                                .isEqualTo(block.getTransactionWitness().size());
                        int dataCount = 0;
                        for (int i = 0; i < block.getTransactionWitness().size(); i++) {
                            Witnesses witness = block.getTransactionWitness().get(i);
                            JsonNode counts = fixture.get("witnessCounts").get(i);
                            dataCount += witness.getDatums().size() + witness.getRedeemers().size();
                            assertThat(witness.getDatums()).as("witness %s datums", i)
                                    .hasSize(counts.get("datums").asInt());
                            assertThat(witness.getRedeemers()).as("witness %s redeemers", i)
                                    .hasSize(counts.get("redeemers").asInt());
                        }
                        assertThat(fixture.get("values").size()).isEqualTo(dataCount);
                        for (JsonNode value : fixture.get("values")) {
                            Witnesses witness = block.getTransactionWitness().get(value.get("witness").asInt());
                            int index = value.get("index").asInt();
                            Datum actual = value.get("kind").asText().equals("datum")
                                    ? witness.getDatums().get(index) : witness.getRedeemers().get(index).getData();
                            int offset = value.get("offset").asInt();
                            assertThat(actual.getCbor()).isEqualTo(HexUtil.encodeHexString(Arrays.copyOfRange(
                                    bytes, offset, offset + value.get("length").asInt())));
                            assertThat(actual.getHash()).isEqualTo(value.get("hash").asText());
                            assertThat(actual.getParseError()).isNull();
                            if (value.get("kind").asText().equals("redeemer")) {
                                var redeemer = witness.getRedeemers().get(index);
                                assertThat(redeemer.getTag().name().toLowerCase())
                                        .isEqualTo(value.get("purpose").asText());
                                assertThat(redeemer.getIndex()).isEqualTo(value.get("redeemerIndex").asInt());
                                assertThat(redeemer.getExUnits().getMem())
                                        .isEqualTo(value.get("mem").bigIntegerValue());
                                assertThat(redeemer.getExUnits().getSteps())
                                        .isEqualTo(value.get("steps").bigIntegerValue());
                            }
                        }
                        assertThat(block.getCbor()).isEqualTo(fullBlock ? HexUtil.encodeHexString(bytes) : null);
                        for (int i = 0; i < block.getTransactionWitness().size(); i++) {
                            String raw = HexUtil.encodeHexString(CborSlice.arrayItem(bytes, 1, 2, i).bytes());
                            assertThat(block.getTransactionWitness().get(i).getCbor()).isEqualTo(fullTx ? raw : null);
                        }
                    }
                }
            }
        }
    }

    /** Exercise the common witness position in every era, with datum/redeemer forms only where applicable. */
    @Test
    void allShelleyOnwardErasKeepSourceBytesAcrossWitnessContainerFormats() throws Exception {
        for (int era = 2; era <= 7; era++) {
            for (String mapHeader : new String[]{"a2", "b802", "b90002", "ba00000002",
                    "bb0000000000000002", "bf"}) {
                for (boolean mapRedeemers : new boolean[]{false, true}) {
                    if (mapRedeemers && era < 7) continue;
                    String redeemers = mapRedeemers ? "bf82000082" + DATA + "820a14ff" : "9f" + REDEEMER + "ff";
                    // witness = {4: 258([datum x 30]), 5: redeemers}; pre-Alonzo uses a vkey witness only.
                    String witness = era < 5 ? "a10081824040" : mapHeader + "04d90102981e" + DATA.repeat(30)
                            + "05" + redeemers + (mapHeader.equals("bf") ? "ff" : "");
                    byte[] bytes = withWitnesses(era, witness, witness);
                    for (Block block : SyncDataIsolationTest.throughBothSyncPaths(bytes)) {
                        assertThat(block.getTransactionWitness()).hasSize(2);
                        for (Witnesses actual : block.getTransactionWitness()) {
                            if (era < 5) {
                                assertThat(actual.getVkeyWitnesses()).hasSize(1);
                                assertThat(actual.getDatums()).isEmpty();
                                assertThat(actual.getRedeemers()).isEmpty();
                            } else {
                                assertThat(actual.getDatums()).hasSize(30);
                                for (Datum datum : actual.getDatums()) assertSourceData(datum);
                                assertThat(actual.getRedeemers()).hasSize(1);
                                assertSourceData(actual.getRedeemers().get(0).getData());
                                // Conway's public whole-redeemer CBOR retains its legacy synthesized encoding.
                                String expected = mapRedeemers ? WitnessesSerializer.INSTANCE.deserialize(hex(witness))
                                        .getRedeemers().get(0).getCbor() : REDEEMER;
                                assertThat(actual.getRedeemers().get(0).getCbor()).isEqualTo(expected);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Tagged redeemer containers must keep source order even when Conway map keys are not sorted. */
    @Test
    void taggedRedeemerContainersPreserveOrderingAndDataAssociation() throws Exception {
        for (boolean map : new boolean[]{false, true}) {
            // Index 9 precedes index 1 intentionally; the associated data values differ.
            String redeemers = map ? "d90102a282000982" + DATA + "820a1482010182182a820b15"
                    : "d9010282840009" + DATA + "820a14840101182a820b15";
            Block block = BlockSerializer.INSTANCE.deserialize(withWitnesses(7, "a105" + redeemers));
            var parsed = block.getTransactionWitness().get(0).getRedeemers();
            assertThat(parsed).hasSize(2);
            assertThat(parsed.get(0).getIndex()).isEqualTo(9);
            assertSourceData(parsed.get(0).getData());
            assertThat(parsed.get(1).getIndex()).isEqualTo(1);
            assertThat(parsed.get(1).getData().getCbor()).isEqualTo("182a");
            assertThat(parsed.get(1).getData().getHash()).isEqualTo(Datum.cborToHash(hex("182a")));
            assertThat(parsed.get(1).getExUnits().getMem()).isEqualTo(11);
            assertThat(parsed.get(1).getExUnits().getSteps()).isEqualTo(21);
        }
    }

    /** Empty witness arrays and empty datum/redeemer collections need no enrichment in any era. */
    @Test
    void emptyCollectionsRemainEmpty() throws Exception {
        for (int era = 2; era <= 7; era++) {
            assertThat(BlockSerializer.INSTANCE.deserialize(withWitnesses(era)).getTransactionWitness()).isEmpty();
            for (String witness : era < 5 ? new String[]{"a0"}
                    : new String[]{"a0", "a204800580", "bf04d901029fff05bfffff"}) {
                Witnesses result = BlockSerializer.INSTANCE.deserialize(withWitnesses(era, witness))
                        .getTransactionWitness().get(0);
                assertThat(result.getDatums()).isEmpty();
                assertThat(result.getRedeemers()).isEmpty();
            }
        }
    }

    /** Skip signatures-only witnesses and share one field scan for native scripts, datums and redeemers. */
    @Test
    void witnessFieldsAreExtractedOnceOnlyWhenNeeded() throws Exception {
        String signatures = "a10081824040";
        String nativeScript = "01818200581c" + "00".repeat(28);
        String signaturesAndScript = "a20081824040" + nativeScript;
        String scriptAndData = "a3" + WITNESS.substring(2) + nativeScript;
        byte[] bytes = withWitnesses(7, signatures, signaturesAndScript, scriptAndData);
        AtomicInteger calls = new AtomicInteger();
        try (MockedStatic<WitnessUtil> mock = mockStatic(WitnessUtil.class, invocation -> {
            if (invocation.getMethod().getName().equals("getWitnessFields")) {
                int index = calls.getAndIncrement();
                assertThat((byte[]) invocation.getArgument(0))
                        .isEqualTo(hex(index == 0 ? signaturesAndScript : scriptAndData));
            }
            return invocation.callRealMethod();
        })) {
            Block block = BlockSerializer.INSTANCE.deserialize(bytes);
            assertThat(calls.get()).isEqualTo(2);
            assertThat(block.getTransactionWitness().get(1)).usingRecursiveComparison()
                    .isEqualTo(WitnessesSerializer.INSTANCE.deserialize(hex(signaturesAndScript)));
            Witnesses combined = block.getTransactionWitness().get(2);
            assertThat(combined.getNativeScripts().get(0).getHash())
                    .isEqualTo(block.getTransactionWitness().get(1).getNativeScripts().get(0).getHash())
                    .isNotNull();
            assertSourceData(combined.getDatums().get(0));
            assertSourceData(combined.getRedeemers().get(0).getData());
        }
    }

    /** A complete extraction failure or count mismatch must return the initial parsed witnesses unchanged. */
    @Test
    void unavailableRawWitnessesNeverPreventBlockDelivery() throws Exception {
        byte[] bytes = withWitnesses(7, WITNESS, WITNESS);
        for (boolean throwFailure : new boolean[]{false, true}) {
            for (boolean full : new boolean[]{false, true}) {
                YaciConfig.INSTANCE.setReturnFullTxCbor(full);
                try (MockedStatic<WitnessUtil> mock = mockStatic(WitnessUtil.class, CALLS_REAL_METHODS)) {
                    if (throwFailure) {
                        mock.when(() -> WitnessUtil.getWitnessRawData(any(byte[].class)))
                                .thenThrow(new CborException("Injected raw witness failure"));
                    } else {
                        mock.when(() -> WitnessUtil.getWitnessRawData(any(byte[].class)))
                                .thenReturn(Collections.emptyList());
                    }
                    Block block = BlockSerializer.INSTANCE.deserialize(bytes);
                    assertThat(block.getTransactionWitness()).hasSize(2);
                    for (Witnesses witness : block.getTransactionWitness()) {
                        assertThat(witness).usingRecursiveComparison()
                                .isEqualTo(WitnessesSerializer.INSTANCE.deserialize(hex(WITNESS)));
                    }
                }
            }
        }
    }

    /** Fail one witness's field extraction, then verify later witnesses and the next block are delivered. */
    @Test
    void failedWitnessEnrichmentDoesNotStopTheSyncAgent() throws Exception {
        for (int era = 2; era <= 7; era++) {
            String witness = era < 5 ? "a10081824040" : WITNESS;
            byte[] bytes = withWitnesses(era, witness, witness);
            List<Block> received = new ArrayList<>();
            List<BlockParseRuntimeException> errors = new ArrayList<>();
            BlockfetchAgent agent = new BlockfetchAgent();
            agent.addListener(new BlockfetchAgentListener() {
                /** Record successfully delivered blocks. */
                @Override
                public void blockFound(Block block) { received.add(block); }
                /** Record parse failures so an enrichment failure cannot silently become a sync failure. */
                @Override
                public void onParsingError(BlockParseRuntimeException error) { errors.add(error); }
            });
            AtomicInteger calls = new AtomicInteger();
            try (MockedStatic<WitnessUtil> mock = mockStatic(WitnessUtil.class, CALLS_REAL_METHODS)) {
                mock.when(() -> WitnessUtil.getWitnessFields(any(byte[].class))).thenAnswer(invocation -> {
                    if (calls.getAndIncrement() == 0) throw new CborException("Injected field failure");
                    return invocation.callRealMethod();
                });
                agent.processResponse(new MsgBlock(bytes));
                agent.processResponse(new MsgBlock(bytes));
            }
            assertThat(errors).isEmpty();
            assertThat(received).hasSize(2);
            assertThat(received.get(0).getTransactionWitness().get(0))
                    .usingRecursiveComparison().isEqualTo(WitnessesSerializer.INSTANCE.deserialize(hex(witness)));
            if (era >= 5) {
                assertSourceData(received.get(0).getTransactionWitness().get(1).getDatums().get(0));
                assertSourceData(received.get(1).getTransactionWitness().get(0).getDatums().get(0));
            }
        }
    }

    /** Datum failures leave redeemers available; redeemer failures leave datums and later witnesses available. */
    @Test
    void failedFieldRetainsParsedDataAndDoesNotStopSiblingEnrichment() throws Exception {
        byte[] bytes = withWitnesses(7, WITNESS, WITNESS);
        for (int key : new int[]{4, 5}) {
            for (String bad : new String[]{"81", "80", "a0", "00", "d90102", "missing"}) {
                AtomicInteger calls = new AtomicInteger();
                try (MockedStatic<WitnessUtil> mock = mockStatic(WitnessUtil.class, CALLS_REAL_METHODS)) {
                    // Obtain real fields before overriding only the first witness's optional value.
                    mock.when(() -> WitnessUtil.getWitnessFields(any(byte[].class))).thenAnswer(invocation -> {
                        @SuppressWarnings("unchecked")
                        var fields = (Map<BigInteger, byte[]>) invocation.callRealMethod();
                        if (calls.getAndIncrement() == 0) {
                            if (bad.equals("missing")) fields.remove(BigInteger.valueOf(key));
                            else fields.put(BigInteger.valueOf(key), hex(bad));
                        }
                        return fields;
                    });
                    Block block = BlockSerializer.INSTANCE.deserialize(bytes);
                    Witnesses first = block.getTransactionWitness().get(0);
                    Witnesses original = WitnessesSerializer.INSTANCE.deserialize(hex(WITNESS));
                    if (key == 4) {
                        assertThat(first.getDatums()).usingRecursiveComparison().isEqualTo(original.getDatums());
                        assertSourceData(first.getRedeemers().get(0).getData());
                    } else {
                        assertThat(first.getRedeemers()).usingRecursiveComparison().isEqualTo(original.getRedeemers());
                        assertSourceData(first.getDatums().get(0));
                    }
                    Witnesses later = block.getTransactionWitness().get(1);
                    assertSourceData(later.getDatums().get(0));
                    assertSourceData(later.getRedeemers().get(0).getData());
                }
            }
        }
    }

    /** Resolve duplicate keys in first-key order, taking the last value even for non-minimal key encodings. */
    @Test
    void duplicateRedeemerKeysUseWinningSourceBytesWithoutReordering() throws Exception {
        for (String prefix : new String[]{"a5", "b805", "d90102a5", "bf"}) {
            // A=[Spend,9], B=[Mint,9], C=[Spend,1]: A0, B0, A1, C0, B1 -> A1, B1, C0.
            // A1's key uses non-minimal array/integer lengths but equals A0 after decoding.
            String entries = "8200098200820102" + "8201098200820304"
                    + "9802180018099802" + DATA + "820a14"
                    + "82000182182b820b15" + "82010982182a820c16";
            String duplicate = "a105" + prefix + entries + (prefix.equals("bf") ? "ff" : "");
            String healthy = "a105a182000082" + DATA + "820a14";
            for (Block block : SyncDataIsolationTest.throughBothSyncPaths(withWitnesses(7, duplicate, healthy))) {
                var actual = block.getTransactionWitness().get(0).getRedeemers();
                var initial = WitnessesSerializer.INSTANCE.deserialize(hex(duplicate)).getRedeemers();
                assertThat(actual).hasSize(3);
                // Count, order, purpose/index, JSON, execution units, and whole-redeemer CBOR stay unchanged.
                assertThat(actual).usingRecursiveComparison().ignoringFields("data.cbor", "data.hash")
                        .isEqualTo(initial);
                assertSourceData(actual.get(0).getData());
                assertThat(actual.get(1).getData().getCbor()).isEqualTo("182a");
                assertThat(actual.get(1).getData().getHash()).isEqualTo(Datum.cborToHash(hex("182a")));
                assertThat(actual.get(2).getData().getCbor()).isEqualTo("182b");
                assertThat(actual.get(2).getData().getHash()).isEqualTo(Datum.cborToHash(hex("182b")));
                assertSourceData(block.getTransactionWitness().get(1).getRedeemers().get(0).getData());
            }
        }
    }

    /** Normal and recovery paths must expose the same winning redeemers when another datum cannot render. */
    @Test
    void duplicateRedeemerKeysStayIdenticalWhenADatumTriggersRecovery() throws Exception {
        // A=[Spend,9], B=[Mint,1]: A0, B0, A1 -> A1, B0. A1 uses a non-minimal key encoding.
        String entries = "8200098200820102" + "82010182182a820b15"
                + "98021800180982" + DATA + "820a14";
        String[] deepValues = {"a100".repeat(3000) + "00", "81".repeat(3000) + "00"};
        for (String prefix : new String[]{"a3", "b803", "d90102a3", "bf"}) {
            String redeemers = prefix + entries + (prefix.equals("bf") ? "ff" : "");
            String duplicate = "a105" + redeemers;
            var expected = SyncDataIsolationTest.throughBothSyncPaths(withWitnesses(7, duplicate))
                    .get(0).getTransactionWitness().get(0).getRedeemers();
            assertThat(expected).hasSize(2);
            assertSourceData(expected.get(0).getData());
            for (boolean full : new boolean[]{false, true}) {
                YaciConfig.INSTANCE.setReturnFullTxCbor(full);
                YaciConfig.INSTANCE.setReturnBlockCbor(full);
                for (String deep : deepValues) {
                    for (boolean sameWitness : new boolean[]{false, true}) {
                        // Put failed data beside the map or in a later witness; both restart block parsing.
                        String first = sameWitness ? "a205" + redeemers + "0481" + deep : duplicate;
                        String second = sameWitness ? "a0" : "a10481" + deep;
                        byte[] bytes = withWitnesses(7, first, second, WITNESS);
                        for (Block block : SyncDataIsolationTest.throughBothSyncPaths(bytes)) {
                            var actual = block.getTransactionWitness().get(0).getRedeemers();
                            assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
                            Datum failed = block.getTransactionWitness().get(sameWitness ? 0 : 1).getDatums().get(0);
                            assertThat(failed.getParseError()).isNotNull();
                            assertThat(failed.getJson()).isNull();
                            assertThat(failed.getCbor()).isEqualTo(deep);
                            assertThat(failed.getHash()).isEqualTo(Datum.cborToHash(hex(deep)));
                            assertSourceData(block.getTransactionWitness().get(2).getRedeemers().get(0).getData());
                            assertThat(block.getCbor()).isEqualTo(full ? HexUtil.encodeHexString(bytes) : null);
                            for (int i = 0; i < block.getTransactionWitness().size(); i++) {
                                String raw = HexUtil.encodeHexString(CborSlice.arrayItem(bytes, 1, 2, i).bytes());
                                assertThat(block.getTransactionWitness().get(i).getCbor()).isEqualTo(full ? raw : null);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Recovery must discard overwritten deep values and preserve a winning deep value's source bytes. */
    @Test
    void duplicateRedeemerRecoveryUsesOnlyTheLastValueWhenItsDataIsDeep() throws Exception {
        String deep = "a100".repeat(3000) + "00";
        for (boolean deepWins : new boolean[]{false, true}) {
            String firstData = deepWins ? "00" : deep;
            String lastData = deepWins ? deep : DATA;
            // First [Spend,9] key is non-minimal; its encoding and position survive the last-value update.
            String duplicate = "a105a298021800180982" + firstData + "820102"
                    + "82000982" + lastData + "820a14";
            byte[] bytes = withWitnesses(7, duplicate, WITNESS);
            for (Block block : SyncDataIsolationTest.throughBothSyncPaths(bytes)) {
                var redeemers = block.getTransactionWitness().get(0).getRedeemers();
                assertThat(redeemers).hasSize(1);
                var redeemer = redeemers.get(0);
                assertThat(redeemer.getIndex()).isEqualTo(9);
                assertThat(redeemer.getExUnits().getMem()).isEqualTo(10);
                assertThat(redeemer.getExUnits().getSteps()).isEqualTo(20);
                assertThat(redeemer.getData().getCbor()).isEqualTo(lastData);
                assertThat(redeemer.getData().getHash()).isEqualTo(Datum.cborToHash(hex(lastData)));
                if (deepWins) {
                    assertThat(redeemer.getData().getParseError()).isNotNull();
                    assertThat(redeemer.getData().getJson()).isNull();
                    assertThat(redeemer.getCbor()).isEqualTo("8418001809" + deep + "820a14");
                } else {
                    assertSourceData(redeemer.getData());
                    var expected = WitnessesSerializer.INSTANCE.deserialize(hex(
                            "a105a182000982" + DATA + "820a14")).getRedeemers().get(0);
                    assertThat(redeemer.getCbor()).isEqualTo(expected.getCbor());
                }
                assertSourceData(block.getTransactionWitness().get(1).getRedeemers().get(0).getData());
            }
        }
    }

    /** Duplicate resolution applies to map keys only; array-form redeemers retain every source entry. */
    @Test
    void arrayFormRedeemersAreNotCollapsedDuringRecovery() throws Exception {
        String arrayWitness = "a10582" + REDEEMER + "840000182a820b15";
        var expected = BlockSerializer.INSTANCE.deserialize(withWitnesses(7, arrayWitness))
                .getTransactionWitness().get(0).getRedeemers();
        assertThat(expected).hasSize(2);
        byte[] bytes = withWitnesses(7, arrayWitness, "a10481" + "a100".repeat(3000) + "00");
        for (Block block : SyncDataIsolationTest.throughBothSyncPaths(bytes)) {
            assertThat(block.getTransactionWitness().get(0).getRedeemers()).usingRecursiveComparison()
                    .isEqualTo(expected);
            assertThat(block.getTransactionWitness().get(1).getDatums().get(0).getParseError()).isNotNull();
        }
    }

    /** Replay the observed duplicate-key block and verify every parsed redeemer reaches raw-data correction. */
    @Test
    void previewDuplicateKeyBlockCompletesRawCorrection() {
        byte[] bytes = CborLoader.getHexBytes("block/preview2587542.txt");
        AtomicInteger corrected = new AtomicInteger();
        Block block;
        try (MockedStatic<WitnessUtil> mock = mockStatic(WitnessUtil.class, invocation -> {
            if (invocation.getMethod().getName().equals("getRedeemerFields")) corrected.incrementAndGet();
            return invocation.callRealMethod();
        })) {
            block = BlockSerializer.INSTANCE.deserialize(bytes);
        }
        assertThat(block.getHeader().getHeaderBody().getBlockNumber()).isEqualTo(2587542);
        assertThat(corrected.get()).isEqualTo(block.getTransactionWitness().stream()
                .mapToInt(witness -> witness.getRedeemers().size()).sum());
        var redeemers = block.getTransactionWitness().get(1).getRedeemers();
        assertThat(redeemers).hasSize(1);
        Datum datum = redeemers.get(0).getData();
        // Independent cbor2 inspection: the last [Mint,0] value's data occupies bytes [4098,4101).
        assertThat(datum.getCbor()).isEqualTo(HexUtil.encodeHexString(Arrays.copyOfRange(bytes, 4098, 4101)))
                .isEqualTo("d87a80");
        assertThat(datum.getHash()).isEqualTo("8392f0c940435c06888f9bdb8c74a95dc69f156367d6a089cf008ae05caae01e");
    }

    /** A mismatch that remains after duplicate resolution keeps parsed values and continues with later witnesses. */
    @Test
    void unresolvedMapCountMismatchRemainsNonblocking() throws Exception {
        String healthy = "a105a282000082" + DATA + "820a1482010182182a820b15";
        byte[] bytes = withWitnesses(7, healthy, healthy);
        AtomicInteger calls = new AtomicInteger();
        try (MockedStatic<WitnessUtil> mock = mockStatic(WitnessUtil.class, invocation -> {
            if (invocation.getMethod().getName().equals("getWitnessFields") && calls.getAndIncrement() == 0) {
                @SuppressWarnings("unchecked")
                var fields = (Map<BigInteger, byte[]>) invocation.callRealMethod();
                // Three raw copies of one key cannot align with the two originally parsed keys.
                fields.put(BigInteger.valueOf(5), hex("a3" + ("82000082" + DATA + "820a14").repeat(3)));
                return fields;
            }
            return invocation.callRealMethod();
        })) {
            Block block = BlockSerializer.INSTANCE.deserialize(bytes);
            assertThat(block.getTransactionWitness().get(0)).usingRecursiveComparison()
                    .isEqualTo(WitnessesSerializer.INSTANCE.deserialize(hex(healthy)));
            assertSourceData(block.getTransactionWitness().get(1).getRedeemers().get(0).getData());
        }
    }

    /** A failed individual redeemer leaves the following redeemer's source correction intact. */
    @Test
    void failedRedeemerEntryDoesNotStopLaterEntries() throws Exception {
        for (String witness : new String[]{
                "a10582" + REDEEMER + "840001" + DATA + "820a14",
                "a105a282000082" + DATA + "820a1482000182" + DATA + "820a14"}) {
            byte[] bytes = withWitnesses(7, witness);
            for (boolean malformed : new boolean[]{false, true}) {
                AtomicInteger calls = new AtomicInteger();
                try (MockedStatic<WitnessUtil> mock = mockStatic(WitnessUtil.class, invocation -> {
                    if (invocation.getMethod().getName().equals("getRedeemerFields") && calls.getAndIncrement() == 0) {
                        if (malformed) return Collections.emptyList();
                        throw new CborException("Injected redeemer entry failure");
                    }
                    return invocation.callRealMethod();
                })) {
                    Witnesses result = BlockSerializer.INSTANCE.deserialize(bytes).getTransactionWitness().get(0);
                    assertThat(result.getRedeemers().get(0)).usingRecursiveComparison().isEqualTo(
                            WitnessesSerializer.INSTANCE.deserialize(hex(witness)).getRedeemers().get(0));
                    assertSourceData(result.getRedeemers().get(1).getData());
                }
            }
        }
    }

    /** Reuse the era-specific parser fixtures and replace only their witness array with source encodings. */
    private static byte[] withWitnesses(int era, String... witnesses) throws Exception {
        CborSlice root = CborSlice.of(SyncDataIsolationTest.eraBlock(era, "a0"));
        CborSlice slot = root.items(MajorType.ARRAY).get(1).items(MajorType.ARRAY).get(2);
        // Indefinite witness array: 9f witness_0 ... witness_n ff.
        return root.replacing(Collections.singletonList(slot),
                Collections.singletonList(hex("9f" + String.join("", witnesses) + "ff")));
    }

    /** Check exact encoding and its hash; JSON remains successfully parsed. */
    private static void assertSourceData(Datum datum) {
        assertThat(datum.getCbor()).isEqualTo(DATA);
        assertThat(datum.getHash()).isEqualTo(Datum.cborToHash(hex(DATA)));
        assertThat(datum.getJson()).isNotNull();
        assertThat(datum.getParseError()).isNull();
    }

    /** Decode a literal CBOR example used by these tests. */
    private static byte[] hex(String value) { return HexUtil.decodeHexString(value); }
}
