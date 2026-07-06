package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.serializers.util.AuxDataExtractor;
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

    @SneakyThrows
    private void handleWitnessDatumRedeemer(long block, List<Witnesses> witnesses, List<byte[]> transactionWitness) {
        if (witnesses != null && !witnesses.isEmpty()) {
            if (transactionWitness == null || transactionWitness.size() != witnesses.size()) {
                log.error("block: {} witness set count mismatch. parsed: {}, raw: {}",
                        block, witnesses.size(), transactionWitness == null ? null : transactionWitness.size());
                return;
            }

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
