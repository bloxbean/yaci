package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.model.serializers.util.TransactionBodyExtractor;
import com.bloxbean.cardano.yaci.core.model.serializers.util.WitnessUtil;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.Tuple;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.model.serializers.util.WitnessUtil.*;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toInt;

@Slf4j
public enum BlockSerializer implements Serializer<Block> {
    INSTANCE;

    @Override
    public Block deserialize(byte[] bytes) {
        try {
            DataItem dataItem = CborSerializationUtil.deserializeOne(bytes);
            Block result = deserializeBlock(dataItem, bytes);
            // JSON may fail even when CBOR decoding succeeds. Recover from source bytes in that
            // case too, rather than returning CBOR produced by re-encoding the failed value.
            for (Witnesses witness : result.getTransactionWitness()) {
                if (DataItemIsolation.hasDataError(witness)) return deserializeWithIsolatedData(bytes);
            }
            for (AuxData aux : result.getAuxiliaryDataMap().values()) {
                if (aux.getMetadataParseError() != null) return deserializeWithIsolatedData(bytes);
            }
            return result;
        } catch (StackOverflowError e) {
            // Restart from original bytes; never resume a decoder whose stream position is uncertain.
            return deserializeWithIsolatedData(bytes);
        }
    }

    /**
     * Failure-only path for Shelley through Conway blocks. Keep header and transaction bodies intact,
     * temporarily remove witnesses/auxiliary data, then restore those fields from their source slices.
     * Byron blocks have separate serializers and do not enter this path.
     */
    @SneakyThrows
    private Block deserializeWithIsolatedData(byte[] bytes) {
        // deserializeOne historically accepts multiple top-level values and returns the first.
        CborSlice root = CborSlice.first(bytes);
        List<CborSlice> envelope = root.items(MajorType.ARRAY);
        if (envelope.size() != 2) throw new CborException("Invalid block envelope");
        // Network envelope: [era, block]. The first four block fields have the same positions from
        // Shelley onward: header, transaction bodies, witnesses, auxiliary data. Alonzo adds invalid txs.
        List<CborSlice> body = envelope.get(1).items(MajorType.ARRAY);
        if (body.size() < 4) throw new CborException("Invalid block body");
        CborSlice witnessSlice = body.get(2);
        CborSlice auxiliarySlice = body.get(3);
        // Empty array/map encodings let the existing parser process required fields without visiting
        // the problematic data. This temporary block is never exposed to the caller.
        byte[] skeletonBytes = root.replacing(Arrays.asList(witnessSlice, auxiliarySlice),
                (byte) 0x80, (byte) 0xa0);
        Block skeleton = deserializeBlock(CborSerializationUtil.deserializeOne(skeletonBytes), skeletonBytes);

        List<Witnesses> witnesses = new ArrayList<>();
        for (CborSlice slice : witnessSlice.items(MajorType.ARRAY)) {
            byte[] raw = slice.bytes();
            Witnesses witness = DataItemIsolation.witness(raw);
            witnesses.add(witness);
        }
        LinkedHashMap<Integer, AuxData> auxiliaryData = new LinkedHashMap<>();
        List<CborSlice> entries = auxiliarySlice.items(MajorType.MAP);
        for (int i = 0; i < entries.size(); i += 2) {
            int index = DataItemIsolation.unsigned(entries.get(i));
            byte[] raw = entries.get(i + 1).bytes();
            AuxData aux = DataItemIsolation.auxiliary(raw);
            auxiliaryData.put(index, aux);
        }
        // Transaction-body bytes were never replaced. Full block/witness/auxiliary CBOR must also
        // come from the original input, never from the temporary skeleton encoding.
        return new Block(skeleton.getEra(), skeleton.getHeader(), skeleton.getTransactionBodies(), witnesses,
                auxiliaryData, skeleton.getInvalidTransactions(),
                YaciConfig.INSTANCE.isReturnBlockCbor() ? HexUtil.encodeHexString(bytes) : null);
    }

    private Block deserializeBlock(DataItem di, byte[] blockBody) {
        Array array = (Array) di;
        int eraValue = ((UnsignedInteger)array.getDataItems().get(0)).getValue().intValue();
        Era era = EraUtil.getEra(eraValue);

        Block.BlockBuilder blockBuilder = Block.builder();
        blockBuilder.era(era);

        Array blockArray = (Array) (array.getDataItems().get(1));
        //header 0
        Array headerArr = (Array) blockArray.getDataItems().get(0);
        BlockHeader blockHeader = BlockHeaderSerializer.INSTANCE.getBlockHeaderFromHeaderArray(headerArr);
        blockBuilder.header(blockHeader);

        //transaction bodies 1
        /**
        Array txnBodiesArr = (Array) blockArray.getDataItems().get(1);

        List<TransactionBody> txnBodies = new ArrayList<>();
        for (DataItem txnBodyDI: txnBodiesArr.getDataItems()) {
            if (txnBodyDI == Special.BREAK)
                continue;
            TransactionBody txBody = TransactionBodySerializer.INSTANCE.deserializeDI(txnBodyDI);
            txnBodies.add(txBody);
        }
        **/

        //Extract transaction bodies from block bytes directly to keep the tx hash same
        List<Tuple<DataItem, byte[]>> txBodyTuples = TransactionBodyExtractor.getTxBodiesFromBlock(blockBody);
        List<TransactionBody> txnBodies = new ArrayList<>();
        for (var tuple: txBodyTuples) {
            TransactionBody txBody = TransactionBodySerializer.INSTANCE.deserializeDI(tuple._1, tuple._2);
            txnBodies.add(txBody);
        }
        blockBuilder.transactionBodies(txnBodies);

        //witnesses
        List<Witnesses> witnessesSet = new ArrayList<>();
        Array witnessesListArr = (Array) blockArray.getDataItems().get(2);
        for (DataItem witnessesDI: witnessesListArr.getDataItems()) {
            if (witnessesDI == SimpleValue.BREAK)
                continue;
            Witnesses witnesses = WitnessesSerializer.INSTANCE.deserializeDI(witnessesDI);
            witnessesSet.add(witnesses);
        }

        //To fix #37 incorrect redeemer & datum hash due to cbor serialization <--> deserialization issue
        //Get redeemer and datum bytes directly without full deserialization
        try {
            List<byte[]> transactionWitness = WitnessUtil.getWitnessRawData(blockBody);
            handleWitnessDatumRedeemer(blockHeader.getHeaderBody().getBlockNumber(), witnessesSet, transactionWitness);
        } catch (Exception e) {
            //If extraction fails due to some reason
            log.error("Extraction of redeemer and datum bytes without serialization/deserialization failed for block : " + blockHeader.getHeaderBody().getBlockNumber());
        }

        blockBuilder.transactionWitness(witnessesSet);

        //auxiliary data
        java.util.Map<Integer, AuxData> auxDataMap = new LinkedHashMap<>();
        Map auxDataMapDI = (Map) blockArray.getDataItems().get(3);
        for (DataItem txIdDI: auxDataMapDI.getKeys()) {
            if (txIdDI == SimpleValue.BREAK)
                continue;
            AuxData auxData = AuxDataSerializer.INSTANCE.deserializeDI(auxDataMapDI.get(txIdDI));
            auxDataMap.put(toInt(txIdDI), auxData);
        }
        blockBuilder.auxiliaryDataMap(auxDataMap);

        if (blockArray.getDataItems().size() > 4) {
            //Invalid transactions
            java.util.List<Integer> invalidTransactions = null;
            List<DataItem> invalidTxnDIList = ((Array) blockArray.getDataItems().get(4)).getDataItems();
            if (invalidTxnDIList.size() > 0)
                invalidTransactions = new ArrayList<>();
            else
                invalidTransactions = Collections.EMPTY_LIST;

            for (DataItem txIndexDI : invalidTxnDIList) {
                if (txIndexDI == SimpleValue.BREAK)
                    continue;
                invalidTransactions.add(toInt(txIndexDI));
            }
            blockBuilder.invalidTransactions(invalidTransactions);
        }

        if (YaciConfig.INSTANCE.isReturnBlockCbor()) {
            blockBuilder.cbor(HexUtil.encodeHexString(blockBody));
        }

        return blockBuilder.build();
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

}
