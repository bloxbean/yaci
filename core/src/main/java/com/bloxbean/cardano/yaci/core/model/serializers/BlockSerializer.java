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
            handleWitnessDatumRedeemer(blockHeader.getHeaderBody().getBlockNumber(), witnessesSet, blockBody);
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

    @SneakyThrows
    private void handleWitnessDatumRedeemer(long block, List<Witnesses> witnesses, byte[] rawBlockBytes) {
        if (witnesses != null && !witnesses.isEmpty()) {
            final List<byte[]> transactionWitness = WitnessUtil.getWitnessRawData(rawBlockBytes);

            for (int witnessIndex = 0; witnessIndex < transactionWitness.size(); witnessIndex++) {

                final var witnessFields = WitnessUtil.getWitnessFields(
                        transactionWitness.get(witnessIndex));
                Witnesses witness = witnesses.get(witnessIndex);

                if (witness.getDatums() != null && !witness.getDatums().isEmpty()) {

                    var datumBytes = getArrayBytes(witnessFields.get(BigInteger.valueOf(4L)));
                    final List<Datum> datums = witness.getDatums();

                    if (datumBytes.size() != datums.size()) {
                        log.error("block: {} datum does not have the same size", block);
                    } else {
                        if (datums != null && !datums.isEmpty()) {
                            for (int datumIndex = 0; datumIndex < datums.size(); datumIndex++) {

                                final Datum datum = datums.get(datumIndex);
                                final byte[] rawCbor = datumBytes.get(datumIndex);

                                final var cbor = HexUtil.encodeHexString(rawCbor);
                                final var hash = Datum.cborToHash(rawCbor);

                                if (!datum.getHash().equals(hash)) {
                                    log.debug("Datum Hash Mismatch : {} - {} - {}", block, datum.getHash(), hash);
                                }

                                var updatedDatum = datum.toBuilder()
                                        .cbor(cbor)
                                        .hash(hash)
                                        .build();

                                datums.set(datumIndex, updatedDatum);
                            }
                        }
                    }
                }

                /*
                 * redeemers =
                 *     [ + [ tag: redeemer_tag, index: uint, data: plutus_data, ex_units: ex_units ] ]
                 *     / { + [ tag: redeemer_tag, index: uint ] => [ data: plutus_data, ex_units: ex_units ] }
                 */
                List<Redeemer> redeemers = witness.getRedeemers();
                if (redeemers != null && !redeemers.isEmpty()) {

                    var redeemersBytes = witnessFields.get(BigInteger.valueOf(5L));

                    //Isolate the first 3 bits of the byte, which represent the "major type" in CBOR's encoding structure. (0xe0 = 11100000)
                    var majorType = MajorType.ofByte(redeemersBytes[0] & 0xe0);

                     if (majorType == MajorType.ARRAY) {
                        List<byte[]> redeemerArrayBytes = null;
                        try {
                            redeemerArrayBytes = getArrayBytes(redeemersBytes);
                        } catch (Exception e) {
                            log.error("Error parsing redeemer array bytes", e);
                            redeemerArrayBytes = new ArrayList<>();
                        }

                        if (redeemerArrayBytes.size() != redeemers.size()) {
                            log.error("block: {} redeemer does not have the same size", block);
                        } else {
                            for (int redeemerIdx = 0; redeemerIdx < redeemers.size(); redeemerIdx++) {
                                var redeemer = redeemers.get(redeemerIdx);
                                var redeemerBytes = redeemerArrayBytes.get(redeemerIdx);
                                var redeemerFields = getRedeemerFields(redeemerBytes);

                                if (redeemerFields.size() != 4) {
                                    log.error("Missing redeemer fields. Expected size 4, but found {}", redeemerFields.size());
                                    continue;
                                    //throw new IllegalStateException("Redeemer missing field");
                                }

                                var actualRedeemerData = redeemerFields.get(2);
                                var redeemerData = redeemer.getData();
                                final var cbor = HexUtil.encodeHexString(actualRedeemerData);
                                final var hash = Datum.cborToHash(actualRedeemerData);

                                if (!redeemerData.getHash().equals(hash)) {
                                    log.debug("Redeemer data hash mismatch : {} - {} - {}",
                                            block, redeemerData.getHash(), hash);
                                }

                                var updatedRedeemerData = redeemerData.toBuilder()
                                        .cbor(cbor)
                                        .hash(hash)
                                        .build();

                                var updatedRedeemer = redeemer.toBuilder()
                                        .cbor(HexUtil.encodeHexString(redeemerBytes))
                                        .data(updatedRedeemerData)
                                        .build();

                                redeemers.set(redeemerIdx, updatedRedeemer);
                            }
                        }
                    } else if (majorType == MajorType.MAP) {
                         List<Tuple<byte[], byte[]>> redeemerMapEntriesBytes = null;
                         try {
                            redeemerMapEntriesBytes = getRedeemerMapBytes(redeemersBytes);
                        } catch (Exception e) {
                            log.error("Error parsing redeemer map bytes", e);
                            redeemerMapEntriesBytes = new ArrayList<>();
                        }
                        if (redeemerMapEntriesBytes.size() != redeemers.size()) {
                            log.error("block: {} redeemer does not have the same size", block);
                        } else {
                            for (int redeemerIdx = 0; redeemerIdx < redeemers.size(); redeemerIdx++) {
                                var redeemer = redeemers.get(redeemerIdx);
                                var redeemerBytesKeyValueTuple = redeemerMapEntriesBytes.get(redeemerIdx);

                                //Get value field, as we only need redeemer data
                                var redeemerFields = getRedeemerFields(redeemerBytesKeyValueTuple._2);

                                if (redeemerFields.size() != 2) {
                                    log.error("Missing redeemer fields in value. Expected size 2, but found {}", redeemerFields.size());
                                    continue;
                                }

                                var actualRedeemerData = redeemerFields.get(0);
                                var redeemerData = redeemer.getData();
                                final var cbor = HexUtil.encodeHexString(actualRedeemerData);
                                final var hash = Datum.cborToHash(actualRedeemerData);

                                if (!redeemerData.getHash().equals(hash)) {
                                    log.debug("Redeemer data hash mismatch : {} - {} - {}",
                                            block, redeemerData.getHash(), hash);
                                }

                                var updatedRedeemerData = redeemerData.toBuilder()
                                        .cbor(cbor)
                                        .hash(hash)
                                        .build();

                                var updatedRedeemer = redeemer.toBuilder()
                                        //.cbor(HexUtil.encodeHexString(redeemerBytes))
                                        .data(updatedRedeemerData)
                                        .build();

                                redeemers.set(redeemerIdx, updatedRedeemer);
                            }
                        }
                    } else {
                        throw new IllegalStateException("Invalid major type for redeemer list bytes : " + majorType);
                    }
                }

            }
        }
    }
}
