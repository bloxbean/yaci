package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.model.leios.EndorserBlock;
import com.bloxbean.cardano.yaci.core.model.leios.EndorserBlockTx;
import com.bloxbean.cardano.yaci.core.model.leios.EndorserBlockTxRef;
import com.bloxbean.cardano.yaci.core.model.serializers.leios.EndorserBlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.leios.EndorserBlockTxListSerializer;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchAgentListener;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.helper.listener.LeiosDataListener;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches the transactions of certified endorser blocks (EBs) over LeiosFetch, on its own node-to-node connection
 * to the same peer. A separate connection means an EB answer can never wait behind block data that the main
 * connection has not read yet, so the main connection can keep its normal back-pressure.
 * <p>
 * Every answer is checked: the EB must hash to the requested EB hash, and every transaction must hash to the
 * EB's reference for it (blake2b-256 of the transaction bytes as received). Requests are sent one after another.
 */
@Slf4j
class EndorserBlockResolver implements CompleteBlockAssembler.EndorserBlockSource {
    private final String host;
    private final int port;
    private final long protocolMagic;
    private final VersionTable versionTable;
    private final Map<String, Request> requests = new ConcurrentHashMap<>();
    private volatile LeiosNetworkClient client;        //created on first use; replaced when it breaks
    private volatile CompletableFuture<Void> ready = new CompletableFuture<>();
    private volatile Throwable notActivated;           //the peer does not run Leios: fail every request at once
    private volatile boolean broken;                   //the fetch agent failed: start a new connection next time

    EndorserBlockResolver(String host, int port, long protocolMagic, VersionTable versionTable) {
        this.host = host;
        this.port = port;
        this.protocolMagic = protocolMagic;
        this.versionTable = versionTable;
    }

    private LeiosNetworkClient newClient() {
        LeiosNetworkClient newClient = new LeiosNetworkClient(host, port, protocolMagic, versionTable);
        newClient.addDataListener(new LeiosDataListener() {
            @Override
            public void onLeiosActivated(AcceptVersion acceptVersion) {
                log.debug("Leios connection ready: {}", acceptVersion);
                notActivated = null;
                ready.complete(null);
            }

            @Override
            public void onLeiosNotActivated(AcceptVersion acceptVersion) {
                notActivated = new IllegalStateException("The peer does not run the Leios mini-protocols (handshake "
                        + acceptVersion + "), so endorser block transactions cannot be fetched");
                failAll(notActivated);
            }

            @Override
            public void onDisconnect() {
                ready = new CompletableFuture<>();
                failAll(new IllegalStateException("Leios connection closed"));
            }
        });
        newClient.addFetchListener(new LeiosFetchAgentListener() {
            @Override
            public void onBlock(LeiosPoint requestedPoint, LeiosRawCbor endorserBlock) {
                Request request = requests.get(key(requestedPoint));
                if (request != null) {
                    request.onEndorserBlock(endorserBlock);
                }
            }

            @Override
            public void onBlockTxs(LeiosPoint requestedPoint, LeiosTxBitmap requestedBitmap, LeiosRawCbor txList) {
                Request request = requests.get(key(requestedPoint));
                if (request != null) {
                    request.onTransactions(txList);
                }
            }

            @Override
            public void onFetchError(Throwable error) {
                broken = true; //an error that names no request leaves the fetch agent unusable
                failAll(error);
            }

            @Override
            public void onFetchError(LeiosPoint requestedPoint, Throwable error) {
                Request request = requestedPoint != null ? requests.get(key(requestedPoint)) : null;
                if (request != null) {
                    request.fail(error);
                } else {
                    onFetchError(error);
                }
            }
        });
        return newClient;
    }

    /**
     * Fetch the transactions of a certified endorser block, in EB order.
     *
     * @return each transaction's bytes as served; completes exceptionally if the EB is not available or an answer
     * does not match the EB. The caller applies its own timeout.
     */
    @Override
    public synchronized CompletableFuture<List<byte[]>> resolve(LeiosPoint point) {
        Request existing = requests.get(key(point));
        if (existing != null) {
            return existing.result;
        }
        Request request = new Request(point);
        if (notActivated != null) {
            request.fail(notActivated);
            return request.result;
        }
        requests.put(key(point), request);
        request.result.whenComplete((txs, error) -> requests.remove(key(point), request));
        if (client == null || broken) {
            //called from the worker or the main connection's thread, never from this client's own thread
            if (client != null) {
                client.shutdown();
            }
            broken = false;
            ready = new CompletableFuture<>();
            client = newClient();
            client.start();
        }
        LeiosNetworkClient current = client;
        ready.thenRun(() -> {
            try {
                log.debug("Requesting endorser block {}", key(point));
                current.requestBlock(point);
            } catch (Exception e) {
                broken = true;
                request.fail(e);
            }
        });
        return request.result;
    }

    @Override
    public synchronized void close() {
        failAll(new IllegalStateException("Endorser block resolver closed"));
        if (client != null) {
            client.shutdown();
            client = null;
        }
    }

    //Runs on the Leios connection's own thread: must not take the resolver lock (resolve() may hold it while
    //shutting a broken connection down, which needs that thread)
    private void requestTransactions(LeiosPoint point, int count) {
        LeiosNetworkClient current = client;
        if (current == null) {
            throw new IllegalStateException("Endorser block resolver closed");
        }
        current.requestBlockTxs(point, LeiosTxBitmap.firstN(count));
    }

    private void failAll(Throwable error) {
        requests.values().forEach(request -> request.fail(error));
    }

    private static String key(LeiosPoint point) {
        return point.getSlot() + "/" + HexUtil.encodeHexString(point.getEbHash());
    }

    private final class Request {
        private final LeiosPoint point;
        private final CompletableFuture<List<byte[]>> result = new CompletableFuture<>();
        private List<EndorserBlockTxRef> refs;

        private Request(LeiosPoint point) {
            this.point = point;
        }

        private void onEndorserBlock(LeiosRawCbor ebCbor) {
            try {
                log.debug("Endorser block {}: {} bytes", key(point), ebCbor.getCbor().length);
                EndorserBlock eb = EndorserBlockSerializer.INSTANCE.deserialize(ebCbor.getCbor());
                if (eb.getTxRefs() == null || eb.getTxRefs().isEmpty()) {
                    fail(new IllegalStateException("The peer does not have this endorser block (empty answer)"));
                    return;
                }
                if (!HexUtil.encodeHexString(point.getEbHash()).equals(eb.getComputedHash())) {
                    fail(new IllegalStateException("Endorser block hash mismatch: " + eb.getComputedHash()));
                    return;
                }
                refs = eb.getTxRefs();
                log.debug("Requesting {} transactions of endorser block {}", refs.size(), key(point));
                requestTransactions(point, refs.size());
            } catch (Exception e) {
                fail(e);
            }
        }

        private void onTransactions(LeiosRawCbor txList) {
            try {
                log.debug("Transactions of endorser block {}: {} bytes", key(point), txList.getCbor().length);
                List<EndorserBlockTx> txs = EndorserBlockTxListSerializer.INSTANCE.deserialize(txList.getCbor());
                if (refs == null || txs.size() != refs.size()) {
                    fail(new IllegalStateException("Expected " + (refs == null ? "?" : refs.size())
                            + " endorser block transactions, received " + txs.size()));
                    return;
                }
                List<byte[]> bytes = new ArrayList<>(txs.size());
                for (int i = 0; i < txs.size(); i++) {
                    EndorserBlockTx tx = txs.get(i);
                    if (!tx.isParsed() || !refs.get(i).getTxHash().equals(tx.getTxHash())) {
                        fail(new IllegalStateException("Endorser block transaction " + i
                                + " does not match the endorser block"));
                        return;
                    }
                    bytes.add(HexUtil.decodeHexString(tx.getTxCbor()));
                }
                result.complete(bytes);
            } catch (Exception e) {
                fail(e);
            }
        }

        private void fail(Throwable error) {
            log.debug("Endorser block {} failed: {}", key(point), error.toString());
            result.completeExceptionally(error);
        }
    }
}
