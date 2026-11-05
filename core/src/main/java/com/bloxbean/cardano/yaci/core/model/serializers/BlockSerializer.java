package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.transaction.raw.BlockEnvelope;
import com.bloxbean.cardano.client.transaction.raw.EraSpec;
import com.bloxbean.cardano.client.transaction.raw.RawBlock;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads a Shelley-family block: CCL's reader, then mappers, then Yaci's models (ADR 0003 D8). Bodies, witnesses and
 * auxiliary data are read from their source bytes, each with its own serializer.
 */
@Slf4j
public enum BlockSerializer implements Serializer<Block> {
    INSTANCE;

    @Override
    public Block deserialize(byte[] bytes) {
        try {
            return read(bytes);
        } catch (com.bloxbean.cardano.client.exception.CborRuntimeException e) {
            throw new CborRuntimeException("Invalid block", e);
        }
    }

    private Block read(byte[] bytes) {
        // Trailing top-level values are tolerated. BlockEnvelope would look into tag 24, which this input never has:
        // block fetch and local chain sync take the block out of its tag 24 byte string first.
        CborSpan first = BlockHeaderSerializer.first(bytes);
        if (first.tag() != -1) throw new CborRuntimeException("A block envelope is an untagged array");
        BlockEnvelope envelope = BlockEnvelope.of(first);
        Era era = era(envelope.eraIndex());
        // ADR 0003 D4: an era this class does not know keeps syncing in the newest layout, with a null era.
        RawBlock rawBlock = era == null ? RawBlock.readAs(envelope, EraSpec.newest()) : RawBlock.of(envelope);

        BlockHeader header = BlockHeaderSerializer.INSTANCE.map(rawBlock.rawHeader());
        long blockNumber = header.getHeaderBody().getBlockNumber();
        List<TransactionBody> bodies = new ArrayList<>(rawBlock.txCount());
        for (CborSpan body : rawBlock.transactionBodies().items()) {
            // The source bytes keep the transaction hash the same.
            bodies.add(TransactionBodySerializer.INSTANCE.read(body.bytes(), rawBlock.era()));
        }
        return Block.builder().era(era).header(header).transactionBodies(bodies)
                .transactionWitness(witnesses(rawBlock))
                .auxiliaryDataMap(auxData(rawBlock, blockNumber, bodies))
                .invalidTransactions(invalidTransactions(rawBlock))
                .cbor(YaciConfig.INSTANCE.isReturnBlockCbor() ? HexUtil.encodeHexString(bytes) : null)
                .build();
    }

    private static Era era(long eraIndex) {
        return eraIndex == (int) eraIndex ? EraUtil.getEra((int) eraIndex) : null;
    }

    /** Empty when the block has the list and none are invalid, and null when its era has no such list. */
    private static List<Integer> invalidTransactions(RawBlock rawBlock) {
        if (rawBlock.invalidTransactions().isEmpty()) return null;
        int[] indexes = rawBlock.invalidTxIndexes();
        return indexes.length == 0 ? Collections.emptyList()
                : Arrays.stream(indexes).boxed().collect(Collectors.toCollection(ArrayList::new));
    }

    private List<Witnesses> witnesses(RawBlock rawBlock) {
        List<CborSpan> witnessSets = rawBlock.transactionWitnessSets().items();
        List<Witnesses> witnesses = new ArrayList<>(witnessSets.size());
        for (CborSpan witnessSet : witnessSets) {
            Witnesses witness = WitnessesSerializer.INSTANCE.read(witnessSet, rawBlock.era(), true);
            if (YaciConfig.INSTANCE.isReturnFullTxCbor())
                witness = witness.toBuilder().cbor(HexUtil.encodeHexString(witnessSet.bytes())).build();
            witnesses.add(witness);
        }
        return witnesses;
    }

    /** A repeated transaction index keeps its first position and takes the last value. */
    private java.util.Map<Integer, AuxData> auxData(RawBlock rawBlock, long blockNumber, List<TransactionBody> bodies) {
        java.util.Map<Integer, CborSpan> spans = new LinkedHashMap<>();
        for (java.util.Map.Entry<CborSpan, CborSpan> entry : rawBlock.auxiliaryDataSet().entries()) {
            spans.put((int) entry.getKey().asLong(), entry.getValue());
        }
        java.util.Map<Integer, AuxData> auxDataMap = new LinkedHashMap<>();
        spans.forEach((txIndex, span) -> {
            AuxData auxData = AuxDataSerializer.INSTANCE.read(span, rawBlock.era(), true);
            // Check the original auxiliary-data bytes against the transaction's hash.
            if (YaciConfig.INSTANCE.isReturnFullTxCbor()) {
                byte[] raw = span.bytes();
                if (isAuxDataHashValid(blockNumber, txIndex, bodies, raw))
                    auxData = auxData.toBuilder().cbor(HexUtil.encodeHexString(raw)).build();
            }
            auxDataMap.put(txIndex, auxData);
        });
        return auxDataMap;
    }

    private boolean isAuxDataHashValid(long block, int txIndex, List<TransactionBody> txnBodies, byte[] auxBytes) {
        String expectedHash = txnBodies.get(txIndex).getAuxiliaryDataHash();
        if (expectedHash == null) {
            return true;
        }

        String actualHash = Datum.cborToHash(auxBytes);
        if (!expectedHash.equals(actualHash)) {
            log.debug("Auxiliary data hash mismatch : {} - {} - {} - {}",
                    block, txIndex, expectedHash, actualHash);
            return false;
        }

        return true;
    }
}
