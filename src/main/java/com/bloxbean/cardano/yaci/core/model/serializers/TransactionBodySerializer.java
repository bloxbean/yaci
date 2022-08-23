package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.TxUtil;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public enum TransactionBodySerializer implements Serializer<TransactionBody> {
    INSTANCE;

    @Override
    public TransactionBody deserializeDI(DataItem di) {
        Map bodyMap = (Map) di;

        TransactionBody.TransactionBodyBuilder transactionBodyBuilder = TransactionBody.builder();

        //derive
        String txHash = TxUtil.calculateTxHash(CborSerializationUtil.serialize(di));
        transactionBodyBuilder.txHash(txHash);

        Array inputArray =  (Array)bodyMap.get(new UnsignedInteger(0));
        Set<TransactionInput> inputs = new HashSet<>();
        for(DataItem inputItem: inputArray.getDataItems()) {
            if (inputItem == Special.BREAK)
                continue;

            TransactionInput ti = TransactionInputSerializer.INSTANCE.deserializeDI(inputItem);
            inputs.add(ti);
        }
        transactionBodyBuilder.inputs(inputs);

        Array outputArray =  (Array)bodyMap.get(new UnsignedInteger(1));
        List<TransactionOutput> outputs = new ArrayList<>();
        for(DataItem ouptutItem: outputArray.getDataItems()) {
            if (ouptutItem == Special.BREAK)
                continue;
            TransactionOutput to = TransactionOutputSerializer.INSTANCE.deserializeDI(ouptutItem);
            outputs.add(to);
        }
        transactionBodyBuilder.outputs(outputs);

        UnsignedInteger feeUI = (UnsignedInteger)bodyMap.get(new UnsignedInteger(2));
        if(feeUI != null) {
            transactionBodyBuilder.fee(feeUI.getValue());
        }

        UnsignedInteger ttlUI = (UnsignedInteger)bodyMap.get(new UnsignedInteger(3));
        if(ttlUI != null) {
            transactionBodyBuilder.ttl(ttlUI.getValue().longValue());
        }

        //certs
        List<Certificate> certificates = null;
        Array certArray = (Array)bodyMap.get(new UnsignedInteger(4));
        if (certArray != null && certArray.getDataItems() != null && certArray.getDataItems().size() > 0) {
            certificates = new ArrayList<>();
            for (DataItem dataItem: certArray.getDataItems()) {
                if (dataItem == Special.BREAK)
                    continue;
                Certificate cert = CertificateSerializer.INSTANCE.deserializeDI(dataItem);
                certificates.add(cert);
            }
        }
        if (certificates != null)
            transactionBodyBuilder.certificates(certificates);

//        //withdrawals
//        Map withdrawalMap = (Map)bodyMap.get(new UnsignedInteger(5));
//        if (withdrawalMap != null && withdrawalMap.getKeys() != null && withdrawalMap.getKeys().size() > 0) {
//            Collection<DataItem> addrKeys = withdrawalMap.getKeys();
//            for (DataItem addrKey: addrKeys) {
//                Withdrawal withdrawal = Withdrawal.deserialize(withdrawalMap, addrKey);
//                transactionBody.getWithdrawals().add(withdrawal);
//            }
//        }
//
//        ByteString metadataHashBS = (ByteString)bodyMap.get(new UnsignedInteger(7));
//        if(metadataHashBS != null) {
//            transactionBody.setAuxiliaryDataHash(metadataHashBS.getBytes());
//        }
//
//        UnsignedInteger validityStartIntervalUI = (UnsignedInteger)bodyMap.get(new UnsignedInteger(8));
//        if(validityStartIntervalUI != null) {
//            transactionBody.setValidityStartInterval(validityStartIntervalUI.getValue().longValue());
//        }

        //Mint
        Map mintMap = (Map)bodyMap.get(new UnsignedInteger(9));
        if(mintMap != null) {
            List<Amount> mintAssets = new ArrayList<>();
            for (DataItem key : mintMap.getKeys()) {
                ByteString keyBS = (ByteString) key;
                String policyId = HexUtil.encodeHexString(keyBS.getBytes());

                Map assetsMap = (Map) mintMap.get(key);
                for (DataItem assetKey : assetsMap.getKeys()) {
                    ByteString assetNameBS = (ByteString) assetKey;

                    DataItem assetValueDI = assetsMap.get(assetKey);
                    BigInteger value = CborSerializationUtil.toBigInteger(assetValueDI);

                    String name = HexUtil.encodeHexString(assetNameBS.getBytes(), true);
                    Amount amount = Amount.builder()
                            .unit(policyId + "." + name)
                            .assetName(new String(assetNameBS.getBytes()))
                            .quantity(value).build();

                    mintAssets.add(amount);
                }
            }
            transactionBodyBuilder.mint(mintAssets);
        }
//
//        //script_data_hash
//        ByteString scriptDataHashBS = (ByteString)bodyMap.get(new UnsignedInteger(11));
//        if (scriptDataHashBS != null) {
//            transactionBody.setScriptDataHash(scriptDataHashBS.getBytes());
//        }
//
//        //collateral
//        Array collateralArray =  (Array)bodyMap.get(new UnsignedInteger(13));
//        if (collateralArray != null) {
//            List<TransactionInput> collateral = new ArrayList<>();
//            for (DataItem inputItem : collateralArray.getDataItems()) {
//                TransactionInput ti = TransactionInput.deserialize((Array) inputItem);
//                collateral.add(ti);
//            }
//            transactionBody.setCollateral(collateral);
//        }
//
//        //required_signers
//        Array requiredSignerArray = (Array)bodyMap.get(new UnsignedInteger(14));
//        if (requiredSignerArray != null) {
//            List<byte[]> requiredSigners = new ArrayList<>();
//            for (DataItem requiredSigDI: requiredSignerArray.getDataItems()) {
//                ByteString requiredSigBS = (ByteString) requiredSigDI;
//                requiredSigners.add(requiredSigBS.getBytes());
//            }
//            transactionBody.setRequiredSigners(requiredSigners);
//        }
//
//        //network Id
//        UnsignedInteger networkIdUI = (UnsignedInteger) bodyMap.get(new UnsignedInteger(15));
//        if (networkIdUI != null) {
//            int networkIdInt = networkIdUI.getValue().intValue();
//            if (networkIdInt == 0) {
//                transactionBody.setNetworkId(NetworkId.TESTNET);
//            }else if (networkIdInt == 1) {
//                transactionBody.setNetworkId(NetworkId.MAINNET);
//            } else {
//                throw new CborDeserializationException("Invalid networkId value : " + networkIdInt);
//            }
//        }
//
//        //collateral return
//        DataItem collateralReturnDI = bodyMap.get(new UnsignedInteger(16));
//        if (collateralReturnDI != null) {
//            TransactionOutput collateralReturn = TransactionOutput.deserialize(collateralReturnDI);
//            transactionBody.setCollateralReturn(collateralReturn);
//        }
//
//        //total collateral
//        UnsignedInteger totalCollateralUI = (UnsignedInteger) bodyMap.get(new UnsignedInteger(17));
//        if (totalCollateralUI != null) {
//            transactionBody.setTotalCollateral(totalCollateralUI.getValue());
//        }
//
//        //reference inputs
//        Array referenceInputsArray =  (Array)bodyMap.get(new UnsignedInteger(18));
//        if (referenceInputsArray != null) {
//            List<TransactionInput> referenceInputs = new ArrayList<>();
//            for (DataItem inputItem : referenceInputsArray.getDataItems()) {
//                TransactionInput ti = TransactionInput.deserialize((Array) inputItem);
//                referenceInputs.add(ti);
//            }
//            transactionBody.setReferenceInputs(referenceInputs);
//        }

        return transactionBodyBuilder.build();

    }
}
