package com.bloxbean.cardano.yaci.core.model.serializers.util;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Special;
import com.bloxbean.cardano.yaci.core.util.Tuple;
import lombok.SneakyThrows;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.bloxbean.cardano.yaci.core.model.serializers.util.TransactionBodyExtractor.getLength;
import static com.bloxbean.cardano.yaci.core.model.serializers.util.TransactionBodyExtractor.getSymbolBytes;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toInt;

public class AuxDataExtractor {

    @SneakyThrows
    public static Map<Integer, byte[]> getAuxDataFromBlock(byte[] blockBytes) {
        Map<Integer, byte[]> auxDataMap = new LinkedHashMap<>();
        ByteArrayInputStream stream = new ByteArrayInputStream(blockBytes);
        CborDecoder decoder = new CborDecoder(stream);

        // era and block body array header
        stream.read();
        decoder.decodeNext();
        stream.read();

        decoder.decodeNext(); // header
        decoder.decodeNext(); // transaction bodies
        decoder.decodeNext(); // transaction witness sets

        int auxDataMapByte = stream.read();
        long auxDataEntryCount = getLength(auxDataMapByte,
                getSymbolBytes(blockBytes.length - stream.available(), blockBytes));

        int extraBytes = WitnessUtil.skipBytes(auxDataMapByte);
        if (extraBytes > 0) {
            stream.skip(extraBytes);
        }

        if (auxDataEntryCount != TransactionBodyExtractor.INFINITY) {
            for (int i = 0; i < auxDataEntryCount; i++) {
                Tuple<Integer, byte[]> auxDataEntry = readAuxDataEntry(blockBytes, stream, decoder);
                auxDataMap.put(auxDataEntry._1, auxDataEntry._2);
            }
        } else {
            for (;;) {
                DataItem txIndexDI = decoder.decodeNext();
                if (txIndexDI == null) {
                    throw new CborException("Unexpected end of stream");
                }

                if (Special.BREAK.equals(txIndexDI)) {
                    break;
                }

                int txIndex = toInt(txIndexDI);
                byte[] auxDataBytes = readNextRawValue(blockBytes, stream, decoder);
                auxDataMap.put(txIndex, auxDataBytes);
            }
        }

        return auxDataMap;
    }

    private static Tuple<Integer, byte[]> readAuxDataEntry(byte[] blockBytes,
                                                           ByteArrayInputStream stream,
                                                           CborDecoder decoder) throws CborException {
        DataItem txIndexDI = decoder.decodeNext();
        int txIndex = toInt(txIndexDI);
        return new Tuple<>(txIndex, readNextRawValue(blockBytes, stream, decoder));
    }

    private static byte[] readNextRawValue(byte[] blockBytes,
                                           ByteArrayInputStream stream,
                                           CborDecoder decoder) throws CborException {
        int start = blockBytes.length - stream.available();
        int previous = stream.available();
        decoder.decodeNext();
        int current = stream.available();

        byte[] rawValue = new byte[previous - current];
        System.arraycopy(blockBytes, start, rawValue, 0, rawValue.length);
        return rawValue;
    }
}
