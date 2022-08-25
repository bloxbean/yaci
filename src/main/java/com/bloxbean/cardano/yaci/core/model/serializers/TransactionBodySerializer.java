package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.TxUtil;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public enum TransactionBodySerializer implements Serializer<TransactionBody> {
    INSTANCE;

    @Override
    public TransactionBody deserializeDI(DataItem di) {
        Map bodyMap = (Map) di;

        TransactionBody.TransactionBodyBuilder transactionBodyBuilder = TransactionBody.builder();

        //derive
        String txHash = TxUtil.calculateTxHash(CborSerializationUtil.serialize(di, false)); //disable canonical ordering
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

        //withdrawals
        Map withdrawalMap = (Map)bodyMap.get(new UnsignedInteger(5));
        if (withdrawalMap != null && withdrawalMap.getKeys() != null && withdrawalMap.getKeys().size() > 0) {
            java.util.Map<String, BigInteger> withdrawals = WithdrawalsSerializer.INSTANCE.deserializeDI(withdrawalMap);
            if (withdrawals != null)
                transactionBodyBuilder.withdrawals(withdrawals);
        }

        //Update
        DataItem updateDI = bodyMap.get(new UnsignedInteger(6));
        if (updateDI != null) {
            Update update = UpdateSerializer.INSTANCE.deserializeDI(updateDI);
            transactionBodyBuilder.update(update);
        }

        //Aux data hash
        ByteString metadataHashBS = (ByteString)bodyMap.get(new UnsignedInteger(7));
        if(metadataHashBS != null) {
            String auxDataHash = HexUtil.encodeHexString(metadataHashBS.getBytes());
            transactionBodyBuilder.auxiliaryDataHash(auxDataHash);
        }

        //Validity interval start
        UnsignedInteger validityStartIntervalUI = (UnsignedInteger)bodyMap.get(new UnsignedInteger(8));
        if(validityStartIntervalUI != null) {
            transactionBodyBuilder.validityIntervalStart(validityStartIntervalUI.getValue().longValue());
        }

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

        //script_data_hash
        ByteString scriptDataHashBS = (ByteString)bodyMap.get(new UnsignedInteger(11));
        if (scriptDataHashBS != null) {
            transactionBodyBuilder.scriptDataHash(HexUtil.encodeHexString(scriptDataHashBS.getBytes()));
        }

        //collateral inputs
        Array collateralArray =  (Array)bodyMap.get(new UnsignedInteger(13));
        if (collateralArray != null) {
            Set<TransactionInput> collateral = new HashSet<>();
            for (DataItem inputItem : collateralArray.getDataItems()) {
                TransactionInput ti = TransactionInputSerializer.INSTANCE.deserializeDI(inputItem);
                collateral.add(ti);
            }
            transactionBodyBuilder.collateralInputs(collateral);
        }

        //required_signers
        Array requiredSignerArray = (Array)bodyMap.get(new UnsignedInteger(14));
        if (requiredSignerArray != null) {
            Set<String> requiredSigners = new HashSet<>();
            for (DataItem requiredSigDI: requiredSignerArray.getDataItems()) {
                ByteString requiredSigBS = (ByteString) requiredSigDI;
                requiredSigners.add(HexUtil.encodeHexString(requiredSigBS.getBytes()));
            }
            transactionBodyBuilder.requiredSigners(requiredSigners);
        }

        //network Id
        UnsignedInteger networkIdUI = (UnsignedInteger) bodyMap.get(new UnsignedInteger(15));
        if (networkIdUI != null) {
            int networkIdInt = networkIdUI.getValue().intValue();
            if (networkIdInt == 0) {
                transactionBodyBuilder.netowrkId(0);
            }else if (networkIdInt == 1) {
                transactionBodyBuilder.netowrkId(1);
            } else {
                log.error("Invalid networkId value : " + networkIdInt);
            }
        }

        //collateral return
        DataItem collateralReturnDI = bodyMap.get(new UnsignedInteger(16));
        if (collateralReturnDI != null) {
            TransactionOutput collateralReturn = TransactionOutputSerializer.INSTANCE.deserializeDI(collateralReturnDI);
            transactionBodyBuilder.collateralReturn(collateralReturn);
        }

        //total collateral
        UnsignedInteger totalCollateralUI = (UnsignedInteger) bodyMap.get(new UnsignedInteger(17));
        if (totalCollateralUI != null) {
            transactionBodyBuilder.totalCollateral(totalCollateralUI.getValue());
        }

        //reference inputs
        Array referenceInputsArray =  (Array)bodyMap.get(new UnsignedInteger(18));
        if (referenceInputsArray != null) {
            Set<TransactionInput> referenceInputs = new HashSet<>();
            for (DataItem inputItem : referenceInputsArray.getDataItems()) {
                TransactionInput ti = TransactionInputSerializer.INSTANCE.deserializeDI(inputItem);
                referenceInputs.add(ti);
            }
            transactionBodyBuilder.referenceInputs(referenceInputs);
        }

        return transactionBodyBuilder.build();

    }
}
