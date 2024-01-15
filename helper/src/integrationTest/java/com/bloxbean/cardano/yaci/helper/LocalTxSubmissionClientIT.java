package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.UtxoByAddressQuery;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.UtxoByAddressQueryResult;
import com.bloxbean.cardano.yaci.core.protocol.localtx.LocalTxSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgAcceptTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgRejectTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionRequest;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.bloxbean.cardano.client.common.ADAConversionUtil.adaToLovelace;
import static com.bloxbean.cardano.yaci.core.util.Constants.LOVELACE;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class LocalTxSubmissionClientIT extends BaseTest {

    @Test
    void submitTx() throws InterruptedException {
        LocalClientProvider localClientProvider = new LocalClientProvider(nodeSocketFile, N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalTxSubmissionClient localTxSubmissionClient = localClientProvider.getTxSubmissionClient();

        CountDownLatch countDownLatch = new CountDownLatch(1);
        localClientProvider.addTxSubmissionListener(new LocalTxSubmissionListener() {
            @Override
            public void txAccepted(TxSubmissionRequest txSubmissionRequest, MsgAcceptTx msgAcceptTx) {
                System.out.println("TxId : " + txSubmissionRequest.getTxHash());
            }

            @Override
            public void txRejected(TxSubmissionRequest txSubmissionRequest, MsgRejectTx msgRejectTx) {
                System.out.println("Rejected: " + msgRejectTx.getReasonCbor());
                countDownLatch.countDown();
            }
        });

        localClientProvider.setLocalClientProviderListener(() -> {
            System.out.println("Connection ready >>>>>>>>>>>>>>>>");
            byte[] txBytes = HexUtil.decodeHexString("84a300818258204d77bc019ede3afd35785a801693c3e96a85c2d6ea05e8a5b2b225bc947c9e3f010182825839001c1ffaf141ebbb8e3a7072bb15f50f938b994c82de2d175f358fc942441f00edfe1b8d6a84f0d19c25a9c8829442160c0b5c758094c423441a002dc6c0825839008c5bf0f2af6f1ef08bb3f6ec702dd16e1c514b7e1d12f7549b47db9f4d943c7af0aaec774757d4745d1a2c8dd3220e6ec2c9df23f757a2f81b00000002534a872c021a00029075a100818258209518c18103cbdab9c6e60b58ecc3e2eb439fef6519bb22570f391327381900a85840da208874993a6ac5955c090f2aed7d54d2d98d47b84ed79edaf3bb7d69844c9fa9ac62a56c5d26a807b2fb264c869efaf4e6889b6c6ac7555e1b5f570c77f405f5f6");
            TxSubmissionRequest txnRequest = new TxSubmissionRequest(TxBodyType.BABBAGE, txBytes);

            localTxSubmissionClient.submitTxCallback(txnRequest);
        });

        localClientProvider.start();
        countDownLatch.await(8000, TimeUnit.SECONDS);

//        byte[] txBytes = HexUtil.decodeHexString("84a300818258204d77bc019ede3afd35785a801693c3e96a85c2d6ea05e8a5b2b225bc947c9e3f010182825839001c1ffaf141ebbb8e3a7072bb15f50f938b994c82de2d175f358fc942441f00edfe1b8d6a84f0d19c25a9c8829442160c0b5c758094c423441a002dc6c0825839008c5bf0f2af6f1ef08bb3f6ec702dd16e1c514b7e1d12f7549b47db9f4d943c7af0aaec774757d4745d1a2c8dd3220e6ec2c9df23f757a2f81b00000002534a872c021a00029075a100818258209518c18103cbdab9c6e60b58ecc3e2eb439fef6519bb22570f391327381900a85840da208874993a6ac5955c090f2aed7d54d2d98d47b84ed79edaf3bb7d69844c9fa9ac62a56c5d26a807b2fb264c869efaf4e6889b6c6ac7555e1b5f570c77f405f5f6");
//        TxSubmissionRequest txnRequest = new TxSubmissionRequest(TxBodyType.BABBAGE, txBytes);
//
//        Mono<TxResult> txResultMono = localTxSubmissionClient.submitTx(txnRequest);
//        TxResult txResult = txResultMono.block(Duration.ofSeconds(20));
//        System.out.println(txResult);
//        assertThat(txResult.getErrorCbor()).isNotNull();
    }

    @SneakyThrows
    @Test
    void submitTx_validTx() {
        LocalClientProvider localClientProvider = new LocalClientProvider(nodeSocketFile, N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalTxSubmissionClient localTxSubmissionClient = localClientProvider.getTxSubmissionClient();

        localClientProvider.start();

        String senderAddr = "addr_test1qp8mg8c5950hhrj3mkfr9ggseae2aj24ya2rndegwzuuyr202959apwtpv7sp0t6vfjnzyr0232uent4urdx7snr23yqa533ha";
        String mnemonic = "wrist approve ethics forest knife treat noise great three simple prize happy toe dynamic number hunt trigger install wrong change decorate vendor glow erosion";

        Era era = null;
        if (protocolMagic == Constants.SANCHONET_PROTOCOL_MAGIC) {
            era = Era.Conway;
        } else {
            era = Era.Babbage;
        }

        Mono<UtxoByAddressQueryResult> mono = localClientProvider.getLocalStateQueryClient()
                .executeQuery(new UtxoByAddressQuery(era, new Address(senderAddr)));
        var utxoQueryResult = mono.block(Duration.ofSeconds(5));

        System.out.println(utxoQueryResult);

        var utxo = utxoQueryResult.getUtxoList()
                .stream()
                .filter(u -> {
                    if (u.getAmount().size() > 1)
                        return false;
                    var qty = u.getAmount().stream().filter(a -> a.getUnit().equals(LOVELACE)).findFirst().get().getQuantity().longValue();
                    return qty > 1200000L;
                }).findFirst().get();

        var amount = utxo.getAmount().get(0).getQuantity();
        BigInteger feeAmt = adaToLovelace(0.50);
        var finalAmount = amount.subtract(feeAmt);

        TransactionBody transactionBody = TransactionBody
                .builder()
                .inputs(List.of(new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex())))
                .outputs(List.of(
                        TransactionOutput.builder()
                                .address(senderAddr)
                                .value(Value.builder()
                                        .coin(finalAmount)
                                        .build())
                                .build()
                ))
                .fee(feeAmt)
                .build();

        Transaction transaction = Transaction.builder()
                .body(transactionBody)
                .build();

        Account account = new Account(Networks.testnet(), mnemonic);
        var signedTransaction = account.sign(transaction);

        var txSubmissionRequest = new TxSubmissionRequest(TxBodyType.BABBAGE, signedTransaction.serialize());
        var txResult = localTxSubmissionClient.submitTx(txSubmissionRequest).block(Duration.ofSeconds(20));

        System.out.println(signedTransaction.serializeToHex());
        System.out.println(txResult);
        assertThat(txResult.isAccepted()).isTrue();
    }

}
