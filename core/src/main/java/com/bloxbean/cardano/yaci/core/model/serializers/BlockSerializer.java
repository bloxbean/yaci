package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.transaction.raw.BlockEnvelope;
import com.bloxbean.cardano.client.transaction.raw.EraSpec;
import com.bloxbean.cardano.client.transaction.raw.RawBlock;
import com.bloxbean.cardano.client.transaction.raw.RawItems;
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
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Reads a Shelley-family block: CCL's reader, then mappers, then Yaci's models (ADR 0003 D8). Bodies, witnesses and
 * auxiliary data are read from their source bytes, each with its own serializer: from the parallel arrays to Conway,
 * and from each whole transaction in Dijkstra (D4a; experimental, as Dijkstra is not final). A Dijkstra block's Leios
 * and Peras certificates come from CCL's views (D4b).
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
        } catch (StackOverflowError e) {
            // Every recursive reader on this path guards itself; this keeps a missed one a parse error for listeners.
            CborRuntimeException error = new CborRuntimeException("Invalid block: too deeply nested");
            error.initCause(e);
            throw error;
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
        TxParts parts = isUnified(rawBlock) ? TxParts.ofTransactions(rawBlock) : TxParts.ofParallelArrays(rawBlock);
        List<TransactionBody> bodies = new ArrayList<>(parts.bodies().size());
        for (CborSpan body : parts.bodies()) {
            // The source bytes keep the transaction hash the same.
            bodies.add(TransactionBodySerializer.INSTANCE.read(body.bytes(), rawBlock.era()));
        }
        return Block.builder().era(era).header(header).transactionBodies(bodies)
                .transactionWitness(witnesses(parts.witnessSets(), rawBlock))
                .auxiliaryDataMap(auxData(parts.auxData(), rawBlock, blockNumber, bodies))
                .invalidTransactions(invalidTransactions(rawBlock))
                .cbor(YaciConfig.INSTANCE.isReturnBlockCbor() ? HexUtil.encodeHexString(bytes) : null)
                .leiosCertificate(rawBlock.leiosCertificate().map(certificate -> new LeiosCertificate(
                        HexUtil.encodeHexString(certificate.signers()),
                        HexUtil.encodeHexString(certificate.signature()))).orElse(null))
                .perasCertificate(rawBlock.perasCertificate()
                        .map(certificate -> HexUtil.encodeHexString(RawItems.bytes(certificate))).orElse(null))
                .build();
    }

    private static Era era(long eraIndex) {
        return eraIndex == (int) eraIndex ? EraUtil.getEra((int) eraIndex) : null;
    }

    // CCL's and Yaci's Era values coincide by construction: both are the hard-fork combinator's block index.

    /** Whether the block holds each transaction whole: from Dijkstra, also for an unknown era read as the newest. */
    private static boolean isUnified(RawBlock rawBlock) {
        return rawBlock.era().getValue() >= Era.Dijkstra.getValue();
    }

    /**
     * Empty when none is invalid, and null before Alonzo, whose blocks have no validity. Alonzo to Conway list them;
     * from Dijkstra each transaction carries its flag.
     */
    private static List<Integer> invalidTransactions(RawBlock rawBlock) {
        if (rawBlock.era().getValue() < Era.Alonzo.getValue()) return null;
        int[] indexes = rawBlock.invalidTxIndexes();
        return indexes.length == 0 ? Collections.emptyList()
                : Arrays.stream(indexes).boxed().collect(Collectors.toCollection(ArrayList::new));
    }

    private List<Witnesses> witnesses(List<CborSpan> witnessSets, RawBlock rawBlock) {
        List<Witnesses> witnesses = new ArrayList<>(witnessSets.size());
        for (CborSpan witnessSet : witnessSets)
            witnesses.add(witnessSet(witnessSet, rawBlock.era()));
        return witnesses;
    }

    /** A witness set of a transaction or sub-transaction, with its CBOR if full transactions are returned. */
    static Witnesses witnessSet(CborSpan witnessSet, com.bloxbean.cardano.client.spec.Era era) {
        Witnesses witness = WitnessesSerializer.INSTANCE.read(witnessSet, era, true);
        if (YaciConfig.INSTANCE.isReturnFullTxCbor())
            witness = witness.toBuilder().cbor(HexUtil.encodeHexString(witnessSet.bytes())).build();
        return witness;
    }

    private java.util.Map<Integer, AuxData> auxData(java.util.Map<Integer, CborSpan> spans, RawBlock rawBlock,
                                                    long blockNumber, List<TransactionBody> bodies) {
        java.util.Map<Integer, AuxData> auxDataMap = new LinkedHashMap<>();
        spans.forEach((txIndex, span) -> auxDataMap.put(txIndex, auxData(span, rawBlock.era(),
                raw -> isAuxDataHashValid(blockNumber, txIndex, bodies, raw))));
        return auxDataMap;
    }

    /**
     * Aux data of a transaction or sub-transaction. If full transactions are returned, its CBOR is the original bytes,
     * given only if they match the transaction's hash.
     */
    static AuxData auxData(CborSpan span, com.bloxbean.cardano.client.spec.Era era, Predicate<byte[]> hashValid) {
        AuxData auxData = AuxDataSerializer.INSTANCE.read(span, era, true);
        // Check the original auxiliary-data bytes against the transaction's hash.
        if (YaciConfig.INSTANCE.isReturnFullTxCbor()) {
            byte[] raw = span.bytes();
            if (hashValid.test(raw))
                auxData = auxData.toBuilder().cbor(HexUtil.encodeHexString(raw)).build();
        }
        return auxData;
    }

    /** A block's transaction parts as encoded: bodies and witness sets in block order, aux data by transaction. */
    private record TxParts(List<CborSpan> bodies, List<CborSpan> witnessSets,
                           java.util.Map<Integer, CborSpan> auxData) {

        /** Aux data in the map's encoded order: a repeated index keeps its first position and takes the last value. */
        static TxParts ofParallelArrays(RawBlock rawBlock) {
            java.util.Map<Integer, CborSpan> auxData = new LinkedHashMap<>();
            for (java.util.Map.Entry<CborSpan, CborSpan> entry : rawBlock.auxiliaryDataSet().entries()) {
                auxData.put((int) entry.getKey().asLong(), entry.getValue());
            }
            return new TxParts(rawBlock.transactionBodies().items(), rawBlock.transactionWitnessSets().items(),
                    auxData);
        }

        /**
         * Aux data in transaction order. The parts are taken as encoded, [body, witnesses, aux / null, isValid], so
         * their records are read only by the serializers, tolerantly, as from the parallel arrays.
         */
        static TxParts ofTransactions(RawBlock rawBlock) {
            List<CborSpan> bodies = new ArrayList<>(rawBlock.txCount());
            List<CborSpan> witnessSets = new ArrayList<>(rawBlock.txCount());
            java.util.Map<Integer, CborSpan> auxData = new LinkedHashMap<>();
            for (int i = 0; i < rawBlock.txCount(); i++) {
                CborSpan tx = rawBlock.transaction(i);
                bodies.add(tx.get(0));
                witnessSets.add(tx.get(1));
                if (!tx.get(2).isNull()) auxData.put(i, tx.get(2));
            }
            return new TxParts(bodies, witnessSets, auxData);
        }
    }

    private boolean isAuxDataHashValid(long block, int txIndex, List<TransactionBody> txnBodies, byte[] auxBytes) {
        String expectedHash = txnBodies.get(txIndex).getAuxiliaryDataHash();
        if (hashMatches(expectedHash, auxBytes)) {
            return true;
        }

        log.debug("Auxiliary data hash mismatch : {} - {} - {} - {}",
                block, txIndex, expectedHash, Datum.cborToHash(auxBytes));
        return false;
    }

    /** Whether aux data's original bytes have the hash a body gives, or the body gives none. */
    static boolean hashMatches(String expectedHash, byte[] auxBytes) {
        return expectedHash == null || expectedHash.equals(Datum.cborToHash(auxBytes));
    }
}
