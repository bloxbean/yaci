package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.transaction.raw.BlockEnvelope;
import com.bloxbean.cardano.client.transaction.raw.EraSpec;
import com.bloxbean.cardano.client.transaction.raw.RawBlock;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.model.serializers.util.WitnessUtil;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import static com.bloxbean.cardano.yaci.core.model.serializers.util.WitnessUtil.*;

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
            Block result = toBlock(bytes, false);
            // JSON may fail even when CBOR decoding succeeds. Recover from source bytes in that
            // case too, rather than returning CBOR produced by re-encoding the failed value.
            boolean dataError = result.getTransactionWitness().stream().anyMatch(DataItemIsolation::hasDataError)
                    || result.getAuxiliaryDataMap().values().stream().anyMatch(a -> a.getMetadataParseError() != null);
            return dataError ? deserializeWithIsolatedData(bytes) : result;
        } catch (StackOverflowError e) {
            return deserializeWithIsolatedData(bytes);
        }
    }

    /** Failure-only path: witnesses and auxiliary data are read from their source bytes with their data isolated. */
    private Block deserializeWithIsolatedData(byte[] bytes) {
        return toBlock(bytes, true);
    }

    private Block toBlock(byte[] bytes, boolean isolated) {
        try {
            return read(bytes, isolated);
        } catch (com.bloxbean.cardano.client.exception.CborRuntimeException e) {
            throw new CborRuntimeException("Invalid block", e);
        }
    }

    private Block read(byte[] bytes, boolean isolated) {
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
            byte[] raw = body.bytes(); // The source bytes keep the transaction hash the same.
            DataItem tree = CborSerializationUtil.deserializeOne(raw);
            bodies.add(TransactionBodySerializer.INSTANCE.deserializeDI(tree, raw));
        }
        List<CborSpan> sets = rawBlock.transactionWitnessSets().items();
        return Block.builder().era(era).header(header).transactionBodies(bodies)
                .transactionWitness(isolated ? isolatedWitnesses(sets) : witnesses(sets, bytes, blockNumber))
                .auxiliaryDataMap(auxData(rawBlock, blockNumber, bodies, isolated))
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

    private List<Witnesses> witnesses(List<CborSpan> witnessSets, byte[] bytes, long blockNumber) {
        List<Witnesses> witnesses = new ArrayList<>(witnessSets.size());
        for (CborSpan witnessSet : witnessSets) {
            witnesses.add(WitnessesSerializer.INSTANCE.deserializeDI(
                    CborSerializationUtil.deserializeOne(witnessSet.bytes())));
        }

        List<byte[]> transactionWitnessRawBytes = null;
        if (!witnesses.isEmpty()) {
            try {
                transactionWitnessRawBytes = WitnessUtil.getWitnessRawData(bytes);
                if (YaciConfig.INSTANCE.isReturnFullTxCbor()) {
                    setWitnessCbor(blockNumber, witnesses, transactionWitnessRawBytes);
                }
            } catch (Exception e) {
                //If extraction fails due to some reason
                log.error("Extraction of witness bytes without serialization/deserialization failed for block : "
                        + blockNumber, e);
            }
        }

        //To fix #37 incorrect redeemer & datum hash due to cbor serialization <--> deserialization issue
        //Get redeemer and datum bytes directly without full deserialization
        try {
            handleWitnessDatumRedeemer(blockNumber, witnesses, transactionWitnessRawBytes);
        } catch (Exception e) {
            log.error("Extraction of redeemer and datum bytes without serialization/deserialization failed for block : "
                    + blockNumber, e);
        }
        return witnesses;
    }

    /** A repeated transaction index keeps its first position and takes the last value. */
    private java.util.Map<Integer, AuxData> auxData(RawBlock rawBlock, long blockNumber, List<TransactionBody> bodies,
                                                    boolean isolated) {
        java.util.Map<Integer, CborSpan> spans = new LinkedHashMap<>();
        for (java.util.Map.Entry<CborSpan, CborSpan> entry : rawBlock.auxiliaryDataSet().entries()) {
            spans.put((int) entry.getKey().asLong(), entry.getValue());
        }
        java.util.Map<Integer, AuxData> auxDataMap = new LinkedHashMap<>();
        spans.forEach((txIndex, span) -> {
            byte[] raw = span.bytes();
            AuxData auxData = isolated ? DataItemIsolation.auxiliary(raw) : auxData(raw);
            // Check the original auxiliary-data bytes against the transaction's hash.
            if (YaciConfig.INSTANCE.isReturnFullTxCbor() && isAuxDataHashValid(blockNumber, txIndex, bodies, raw)) {
                auxData = auxData.toBuilder().cbor(HexUtil.encodeHexString(raw)).build();
            }
            auxDataMap.put(txIndex, auxData);
        });
        return auxDataMap;
    }

    private static AuxData auxData(byte[] raw) {
        AuxData auxData = AuxDataSerializer.INSTANCE.deserializeDI(CborSerializationUtil.deserializeOne(raw));
        NativeScriptHashes.fromAuxiliaryData(auxData.getNativeScripts(), raw);
        return auxData;
    }

    private List<Witnesses> isolatedWitnesses(List<CborSpan> witnessSets) {
        List<Witnesses> witnesses = new ArrayList<>(witnessSets.size());
        for (CborSpan witnessSet : witnessSets) {
            byte[] raw = witnessSet.bytes();
            Witnesses witness = DataItemIsolation.witness(raw);
            if (YaciConfig.INSTANCE.isReturnFullTxCbor()) {
                witness = witness.toBuilder().cbor(HexUtil.encodeHexString(raw)).build();
            }
            witnesses.add(witness);
        }
        return witnesses;
    }

    /** Correct optional datum/redeemer bytes per witness; extraction failures never reject the parsed block. */
    private void handleWitnessDatumRedeemer(long block, List<Witnesses> witnesses, List<byte[]> transactionWitness) {
        if (witnesses == null || witnesses.isEmpty()) return;
        if (transactionWitness == null || transactionWitness.size() != witnesses.size()) {
            log.error("block: {} witness set count mismatch. parsed: {}, raw: {}",
                    block, witnesses.size(), transactionWitness == null ? null : transactionWitness.size());
            return;
        }

        for (int witnessIndex = 0; witnessIndex < witnesses.size(); witnessIndex++) {
            Witnesses witness = witnesses.get(witnessIndex);
            NativeScriptHashes.fromWitness(witness.getNativeScripts(), transactionWitness.get(witnessIndex));
            boolean hasDatums = witness.getDatums() != null && !witness.getDatums().isEmpty();
            boolean hasRedeemers = witness.getRedeemers() != null && !witness.getRedeemers().isEmpty();
            // Signature/script-only witnesses need no optional data correction or field copies.
            if (!hasDatums && !hasRedeemers) continue;
            try {
                var fields = WitnessUtil.getWitnessFields(transactionWitness.get(witnessIndex));
                // Enrichment is optional. A failed datum pass must not prevent redeemer correction or
                // correction of later witnesses; retain the initially parsed values on failure.
                if (hasDatums) {
                    try {
                        List<byte[]> rawDatums = getArrayBytes(fields.get(BigInteger.valueOf(4)));
                        if (rawDatums.size() != witness.getDatums().size()) {
                            log.warn("Datum count mismatch. block: {}, witness: {}, parsed: {}, raw: {}",
                                    block, witnessIndex, witness.getDatums().size(), rawDatums.size());
                        } else {
                            for (int i = 0; i < rawDatums.size(); i++) {
                                witness.getDatums().set(i,
                                        withOriginalData(witness.getDatums().get(i), rawDatums.get(i)));
                            }
                        }
                    } catch (Exception e) {
                        log.error("Raw datum extraction failed. block: {}, witness: {}", block, witnessIndex, e);
                    }
                }
                if (hasRedeemers) {
                    try {
                        correctRedeemers(block, witnessIndex, witness.getRedeemers(),
                                fields.get(BigInteger.valueOf(5)));
                    } catch (Exception e) {
                        log.error("Raw redeemer extraction failed. block: {}, witness: {}", block, witnessIndex, e);
                    }
                }
            } catch (Exception e) {
                log.error("Raw witness field extraction failed. block: {}, witness: {}", block, witnessIndex, e);
            }
        }
    }

    /**
     * Correct redeemer data in source order, preserving the existing public CBOR representation.
     * Pre-Conway: [[tag, index, data, [memory, steps]], ...].
     * Conway also accepts: {[tag, index]: [data, [memory, steps]], ...}.
     * On a map count mismatch, resolve duplicate keys using the decoder's last-value-wins ordering.
     * Equal decoded keys retain their first insertion position but take the last raw value.
     * For example, two [Spend, 0] entries decode to one redeemer:
     * <pre>
     * {[0, 0]: [1, [10, 20]], [0, 0]: [0, [10, 20]]}
     * a2 820000 8201820a14 820000 821800820a14
     * Last value: 82 1800 820a14; its data is 1800 (non-minimal integer zero).
     * </pre>
     * The winning datum keeps bytes 1800 and their hash, rather than re-encoding zero as 00.
     * Counts must match after duplicate resolution before any source bytes are attached.
     * Matching-count maps and array-form redeemers do not enter this fallback.
     * A malformed entry leaves that parsed redeemer intact and does not stop later entries.
     */
    private void correctRedeemers(long block, int witnessIndex, List<Redeemer> redeemers, byte[] bytes)
            throws CborException {
        MajorType type = CborSlice.of(bytes).type();
        List<byte[]> entries;
        int rawCount;
        boolean map = type == MajorType.MAP;
        if (map) {
            entries = new ArrayList<>();
            var rawEntries = getRedeemerMapBytes(bytes);
            rawCount = rawEntries.size();
            if (rawEntries.size() != redeemers.size()) {
                // Conway: {[purpose, index]: [data, execution_units], ...}.
                // The decoder uses DataItem key equality and keeps the last value at the key's
                // first insertion position. Mirror that only on mismatch; different encodings of
                // the same key (e.g. 00 vs 1800) must also collapse before the count check below.
                var uniqueEntries = new LinkedHashMap<DataItem, byte[]>();
                for (Tuple<byte[], byte[]> entry : rawEntries) {
                    uniqueEntries.put(CborSerializationUtil.deserializeOne(entry._1), entry._2);
                }
                entries.addAll(uniqueEntries.values());
            } else {
                for (Tuple<byte[], byte[]> entry : rawEntries) entries.add(entry._2);
            }
        } else if (type == MajorType.ARRAY) {
            entries = getArrayBytes(bytes);
            rawCount = entries.size();
        } else {
            throw new CborException("Expected redeemer array or map");
        }
        if (entries.size() != redeemers.size()) {
            log.warn("Redeemer count mismatch. block: {}, witness: {}, parsed: {}, raw: {}, resolved: {}",
                    block, witnessIndex, redeemers.size(), rawCount, entries.size());
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            try {
                byte[] entry = entries.get(i);
                List<byte[]> fields = getRedeemerFields(entry);
                if (fields.size() != (map ? 2 : 4)) throw new CborException("Unexpected redeemer field count");
                Redeemer redeemer = redeemers.get(i);
                Datum data = withOriginalData(redeemer.getData(), fields.get(map ? 0 : 2));
                // Array-form CBOR is the original whole redeemer. Conway map-form CBOR keeps the
                // existing synthesized four-field array for API compatibility; only data is corrected.
                redeemers.set(i, redeemer.toBuilder().data(data)
                        .cbor(map ? redeemer.getCbor() : HexUtil.encodeHexString(entry)).build());
            } catch (Exception e) {
                log.error("Raw redeemer data extraction failed. block: {}, witness: {}, redeemer: {}",
                        block, witnessIndex, i, e);
            }
        }
    }

    /** Replace only source CBOR and its Blake2b-256 hash, retaining JSON and any existing parse error. */
    private Datum withOriginalData(Datum datum, byte[] bytes) {
        return datum.toBuilder().cbor(HexUtil.encodeHexString(bytes)).hash(Datum.cborToHash(bytes)).build();
    }

    private void setWitnessCbor(long block, List<Witnesses> witnesses, List<byte[]> transactionWitness) {
        if (witnesses == null || transactionWitness == null || witnesses.size() != transactionWitness.size()) {
            log.error("block: {} witness set count mismatch. full transaction cbor will not be available. parsed: {}, raw: {}",
                    block, witnesses == null ? null : witnesses.size(),
                    transactionWitness == null ? null : transactionWitness.size());
            return;
        }

        for (int i = 0; i < witnesses.size(); i++) {
            witnesses.set(i, witnesses.get(i).toBuilder()
                    .cbor(HexUtil.encodeHexString(transactionWitness.get(i)))
                    .build());
        }
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
