package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.model.serializers.leios.LeiosCertificateSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.util.DijkstraTransactionExtractor;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toInt;

/**
 * Maps the w27 Dijkstra nested block body into Yaci's existing block model.
 */
@Slf4j
public enum DijkstraBlockBodySerializer {
    INSTANCE;

    public Block deserialize(Block.BlockBuilder blockBuilder, byte[] rawBlockBytes, BlockHeader blockHeader) {
        DijkstraTransactionExtractor.BlockBodySlice bodySlice =
                DijkstraTransactionExtractor.extractBlockBody(rawBlockBytes);
        if (bodySlice.extraItems() > 0 && log.isDebugEnabled()) {
            log.debug("Ignoring {} extra Dijkstra block_body item(s) at block {}",
                    bodySlice.extraItems(), blockHeader.getHeaderBody().getBlockNumber());
        }

        List<TransactionBody> transactionBodies = new ArrayList<>();
        List<Witnesses> witnessesSet = new ArrayList<>();
        List<byte[]> witnessRawBytes = new ArrayList<>();
        Map<Integer, AuxData> auxiliaryDataMap = new LinkedHashMap<>();

        for (DijkstraTransactionExtractor.TransactionSlice transaction : bodySlice.transactions()) {
            transactionBodies.add(TransactionBodySerializer.INSTANCE.deserializeDI(
                    transaction.body(), transaction.bodyBytes()));
            Witnesses witnesses = WitnessesSerializer.INSTANCE.deserializeDI(transaction.witnesses());
            witnessesSet.add(witnesses);
            witnessRawBytes.add(transaction.witnessesBytes());

            if (isPresentCborItem(transaction.auxiliaryData())) {
                auxiliaryDataMap.put(transaction.index(),
                        AuxDataSerializer.INSTANCE.deserializeDI(transaction.auxiliaryData()));
            }
        }

        try {
            BlockSerializer.fixWitnessDatumRedeemer(blockHeader.getHeaderBody().getBlockNumber(),
                    witnessesSet, witnessRawBytes);
        } catch (Exception e) {
            log.error("Extraction of Dijkstra redeemer and datum bytes failed for block: {}",
                    blockHeader.getHeaderBody().getBlockNumber(), e);
        }

        blockBuilder.transactionBodies(transactionBodies);
        blockBuilder.transactionWitness(witnessesSet);
        blockBuilder.auxiliaryDataMap(auxiliaryDataMap);
        blockBuilder.invalidTransactions(readInvalidTransactions(bodySlice.invalidTransactions(),
                transactionBodies.size()));

        if (isPresentCborItem(bodySlice.leiosCertificate())) {
            blockBuilder.leiosCertificate(LeiosCertificateSerializer.INSTANCE.deserialize(
                    bodySlice.leiosCertificateBytes()));
        }
        if (isPresentCborItem(bodySlice.perasCertificate())) {
            blockBuilder.perasCertCbor(HexUtil.encodeHexString(bodySlice.perasCertificateBytes()));
        }
        if (YaciConfig.INSTANCE.isReturnBlockCbor()) {
            blockBuilder.cbor(HexUtil.encodeHexString(rawBlockBytes));
        }

        return blockBuilder.build();
    }

    private List<Integer> readInvalidTransactions(DataItem invalidTransactions, int transactionCount) {
        if (!isPresentCborItem(invalidTransactions)) {
            return Collections.emptyList();
        }
        if (!(invalidTransactions instanceof Array invalidArray)) {
            throw new IllegalArgumentException("Dijkstra invalid_transactions must be nil or an array/set");
        }

        List<Integer> indexes = new ArrayList<>();
        for (DataItem txIndex : invalidArray.getDataItems()) {
            if (txIndex == SimpleValue.BREAK) {
                continue;
            }
            int index = toInt(txIndex);
            if (index < 0 || index >= transactionCount) {
                throw new IllegalArgumentException("Dijkstra invalid transaction index out of range: " + index);
            }
            indexes.add(index);
        }
        return indexes;
    }

    private boolean isPresentCborItem(DataItem dataItem) {
        return dataItem != null && dataItem != SimpleValue.NULL && dataItem != SimpleValue.BREAK;
    }
}
