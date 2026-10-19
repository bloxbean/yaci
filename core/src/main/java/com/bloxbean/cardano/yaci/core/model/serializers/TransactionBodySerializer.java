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
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import co.nstant.in.cbor.CborException;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.StringUtil;
import com.bloxbean.cardano.yaci.core.util.TxUtil;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBigInteger;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBytes;
import static com.bloxbean.cardano.yaci.core.util.StringUtil.sanitize;

@Slf4j
public enum TransactionBodySerializer implements Serializer<TransactionBody> {
    INSTANCE;

    public TransactionBody deserializeDI(DataItem di, byte[] txBytes) {
        return deserializeDI(di, txBytes, false);
    }

    /**
     * @param dijkstra true for a Dijkstra-era transaction body: fills {@code guards} for key 14 and reads the
     *                 Dijkstra-only keys 23-27. With false the result is the same as before Dijkstra support.
     */
    public TransactionBody deserializeDI(DataItem di, byte[] txBytes, boolean dijkstra) {
        return deserialize(di, txBytes, dijkstra, false);
    }

    /** A Dijkstra sub_transaction_body: like a transaction body, without keys 23 and 27. */
    TransactionBody deserializeSubTransactionBody(DataItem di, byte[] txBytes) {
        return deserialize(di, txBytes, true, true);
    }

    private TransactionBody deserialize(DataItem di, byte[] txBytes, boolean dijkstra, boolean subTransaction) {
        Map bodyMap = (Map) di;

        TransactionBody.TransactionBodyBuilder transactionBodyBuilder = TransactionBody.builder();

        //derive
        String txHash = TxUtil.calculateTxHash(txBytes);
        transactionBodyBuilder.txHash(txHash);

        if (YaciConfig.INSTANCE.isReturnTxBodyCbor() || YaciConfig.INSTANCE.isReturnFullTxCbor()) {
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
            //Dijkstra guards = nonempty_set<addr_keyhash> / nonempty_oset<credential>
            List<Credential> guards = new ArrayList<>();
            boolean credentialGuards = false;
            for (DataItem requiredSigDI: requiredSignerArray.getDataItems()) {
                if (requiredSigDI == Special.BREAK)
                    continue;

                if (requiredSigDI instanceof ByteString requiredSigBS) {
                    requiredSigners.add(HexUtil.encodeHexString(requiredSigBS.getBytes()));
                    guards.add(Credential.fromKeyHash(requiredSigBS.getBytes()));
                } else if (isCredential(requiredSigDI)) {
                    //credential = [0, addr_keyhash] / [1, script_hash]. A key hash is also a required signer
                    credentialGuards = true;
                    Credential guard = Credential.deserialize((Array) requiredSigDI);
                    guards.add(guard);
                    if (guard.getType() == StakeCredType.ADDR_KEYHASH) {
                        requiredSigners.add(guard.getHash());
                    }
                } else {
                    //Fail loud: silently hex-encoding the CBOR framing would ship a bogus signer downstream
                    throw new IllegalStateException("required_signers element is not a byte string. Major type: "
                            + requiredSigDI.getMajorType());
                }
            }
            transactionBodyBuilder.requiredSigners(requiredSigners);
            if (dijkstra || credentialGuards) {
                transactionBodyBuilder.guards(guards);
            }
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

        if (dijkstra) {
            readDijkstraFields(bodyMap, txBytes, transactionBodyBuilder, subTransaction);
        }

        return transactionBodyBuilder.build();

    }

    /**
     * Dijkstra-only keys 23-27. Each key is read on its own: a value Yaci cannot read is logged and left null, and
     * the rest of the transaction (and block) is still delivered, as before these keys were read.
     *
     * @param subTransaction true for a sub_transaction_body, which has no keys 23 and 27
     */
    private void readDijkstraFields(Map bodyMap, byte[] txBytes, TransactionBody.TransactionBodyBuilder builder,
                                    boolean subTransaction) {
        //23: sub_transactions, from the original bytes so each sub-transaction keeps its own id
        if (!subTransaction && bodyMap.get(new UnsignedInteger(23)) != null) {
            readField(23, txBytes, () -> builder.subTransactions(
                    SubTransactionSerializer.INSTANCE.deserialize(bodyValue(txBytes, 23))));
        }

        //24: required_top_level_guards = {+ credential => plutus_data / nil}
        if (bodyMap.get(new UnsignedInteger(24)) != null) {
            readField(24, txBytes, () -> {
                List<RequiredTopLevelGuard> guards = new ArrayList<>();
                List<CborSlice> entries = slices(bodyValue(txBytes, 24), MajorType.MAP);
                for (int i = 0; i < entries.size(); i += 2) {
                    byte[] datum = entries.get(i + 1).bytes();
                    guards.add(new RequiredTopLevelGuard(
                            Credential.deserialize((Array) CborSerializationUtil.deserializeOne(entries.get(i).bytes())),
                            isNil(datum) ? null : HexUtil.encodeHexString(datum))); //datum bytes as on chain
                }
                builder.requiredTopLevelGuards(guards);
            });
        }

        //25: direct_deposits = {+ reward_account => coin}
        DataItem directDeposits = bodyMap.get(new UnsignedInteger(25));
        if (directDeposits != null) {
            readField(25, txBytes, () -> builder.directDeposits(WithdrawalsSerializer.INSTANCE.deserializeDI(directDeposits)));
        }

        //26, 27: {+ reward_account => account_balance_interval}
        DataItem balanceIntervals = bodyMap.get(new UnsignedInteger(26));
        if (balanceIntervals != null) {
            readField(26, txBytes, () -> builder.accountBalanceIntervals(accountBalanceIntervals((Map) balanceIntervals)));
        }
        DataItem startingBalanceIntervals = bodyMap.get(new UnsignedInteger(27));
        if (!subTransaction && startingBalanceIntervals != null) {
            readField(27, txBytes, () -> builder.startingAccountBalanceIntervals(
                    accountBalanceIntervals((Map) startingBalanceIntervals)));
        }
    }

    private static void readField(int key, byte[] txBytes, Runnable read) {
        try {
            read.run();
        } catch (RuntimeException | StackOverflowError e) {
            log.warn("Dijkstra transaction body key {} could not be read and is left null. Tx: {}, error: {}",
                    key, TxUtil.calculateTxHash(txBytes), e.toString());
        }
    }

    private static java.util.Map<String, AccountBalanceInterval> accountBalanceIntervals(Map map) {
        java.util.Map<String, AccountBalanceInterval> intervals = new LinkedHashMap<>();
        for (DataItem key : map.getKeys()) {
            intervals.put(HexUtil.encodeHexString(toBytes(key)), accountBalanceInterval(map.get(key)));
        }
        return intervals;
    }

    static AccountBalanceInterval accountBalanceInterval(DataItem di) {
        if (di instanceof Array array) { //[lower / nil, upper / nil]
            List<DataItem> bounds = array.getDataItems();
            return new AccountBalanceInterval(bound(bounds.get(0)), bound(bounds.get(1)), null);
        }
        return AccountBalanceInterval.exact(toBigInteger(di)); //coin: an exact balance
    }

    private static BigInteger bound(DataItem di) {
        return SimpleValue.NULL.equals(di) ? null : toBigInteger(di);
    }

    private static boolean isNil(byte[] cbor) {
        return cbor.length == 1 && (cbor[0] & 0xff) == 0xf6;
    }

    /** The value of a body map key, as the exact source bytes. */
    private static CborSlice bodyValue(byte[] txBytes, int key) {
        List<CborSlice> entries = slices(sliceOf(txBytes), MajorType.MAP);
        for (int i = 0; i < entries.size(); i += 2) {
            DataItem keyDI = CborSerializationUtil.deserializeOne(entries.get(i).bytes());
            if (keyDI instanceof UnsignedInteger ui && ui.getValue().intValue() == key) {
                return entries.get(i + 1);
            }
        }
        throw new CborRuntimeException("Transaction body has no key " + key);
    }

    private static CborSlice sliceOf(byte[] bytes) {
        try {
            return CborSlice.of(bytes);
        } catch (CborException e) {
            throw new CborRuntimeException("Invalid transaction body CBOR", e);
        }
    }

    static List<CborSlice> slices(CborSlice slice, MajorType type) {
        try {
            return slice.items(type);
        } catch (CborException e) {
            throw new CborRuntimeException("Expected CBOR " + type, e);
        }
    }

    private static boolean isCredential(DataItem dataItem) {
        if (!(dataItem instanceof Array array)) return false;
        List<DataItem> items = array.getDataItems();
        return items.size() == 2 && items.get(0) instanceof UnsignedInteger type
                && type.getValue().intValue() <= 1 && items.get(1) instanceof ByteString;
    }
}
