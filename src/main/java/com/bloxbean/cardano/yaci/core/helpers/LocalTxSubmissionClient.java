package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.helpers.api.Fetcher;
import com.bloxbean.cardano.yaci.core.helpers.model.TxResult;
import com.bloxbean.cardano.yaci.core.network.N2CClient;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.localtx.LocalTxSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.localtx.LocalTxSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgAcceptTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgRejectTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * Use this helper to submit transactions to a local Cardano node through Node-to-client mini-protocol.
 * The transaction result can be received through <br/>
 * - a Consumer function passed to {@link #start(Consumer)} <br/>
 * - or, by adding a {@link LocalTxSubmissionListener} using {@link #addTxSubmissionListener(LocalTxSubmissionListener)}
 */
@Slf4j
public class LocalTxSubmissionClient implements Fetcher<TxResult> {
    private String nodeSocketFile;
    private VersionTable versionTable;
    private HandshakeAgent handshakeAgent;
    private LocalTxSubmissionAgent txSubmissionAgent;
    private N2CClient n2cClient;

    public LocalTxSubmissionClient(String nodeSocketFile, VersionTable versionTable) {
        this.nodeSocketFile = nodeSocketFile;
        this.versionTable = versionTable;
        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        txSubmissionAgent = new LocalTxSubmissionAgent();
        n2cClient = new N2CClient(nodeSocketFile, handshakeAgent, txSubmissionAgent);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                txSubmissionAgent.sendNextMessage();
            }
        });
    }

    @Override
    public void start(Consumer<TxResult> consumer) {
        txSubmissionAgent.addListener(new LocalTxSubmissionListener() {
            @Override
            public void txAccepted(TxSubmissionRequest txSubmissionRequest, MsgAcceptTx msgAcceptTx) {
                TxResult txResult = TxResult.builder()
                        .txHash(txSubmissionRequest.getTxHash())
                        .accepted(true)
                        .build();
                consumer.accept(txResult);
            }

            @Override
            public void txRejected(TxSubmissionRequest txSubmissionRequest, MsgRejectTx msgRejectTx) {
                TxResult txResult = TxResult.builder()
                        .txHash(txSubmissionRequest.getTxHash())
                        .accepted(false)
                        .errorCbor(msgRejectTx.getReasonCbor())
                        .build();
                consumer.accept(txResult);
            }
        });

        n2cClient.start();
        txSubmissionAgent.sendNextMessage();
    }

    public void submitTx(TxSubmissionRequest txSubmissionRequest) {
        txSubmissionAgent.submitTx(txSubmissionRequest);
        txSubmissionAgent.sendNextMessage();
    }

    @Override
    public void shutdown() {
        n2cClient.shutdown();
    }

    @Override
    public boolean isRunning() {
        return n2cClient.isRunning();
    }

    public void addTxSubmissionListener(LocalTxSubmissionListener listener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (listener != null)
            txSubmissionAgent.addListener(listener);
    }

}
