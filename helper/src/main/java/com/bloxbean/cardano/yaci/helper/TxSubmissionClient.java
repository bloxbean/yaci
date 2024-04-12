package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveAgent;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.RequestTxIds;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.RequestTxs;
import lombok.extern.slf4j.Slf4j;

import static com.bloxbean.cardano.yaci.core.common.TxBodyType.BABBAGE;

/**
 * This helper is still under development.
 * Use {@link LocalTxSubmissionClient} to submitTx to a local Cardano node.
 * Tx submission with Node to Node protocol
 */
@Slf4j
public class TxSubmissionClient {
    private String host;
    private int port;
    private VersionTable versionTable;
    private HandshakeAgent handshakeAgent;
    private TxSubmissionAgent txSubmissionAgent;
    private KeepAliveAgent keepAliveAgent;
    private TCPNodeClient n2nClient;

    public TxSubmissionClient(String host, int port, VersionTable versionTable) {
        this.host = host;
        this.port = port;
        this.versionTable = versionTable;
        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        txSubmissionAgent = new TxSubmissionAgent();
        keepAliveAgent = new KeepAliveAgent();

        n2nClient = new TCPNodeClient(host, port, handshakeAgent, txSubmissionAgent, keepAliveAgent);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("Handshake okay: {}:{}", host, port);
                keepAliveAgent.sendKeepAlive(1234);
                txSubmissionAgent.sendNextMessage();
            }
        });

        txSubmissionAgent.addListener(new TxSubmissionListener() {
            @Override
            public void handleRequestTxs(RequestTxs requestTxs) {
                txSubmissionAgent.sendNextMessage();
            }

            @Override
            public void handleRequestTxIdsNonBlocking(RequestTxIds requestTxIds) {
                submit(false);
            }

            @Override
            public void handleRequestTxIdsBlocking(RequestTxIds requestTxIds) {
                submit(true);
            }

            private void submit(boolean isBlocking) {
                if (isBlocking) {
                    if (txSubmissionAgent.hasPendingTx()) {
                        txSubmissionAgent.sendNextMessage();
                    }
                } else {
                    txSubmissionAgent.sendNextMessage();
                }
            }

        });

    }

    public void addListener(TxSubmissionListener txSubmissionListener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (txSubmissionListener != null)
            txSubmissionAgent.addListener(txSubmissionListener);
    }

    public void start() {
        n2nClient.start();
    }

    public void shutdown() {
        n2nClient.shutdown();
    }

    public boolean isRunning() {
        return n2nClient.isRunning();
    }

    public void submitTxBytes(byte[] txBytes) {
        var txHash = TransactionUtil.getTxHash(txBytes);
        this.submitTxBytes(txHash, txBytes, BABBAGE);
    }

    public void submitTxBytes(String txHash, byte[] txBytes, TxBodyType txBodyType) {
        txSubmissionAgent.enqueueTransaction(txHash, txBytes, txBodyType);
    }

    public void sendKeepAlive() {
        int min = 1;
        int max = 65000;
        int randomNum = (int) (Math.random() * (max - min + 1)) + min;
        keepAliveAgent.sendKeepAlive(randomNum);
    }

}
