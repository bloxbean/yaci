package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.PlutusScript;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class BlockFetcherIT extends BaseTest {

    @Test
    public void fetchBlock() throws InterruptedException {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(protocolMagic);
        BlockFetcher blockFetcher = new BlockFetcher(node, nodePort, versionTable);

        CountDownLatch countDownLatch = new CountDownLatch(1);

        List<Block> blocks = new ArrayList<>();
        blockFetcher.start(block -> {
            log.info("Block >>> {} -- {} {}", block.getHeader().getHeaderBody().getBlockNumber(), block.getHeader().getHeaderBody().getSlot() + "  ", block.getEra());
            blocks.add(block);
            countDownLatch.countDown();
        });

        //Byron blocks
//        Point from = new Point(0, "f0f7892b5c333cffc4b3c4344de48af4cc63f55e44936196f365a9ef2244134f");
//        Point to = new Point(5, "365201e928da50760fce4bdad09a7338ba43a43aff1c0e8d3ec458388c932ec8");

        Point from = new Point(13006114, "86dabb90d316b104af0bb926a999fecd17c59be3fa377302790ad70495c4b509");
        Point to = new Point(13006114, "86dabb90d316b104af0bb926a999fecd17c59be3fa377302790ad70495c4b509");
        blockFetcher.fetch(from, to);

        countDownLatch.await(10, TimeUnit.SECONDS);
        blockFetcher.shutdown();

        assertThat(blocks.get(0).getHeader().getHeaderBody().getBlockNumber()).isEqualTo(287622);
    }


    @Test
    @Disabled
    public void fetchBlock_tillTip() throws InterruptedException {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(protocolMagic);
        BlockFetcher blockFetcher = new BlockFetcher(node, nodePort, versionTable);

        AtomicInteger count = new AtomicInteger(0);
        blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            @Override
            public void byronBlockFound(ByronMainBlock byronBlock) {
                if (count.incrementAndGet() % 1000 == 0)
                    System.out.println("Byron Block >> " + byronBlock.getHeader().getConsensusData().getDifficulty());
                count.incrementAndGet();
            }

            @Override
            public void blockFound(Block block) {
                if (count.incrementAndGet() % 1000 == 0)
                    System.out.println("Block >> " + block.getHeader().getHeaderBody().getBlockNumber());
            }
        });
        blockFetcher.start();

        Point from = null;
        Point to = null;
        if (protocolMagic == Constants.SANCHONET_PROTOCOL_MAGIC) {
            from = new Point(1020, "6924ebdac53dc23c8d29ee10b2c8e2b7ea500243006befa388faed6db587a269");
            to = new Point(22214398, "5c9cb0742f12c528908a595f27152b0080843d2c2d65a11fa36ec18307355588");
        } else if (protocolMagic == Constants.PREPROD_PROTOCOL_MAGIC) {
            from = new Point(2, "1d031daf47281f69cd95ab929c269fd26b1434a56a5bbbd65b7afe85ef96b233");
            to = new Point(50468813, "2fb2554a9fec38ce4b8121c001087f867b1bd19cda11e93dc5475dc253baf0e9");
        } else if (protocolMagic == Constants.MAINNET_PROTOCOL_MAGIC) {
            from = new Point(1, "1dbc81e3196ba4ab9dcb07e1c37bb28ae1c289c0707061f28b567c2f48698d50");
            to = new Point(114620634, "fc1e525bd6406a1bf01b2423ea761336546ff14fc5bb3c4b711b60f57ae143a4");
        }

        blockFetcher.fetch(from, to);

        while (true) {
            int min = 1;
            int max = 65000;
            int randomNum = (int)(Math.random() * (max - min + 1)) + min;
            blockFetcher.sendKeepAliveMessage(randomNum);

            System.out.println("Last Keep Alive Message Time : " + blockFetcher.getLastKeepAliveResponseTime());
            System.out.println("Last Keep Alive Message Cookie : " + blockFetcher.getLastKeepAliveResponseCookie());

            Thread.sleep(3000);
        }
    }

    @Test
    public void fetchBlockByron() throws InterruptedException {
        BlockFetcher blockFetcher = new BlockFetcher(node, nodePort, protocolMagic);

        CountDownLatch countDownLatch = new CountDownLatch(3);
        List<ByronMainBlock> blocks = new ArrayList<>();
        blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            @Override
            public void byronBlockFound(ByronMainBlock byronBlock) {
                System.out.println("Byron Block >> " + byronBlock.getHeader().getConsensusData());
                blocks.add(byronBlock);
                countDownLatch.countDown();
            }
        });

        blockFetcher.start();

        Point from = new Point(4325, "f3d7cd6f93cb4c59b61b28ac974f4a4dccfc44a4c83c1998aad17bb6b7b03446");
        Point to = new Point(8641, "f5441700216e5516c6dc19e7eb616f0bf1d04dd1368add35e3a7fd114e30b880");
        blockFetcher.fetch(from, to);

        countDownLatch.await(100, TimeUnit.SECONDS);
        blockFetcher.shutdown();

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).getHeader().getBlockHash()).isEqualTo("f3d7cd6f93cb4c59b61b28ac974f4a4dccfc44a4c83c1998aad17bb6b7b03446");
        assertThat(blocks.get(1).getHeader().getBlockHash()).isEqualTo("ab0a64eaf8bc9e96e4df7161d3ace4d32e88cab2315f952665807509e49892eb");
        assertThat(blocks.get(2).getHeader().getBlockHash()).isEqualTo("f5441700216e5516c6dc19e7eb616f0bf1d04dd1368add35e3a7fd114e30b880");
    }

    @Test
    public void fetchBlock_verifyRedeemerCbor_whenSerDeserMismatch() throws InterruptedException {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Constants.PREVIEW_PROTOCOL_MAGIC);
        BlockFetcher blockFetcher = new BlockFetcher(Constants.PREVIEW_IOHK_RELAY_ADDR, Constants.PREVIEW_IOHK_RELAY_PORT, versionTable);

        CountDownLatch countDownLatch = new CountDownLatch(1);

        AtomicReference<Redeemer> redeemerAtomicReference = new AtomicReference<>();
        blockFetcher.start(block -> {
            log.info("Block >>> {} -- {} {}", block.getHeader().getHeaderBody().getBlockNumber(), block.getHeader().getHeaderBody().getSlot() + "  ", block.getEra());
            redeemerAtomicReference.set(block.getTransactionWitness().get(0).getRedeemers().get(0));
            System.out.println(JsonUtil.getPrettyJson(block));
            countDownLatch.countDown();
        });

        Point from = new Point(2340288, "7b1e3f57a73c5656599de4f38b0f44b78c7ea650e07a81e6cdd13a6f2ede31c4");
        Point to = new Point(2340288, "7b1e3f57a73c5656599de4f38b0f44b78c7ea650e07a81e6cdd13a6f2ede31c4");
        blockFetcher.fetch(from, to);

        countDownLatch.await(10, TimeUnit.SECONDS);
        blockFetcher.shutdown();

        var redeemer = redeemerAtomicReference.get();
        assertThat(redeemer.getCbor()).isEqualTo("840100d8799fbfff01ff821a0008efb61a125066ec");
        assertThat(redeemer.getData().getCbor()).isEqualTo("d8799fbfff01ff");
    }


    @Test
    public void fetchBlock_comparePlutusV1ScriptContent() throws InterruptedException {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(protocolMagic);
        BlockFetcher blockFetcher = new BlockFetcher(node, nodePort, versionTable);

        CountDownLatch countDownLatch = new CountDownLatch(1);
        List<PlutusScript> plutusScripts = new ArrayList<>();
        blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            @Override
            public void byronBlockFound(ByronMainBlock byronBlock) {

            }

            @Override
            public void blockFound(Block block) {
                plutusScripts.add(block.getTransactionWitness().get(0).getPlutusV1Scripts().get(0));
                countDownLatch.countDown();
            }
        });
        blockFetcher.start();

        Point from = new Point(25909468, "9721fed79df1ac1af39b844e9e0f2c89bf8d25655c5c155223da4ada91f5deab");
        Point to = new Point(25909468, "9721fed79df1ac1af39b844e9e0f2c89bf8d25655c5c155223da4ada91f5deab");

        blockFetcher.fetch(from, to);

        countDownLatch.await(10, TimeUnit.SECONDS);
        blockFetcher.shutdown();

        assertThat(plutusScripts).hasSize(1);
        assertThat(plutusScripts.get(0).getContent()).isEqualTo("59088f59088c01000033233223232323233223232323232323232323232323232323232323222232232325335001102013263201b33573892103505435000203500622222353232323232323333332222221233333300100700600500400300235742a00a6ae854010d5d0a8019aba1500235742a0026ae84d5d1280089aba250011357446ae88d5d1280089aba235744a00226ae8940044d55cf1baa00135742a00c4444464464646a6040026444646a6464646002012640026aa06c44a66a0022c464426a6a664424660020060046ae854008d4c08cd5d09aba250022223330290030020012212330010030022235001222323253353029005215335333573466ebc0200380f00ec54cd4ccd5cd19b87003480080f00ec448c0040084c00926130024984c0052625335333573466e24009200003a03b161300c00833302900c025002135573c6ea8004c8c94cd4ccd5cd19b8735573aa002900101801789aba135573ca0022c26ea8004d5d09aba25014500125335333573466e3cdd7299a999ab9a357466ae8940240b80b44d5d0a8048b0088170168a99a9999ab9a3370ea02c9001109100111999ab9a3370ea02e9000109100091931901799ab9c03103402d02c15335333573466e20ccc0794008068031200002e02d13301b001006102d153350061622135301f0022223253353022004215335333573466e3c0040200d40d04d4c0b0c8c8c004dd6008990009aa81e91191999aab9f0022625335333573466ebcd5d0a80100281d01c89aba135744a004260086ae8800c0f44d55cf1baa35742002a66a666ae68cdc39aab9d5002480000d40d04d5d09aab9e500216222350022223500322350042253353253353335734666e54090cdc51919b8a303535001220023371466e2d204a488100303535001220013303a005004337146a006446a0084466e28cdc5a416c0207466e28c0dc010cdc519b8b481600e8cdc5181b80119b8b482e8040e801802c1000fc54cd4ccd5cd19b8900533704008900001f8200a99a999ab9a3371e04200c08007e26a00644a666a004426a00a44a666a004426a00e446a00444a666a004426a00844a666a00442a66a666ae68cdc48060020270268a99a999ab9a3371200201209c09a2666ae68cdc399b8100900c03004e04d104d104d1616161616161616103f103f103f33503a75a0382a66a666ae68cdc79bae008501303f03e13501522235002223500122533553335002213500e2235002225333500221333573466e20cdc0000a4181f82a00c0960982c2c2c2c2666ae68cdc399981b280d01900aa400408c08a208a207c207c2c2c26ea800440b44dd700098159bac0073029375800e26eb00044d55cf1baa001135573a6ea800522010d446a65644f7261636c654e4654003200135501a222533500110162213535300700222233300d00300200122253353009003215335333573466e3c00402007006c406c4cc0240200144cc02001c0108c94cd4ccd5cd19b8735573aa002900100a00989909118010019bae357426aae79400444880044dd51aba135573c6ea80048c8c8ccc88848ccc00401000c008d5d0a8011aba15001357426ae8940044d5d1280089aab9e37540024464460046eac004c8004d5406488c8cccd55cf8011240004a66a666ae68cdc79bae35573aa00400c02c02a26600e00a6eacd55cf2801098021aba2003019135742002640026aa02c444646666aae7c0089200025335333573466e3cdd71aab9d50020040140131375a6aae7940084cc014010d5d100180b89aba1001222123330010040030022333500123003001489042d496e66004881042b496e660025335333573466e20005200000d00c1337169001180119b8148000cdc0000a4004266e2d2000300200132001355012225335333573466e2000520800400d00c133716002006266e2ccdc3000a410008600466e0c005208004489002323233335573ea0044c4646464246660020080060046ae84d5d128021919191999aab9f500226232323212333001004003002375c6ae84d5d1280219a8073ad35742a00664646464a66a666ae68cdc380080700b00a8b0a99a999ab9a3371000201c02c02a26601e66e04038008cdc08070008998078010009bad357426ae894008dd69aba15001135573c6ea8004d5d0a80180a89aba25001135573c6ea8004d5d0a8019bae35742a00602026ae8940044d55cf1baa0014800088cc00c0080048848cc00400c008488c8c8cccd5cd19b8735573aa00490001199109198008018011919191999ab9a3370e6aae754009200023322123300100300233500a00935742a00460166ae84d5d1280111931900699ab9c00f01200b135573ca00226ea8004d5d0a8011919191999ab9a3370e6aae754009200023322123300100300233500a00935742a00460166ae84d5d1280111931900699ab9c00f01200b135573ca00226ea8004d5d09aba2500223263200933573801601c00e26aae7940044dd500089119191999ab9a3370ea00290021091100091999ab9a3370ea00490011190911180180218031aba135573ca00846666ae68cdc3a801a400042444004464c6401466ae7003003c02001c0184d55cea80089baa0012323333573466e1d40052002200623333573466e1d40092000200623263200633573801001600800626aae74dd5000a4c2440042440029210350543100320013550052233335573e0024a00e466a00c6ae84008c00cd5d1001002190009aa802111999aab9f0012500623350053574200460066ae8800800c480044488008488488cc00401000c448c8c00400488cc00cc008008004cd4488cd4488ccccc008cc011220120a8f62061488f074f1a0f8f8c57a37633717eaffd4dd091d85049e959cb4960930048009221205e3537791bd5c62dcf54be6a438615186566fafe543571b18066f5552902a8b90048811cc98a2f16a5800847a3c027fafc3f63a8442b92836773533d74cbf2080048203776c052210355534400222221233333001006005004003002200122123300100300220011");
    }

    @Test
    public void fetchBlock_checkInvalidUnicodeValueInAmount() throws InterruptedException {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(protocolMagic);
        BlockFetcher blockFetcher = new BlockFetcher(node, nodePort, versionTable);

        CountDownLatch countDownLatch = new CountDownLatch(1);
        List<Amount> amounts = new ArrayList<>();
        blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                List<Amount> txAmounts = block.getTransactionBodies().get(0).getOutputs().get(1).getAmounts();
                var amtsWithInvalidUnicode = txAmounts.stream().filter(amount -> amount.getAssetName().contains("\u0000"))
                                .collect(Collectors.toList());
                amounts.addAll(amtsWithInvalidUnicode);
                countDownLatch.countDown();
            }
        });
        blockFetcher.start();

        Point from = new Point(53545732, "04a2708da467ad9d9725f6986af668978e88d0511dba8c215c9ff52bb254d970");
        Point to = new Point(53545732, "04a2708da467ad9d9725f6986af668978e88d0511dba8c215c9ff52bb254d970");

        blockFetcher.fetch(from, to);

        countDownLatch.await(10, TimeUnit.SECONDS);
        blockFetcher.shutdown();

        assertThat(amounts).hasSize(0);
    }

        /** Not able to fetch block 0
         @Test
         public void fetchGenesisBlockByron() throws InterruptedException {
         BlockFetcher blockFetcher = new BlockFetcher(node, nodePort, protocolMagic);

         CountDownLatch countDownLatch = new CountDownLatch(2);
         List<ByronBlock> blocks = new ArrayList<>();
         blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
         @Override
         public void byronBlockFound(ByronMainBlock byronBlock) {
         System.out.println("Byron Block >> " + byronBlock.getHeader().getConsensusData());
         blocks.add(byronBlock);
         countDownLatch.countDown();
         }

         @Override
         public void byronEbBlockFound(ByronEbBlock byronEbBlock) {
         System.out.println("Byron Block >> " + byronEbBlock.getHeader().getConsensusData());
         blocks.add(byronEbBlock);
         countDownLatch.countDown();
         }
         });

         blockFetcher.start();

         Point from = new Point(0, "9ad7ff320c9cf74e0f5ee78d22a85ce42bb0a487d0506bf60cfb5a91ea4497d2");
         Point to = new Point(1, "1d031daf47281f69cd95ab929c269fd26b1434a56a5bbbd65b7afe85ef96b233");
         blockFetcher.fetch(from, to);

         countDownLatch.await(100, TimeUnit.SECONDS);
         blockFetcher.shutdown();

         assertThat(blocks).hasSize(3);
         assertThat(blocks.get(0).getHeader().getBlockHash()).isEqualTo("9ad7ff320c9cf74e0f5ee78d22a85ce42bb0a487d0506bf60cfb5a91ea4497d2");
         assertThat(blocks.get(1).getHeader().getBlockHash()).isEqualTo("ab0a64eaf8bc9e96e4df7161d3ace4d32e88cab2315f952665807509e49892eb");
         }
         **/
}
