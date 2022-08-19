package com.bloxbean.cardano.yaci.core.reactive;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.bloxbean.cardano.yaci.core.common.Constants.*;

@Slf4j
public class BlockStreamer {

    public static Flux<Block> fromLatest(boolean mainnet) {
        if (mainnet)
            return fromLatest(MAINNET_IOHK_RELAY_ADDR, MAINNET_IOHK_RELAY_PORT,
                    N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic()), WELL_KNOWN_MAINNET_POINT);
        else
            return fromLatest(TESTNET_IOHK_RELAY_ADDR, TESTNET_IOHK_RELAY_PORT,
                    N2NVersionTableConstant.v4AndAbove(Networks.testnet().getProtocolMagic()), WELL_KNOWN_TESTNET_POINT);
    }

    public static Flux<Block> fromLatest(String host, int port, VersionTable versionTable, Point wellKnownPoint) {
        final AtomicBoolean tipFound = new AtomicBoolean(false);

        Agent chainSyncAgent = new ChainsyncAgent(new Point[]{wellKnownPoint});
        Agent blockFetch = new BlockfetchAgent(wellKnownPoint, wellKnownPoint);

        N2NClient n2CClient = new N2NClient(host, port, new HandshakeAgent(versionTable),
                chainSyncAgent, blockFetch);

        Flux<Block> stream = Flux.create(sink -> {
            sink.onDispose(() -> {
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
                            chainSyncAgent.sendNextMessage();
                        }

                        @Override
                        public void batchDone() {
                            if (log.isTraceEnabled())
                                log.trace("batchDone");
                        }
                    });

        });

        stream = stream.doOnSubscribe(subscription -> {
            log.debug("Subscription started");
            n2CClient.start();

            chainSyncAgent.sendNextMessage();
        });

        chainSyncAgent.addListener(new ChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
                if (!tip.getPoint().equals(point) && !tipFound.get()) {
                    ((ChainsyncAgent) chainSyncAgent).reset(tip.getPoint());
                    tipFound.set(true);
                    chainSyncAgent.sendNextMessage();
                }
            }

            @Override
            public void intersactNotFound(Point point) {
                log.error("IntersactNotFound: {}", point);
            }

            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                long slot = blockHeader.getHeaderBody().getSlot();
                String hash = blockHeader.getHeaderBody().getBlockHash();

                ((BlockfetchAgent) blockFetch).reset(new Point(slot, hash), new Point(slot, hash));

                if (log.isDebugEnabled())
                    log.debug("Trying to fetch block for {}", new Point(slot, hash));

                blockFetch.sendNextMessage();
                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void rollbackward(Tip tip, Point toPoint) {
                if (log.isDebugEnabled())
                    log.debug("Rolling backward {}", toPoint);
                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void onStateUpdate(State oldState, State newState) {
                chainSyncAgent.sendNextMessage();
            }
        });

        return stream;
    }

}
