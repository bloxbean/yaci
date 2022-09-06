package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.helpers.api.Fetcher;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * Use this fetcher to fetch blocks from Point-1 to Point-2 from a remote Cardano node using Node-to-node protocol.
 * There are two ways to receive blocks fetched by this fetcher
 * <p>
 * 1. through a consumer function passed to {@link #start(Consumer)},
 * <br>
 * 2. by attaching a {@link BlockfetchAgentListener} through {@link #addBlockFetchListener(BlockfetchAgentListener)}
 * </p>
 * <br>
 * <b>Note:</b> This fetcher can only return Shelley / Post-Shelley era blocks for now
 * <p></p>
 * <p>
 * Example: Fetch block from point-1 to point-2 and receive blocks in a {@link Consumer} function
 * </p>
 *
 * <pre>
 * {@code
 * BlockFetcher blockFetcher = new BlockFetcher(node, nodePort, Constants.MAINNET_PROTOCOL_MAGIC);
 * blockFetcher.start(block -> {
 *    //process block
 * });
 *
 * Point from = new Point(70895877, "094de3242c9cc6504851c9ca1f109c379840364bb6a1a941353c87cf1f22cf06");
 * Point to = new Point(70896002, "1f58983e784ff3eabd9bdb97808402086baffbf51742a120d3635df867c16ad9");
 * blockFetcher.fetch(from, to);
 *
 * blockFetcher.shutdown();
 *  }
 * </pre>
 */
@Slf4j
public class BlockFetcher implements Fetcher<Block> {
    private String host;
    private int port;
    private VersionTable versionTable;
    private HandshakeAgent handshakeAgent;
    private BlockfetchAgent blockfetchAgent;
    private N2NClient n2CClient;

    /**
     * Constructor to create BlockFetcher instance
     * @param host Cardano node host
     * @param port Cardano node port
     * @param protocolMagic Protocol Magic
     */
    public BlockFetcher(String host, int port, long protocolMagic) {
        this(host, port, N2NVersionTableConstant.v4AndAbove(protocolMagic));
    }

    /**
     * Constructor to create BlockFetcher instance
     * @param host Cardano node host
     * @param port Cardano node port
     * @param versionTable VersionTable for N2N protocol
     */
    public BlockFetcher(String host, int port, VersionTable versionTable) {
        this.host = host;
        this.port = port;
        this.versionTable = versionTable;
        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        blockfetchAgent = new BlockfetchAgent();
        n2CClient = new N2NClient(host, port, handshakeAgent, blockfetchAgent);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                blockfetchAgent.sendNextMessage();
            }
        });
    }

    /**
     * Invoke this method to establish the connection with remote Cardano node.
     * This method should be called only once.
     * @param receiver Consumer function to receive {@link Block}
     */
    public void start(Consumer<Block> receiver) {
        blockfetchAgent.addListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                receiver.accept(block);
            }
        });

        if (!n2CClient.isRunning())
            n2CClient.start();
    }

    /**
     * Invoke this method to fetch blocks
     * This method should be called after {@link #start(Consumer)}
     * @param from Start point
     * @param to End point
     */
    public void fetch(Point from, Point to) {
        if (!n2CClient.isRunning())
            throw new IllegalStateException("fetch() should be called after start()");

        blockfetchAgent.resetPoints(from, to);
        if (!blockfetchAgent.isDone())
            blockfetchAgent.sendNextMessage();
        else
            log.warn("Agent status is Done. Can't reschedule new points.");
    }

    /**
     * Add a {@link BlockfetchAgentListener} to listen different block fetch events from {@link BlockfetchAgent}
     * @param listener
     */
    public void addBlockFetchListener(BlockfetchAgentListener listener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (listener != null)
            blockfetchAgent.addListener(listener);
    }

    /**
     * Check if the agent connection is still alive
     * @return true if alive, false if not
     */
    @Override
    public boolean isRunning() {
        return n2CClient.isRunning();
    }

    /**
     * Invoke this method to close the socket connection to node
     */
    @Override
    public void shutdown() {
        n2CClient.shutdown();
    }

    public static void main(String[] args) {
        //shelley
        Point from = new Point(16588737, "4e9bbbb67e3ae262133d94c3da5bffce7b1127fc436e7433b87668dba34c354a");
        Point to = new Point(70223766, "21155bb822637508a91e9952e712040c0ea45107fb91898bfe8c9a95389b0d90");

        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic());
        BlockFetcher blockFetcher = new BlockFetcher("192.168.0.228", 6000, versionTable);

        blockFetcher.start(block -> {
            log.info("Block >>> {} -- {} {}", block.getHeader().getHeaderBody().getBlockNumber(), block.getHeader().getHeaderBody().getSlot() + "  ", block.getEra());
        });

        blockFetcher.fetch(from, to);
    }
}
