package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockEvent;
import com.bloxbean.cardano.yaci.helper.model.leios.LeiosVotesEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manual fixture-capture tool for Musashi re-pins (see
 * docs/leios/leios-musashi-source-tracking-guide.md, procedure step 4).
 * Connects to the public Musashi relay, tip-syncs, and dumps raw hex fixtures
 * under core/src/test/resources/leios/w27/. Not a JUnit test on purpose (has
 * only a main method, so it never runs in CI). Run via:
 * {@code ./gradlew :helper:captureW27Fixtures}
 */
public class MusashiW27FixtureCapture {

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "core/src/test/resources/leios/w27");
        Files.createDirectories(outDir);
        long durationSeconds = args.length > 1 ? Long.parseLong(args[1]) : 180;

        YaciConfig.INSTANCE.setReturnBlockCbor(true);

        AtomicInteger blocks = new AtomicInteger();
        AtomicInteger announcedBlocks = new AtomicInteger();
        AtomicInteger certifiedBlocks = new AtomicInteger();
        AtomicInteger endorserBlocks = new AtomicInteger();
        AtomicInteger votes = new AtomicInteger();

        BlockSync blockSync = new BlockSync("leios-node.play.dev.cardano.org", 3001,
                164L, com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point.ORIGIN,
                LeiosConfig.builder().deliverVotes(true).build());
        blockSync.startSyncFromTip(new BlockChainDataListener() {
            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                try {
                    long no = block.getHeader().getHeaderBody().getBlockNumber();
                    var hb = block.getHeader().getHeaderBody();
                    boolean announced = hb.getLeiosAnnouncement() != null;
                    boolean certified = Boolean.TRUE.equals(hb.getLeiosCertified());
                    String kind = "block";
                    if (announced && announcedBlocks.get() < 3) {
                        kind = "block-announced";
                        announcedBlocks.incrementAndGet();
                    } else if (certified && certifiedBlocks.get() < 3) {
                        kind = "block-certified";
                        certifiedBlocks.incrementAndGet();
                    } else if (blocks.get() >= 5) {
                        return; //enough plain blocks
                    }
                    blocks.incrementAndGet();
                    write(outDir, kind + "-" + era + "-" + no + ".hex", block.getCbor());
                    System.out.println("captured " + kind + " " + no + " era=" + era
                            + " txs=" + block.getTransactionBodies().size()
                            + " announced=" + announced + " certified=" + certified
                            + " leiosCert=" + (block.getLeiosCertificate() != null));
                } catch (Exception e) {
                    System.err.println("capture failed: " + e);
                }
            }

            @Override
            public void onEndorserBlock(EndorserBlockEvent event) {
                try {
                    if (endorserBlocks.getAndIncrement() >= 5) return;
                    String slot = String.valueOf(event.getPoint().getSlot());
                    write(outDir, "endorser-block-" + slot + ".hex", event.getEndorserBlock().getCbor());
                    if (event.getAnnouncementCbor() != null) {
                        write(outDir, "announcement-" + slot + ".hex", event.getAnnouncementCbor());
                    }
                    System.out.println("captured EB slot=" + slot
                            + " txRefs=" + event.getEndorserBlock().txCount()
                            + " fetchedTxs=" + event.getTransactions().size()
                            + " complete=" + event.isTxsComplete());
                    if (!event.getTransactions().isEmpty()) {
                        write(outDir, "endorser-block-tx0-" + slot + ".hex",
                                event.getTransactions().get(0).getTxCbor());
                    }
                } catch (Exception e) {
                    System.err.println("EB capture failed: " + e);
                }
            }

            @Override
            public void onLeiosVotes(LeiosVotesEvent event) {
                try {
                    if (votes.getAndIncrement() >= 3 || event.getVotes().isEmpty()) return;
                    write(outDir, "vote-" + votes.get() + ".hex", event.getVotes().get(0).getCbor());
                    System.out.println("captured vote format=" + event.getVotes().get(0).getFormat());
                } catch (Exception e) {
                    System.err.println("vote capture failed: " + e);
                }
            }

            @Override
            public void onParsingError(com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException e) {
                System.err.println("PARSE ERROR block " + e.getBlockNumber() + ": " + e.getCause());
            }
        });

        TimeUnit.SECONDS.sleep(durationSeconds);
        blockSync.stop();
        System.out.println("done: blocks=" + blocks.get() + " announced=" + announcedBlocks.get()
                + " certified=" + certifiedBlocks.get() + " endorserBlocks=" + endorserBlocks.get()
                + " votes=" + votes.get());
        System.exit(0);
    }

    private static void write(Path dir, String name, String hex) throws Exception {
        if (hex == null) return;
        Files.write(dir.resolve(name), hex.getBytes(StandardCharsets.UTF_8));
    }
}
