package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.helpers.api.Fetcher;
import com.bloxbean.cardano.yaci.core.helpers.model.TxResult;
import com.bloxbean.cardano.yaci.core.network.N2CClient;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.localtx.LocalTxSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.localtx.LocalTxSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgAcceptTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgRejectTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * Use this helper to submit transactions to a local Cardano node through Node-to-client mini-protocol.
 * The transaction result can be received through <br>
 * - a Consumer function passed to {@link #start(Consumer)} <br>
 * - or, by adding a {@link LocalTxSubmissionListener} using {@link #addTxSubmissionListener(LocalTxSubmissionListener)}
 *
 * <p>Example:</p>
 * <pre>
 * {@code
 * LocalTxSubmissionClient localTxSubmissionClient = new LocalTxSubmissionClient(nodeSocketFile, Constants.MAINNET_PROTOCOL_MAGIC);
 *
 * localTxSubmissionClient.start(txResult -> {
 *      log.info(" RESULT >> " + txResult);
 * });
 *
 * byte[] txBytes = ...;
 * TxSubmissionRequest txnRequest = new TxSubmissionRequest(TxBodyType.BABBAGE, txBytes);
 * localTxSubmissionClient.submitTx(txnRequest);
 * }
 * </pre>
 */
@Slf4j
public class LocalTxSubmissionClient implements Fetcher<TxResult> {
    private String nodeSocketFile;
    private VersionTable versionTable;
    private HandshakeAgent handshakeAgent;
    private LocalTxSubmissionAgent txSubmissionAgent;
    private N2CClient n2cClient;

    /**
     * Construct a LocalTxSubmissionClient
     * @param nodeSocketFile Cardano node socket file
     * @param protocolMagic Network protocol magic
     */
    public LocalTxSubmissionClient(String nodeSocketFile, long protocolMagic) {
        this(nodeSocketFile, N2CVersionTableConstant.v1AndAbove(protocolMagic));
    }

    /**
     * Construct a LocalTxSubmissionClient
     * @param nodeSocketFile Cardano node socket file
     * @param versionTable VersionTable for Node to Client protocol
     */
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

    /**
     * Establish the connection with the node. This method should be called first.
     * The transaction result can be received through the {@link Consumer} function passed to this method.
     * @param consumer
     */
    @Override
    public void start(Consumer<TxResult> consumer) {
        txSubmissionAgent.addListener(new LocalTxSubmissionListener() {
            @Override
            public void txAccepted(TxSubmissionRequest txSubmissionRequest, MsgAcceptTx msgAcceptTx) {
                if (consumer == null) return;
                TxResult txResult = TxResult.builder()
                        .txHash(txSubmissionRequest.getTxHash())
                        .accepted(true)
                        .build();
                consumer.accept(txResult);
            }

            @Override
            public void txRejected(TxSubmissionRequest txSubmissionRequest, MsgRejectTx msgRejectTx) {
                if (consumer == null) return;
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

    /**
     * Submit transaction to the Cardano network
     * @param txSubmissionRequest
     */
    public void submitTx(TxSubmissionRequest txSubmissionRequest) {
        txSubmissionAgent.submitTx(txSubmissionRequest);
        txSubmissionAgent.sendNextMessage();
    }

    /**
     * Shutdown the connection
     */
    @Override
    public void shutdown() {
        n2cClient.shutdown();
    }

    /**
     * Check if the connection is alive
     * @return true if alive, otherwise false
     */
    @Override
    public boolean isRunning() {
        return n2cClient.isRunning();
    }

    /**
     * Add a {@link LocalTxSubmissionListener} to listen {@link LocalTxSubmissionAgent} events
     * @param listener
     */
    public void addTxSubmissionListener(LocalTxSubmissionListener listener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (listener != null)
            txSubmissionAgent.addListener(listener);
    }

}
