package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.localtx.LocalTxSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.localtx.LocalTxSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgAcceptTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgRejectTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionRequest;
import com.bloxbean.cardano.yaci.helper.api.QueryClient;
import com.bloxbean.cardano.yaci.helper.model.TxResult;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Use this helper to submit transactions to a Cardano node through Node-to-client mini-protocol.
 * The transaction result can be received through <br>
 * - a reactive way using Mono <br>
 * - or, in a {@link LocalTxSubmissionListener}
 *
 * <p>Example:</p>
 * <pre>
 * {@code
 *  LocalClientProvider LocalClientProvider = new LocalClientProvider(nodeSocketFile, protocolMagic);
 *  LocalTxSubmissionClient localTxSubmissionClient = LocalClientProvider.getTxSubmissionClient();
 *  localClientProvider.start();
 *
 * byte[] txBytes = ...;
 * TxSubmissionRequest txnRequest = new TxSubmissionRequest(TxBodyType.BABBAGE, txBytes);
 * Mono<TxResult> txResultMono = localTxSubmissionClient.submitTx(txnRequest);
 * txResultMono.subscribe(txResult -> {
 *      String txId = txResult.getTxHash();
 * });
 * }</pre>
 */
@Slf4j
public class LocalTxSubmissionClient extends QueryClient {
    private final LocalTxSubmissionAgent localTxSubmissionAgent;

    public LocalTxSubmissionClient(LocalTxSubmissionAgent localTxSubmissionAgent) {
        this.localTxSubmissionAgent = localTxSubmissionAgent;
        init();
    }

    private void init() {
        localTxSubmissionAgent.addListener(new LocalTxSubmissionListener() {
            @Override
            public void txAccepted(TxSubmissionRequest txSubmissionRequest, MsgAcceptTx msgAcceptTx) {
                TxResult txResult = TxResult.builder()
                        .txHash(txSubmissionRequest.getTxHash())
                        .accepted(true)
                        .build();

                applyMonoSuccess(txSubmissionRequest, txResult);
            }

            @Override
            public void txRejected(TxSubmissionRequest txSubmissionRequest, MsgRejectTx msgRejectTx) {
                TxResult txResult = TxResult.builder()
                        .txHash(txSubmissionRequest.getTxHash())
                        .accepted(false)
                        .errorCbor(msgRejectTx.getReasonCbor())
                        .errorMessage(msgRejectTx.getUserFriendlyMessage())
                        .parsedError(msgRejectTx.getParsedError())
                        .build();

                applyMonoSuccess(txSubmissionRequest, txResult);
            }

            @Override
            public void onDisconnect() {
                applyError("Connection Error !!!");
            }
        });
    }

    /**
     * Submit transaction to the Cardano network and get the result through registered {@link LocalTxSubmissionListener}
     *
     * @param txSubmissionRequest
     */
    public void submitTxCallback(TxSubmissionRequest txSubmissionRequest) {
        localTxSubmissionAgent.submitTx(txSubmissionRequest);
        localTxSubmissionAgent.sendNextMessage();
    }

    /**
     * Submit transaction to the local Cardano network
     * @param txSubmissionRequest
     * @return Mono with TxResult
     */
    public Mono<TxResult> submitTx(TxSubmissionRequest txSubmissionRequest) {
        return Mono.create(monoSink -> {
            localTxSubmissionAgent.submitTx(txSubmissionRequest);
            storeMonoSinkReference(txSubmissionRequest, monoSink);
            localTxSubmissionAgent.sendNextMessage();
        });
    }
}
