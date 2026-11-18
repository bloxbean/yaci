package com.bloxbean.cardano.yaci.core.model.serializers.leios;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosEndorserBlockRef;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.DijkstraBlockBodySerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.util.DijkstraTransactionExtractor;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.TxUtil;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndorserBlockMergeTest {

    @Test
    void readsAllEndorserBlockTransactionEncodings() throws Exception {
        byte[] body = body(7);
        //w40a mempool [body, wits, aux/nil]; legacy [body, wits, is_valid, aux]; w42 block [body, wits, aux, is_valid]
        var mempool = DijkstraTransactionExtractor.readEndorserBlockTransaction(0, tx(body, SimpleValue.NULL));
        var legacy = DijkstraTransactionExtractor.readEndorserBlockTransaction(1, tx(body, SimpleValue.FALSE, auxiliaryData()));
        var block = DijkstraTransactionExtractor.readEndorserBlockTransaction(2, tx(body, auxiliaryData(), SimpleValue.FALSE));

        assertTrue(mempool.valid());
        assertNull(mempool.auxiliaryDataBytes());
        assertFalse(legacy.valid());
        assertEquals("d90103a0", HexUtil.encodeHexString(legacy.auxiliaryDataBytes()));
        assertFalse(block.valid());
        assertEquals("d90103a0", HexUtil.encodeHexString(block.auxiliaryDataBytes()));
        assertEquals(HexUtil.encodeHexString(body), HexUtil.encodeHexString(block.bodyBytes()));
        assertThrows(CborException.class,
                () -> DijkstraTransactionExtractor.readEndorserBlockTransaction(0, tx(body)));
    }

    @Test
    void mergesEndorserBlockTransactionsIntoLiveCertifyingBlock() throws Exception {
        //block 162998: a live w40a certifying block (no own transactions, Leios certificate)
        byte[] raw = HexUtil.decodeHexString(Files.readString(
                Path.of(getClass().getResource("/leios/w40a/block-162998.hex").toURI())).trim());
        Block certifying = BlockSerializer.INSTANCE.deserialize(raw);
        List<byte[]> ebTxs = List.of(tx(body(1), SimpleValue.NULL), tx(body(2), SimpleValue.NULL), tx(body(3), auxiliaryData()));
        LeiosEndorserBlockRef ref = LeiosEndorserBlockRef.builder().ebHash("aa".repeat(32)).ebSlot(10).ebSize(99).build();

        Block merged = DijkstraBlockBodySerializer.INSTANCE.mergeEndorserBlockTransactions(certifying, ref, ebTxs);

        assertEquals(3, merged.getTransactionBodies().size());
        assertEquals(3, merged.getTransactionWitness().size());
        assertEquals(TxUtil.calculateTxHash(body(2)), merged.getTransactionBodies().get(1).getTxHash());
        assertEquals(List.of(2), new ArrayList<>(merged.getAuxiliaryDataMap().keySet()));
        assertTrue(merged.getInvalidTransactions().isEmpty());
        assertEquals(ref.toBuilder().txCount(3).build(), merged.getLeiosEndorserBlock());
        assertSame(certifying.getHeader(), merged.getHeader());
        assertSame(certifying.getLeiosCertificate(), merged.getLeiosCertificate());
        assertNull(certifying.getLeiosEndorserBlock()); //the wire block is not modified
    }

    @Test
    void ownTransactionsComeAfterEndorserBlockTransactionsWithShiftedIndexes() throws Exception {
        TransactionBody own = TransactionBody.builder().txHash("own").build();
        java.util.Map<Integer, AuxData> ownAux = new LinkedHashMap<>();
        ownAux.put(0, AuxData.builder().build());
        Block certifying = Block.builder()
                .header(BlockSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(Files.readString(
                        Path.of(getClass().getResource("/leios/w40a/block-162998.hex").toURI())).trim())).getHeader())
                .transactionBodies(List.of(own))
                .transactionWitness(List.of(Witnesses.builder().build()))
                .auxiliaryDataMap(ownAux)
                .invalidTransactions(List.of(0))
                .build();
        List<byte[]> ebTxs = List.of(tx(body(1), SimpleValue.NULL), tx(body(2), SimpleValue.FALSE, SimpleValue.NULL));

        Block merged = DijkstraBlockBodySerializer.INSTANCE.mergeEndorserBlockTransactions(
                certifying, LeiosEndorserBlockRef.builder().build(), ebTxs);

        assertEquals(3, merged.getTransactionBodies().size());
        assertSame(own, merged.getTransactionBodies().get(2));
        assertEquals(List.of(1, 2), merged.getInvalidTransactions()); //EB tx 1 (is_valid false), own tx moved to 2
        assertEquals(List.of(2), new ArrayList<>(merged.getAuxiliaryDataMap().keySet()));
        assertEquals(2, merged.getLeiosEndorserBlock().getTxCount());
    }

    private static byte[] body(long fee) {
        Map body = new Map();
        body.put(new UnsignedInteger(0), new Array());
        body.put(new UnsignedInteger(1), new Array());
        body.put(new UnsignedInteger(2), new UnsignedInteger(fee));
        return CborSerializationUtil.serialize(body, false);
    }

    private static Map auxiliaryData() {
        Map aux = new Map();
        aux.setTag(259L);
        return aux;
    }

    private static byte[] tx(byte[] body, DataItem... rest) {
        Array tx = new Array();
        tx.add(CborSerializationUtil.deserializeOne(body));
        tx.add(new Map());
        for (DataItem item : rest) tx.add(item);
        return CborSerializationUtil.serialize(tx, false);
    }
}
