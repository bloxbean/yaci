package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.serializers.util.AuxDataExtractor;
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
            if (YaciConfig.INSTANCE.isReturnFullTxCbor()) {
                witness = witness.toBuilder().cbor(HexUtil.encodeHexString(raw)).build();
            }
            witnesses.add(witness);
        }
        LinkedHashMap<Integer, AuxData> auxiliaryData = new LinkedHashMap<>();
        List<CborSlice> entries = auxiliarySlice.items(MajorType.MAP);
        for (int i = 0; i < entries.size(); i += 2) {
            int index = DataItemIsolation.unsigned(entries.get(i));
            byte[] raw = entries.get(i + 1).bytes();
            AuxData aux = DataItemIsolation.auxiliary(raw);
            // Check the original auxiliary-data bytes against the transaction's hash, as on the normal path.
            if (YaciConfig.INSTANCE.isReturnFullTxCbor()
                    && isAuxDataHashValid(skeleton.getHeader().getHeaderBody().getBlockNumber(),
                    index, skeleton.getTransactionBodies(), raw)) {
                aux = aux.toBuilder().cbor(HexUtil.encodeHexString(raw)).build();
            }
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

        List<byte[]> transactionWitnessRawBytes = null;
        if (!witnessesSet.isEmpty()) {
            try {
                transactionWitnessRawBytes = WitnessUtil.getWitnessRawData(blockBody);
                if (YaciConfig.INSTANCE.isReturnFullTxCbor()) {
                    setWitnessCbor(blockHeader.getHeaderBody().getBlockNumber(), witnessesSet, transactionWitnessRawBytes);
                }
            } catch (Exception e) {
                //If extraction fails due to some reason
                log.error("Extraction of witness bytes without serialization/deserialization failed for block : "
                        + blockHeader.getHeaderBody().getBlockNumber(), e);
            }
        }

        //To fix #37 incorrect redeemer & datum hash due to cbor serialization <--> deserialization issue
        //Get redeemer and datum bytes directly without full deserialization
        try {
            handleWitnessDatumRedeemer(blockHeader.getHeaderBody().getBlockNumber(), witnessesSet, transactionWitnessRawBytes);
        } catch (Exception e) {
            log.error("Extraction of redeemer and datum bytes without serialization/deserialization failed for block : "
                    + blockHeader.getHeaderBody().getBlockNumber(), e);
        }

        blockBuilder.transactionWitness(witnessesSet);

        java.util.Map<Integer, byte[]> auxDataRawBytes = Collections.emptyMap();
        if (YaciConfig.INSTANCE.isReturnFullTxCbor()) {
            try {
                auxDataRawBytes = AuxDataExtractor.getAuxDataFromBlock(blockBody);
            } catch (Exception e) {
                log.error("Extraction of auxiliary data bytes failed for block : "
                        + blockHeader.getHeaderBody().getBlockNumber(), e);
            }
        }

        //auxiliary data
        java.util.Map<Integer, AuxData> auxDataMap = new LinkedHashMap<>();
        Map auxDataMapDI = (Map) blockArray.getDataItems().get(3);
        for (DataItem txIdDI: auxDataMapDI.getKeys()) {
            if (txIdDI == SimpleValue.BREAK)
                continue;
            int txIndex = toInt(txIdDI);
            AuxData auxData = AuxDataSerializer.INSTANCE.deserializeDI(auxDataMapDI.get(txIdDI));
            if (YaciConfig.INSTANCE.isReturnFullTxCbor()) {
                byte[] auxBytes = auxDataRawBytes.get(txIndex);
                if (auxBytes != null && isAuxDataHashValid(blockHeader.getHeaderBody().getBlockNumber(),
                        txIndex, txnBodies, auxBytes)) {
                    auxData = auxData.toBuilder()
                            .cbor(HexUtil.encodeHexString(auxBytes))
                            .build();
                } else if (auxBytes == null) {
                    log.debug("Missing raw auxiliary data bytes for block: {}, tx index: {}",
                            blockHeader.getHeaderBody().getBlockNumber(), txIndex);
                }
            }
            auxDataMap.put(txIndex, auxData);
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
            try {
                var fields = WitnessUtil.getWitnessFields(transactionWitness.get(witnessIndex));
                Witnesses witness = witnesses.get(witnessIndex);
                // Enrichment is optional. A failed datum pass must not prevent redeemer correction or
                // correction of later witnesses; retain the initially parsed values on failure.
                if (witness.getDatums() != null && !witness.getDatums().isEmpty()) {
                    try {
                        List<byte[]> rawDatums = getArrayBytes(fields.get(BigInteger.valueOf(4)));
                        if (rawDatums.size() != witness.getDatums().size()) {
                            throw new CborException("Datum count mismatch");
                        }
                        for (int i = 0; i < rawDatums.size(); i++) {
                            witness.getDatums().set(i, withOriginalData(witness.getDatums().get(i), rawDatums.get(i)));
                        }
                    } catch (Exception e) {
                        log.error("Raw datum extraction failed. block: {}, witness: {}", block, witnessIndex, e);
                    }
                }
                if (witness.getRedeemers() != null && !witness.getRedeemers().isEmpty()) {
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
     * A malformed entry leaves that parsed redeemer intact and does not stop later entries.
     */
    private void correctRedeemers(long block, int witnessIndex, List<Redeemer> redeemers, byte[] bytes)
            throws CborException {
        MajorType type = CborSlice.of(bytes).type();
        List<byte[]> entries;
        boolean map = type == MajorType.MAP;
        if (map) {
            entries = new ArrayList<>();
            for (Tuple<byte[], byte[]> entry : getRedeemerMapBytes(bytes)) entries.add(entry._2);
        } else if (type == MajorType.ARRAY) {
            entries = getArrayBytes(bytes);
        } else {
            throw new CborException("Expected redeemer array or map");
        }
        if (entries.size() != redeemers.size()) throw new CborException("Redeemer count mismatch");
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
        if (txIndex >= txnBodies.size()) {
            log.debug("Auxiliary data index outside transaction body list. block: {}, tx index: {}", block, txIndex);
            return false;
        }

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
