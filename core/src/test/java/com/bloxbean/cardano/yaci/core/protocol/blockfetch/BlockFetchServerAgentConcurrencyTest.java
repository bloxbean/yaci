package com.bloxbean.cardano.yaci.core.protocol.blockfetch;

import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoRequestDataEncoder;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.BatchDone;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.MsgBlock;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.RequestRange;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.StartBatch;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #207 / ADR 0014: the block-fetch server writes from one thread and schedules its batch loop only when there
 * is work.
 */
class BlockFetchServerAgentConcurrencyTest {
    private static final int SERVER_PROTOCOL_ID = 0x8003;
    private static final int RANGES = 20;
    private static final int BLOCKS_PER_RANGE = 5;
    private static final int BACKPRESSURE_BIT = 1;

    private final EventLoopGroup group = new DefaultEventLoopGroup(2);
    private BlockFetchServerAgent agent;
    private int expectedWireBytes;

    @AfterEach
    void tearDown() {
        if (agent != null)
            agent.shutdown();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    /**
     * Messages must reach the peer in protocol order, and the mux segments of a large block must not be interleaved
     * with another block-fetch message, while the channel keeps switching between writable and not writable during
     * a batch (a peer that reads slower than the server reads its store).
     */
    @Test
    void pipelinedBatchesStayInOrderAndUninterleavedUnderBackpressure() throws Exception {
        Random random = new Random(207);
        Map<String, byte[]> bodies = new HashMap<>();
        Map<Point, List<Point>> rangesByFrom = new HashMap<>();
        List<RequestRange> requests = new ArrayList<>();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();

        for (int r = 0; r < RANGES; r++) {
            List<Point> range = new ArrayList<>();
            write(expected, new StartBatch().serialize());
            for (int b = 0; b < BLOCKS_PER_RANGE; b++) {
                byte[] hash = new byte[32];
                random.nextBytes(hash);
                byte[] body = new byte[20_000 + random.nextInt(180_000)];
                random.nextBytes(body);
                Point point = new Point(r * 100L + b, HexUtil.encodeHexString(hash));
                range.add(point);
                bodies.put(point.getHash(), body);
                write(expected, new MsgBlock(body).serialize());
            }
            write(expected, new BatchDone().serialize());
            rangesByFrom.put(range.get(0), range);
            requests.add(new RequestRange(range.get(0), range.get(range.size() - 1)));
        }

        CompletableFuture<Channel> serverChannel = new CompletableFuture<>();
        ChainState chainState = mock(ChainState.class);
        when(chainState.findBlocksInRange(any(), any()))
                .thenAnswer(inv -> rangesByFrom.get((Point) inv.getArgument(0)));
        when(chainState.hasBlock(any())).thenReturn(true);
        when(chainState.getBlock(any())).thenAnswer(inv -> {
            // Each store read finds the outbound buffer full; the event loop drains it shortly after
            Channel ch = serverChannel.get();
            ch.unsafe().outboundBuffer().setUserDefinedWritability(BACKPRESSURE_BIT, false);
            ch.eventLoop().schedule(() -> ch.unsafe().outboundBuffer()
                    .setUserDefinedWritability(BACKPRESSURE_BIT, true), 200, TimeUnit.MICROSECONDS);
            Thread.sleep(0, 300_000);
            return bodies.get(HexUtil.encodeHexString((byte[]) inv.getArgument(0)));
        });
        agent = new BlockFetchServerAgent(chainState);

        LocalAddress address = new LocalAddress("blockfetch-207-" + UUID.randomUUID());
        Channel listener = new ServerBootstrap()
                .group(group)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    protected void initChannel(LocalChannel ch) {
                        ch.pipeline().addLast(new MiniProtoRequestDataEncoder(), new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                serverChannel.complete(ctx.channel());
                                ctx.fireChannelActive();
                            }

                            @Override
                            public void channelWritabilityChanged(ChannelHandlerContext ctx) {
                                // as MiniProtoServerInboundHandler does
                                agent.onChannelWritabilityChanged(ctx.channel());
                                ctx.fireChannelWritabilityChanged();
                            }
                        });
                    }
                })
                .bind(address).sync().channel();

        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        CompletableFuture<Void> received = new CompletableFuture<>();
        Channel client = new Bootstrap()
                .group(group)
                .channel(LocalChannel.class)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ByteBuf buf = (ByteBuf) msg;
                        try {
                            byte[] bytes = new byte[buf.readableBytes()];
                            buf.readBytes(bytes);
                            wire.writeBytes(bytes);
                        } finally {
                            buf.release();
                        }
                        if (wire.size() >= expectedWireBytes)
                            received.complete(null);
                    }
                })
                .connect(address).sync().channel();

        Channel server = serverChannel.get(5, TimeUnit.SECONDS);
        agent.setChannel(server);
        for (RequestRange request : requests)
            server.eventLoop().execute(() -> agent.receiveResponse(request));

        received.get(60, TimeUnit.SECONDS);
        client.close().sync();
        listener.close().sync();

        byte[] stream = reassemble(wire.toByteArray());
        assertThat(Arrays.mismatch(stream, expected.toByteArray()))
                .as("first byte where the block-fetch stream differs from the batches in order")
                .isEqualTo(-1);
    }

    @Test
    void idleAgentStopsPollingOnceItsRequestsAreAnswered() throws Exception {
        ChainState chainState = mock(ChainState.class);
        when(chainState.findBlocksInRange(any(), any())).thenReturn(List.of());
        agent = new BlockFetchServerAgent(chainState);
        EmbeddedChannel channel = spy(new EmbeddedChannel());
        agent.setChannel(channel);

        agent.receiveResponse(new RequestRange(new Point(1, "00"), new Point(1, "00")));
        verify(chainState, timeout(5_000)).findBlocksInRange(any(), any());
        Thread.sleep(200);
        clearInvocations(channel);
        Thread.sleep(200);

        verify(channel, never()).isActive();
    }

    /** Appends one message and counts its wire size: an 8-byte mux header per segment of at most 65535 bytes. */
    private void write(ByteArrayOutputStream expected, byte[] message) {
        expected.writeBytes(message);
        expectedWireBytes += message.length + 8 * ((message.length + 65534) / 65535);
    }

    /** Concatenates the payloads of the server's block-fetch segments, failing on any other protocol. */
    private static byte[] reassemble(byte[] wire) {
        ByteBuffer in = ByteBuffer.wrap(wire);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        while (in.remaining() >= 8) {
            in.getInt(); // timestamp
            int protocol = Short.toUnsignedInt(in.getShort());
            int length = Short.toUnsignedInt(in.getShort());
            assertThat(protocol).isEqualTo(SERVER_PROTOCOL_ID);
            byte[] segment = new byte[length];
            in.get(segment);
            payload.writeBytes(segment);
        }
        return payload.toByteArray();
    }
}
