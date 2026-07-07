package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.serializers.leios.LeiosCborReader;
import com.bloxbean.cardano.yaci.core.model.serializers.leios.LeiosCertificateSerializer;
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
        DataItem dataItem = CborSerializationUtil.deserializeOne(bytes);
        return deserializeBlock(dataItem, bytes);
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
        if (isDijkstraRestructuredBlock(era, blockArray)) {
            return DijkstraBlockBodySerializer.INSTANCE.deserialize(blockBuilder, blockBody, blockHeader);
        }

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

        //Legacy (pre-w27) Dijkstra segmented body: trailing leios_cert/peras_cert at positions 5/6.
        //Dijkstra-gated so no other era's parsing can grow these fields; raw bytes are sliced from
        //the block bytes, never re-encoded from the DataItem tree.
        if (era == Era.Dijkstra) {
            populateLegacyDijkstraCertificates(blockBuilder, blockArray, blockBody);
        }

        if (YaciConfig.INSTANCE.isReturnBlockCbor()) {
            blockBuilder.cbor(HexUtil.encodeHexString(blockBody));
        }

        return blockBuilder.build();
    }

    private boolean isPresentCborItem(DataItem dataItem) {
        return dataItem != null && dataItem != SimpleValue.NULL && dataItem != SimpleValue.BREAK;
    }

    private void populateLegacyDijkstraCertificates(Block.BlockBuilder blockBuilder, Array blockArray, byte[] blockBody) {
        List<DataItem> items = blockArray.getDataItems();
        int itemCount = items.size();
        if (itemCount > 0 && items.get(itemCount - 1) == SimpleValue.BREAK) {
            itemCount--;
        }
        if (itemCount <= 5) {
            return;
        }
        boolean hasLeiosCert = isPresentCborItem(items.get(5));
        boolean hasPerasCert = itemCount > 6 && isPresentCborItem(items.get(6));
        if (!hasLeiosCert && !hasPerasCert) {
            return;
        }

        //Walk the raw block bytes to slice the certificate items byte-exactly
        LeiosCborReader reader = new LeiosCborReader(blockBody);
        reader.readLength(LeiosCborReader.MAJOR_TYPE_ARRAY); //outer [era, block]
        reader.readDataItem(); //era tag
        reader.readLength(LeiosCborReader.MAJOR_TYPE_ARRAY); //inner block array
        for (int i = 0; i < 5; i++) { //header + 4 segments
            reader.readDataItem();
        }
        byte[] leiosCertBytes = reader.readDataItem().rawBytes();
        if (hasLeiosCert) {
            blockBuilder.leiosCertificate(LeiosCertificateSerializer.INSTANCE.deserialize(leiosCertBytes));
        }
        if (hasPerasCert) {
            blockBuilder.perasCertCbor(HexUtil.encodeHexString(reader.readDataItem().rawBytes()));
        }
    }

    private boolean isDijkstraRestructuredBlock(Era era, Array blockArray) {
        if (era != Era.Dijkstra) {
            return false;
        }
        List<DataItem> items = blockArray.getDataItems();
        int itemCount = items.size();
        if (itemCount > 0 && items.get(itemCount - 1) == SimpleValue.BREAK) {
            itemCount--; //indefinite-length arrays carry a trailing BREAK in getDataItems()
        }
        return itemCount == 2 && items.get(1) instanceof Array;
    }

    @SneakyThrows
    private void handleWitnessDatumRedeemer(long block, List<Witnesses> witnesses, byte[] rawBlockBytes) {
        final List<byte[]> transactionWitness = WitnessUtil.getWitnessRawData(rawBlockBytes);
        fixWitnessDatumRedeemer(block, witnesses, transactionWitness);
    }

    @SneakyThrows
    static void fixWitnessDatumRedeemer(long block, List<Witnesses> witnesses, List<byte[]> transactionWitness) {
        if (witnesses != null && !witnesses.isEmpty()) {
            if (transactionWitness.size() != witnesses.size()) {
                log.error("block: {} witness raw byte count {} does not match parsed witness count {}",
                        block, transactionWitness.size(), witnesses.size());
            }

            int witnessCount = Math.min(transactionWitness.size(), witnesses.size());
            for (int witnessIndex = 0; witnessIndex < witnessCount; witnessIndex++) {

                final var witnessFields = WitnessUtil.getWitnessFields(
                        transactionWitness.get(witnessIndex));
                Witnesses witness = witnesses.get(witnessIndex);

                if (witness.getDatums() != null && !witness.getDatums().isEmpty()) {

                    byte[] datumFieldBytes = witnessFields.get(BigInteger.valueOf(4L));
                    if (datumFieldBytes == null) {
                        //Do NOT skip the rest of this witness: the redeemer fix-up below must still run
                        log.error("block: {} witness {} has parsed datums but no raw datum field",
                                block, witnessIndex);
                    } else {
                        var datumBytes = getArrayBytes(datumFieldBytes);
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
                }

                /*
                 * redeemers =
                 *     [ + [ tag: redeemer_tag, index: uint, data: plutus_data, ex_units: ex_units ] ]
                 *     / { + [ tag: redeemer_tag, index: uint ] => [ data: plutus_data, ex_units: ex_units ] }
                 */
                List<Redeemer> redeemers = witness.getRedeemers();
                if (redeemers != null && !redeemers.isEmpty()) {

                    var redeemersBytes = witnessFields.get(BigInteger.valueOf(5L));
                    if (redeemersBytes == null) {
                        log.error("block: {} witness {} has parsed redeemers but no raw redeemer field",
                                block, witnessIndex);
                        continue;
                    }

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
