package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.api.util.AssetUtil;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.governance.ProposalProcedure;
import com.bloxbean.cardano.yaci.core.model.governance.VotingProcedures;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.ProposalProcedureSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.VotingProceduresSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.StringUtil;
import com.bloxbean.cardano.yaci.core.util.TxUtil;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBigInteger;
import static com.bloxbean.cardano.yaci.core.util.StringUtil.sanitize;

@Slf4j
public enum TransactionBodySerializer implements Serializer<TransactionBody> {
    INSTANCE;

    public TransactionBody deserializeDI(DataItem di, byte[] txBytes) {
        Map bodyMap = (Map) di;

        TransactionBody.TransactionBodyBuilder transactionBodyBuilder = TransactionBody.builder();

        //derive
        String txHash = TxUtil.calculateTxHash(txBytes);
        transactionBodyBuilder.txHash(txHash);

        if (YaciConfig.INSTANCE.isReturnTxBodyCbor()) {
            transactionBodyBuilder.cbor(HexUtil.encodeHexString(txBytes));
        }

        Array inputArray =  (Array)bodyMap.get(new UnsignedInteger(0));
        Set<TransactionInput> inputs = new LinkedHashSet<>();
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
                    BigInteger value = toBigInteger(assetValueDI);

                    String hexName = HexUtil.encodeHexString(assetNameBS.getBytes(), false);
                    String assetName = StringUtil.isUtf8(assetNameBS.getBytes())?
                            new String(assetNameBS.getBytes(), StandardCharsets.UTF_8): AssetUtil.calculateFingerPrint(policyId, hexName);

                    Amount amount = Amount.builder()
                            .unit(policyId + "." + hexName)
                            .policyId(policyId)
                            .assetName(sanitize(assetName))
                            .assetNameBytes(assetNameBS.getBytes())
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
                if (inputItem == Special.BREAK)
                    continue;

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
                if (requiredSigDI == Special.BREAK)
                    continue;

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
            Set<TransactionInput> referenceInputs = new LinkedHashSet<>();
            for (DataItem inputItem : referenceInputsArray.getDataItems()) {
                if (inputItem == Special.BREAK)
                    continue;

                TransactionInput ti = TransactionInputSerializer.INSTANCE.deserializeDI(inputItem);
                referenceInputs.add(ti);
            }
            transactionBodyBuilder.referenceInputs(referenceInputs);
        }

        //Voting procedures 19
        DataItem votingProceduresDI = bodyMap.get(new UnsignedInteger(19));
        if (votingProceduresDI != null) {
            VotingProcedures votingProcedures = VotingProceduresSerializer.INSTANCE.deserializeDI(votingProceduresDI);
            transactionBodyBuilder.votingProcedures(votingProcedures);
        }
        //Proposal procedure 20
        DataItem proposalProcedureDI = bodyMap.get(new UnsignedInteger(20));
        if (proposalProcedureDI != null) {
            List<ProposalProcedure> proposalProcedures = new ArrayList<>();
            for (DataItem ppDI: ((Array)proposalProcedureDI).getDataItems()) {
                if (ppDI == Special.BREAK)
                    continue;
                ProposalProcedure proposalProcedure = ProposalProcedureSerializer.INSTANCE.deserializeDI(ppDI);
                proposalProcedures.add(proposalProcedure);
            }
            transactionBodyBuilder.proposalProcedures(proposalProcedures);
        }

        //Current Treasury Value 21
        DataItem currentTreasuryValueDI = bodyMap.get(new UnsignedInteger(21));
        if (currentTreasuryValueDI != null) {
            BigInteger currentTreasuryValue = toBigInteger(currentTreasuryValueDI);
            transactionBodyBuilder.currentTreasuryValue(currentTreasuryValue);
        }

        //Donatioin Coin 22
        DataItem donationDI = bodyMap.get(new UnsignedInteger(22));
        if (donationDI != null) {
            BigInteger donation = toBigInteger(donationDI);
            transactionBodyBuilder.donation(donation);
        }

        return transactionBodyBuilder.build();

    }
}
