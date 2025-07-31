package com.bloxbean.cardano.yaci.helper.reactive;

import com.bloxbean.cardano.yaci.core.common.NetworkType;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bloxbean.cardano.yaci.core.common.Constants.*;

/**
 * The reactive implementation is experimental and needs more testing.
 * <br>
 * This class provides reactive stream apis to stream blocks from a Cardano node.
 * Using this class, you can stream blocks
 * <p>
 * 1. from tip of the chain <br>
 * 2. from a given point <br>
 * 3. for a range from Point-1 to Point-2 <br>
 */
@Slf4j
public class BlockStreamer {
    private TCPNodeClient n2nClient;
    private Flux<Block> blockFlux;

    private BlockStreamer() {

    }

    /**
     * Get {@link BlockStreamer} to stream from the latest block
     *
     * @param networkType Network type (Mainnet, Legacy Testnet, Preprod, Preview)
     * @return a new BlockStreamer instance
     */
    public static BlockStreamer fromLatest(NetworkType networkType) {
        switch (networkType) {
            case MAINNET:
                return fromLatest(MAINNET_IOHK_RELAY_ADDR, MAINNET_IOHK_RELAY_PORT, WELL_KNOWN_MAINNET_POINT, networkType.getN2NVersionTable());
            case PREPROD:
                return fromLatest(PREPROD_IOHK_RELAY_ADDR, PREPROD_IOHK_RELAY_PORT, WELL_KNOWN_PREPROD_POINT, networkType.getN2NVersionTable());
            case PREVIEW:
                return fromLatest(PREVIEW_IOHK_RELAY_ADDR, PREVIEW_IOHK_RELAY_PORT, WELL_KNOWN_PREVIEW_POINT, networkType.getN2NVersionTable());
            default:
                return null;
        }
    }

    /**
     * Get {@link BlockStreamer} to stream from a known point
     *
     * @param networkType Network type (Mainnet, Legacy Testnet, Preprod, Preview)
     * @param point
     * @return a new BlockStreamer instance
     */
    public static BlockStreamer fromPoint(NetworkType networkType, Point point) {
        switch (networkType) {
            case MAINNET:
                return fromPoint(MAINNET_IOHK_RELAY_ADDR, MAINNET_IOHK_RELAY_PORT, point, networkType.getN2NVersionTable());
            case PREPROD:
                return fromPoint(PREPROD_IOHK_RELAY_ADDR, PREPROD_IOHK_RELAY_PORT, point, networkType.getN2NVersionTable());
            case PREVIEW:
                return fromPoint(PREVIEW_IOHK_RELAY_ADDR, PREVIEW_IOHK_RELAY_PORT, point, networkType.getN2NVersionTable());
            default:
                return null;
        }
    }

    /**
     * Get {@link BlockStreamer} to stream from latest block
     *
     * @param host           Cardano host
     * @param port           Cardano port
     * @param wellKnownPoint a well known point
     * @param versionTable   N2N version table
     * @return a new BlockStreamer instance
     */
    public static BlockStreamer fromLatest(String host, int port, Point wellKnownPoint, VersionTable versionTable) {
        BlockStreamer blockStreamer = new BlockStreamer();
        blockStreamer.initBlockFluxFromPoint(host, port, wellKnownPoint, versionTable, true);
        return blockStreamer;
    }


    /**
     * Get {@link BlockStreamer} to stream from a known point
     *
     * @param host         Cardano host
     * @param port         Cardano port
     * @param point        a known point
     * @param versionTable N2N version table
     * @return a new BlockStreamer instance
     */
    public static BlockStreamer fromPoint(String host, int port, Point point, VersionTable versionTable) {
        BlockStreamer blockStreamer = new BlockStreamer();
        blockStreamer.initBlockFluxFromPoint(host, port, point, versionTable, false);
        return blockStreamer;
    }

    /**
     * Shutdown streamer.
     * This method should be called to close the connection. The stream can't be reused after this method call.
     */
    public void shutdown() {
        if (n2nClient != null)
            n2nClient.shutdown();
    }

    /**
     * Get a reactive {@link Flux} which can be used to receive incoming {@link Block}
     *
     * @return a {@link Flux} for {@link Block}
     */
    public Flux<Block> stream() {
        return blockFlux;
    }

    private void initBlockFluxFromPoint(String host, int port, Point wellKnownPoint, VersionTable versionTable, boolean startFromTip) {
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

        n2nClient = new TCPNodeClient(host, port, handshakeAgent,
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

                            Point fetchedPoint = new Point(
                                block.getHeader().getHeaderBody().getSlot(),
                                block.getHeader().getHeaderBody().getBlockHash()
                            );
                            chainSyncAgent.confirmBlock(fetchedPoint);

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

    /**
     * Get {@link BlockStreamer} to stream from Point 1 to Point 2
     *
     * @param networkType networkType Network type (Mainnet, Legacy Testnet, Preprod, Preview)
     * @param fromPoint   start point
     * @param toPoint     end point
     * @return new BlockStreamer instance
     */
    public static BlockStreamer forRange(NetworkType networkType, Point fromPoint, Point toPoint) {
        switch (networkType) {
            case MAINNET:
                return forRange(MAINNET_IOHK_RELAY_ADDR, MAINNET_IOHK_RELAY_PORT, fromPoint, toPoint, networkType.getN2NVersionTable());
            case PREPROD:
                return forRange(PREPROD_IOHK_RELAY_ADDR, PREPROD_IOHK_RELAY_PORT, fromPoint, toPoint, networkType.getN2NVersionTable());
            case PREVIEW:
                return forRange(PREVIEW_IOHK_RELAY_ADDR, PREVIEW_IOHK_RELAY_PORT, fromPoint, toPoint, networkType.getN2NVersionTable());
            default:
                return null;
        }
    }

    /**
     * Get {@link BlockStreamer} to stream from Point 1 to Point 2
     *
     * @param host Cardano node host
     * @param port Cardano node port
     * @param fromPoint Start point
     * @param toPoint End point
     * @param versionTable N2N version table
     * @return
     */
    public static BlockStreamer forRange(String host, int port, Point fromPoint, Point toPoint, VersionTable versionTable) {
        BlockStreamer blockStreamer = new BlockStreamer();
        blockStreamer.initBlockFluxForRange(host, port, fromPoint, toPoint, versionTable);
        return blockStreamer;
    }

    private void initBlockFluxForRange(String host, int port, Point fromPoint, Point toPoint, VersionTable versionTable) {
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

        TCPNodeClient n2CClient = new TCPNodeClient(host, port, handshakeAgent,
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
