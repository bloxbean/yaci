package com.bloxbean.cardano.yaci.core.model.serializers.util;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.Special;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.Tuple;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.model.serializers.util.TransactionBodyExtractor.getLength;
import static com.bloxbean.cardano.yaci.core.model.serializers.util.TransactionBodyExtractor.getSymbolBytes;

@Slf4j
public class TransactionSizeExtractor {

    /**
     * Extracts TransactionSizes and ScriptSizes out of the byte array blockbody.
     * @param blockBody raw blockbody bytes
     * @return Tuple containing lists for transactionSizes and scriptSizes
     */
    @SneakyThrows
    public static Tuple<List<Integer>, List<Integer>> getSizesForTransactions(byte[] blockBody) {
        ByteArrayInputStream bais = new ByteArrayInputStream(blockBody);
        CborDecoder decoder = new CborDecoder(bais);

        //era and body not needed
        bais.read();
        decoder.decodeNext();
        //body not needed
        bais.read();
        decoder.decodeNext();// skip header

        // txbody
        int transactionBodyArraySize = getArraySize(bais, blockBody);
        List<Integer> txByteSizes;
        Tuple<List<Integer>, List<Integer>> witnessSetByteSizesAndScriptSize;

        if(transactionBodyArraySize >= 0) {
            txByteSizes = getTxSizesFromDataItem(transactionBodyArraySize, bais, decoder);
            bais.read(); // reading witnessSetArraySize. Not needed since txSize and witnessSize are equal length
            witnessSetByteSizesAndScriptSize = getTxSizesAndScriptSizes(transactionBodyArraySize, bais, decoder);
        } else {
            txByteSizes = new ArrayList<>();
            int start = bais.available();
            for(;;) {

                var dataItem = decoder.decodeNext();
                if(dataItem == null) {
                    throw new CborException("Unexpected end of stream");
                }
                if(Special.BREAK.equals(dataItem)) {
                    break;
                }

                int end = bais.available();
                txByteSizes.add(start - end);
                start = end;
            }

            List<Integer> witnessSizeList = new ArrayList<>();
            List<Integer> scriptSizeList = new ArrayList<>();
            bais.read(); // reading witnessSetArraySize. Not needed since txSize and witnessSize are equal length
            start = bais.available();
            for(;;) {
                var dataItem = decoder.decodeNext();
                if(dataItem == null) {
                    throw new CborException("Unexpected end of stream");
                }
                if(Special.BREAK.equals(dataItem)) {
                    break;
                }

                Map witnessMap = (Map) dataItem;
                int scriptSize = getScriptSize(witnessMap, 3);
                scriptSize += getScriptSize(witnessMap, 6);
                scriptSize += getScriptSize(witnessMap, 7);

                int end = bais.available();
                witnessSizeList.add(start - end);
                scriptSizeList.add(scriptSize);
                start = end;
            }
            witnessSetByteSizesAndScriptSize = new Tuple<>(witnessSizeList, scriptSizeList);
        }
        // Processing Auxiliary Data and adding transaction size to respective transactions
        transactionBodyArraySize = getArraySize(bais, blockBody);
        for(int i = 0; i < transactionBodyArraySize; i++) {
            int key = ((UnsignedInteger) decoder.decodeNext()).getValue().intValue();
            txByteSizes.set(key, txByteSizes.get(key) + getByteSizeOfNextDataItem(bais, decoder));
        }
        // summing up byteSizes and WitnessByteSizes elementwise
        for(int i = 0; i < txByteSizes.size(); i++) {
            txByteSizes.set(i, txByteSizes.get(i) + witnessSetByteSizesAndScriptSize._1.get(i));
        }

        return new Tuple<>(txByteSizes, witnessSetByteSizesAndScriptSize._2);
    }

    private static Tuple<List<Integer>, List<Integer>> getTxSizesAndScriptSizes(int length, ByteArrayInputStream bais, CborDecoder decoder) {
        List<Integer> txSizes = new ArrayList<>();
        List<Integer> scriptSizes = new ArrayList<>();
        for(int i = 0; i < length;i++) {
            Tuple<Integer, Integer> txSizeAndScriptSize = getByteSizeAndScriptSizeOfNextDataItem(bais, decoder);
            txSizes.add(txSizeAndScriptSize._1);
            scriptSizes.add(txSizeAndScriptSize._2);
        }
        return new Tuple<>(txSizes,scriptSizes);
    }

    private static List<Integer> getTxSizesFromDataItem(int length, ByteArrayInputStream bais, CborDecoder decoder) {
        List<Integer> txSizes = new ArrayList<>();
        for(int i = 0; i < length; i++) {
            txSizes.add(getByteSizeOfNextDataItem(bais, decoder));
        }
        return txSizes;
    }

    @SneakyThrows
    private static int getByteSizeOfNextDataItem(ByteArrayInputStream bais, CborDecoder decoder) {
        int previous = bais.available();
        decoder.decodeNext();
        return previous - bais.available();
    }

    @SneakyThrows
    private static Tuple<Integer, Integer> getByteSizeAndScriptSizeOfNextDataItem(ByteArrayInputStream bais, CborDecoder decoder) {
        int previous = bais.available();
        Map witnessMap = (Map) decoder.decodeNext();
        int scriptSize = getScriptSize(witnessMap, 3);
        scriptSize += getScriptSize(witnessMap, 6);
        scriptSize += getScriptSize(witnessMap, 7);
        return new Tuple<>(previous - bais.available(), scriptSize);
    }

    private static int getScriptSize(Map witnessMap, int index) {
        Collection<DataItem> keys = witnessMap.getKeys();
        UnsignedInteger unsignedInteger = new UnsignedInteger(BigInteger.valueOf(index));
        if(keys.contains(unsignedInteger)) {
            return CborSerializationUtil.serialize(witnessMap.get(unsignedInteger)).length;
        } else {
            return 0;
        }
    }

    @SneakyThrows
    private static int getArraySize(ByteArrayInputStream bais, byte[] blockBody) {
        int arrTxBodySize = bais.read();
        // tx bodies
        return (int) getLength(arrTxBodySize,getSymbolBytes(blockBody.length - bais.available(),blockBody));
    }
}
