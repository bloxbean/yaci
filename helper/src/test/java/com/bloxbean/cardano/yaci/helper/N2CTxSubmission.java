package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.common.NetworkType;
import com.bloxbean.cardano.yaci.core.protocol.localtx.LocalTxSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgAcceptTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgRejectTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionRequest;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class N2CTxSubmission {

    private static final String MNEMONIC_PREPROD = "piano express battle indicate argue boy hero kit game clarify vehicle then tunnel worry brave pass congress bicycle fortune eyebrow face federal mask cement";

    public static void main(String[] args) throws InterruptedException {

        var account = new Account(Networks.preprod(), MNEMONIC_PREPROD);
        log.info("address: {}", account.getBaseAddress().getAddress());

        LocalClientProvider localClientProvider = new LocalClientProvider("192.168.0.15", 30110, NetworkType.PREPROD.getProtocolMagic());
        localClientProvider.addTxSubmissionListener(new LocalTxSubmissionListener() {
            @Override
            public void txAccepted(TxSubmissionRequest txSubmissionRequest, MsgAcceptTx msgAcceptTx) {
                log.info("Accepted");
            }

            @Override
            public void txRejected(TxSubmissionRequest txSubmissionRequest, MsgRejectTx msgRejectTx) {
                log.info("Rejected");
            }
        });
        localClientProvider.start();
        var client = localClientProvider.getTxSubmissionClient();

        var txEncoded = "84a300818258204d77bc019ede3afd35785a801693c3e96a85c2d6ea05e8a5b2b225bc947c9e3f010182825839001c1ffaf141ebbb8e3a7072bb15f50f938b994c82de2d175f358fc942441f00edfe1b8d6a84f0d19c25a9c8829442160c0b5c758094c423441a002dc6c0825839008c5bf0f2af6f1ef08bb3f6ec702dd16e1c514b7e1d12f7549b47db9f4d943c7af0aaec774757d4745d1a2c8dd3220e6ec2c9df23f757a2f81b00000002534a872c021a00029075a100818258209518c18103cbdab9c6e60b58ecc3e2eb439fef6519bb22570f391327381900a85840da208874993a6ac5955c090f2aed7d54d2d98d47b84ed79edaf3bb7d69844c9fa9ac62a56c5d26a807b2fb264c869efaf4e6889b6c6ac7555e1b5f570c77f405f5f6";
        var txBytes = HexUtil.decodeHexString(txEncoded);

        while (true) {

            var txRequest = new TxSubmissionRequest(txBytes);
            client.submitTxCallback(txRequest);

            Thread.sleep(5000L);

        }


    }

}
