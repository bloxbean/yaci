package com.bloxbean.cardano.yaci.helper;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.helper.listener.LeiosDataListener;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@EnabledIfEnvironmentVariable(named = "YACI_MUSASHI_SMOKE", matches = "true")
class LeiosMusashiSmokeTest {
    private static final String DEFAULT_HOST = "leios-node.play.dev.cardano.org";
    private static final int DEFAULT_PORT = 3001;
    private static final int DEFAULT_HANDSHAKE_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_NOTIFICATION_WAIT_SECONDS = 90;
    private static final int DEFAULT_PROGRESS_LOG_SECONDS = 60;
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger WORD16_MAX = BigInteger.valueOf(0xFFFF);

    @Test
    void connectsToMusashiAndActivatesLeiosMiniProtocols() throws InterruptedException {
        String host = env("YACI_MUSASHI_HOST", DEFAULT_HOST);
        int port = intEnv("YACI_MUSASHI_PORT", DEFAULT_PORT);
        int handshakeTimeoutSeconds = intEnv("YACI_MUSASHI_HANDSHAKE_TIMEOUT_SECONDS",
                DEFAULT_HANDSHAKE_TIMEOUT_SECONDS);
        int notificationWaitSeconds = intEnv("YACI_MUSASHI_NOTIFICATION_WAIT_SECONDS",
                DEFAULT_NOTIFICATION_WAIT_SECONDS);
        int progressLogSeconds = intEnv("YACI_MUSASHI_PROGRESS_LOG_SECONDS", DEFAULT_PROGRESS_LOG_SECONDS);
        boolean runFullWindow = booleanEnv("YACI_MUSASHI_RUN_FULL_WINDOW", false);
        boolean fetchFromVote = booleanEnv("YACI_MUSASHI_FETCH_FROM_VOTE", false);

        CountDownLatch handshakeLatch = new CountDownLatch(1);
        CountDownLatch firstNotificationLatch = new CountDownLatch(1);
        CountDownLatch fetchLatch = new CountDownLatch(1);
        AtomicReference<AcceptVersion> acceptedVersion = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean blockOfferFetchRequested = new AtomicBoolean(false);
        AtomicBoolean voteFetchRequested = new AtomicBoolean(false);
        AtomicInteger announcements = new AtomicInteger();
        AtomicInteger blockOffers = new AtomicInteger();
        AtomicInteger blockTxsOffers = new AtomicInteger();
        AtomicInteger voteMessages = new AtomicInteger();
        AtomicInteger decodedVotes = new AtomicInteger();
        AtomicInteger voteFetchRequests = new AtomicInteger();
        AtomicInteger voteFetchErrors = new AtomicInteger();
        AtomicInteger fetchedBlocks = new AtomicInteger();

        LeiosNetworkClient client = new LeiosNetworkClient(host, port);
        client.addDataListener(new LeiosDataListener() {
            @Override
            public void onHandshake(AcceptVersion acceptVersion) {
                acceptedVersion.set(acceptVersion);
                handshakeLatch.countDown();
                log.info("Musashi handshake accepted: {}", acceptVersion);
            }

            @Override
            public void onLeiosActivated(AcceptVersion acceptVersion) {
                log.info("Leios mini-protocols activated: {}", acceptVersion);
            }

            @Override
            public void onLeiosNotActivated(AcceptVersion acceptVersion) {
                failure.compareAndSet(null,
                        new IllegalStateException("Handshake did not activate Leios: " + acceptVersion));
                handshakeLatch.countDown();
            }

            @Override
            public void onBlockAnnouncement(LeiosRawCbor announcement) {
                announcements.incrementAndGet();
                firstNotificationLatch.countDown();
                log.info("Leios block announcement received: {} bytes", announcement.getCbor().length);
            }

            @Override
            public void onBlockOffer(LeiosPoint point, long ebSize) {
                blockOffers.incrementAndGet();
                firstNotificationLatch.countDown();
                log.info("Leios block offer received: point={}, ebSize={}", formatPoint(point), ebSize);
                if (blockOfferFetchRequested.compareAndSet(false, true)) {
                    client.requestBlock(point);
                }
            }

            @Override
            public void onBlockTxsOffer(LeiosPoint point) {
                blockTxsOffers.incrementAndGet();
                firstNotificationLatch.countDown();
                log.info("Leios block txs offer received: point={}", formatPoint(point));
            }

            @Override
            public void onVotes(List<LeiosRawCbor> votes) {
                voteMessages.incrementAndGet();
                firstNotificationLatch.countDown();
                log.info("Leios votes received: count={}", votes.size());
                for (LeiosRawCbor rawVote : votes) {
                    LeiosVote vote = decodeVote(rawVote);
                    decodedVotes.incrementAndGet();
                    log.info("Leios vote decoded: point={}, voterId={}, signatureBytes={}",
                            formatPoint(vote.point()), vote.voterId(), vote.signatureBytes());
                    if (fetchFromVote && voteFetchRequested.compareAndSet(false, true)) {
                        voteFetchRequests.incrementAndGet();
                        log.info("Requesting EB body from vote point: {}", formatPoint(vote.point()));
                        client.requestBlock(vote.point());
                    }
                }
            }

            @Override
            public void onBlock(LeiosPoint requestedPoint, LeiosRawCbor endorserBlock) {
                fetchedBlocks.incrementAndGet();
                fetchLatch.countDown();
                log.info("Leios block fetched: point={}, bytes={}", formatPoint(requestedPoint),
                        endorserBlock.getCbor().length);
            }

            @Override
            public void onNotifyError(Throwable error) {
                failure.compareAndSet(null, error);
                handshakeLatch.countDown();
                firstNotificationLatch.countDown();
                fetchLatch.countDown();
            }

            @Override
            public void onFetchError(Throwable error) {
                if (voteFetchRequested.get() && !blockOfferFetchRequested.get()) {
                    voteFetchErrors.incrementAndGet();
                    log.warn("Diagnostic vote-point fetch failed", error);
                } else {
                    failure.compareAndSet(null, error);
                }
                firstNotificationLatch.countDown();
                fetchLatch.countDown();
            }

            @Override
            public void onDisconnect() {
                if (voteFetchRequested.get() && !blockOfferFetchRequested.get() && fetchedBlocks.get() == 0) {
                    log.warn("Disconnected after diagnostic vote-point fetch request");
                } else {
                    failure.compareAndSet(null, new IllegalStateException("Disconnected from Musashi relay"));
                }
                handshakeLatch.countDown();
                firstNotificationLatch.countDown();
                fetchLatch.countDown();
            }
        });

        try {
            log.info("Connecting to Musashi Leios testnet at {}:{}", host, port);
            client.start();

            assertTrue(handshakeLatch.await(handshakeTimeoutSeconds, TimeUnit.SECONDS),
                    "Timed out waiting for Musashi handshake");
            assertNull(failure.get(), () -> "Musashi smoke test failed: " + failure.get());
            assertTrue(client.isLeiosActive(), () -> "Leios mini-protocols were not active after handshake: "
                    + acceptedVersion.get());
            assertTrue(acceptedVersion.get() != null && acceptedVersion.get().getVersionNumber() >= 15,
                    () -> "Expected Leios-capable protocol version, got " + acceptedVersion.get());

            boolean notificationReceived = waitForNotifications(firstNotificationLatch, failure, client,
                    notificationWaitSeconds, progressLogSeconds, runFullWindow, acceptedVersion, announcements,
                    blockOffers, blockTxsOffers, voteMessages, decodedVotes, voteFetchRequests, voteFetchErrors,
                    fetchedBlocks);
            if (!notificationReceived) {
                log.warn("No Leios notify messages received within {} seconds; public testnet load may be idle",
                        notificationWaitSeconds);
            }

            if (blockOfferFetchRequested.get() && fetchedBlocks.get() == 0) {
                assertTrue(fetchLatch.await(Math.min(notificationWaitSeconds, 60), TimeUnit.SECONDS),
                        "Leios block offer was received, but leios-fetch did not return a block body");
            }

            assertNull(failure.get(), () -> "Musashi smoke test failed: " + failure.get());
            log.info("Musashi smoke summary: version={}, announcements={}, blockOffers={}, blockTxsOffers={}, " +
                            "voteMessages={}, decodedVotes={}, voteFetchRequests={}, voteFetchErrors={}, " +
                            "fetchedBlocks={}", acceptedVersion.get(), announcements.get(), blockOffers.get(),
                    blockTxsOffers.get(), voteMessages.get(), decodedVotes.get(), voteFetchRequests.get(),
                    voteFetchErrors.get(), fetchedBlocks.get());
        } finally {
            client.shutdown();
        }
    }

    private static boolean waitForNotifications(CountDownLatch firstNotificationLatch,
                                                AtomicReference<Throwable> failure,
                                                LeiosNetworkClient client,
                                                int notificationWaitSeconds,
                                                int progressLogSeconds,
                                                boolean runFullWindow,
                                                AtomicReference<AcceptVersion> acceptedVersion,
                                                AtomicInteger announcements,
                                                AtomicInteger blockOffers,
                                                AtomicInteger blockTxsOffers,
                                                AtomicInteger voteMessages,
                                                AtomicInteger decodedVotes,
                                                AtomicInteger voteFetchRequests,
                                                AtomicInteger voteFetchErrors,
                                                AtomicInteger fetchedBlocks) throws InterruptedException {
        boolean notificationReceived = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(notificationWaitSeconds);
        long startedAt = System.nanoTime();

        while (System.nanoTime() < deadline) {
            long remainingSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()));
            long waitSeconds = Math.max(1, Math.min(progressLogSeconds, remainingSeconds));

            if (!notificationReceived) {
                notificationReceived = firstNotificationLatch.await(waitSeconds, TimeUnit.SECONDS);
            } else {
                TimeUnit.SECONDS.sleep(waitSeconds);
            }

            long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);
            log.info("Musashi smoke progress: elapsed={}s, active={}, version={}, announcements={}, " +
                            "blockOffers={}, blockTxsOffers={}, voteMessages={}, decodedVotes={}, " +
                            "voteFetchRequests={}, voteFetchErrors={}, fetchedBlocks={}",
                    elapsedSeconds, client.isLeiosActive(), acceptedVersion.get(), announcements.get(),
                    blockOffers.get(), blockTxsOffers.get(), voteMessages.get(), decodedVotes.get(),
                    voteFetchRequests.get(), voteFetchErrors.get(), fetchedBlocks.get());

            if (failure.get() != null || (notificationReceived && !runFullWindow)) {
                break;
            }
        }

        return notificationReceived;
    }

    private static String formatPoint(LeiosPoint point) {
        return point.getSlot() + "@" + HexUtil.encodeHexString(point.getEbHash());
    }

    private static LeiosVote decodeVote(LeiosRawCbor rawVote) {
        DataItem dataItem = CborSerializationUtil.deserializeOne(rawVote.getCbor());
        if (!(dataItem instanceof Array array)) {
            throw new IllegalArgumentException("Leios vote must be a CBOR array");
        }

        List<DataItem> items = array.getDataItems();
        if (items.size() != 4) {
            throw new IllegalArgumentException("Leios vote must have 4 fields");
        }

        long slot = toLong((UnsignedInteger) items.get(0), "vote slot");
        byte[] ebHash = ((ByteString) items.get(1)).getBytes();
        int voterId = toWord16((UnsignedInteger) items.get(2));
        int signatureBytes = ((ByteString) items.get(3)).getBytes().length;
        return new LeiosVote(new LeiosPoint(slot, ebHash), voterId, signatureBytes);
    }

    private static long toLong(UnsignedInteger value, String name) {
        BigInteger bigint = value.getValue();
        if (bigint.signum() < 0 || bigint.compareTo(LONG_MAX) > 0) {
            throw new IllegalArgumentException(name + " must fit in signed long");
        }
        return bigint.longValue();
    }

    private static int toWord16(UnsignedInteger value) {
        BigInteger bigint = value.getValue();
        if (bigint.signum() < 0 || bigint.compareTo(WORD16_MAX) > 0) {
            throw new IllegalArgumentException("voter_id must fit in word16");
        }
        return bigint.intValue();
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    private static boolean booleanEnv(String name, boolean defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    private record LeiosVote(LeiosPoint point, int voterId, int signatureBytes) {
    }
}
