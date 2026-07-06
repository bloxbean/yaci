package com.bloxbean.cardano.yaci.helper.listener;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.util.AuxDataExtractor;
import com.bloxbean.cardano.yaci.core.model.serializers.util.TransactionBodyExtractor;
import com.bloxbean.cardano.yaci.core.model.serializers.util.WitnessUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.Tuple;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlockFetchAgentListenerAdapterTest {

    @AfterEach
    void tearDown() {
        YaciConfig.INSTANCE.setReturnFullTxCbor(false);
    }

    @Test
    void blockFound_populatesTxCborWhenRawSegmentsExist() {
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        CapturingListener listener = new CapturingListener();
        BlockFetchAgentListenerAdapter adapter = new BlockFetchAgentListenerAdapter(listener);

        adapter.blockFound(block(List.of(txBody("tx1", null)), List.of(witness("a0")),
                Collections.emptyMap(), Collections.emptyList()));

        assertThat(listener.transactions).hasSize(1);
        assertThat(listener.transactions.get(0).getTxCbor()).isEqualTo("84a0a0f5f6");
    }

    @Test
    void blockFound_usesInvalidTransactionFlag() {
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        CapturingListener listener = new CapturingListener();
        BlockFetchAgentListenerAdapter adapter = new BlockFetchAgentListenerAdapter(listener);

        adapter.blockFound(block(List.of(txBody("tx1", null)), List.of(witness("a0")),
                Collections.emptyMap(), List.of(0)));

        assertThat(listener.transactions).hasSize(1);
        assertThat(listener.transactions.get(0).getTxCbor()).isEqualTo("84a0a0f4f6");
    }

    @Test
    void blockFound_returnsNullTxCborWhenAuxDataRawBytesMissing() {
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        CapturingListener listener = new CapturingListener();
        BlockFetchAgentListenerAdapter adapter = new BlockFetchAgentListenerAdapter(listener);
        Map<Integer, AuxData> auxData = new LinkedHashMap<>();
        auxData.put(0, AuxData.builder().metadataCbor("a0").build());

        adapter.blockFound(block(List.of(txBody("tx1", null)), List.of(witness("a0")),
                auxData, Collections.emptyList()));

        assertThat(listener.transactions).hasSize(1);
        assertThat(listener.transactions.get(0).getTxCbor()).isNull();
    }

    @Test
    void blockFound_skipsWholeBlockWhenRequiredSegmentMissing() {
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        CapturingListener listener = new CapturingListener();
        BlockFetchAgentListenerAdapter adapter = new BlockFetchAgentListenerAdapter(listener);

        adapter.blockFound(block(
                List.of(txBody("tx1", null), txBody("tx2", null)),
                List.of(witness("a0"), witness(null)),
                Collections.emptyMap(),
                Collections.emptyList()));

        assertThat(listener.transactions).hasSize(2);
        assertThat(listener.transactions).allMatch(tx -> tx.getTxCbor() == null);
    }

    @Test
    void blockFound_skipsWholeBlockWhenSegmentCountsMismatch() {
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        CapturingListener listener = new CapturingListener();
        BlockFetchAgentListenerAdapter adapter = new BlockFetchAgentListenerAdapter(listener);

        adapter.blockFound(block(
                List.of(txBody("tx1", null), txBody("tx2", null)),
                List.of(witness("a0")),
                Collections.emptyMap(),
                Collections.emptyList()));

        assertThat(listener.transactions).hasSize(2);
        assertThat(listener.transactions).allMatch(tx -> tx.getTxCbor() == null);
    }

    @Test
    void blockFound_populatesTxCborFromRealBlockFixture() throws Exception {
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        byte[] blockBytes = loadBlockFixture("preprod292683.txt");
        Block block = BlockSerializer.INSTANCE.deserialize(blockBytes);
        CapturingListener listener = new CapturingListener();

        new BlockFetchAgentListenerAdapter(listener).blockFound(block);

        List<Tuple<DataItem, byte[]>> rawBodies = TransactionBodyExtractor.getTxBodiesFromBlock(blockBytes);
        List<byte[]> rawWitnesses = WitnessUtil.getWitnessRawData(blockBytes);
        Map<Integer, byte[]> rawAuxData = AuxDataExtractor.getAuxDataFromBlock(blockBytes);
        assertThat(block.getTransactionWitness()).anyMatch(this::hasPlutusWitnessContent);
        assertThat(rawAuxData).isNotEmpty();
        assertThat(listener.transactions).hasSize(rawBodies.size());
        assertThat(rawWitnesses).hasSize(rawBodies.size());
        assertThat(listener.transactions).allSatisfy(tx -> assertThat(tx.getTxCbor()).isNotNull());

        for (int txIndex = 0; txIndex < listener.transactions.size(); txIndex++) {
            byte[] txBytes = HexUtil.decodeHexString(listener.transactions.get(txIndex).getTxCbor());
            assertThat(com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(txBytes)).isNotNull();

            List<byte[]> txItems = topLevelItemBytes(txBytes);
            assertThat(txItems).hasSize(4);
            assertThat(HexUtil.encodeHexString(txItems.get(0)))
                    .isEqualTo(HexUtil.encodeHexString(rawBodies.get(txIndex)._2));
            assertThat(HexUtil.encodeHexString(txItems.get(1)))
                    .isEqualTo(HexUtil.encodeHexString(rawWitnesses.get(txIndex)));
            assertThat(txItems.get(2)).containsExactly(isValidTx(block, txIndex) ? (byte) 0xf5 : (byte) 0xf4);
            assertThat(HexUtil.encodeHexString(txItems.get(3))).isEqualTo(
                    HexUtil.encodeHexString(rawAuxData.getOrDefault(txIndex, new byte[] {(byte) 0xf6})));
        }
    }

    private Block block(List<TransactionBody> bodies, List<Witnesses> witnesses,
                        Map<Integer, AuxData> auxData, List<Integer> invalidTransactions) {
        return Block.builder()
                .era(Era.Alonzo)
                .header(BlockHeader.builder()
                        .headerBody(HeaderBody.builder()
                                .blockNumber(100)
                                .slot(200)
                                .build())
                        .build())
                .transactionBodies(bodies)
                .transactionWitness(witnesses)
                .auxiliaryDataMap(auxData)
                .invalidTransactions(invalidTransactions)
                .build();
    }

    private TransactionBody txBody(String txHash, String auxDataHash) {
        return TransactionBody.builder()
                .txHash(txHash)
                .cbor("a0")
                .outputs(Collections.emptyList())
                .auxiliaryDataHash(auxDataHash)
                .build();
    }

    private Witnesses witness(String cbor) {
        return Witnesses.builder()
                .cbor(cbor)
                .build();
    }

    private byte[] loadBlockFixture(String fileName) throws Exception {
        try (InputStream resource = getClass().getClassLoader().getResourceAsStream("block/" + fileName)) {
            assertThat(resource).isNotNull();
            return HexUtil.decodeHexString(new String(resource.readAllBytes()).trim());
        }
    }

    private boolean isValidTx(Block block, int txIndex) {
        return block.getInvalidTransactions() == null || !block.getInvalidTransactions().contains(txIndex);
    }

    private boolean hasPlutusWitnessContent(Witnesses witnesses) {
        return hasItems(witnesses.getPlutusV1Scripts())
                || hasItems(witnesses.getPlutusV2Scripts())
                || hasItems(witnesses.getPlutusV3Scripts())
                || hasItems(witnesses.getDatums())
                || hasItems(witnesses.getRedeemers());
    }

    private boolean hasItems(List<?> items) {
        return items != null && !items.isEmpty();
    }

    private List<byte[]> topLevelItemBytes(byte[] txBytes) throws CborException {
        ByteArrayInputStream stream = new ByteArrayInputStream(txBytes);
        int arrayHeader = stream.read();
        assertThat(arrayHeader).isEqualTo(0x84);

        CborDecoder decoder = new CborDecoder(stream);
        List<byte[]> items = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int start = txBytes.length - stream.available();
            int before = stream.available();
            decoder.decodeNext();
            int length = before - stream.available();
            items.add(Arrays.copyOfRange(txBytes, start, start + length));
        }

        assertThat(stream.available()).isZero();
        return items;
    }

    private static class CapturingListener implements BlockChainDataListener {
        private List<Transaction> transactions = new ArrayList<>();

        @Override
        public void onBlock(Era era, Block block, List<Transaction> transactions) {
            this.transactions = transactions;
        }
    }
}
