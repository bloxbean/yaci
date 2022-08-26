package com.bloxbean.cardano.yaci.core.reactive;

import com.bloxbean.cardano.yaci.core.common.NetworkType;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bloxbean.cardano.yaci.core.common.Constants.*;

@Slf4j
public class BlockStreamer {
    private N2NClient n2nClient;
    private Flux<Block> blockFlux;

    private BlockStreamer() {

    }

    public static BlockStreamer fromLatest(NetworkType networkType) {
        switch (networkType) {
            case MAINNET:
                return fromLatest(MAINNET_IOHK_RELAY_ADDR, MAINNET_IOHK_RELAY_PORT,
                        networkType.getN2NVersionTable(), WELL_KNOWN_MAINNET_POINT);
            case LEGACY_TESTNET:
                return fromLatest(TESTNET_IOHK_RELAY_ADDR, TESTNET_IOHK_RELAY_PORT, networkType.getN2NVersionTable(), WELL_KNOWN_TESTNET_POINT);
            case PREPOD:
                return fromLatest(PREPOD_IOHK_RELAY_ADDR, PREPOD_IOHK_RELAY_PORT, networkType.getN2NVersionTable(), WELL_KNOWN_PREPOD_POINT);
            default:
                return null;
        }
    }

    public static BlockStreamer fromPoint(NetworkType networkType, Point point) {
        switch (networkType) {
            case MAINNET:
                return fromPoint(MAINNET_IOHK_RELAY_ADDR, MAINNET_IOHK_RELAY_PORT, networkType.getN2NVersionTable(), point);
            case LEGACY_TESTNET:
                return fromPoint(TESTNET_IOHK_RELAY_ADDR, TESTNET_IOHK_RELAY_PORT, networkType.getN2NVersionTable(), point);
            case PREPOD:
                return fromPoint(PREPOD_IOHK_RELAY_ADDR, PREPOD_IOHK_RELAY_PORT, networkType.getN2NVersionTable(), point);
            default:
                return null;
        }
    }

    public static BlockStreamer fromLatest(String host, int port, VersionTable versionTable, Point wellKnownPoint) {
        BlockStreamer blockStreamer = new BlockStreamer();
        blockStreamer.initBlockFluxFromPoint(host, port, versionTable, wellKnownPoint, true);
        return blockStreamer;
    }

    public static BlockStreamer fromPoint(String host, int port, VersionTable versionTable, Point point) {
        BlockStreamer blockStreamer = new BlockStreamer();
        blockStreamer.initBlockFluxFromPoint(host, port, versionTable, point, false);
        return blockStreamer;
    }

    public void shutdown() {
        if (n2nClient != null)
            n2nClient.shutdown();
    }

    public Flux<Block> stream() {
        return blockFlux;
    }

    private void initBlockFluxFromPoint(String host, int port, VersionTable versionTable, Point wellKnownPoint, boolean startFromTip) {
        final AtomicBoolean tipFound = new AtomicBoolean(false);

        ChainsyncAgent chainSyncAgent = new ChainsyncAgent(new Point[]{wellKnownPoint});
        BlockfetchAgent blockFetch = new BlockfetchAgent();
        HandshakeAgent handshakeAgent = new HandshakeAgent(versionTable);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                //start
                chainSyncAgent.sendNextMessage();
            }
        });

        n2nClient = new N2NClient(host, port, handshakeAgent,
                chainSyncAgent, blockFetch);

        blockFlux = Flux.create(sink -> {
            sink.onDispose(() -> {

            });

            blockFetch.addListener(
                    new BlockfetchAgentListener() {
                        @Override
                        public void blockFound(Block block) {
                            if (log.isTraceEnabled()) {
                                log.trace("Block found {}", block);
                            }
                            sink.next(block);
                            chainSyncAgent.sendNextMessage();
                        }

                        @Override
                        public void batchDone() {
                            if (log.isTraceEnabled())
                                log.trace("batchDone");
                        }
                    });

        });

        blockFlux = blockFlux.doOnSubscribe(subscription -> {
            if (!n2nClient.isRunning()) {
                log.debug("Subscription started");
                n2nClient.start();
            }
        });

        chainSyncAgent.addListener(new ChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
                log.debug("Intersact found {}", point);
                if (startFromTip) {
                    if (!tip.getPoint().equals(point) && !tipFound.get()) {
                        chainSyncAgent.reset(tip.getPoint());
                        tipFound.set(true);
                    }
                }
                //Now move to the point
                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void intersactNotFound(Tip tip) {
                log.error("IntersactNotFound: {}", tip);
            }

            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                long slot = blockHeader.getHeaderBody().getSlot();
                String hash = blockHeader.getHeaderBody().getBlockHash();

                blockFetch.resetPoints(new Point(slot, hash), new Point(slot, hash));

                if (log.isDebugEnabled())
                    log.debug("Trying to fetch block for {}", new Point(slot, hash));

                blockFetch.sendNextMessage();
            }

            @Override
            public void rollbackward(Tip tip, Point toPoint) {
                if (log.isDebugEnabled())
                    log.debug("Rolling backward {}", toPoint);

                //Rolled back. Find the next message
                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void onStateUpdate(State oldState, State newState) {
            }
        });
    }

    public static BlockStreamer forRange(NetworkType networkType, Point fromPoint, Point toPoint) {
        switch (networkType) {
            case MAINNET:
                return forRange(MAINNET_IOHK_RELAY_ADDR, MAINNET_IOHK_RELAY_PORT,
                        networkType.getN2NVersionTable(), fromPoint, toPoint);
            case LEGACY_TESTNET:
                return forRange(TESTNET_IOHK_RELAY_ADDR, TESTNET_IOHK_RELAY_PORT, networkType.getN2NVersionTable(), fromPoint, toPoint);
            case PREPOD:
                return forRange(PREPOD_IOHK_RELAY_ADDR, PREPOD_IOHK_RELAY_PORT, networkType.getN2NVersionTable(), fromPoint, toPoint);
            default:
                return null;
        }
    }

    public static BlockStreamer forRange(String host, int port, VersionTable versionTable, Point fromPoint, Point toPoint) {
        BlockStreamer blockStreamer = new BlockStreamer();
        blockStreamer.initBlockFluxForRange(host, port, versionTable, fromPoint, toPoint);
        return blockStreamer;
    }

    private void initBlockFluxForRange(String host, int port, VersionTable versionTable, Point fromPoint, Point toPoint) {
        BlockfetchAgent blockFetch = new BlockfetchAgent();
        blockFetch.resetPoints(fromPoint, toPoint);
        HandshakeAgent handshakeAgent = new HandshakeAgent(versionTable);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                //start
                blockFetch.sendNextMessage();
            }
        });

        N2NClient n2CClient = new N2NClient(host, port, handshakeAgent,
                blockFetch);

        AtomicInteger subscriberCount = new AtomicInteger(0);
        blockFlux = Flux.create(sink -> {
            sink.onDispose(() -> {
                int count = subscriberCount.decrementAndGet();
                if (count == 0)
                    n2CClient.shutdown();
            });

            blockFetch.addListener(
                    new BlockfetchAgentListener() {
                        @Override
                        public void blockFound(Block block) {
                            if (log.isTraceEnabled()) {
                                log.trace("Block found {}", block);
                            }
                            sink.next(block);
                        }

                        @Override
                        public void batchDone() {
                            if (log.isTraceEnabled())
                                log.trace("batchDone");
                            sink.complete();
                        }
                    });

        });

        blockFlux = blockFlux.doOnSubscribe(subscription -> {
            subscriberCount.incrementAndGet();
            if (!n2CClient.isRunning()) {
                log.debug("Subscription started");
                n2CClient.start();
            }
        });
    }
}
