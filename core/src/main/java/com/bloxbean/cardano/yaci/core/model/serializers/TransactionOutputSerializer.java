package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Number;
import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.client.api.util.AssetUtil;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.StringUtil;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.Constants.LOVELACE;
import static com.bloxbean.cardano.yaci.core.util.StringUtil.sanitize;

public enum TransactionOutputSerializer implements Serializer<TransactionOutput> {
    INSTANCE;

    @Override
    public TransactionOutput deserializeDI(DataItem dataItem) {
        if (MajorType.ARRAY == dataItem.getMajorType()) { //Alonzo (Legacy)
            return deserializeAlonzo((Array) dataItem);
        } else if (MajorType.MAP == dataItem.getMajorType()) { //Post Alonzo
            return deserializePostAlonzo((Map) dataItem);
        } else
            throw new CborRuntimeException("Invalid type for TransactionOutput : " + dataItem.getMajorType());
    }

    private TransactionOutput deserializePostAlonzo(Map ouptutItem) {
        TransactionOutput.TransactionOutputBuilder outputBuilder = TransactionOutput.builder();

        //address
        ByteString addrByteStr = (ByteString) ouptutItem.get(new UnsignedInteger(0));
        if(addrByteStr != null) {
            try {
                outputBuilder.address(AddressUtil.bytesToAddress(addrByteStr.getBytes()));
            } catch (Exception e) {
                throw new CborRuntimeException("Bytes cannot be converted to bech32 address", e);
            }
        }

        //value
        DataItem valueItem = ouptutItem.get(new UnsignedInteger(1));
        List<Amount> amounts = parseValue(valueItem);
        outputBuilder.amounts(amounts);

        //datum_options
        String datumHash = null;
        String inlineDatum = null;
        Array datumOptionsItem = (Array) ouptutItem.get(new UnsignedInteger(2));
        if (datumOptionsItem != null) {
            List<DataItem> datumOptionsList = datumOptionsItem.getDataItems();
            if (datumOptionsList.size() != 2)
                throw new CborRuntimeException("Invalid size for datum_options : " + datumOptionsList.size());

            if (new UnsignedInteger(0).equals(datumOptionsList.get(0))) { //datum hash
                datumHash = HexUtil.encodeHexString(((ByteString) datumOptionsList.get(1)).getBytes());
            } else if (new UnsignedInteger(1).equals(datumOptionsList.get(0))) { //datum
                ByteString inlineDatumBS = (ByteString) datumOptionsList.get(1);
                //inlineDatum = Datum.deserialize(inlineDatumBS.getBytes());
                inlineDatum = HexUtil.encodeHexString(inlineDatumBS.getBytes());
            }
        }
        outputBuilder.datumHash(datumHash);
        outputBuilder.inlineDatum(inlineDatum);

        //script_ref
        ByteString scriptRefBs = (ByteString) ouptutItem.get(new UnsignedInteger(3));
        if (scriptRefBs != null) {
            outputBuilder.scriptRef(HexUtil.encodeHexString(scriptRefBs.getBytes()));
        }

        return outputBuilder.build();
    }

    private TransactionOutput deserializeAlonzo(Array ouptutItem)  {
        List<DataItem> items = ouptutItem.getDataItems();
        TransactionOutput.TransactionOutputBuilder outputBuilder = TransactionOutput.builder();

        if(items == null || (items.size() != 2 && items.size() != 3)) {
            throw new CborRuntimeException("TransactionOutput deserialization failed. Invalid no of DataItems");
        }

        ByteString addrByteStr = (ByteString)items.get(0);
        if(addrByteStr != null) {
            try {
                outputBuilder.address(AddressUtil.bytesToAddress(addrByteStr.getBytes()));
            } catch (Exception e) {
                throw new CborRuntimeException("Bytes cannot be converted to bech32 address", e);
            }
        }

        DataItem valueItem = items.get(1);
        List<Amount> amounts = parseValue(valueItem);
        outputBuilder.amounts(amounts);

        if (items.size() == 3) {
            ByteString datumBytes = (ByteString) items.get(2);
            if(datumBytes != null) {
                outputBuilder.datumHash(HexUtil.encodeHexString(datumBytes.getBytes()));
            }
        }

        return outputBuilder.build();
    }

    private List<Amount> parseValue(DataItem valueItem) {
        List<Amount> amounts = new ArrayList<>();

        if(MajorType.UNSIGNED_INTEGER.equals(valueItem.getMajorType()) || MajorType.NEGATIVE_INTEGER.equals(valueItem.getMajorType())) {
            Amount amount = Amount.builder()
                    .unit(LOVELACE)
                    .assetName(LOVELACE)
                    .quantity(((Number) valueItem).getValue())
                    .build();
            amounts.add(amount);
        } else if(MajorType.BYTE_STRING.equals(valueItem.getMajorType())) { //For BigNum. >  2 pow 64 Tag 2
            if(valueItem.getTag().getValue() == 2) {
                Amount amount = Amount.builder()
                        .unit(LOVELACE)
                        .assetName(LOVELACE)
                        .quantity(new BigInteger(((ByteString) valueItem).getBytes()))
                        .build();
                amounts.add(amount);
            } else if(valueItem.getTag().getValue() == 3) {
                Amount amount = Amount.builder()
                        .unit(LOVELACE)
                        .assetName(LOVELACE)
                        .quantity(new BigInteger(((ByteString) valueItem).getBytes()).multiply(BigInteger.valueOf(-1)))
                        .build();
                amounts.add(amount);
            }
        } else if(MajorType.ARRAY.equals(valueItem.getMajorType())) {
            Array coinAssetArray = (Array) valueItem;
            DataItem valueDI = coinAssetArray.getDataItems().get(0);
            BigInteger coin = CborSerializationUtil.toBigInteger(valueDI);
            Amount lovelaceAmount = Amount.builder()
                    .unit(LOVELACE)
                    .assetName(LOVELACE)
                    .quantity(coin).build();
            amounts.add(lovelaceAmount);

            Map multiAssetsMap = (Map) coinAssetArray.getDataItems().get(1);
            if (multiAssetsMap != null) {
                for (DataItem key : multiAssetsMap.getKeys()) {
                    ByteString keyBS = (ByteString) key;
                    String policyId = HexUtil.encodeHexString(keyBS.getBytes());

                    Map assetsMap = (Map) multiAssetsMap.get(key);
                    for (DataItem assetKey : assetsMap.getKeys()) {
                        ByteString assetNameBS = (ByteString) assetKey;

                        DataItem assetValueDI = assetsMap.get(assetKey);
                        BigInteger value = CborSerializationUtil.toBigInteger(assetValueDI);

                        String hexName = HexUtil.encodeHexString(assetNameBS.getBytes(), false);
                        String assetName = StringUtil.isUtf8(assetNameBS.getBytes())?
                                new String(assetNameBS.getBytes(), StandardCharsets.UTF_8): AssetUtil.calculateFingerPrint(policyId, hexName);

                        Amount amount = Amount.builder()
                                .unit(policyId + "." + hexName)
                                .policyId(policyId)
                                .assetName(sanitize(assetName))
                                .assetNameBytes(assetNameBS.getBytes())
                                .quantity(value).build();

                        amounts.add(amount);
                    }
                }
            }
        }

        return amounts;
    }

}
