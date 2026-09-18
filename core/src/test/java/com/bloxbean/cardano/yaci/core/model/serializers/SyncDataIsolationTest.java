package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Datum;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.MsgBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.LocalRollForwardSerializer;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SyncDataIsolationTest {
    @Test
    void eraDispatchPreservesBothByronBlockTypes() throws Exception {
        // Main-block fixture copied from the existing ByronBlockSerializer example.
        byte[] main = CborLoader.getHexBytes("block/byron-main.txt");
        // Small epoch-boundary parser fixture (header, empty body, extra data).
        Array header = new Array().add(new UnsignedInteger(764824073)).add(new ByteString(new byte[32]))
                .add(new ByteString(new byte[32]))
                .add(new Array().add(new UnsignedInteger(0)).add(new Array().add(new UnsignedInteger(0))))
                .add(new Array());
        byte[] boundary = CborSerializationUtil.serialize(new Array().add(new UnsignedInteger(0))
                .add(new Array().add(header).add(new Array()).add(new Array())), false);
        for (byte[] bytes : new byte[][]{boundary, main}) {
            AtomicReference<ByronEbBlock> epochBlock = new AtomicReference<>();
            AtomicReference<ByronMainBlock> mainBlock = new AtomicReference<>();
            AtomicReference<BlockParseRuntimeException> error = new AtomicReference<>();
            BlockfetchAgent agent = new BlockfetchAgent();
            agent.addListener(new BlockfetchAgentListener() {
                @Override
                public void byronEbBlockFound(ByronEbBlock block) { epochBlock.set(block); }
                @Override
                public void byronBlockFound(ByronMainBlock block) { mainBlock.set(block); }
                @Override
                public void onParsingError(BlockParseRuntimeException exception) { error.set(exception); }
            });
            onSmallStack(() -> { agent.processResponse(new MsgBlock(bytes)); return null; });
            assertThat(error.get()).isNull();
            var local = onSmallStack(() -> LocalRollForwardSerializer.INSTANCE.deserialize(rollForwardMessage(bytes)));
            assertThat(local.getBlock()).isNull();
            if (bytes == boundary) {
                assertThat(mainBlock.get()).isNull();
                String hash = ByronEbBlockSerializer.INSTANCE.deserialize(bytes).getHeader().getBlockHash();
                assertThat(epochBlock.get().getHeader().getBlockHash()).isEqualTo(hash);
                assertThat(local.getByronEbBlock().getHeader().getBlockHash()).isEqualTo(hash);
            } else {
                assertThat(epochBlock.get()).isNull();
                String hash = ByronBlockSerializer.INSTANCE.deserialize(bytes).getHeader().getBlockHash();
                assertThat(mainBlock.get().getHeader().getBlockHash()).isEqualTo(hash);
                assertThat(local.getByronBlock().getHeader().getBlockHash()).isEqualTo(hash);
            }
        }
    }

    @Test
    void bothSyncEntryPointsRecoverDeepDatumsOnASmallStack() throws Exception {
        byte[] original = CborLoader.getHexBytes("block/preprod292683.txt");
        String[] datums = {"81".repeat(8000) + "00", "a100".repeat(5000) + "00",
                "d87981".repeat(5000) + "00", "a1" + "81".repeat(5000) + "0000"};
        for (String datum : datums) {
            byte[] bytes = DatumMetadataIsolationTest.blockWithData(original, datum, "a10000");
            for (Block result : throughBothSyncPaths(bytes)) {
                Datum parsed = result.getTransactionWitness().get(0).getDatums().get(0);
                assertThat(parsed.getCbor()).isEqualTo(datum);
                assertThat(parsed.getHash()).isEqualTo(Datum.cborToHash(HexUtil.decodeHexString(datum)));
                assertThat(parsed.getJson()).isNull();
                assertThat(parsed.getParseError()).isNotNull();
                assertThat(result.getTransactionBodies()).hasSize(
                        BlockSerializer.INSTANCE.deserialize(original).getTransactionBodies().size());
            }
        }
    }

    @Test
    void metadataRecoveryUsesTheEraSpecificLayoutsThroughBothSyncPaths() throws Exception {
        boolean fullBlock = YaciConfig.INSTANCE.isReturnBlockCbor();
        String metadata = "a100".repeat(3000) + "00";
        String script = "8200581c" + "00".repeat(28);
        // All legacy auxiliary encodings remain valid in later eras.
        String[] auxiliaryFormats = {metadata, "82" + metadata + "81" + script,
                "d90103a200" + metadata + "0181" + script};
        try {
            for (boolean full : new boolean[]{false, true}) {
                YaciConfig.INSTANCE.setReturnBlockCbor(full);
                for (int era = 2; era <= 7; era++) {
                    int formats = era == 2 ? 1 : era < 5 ? 2 : 3;
                    for (int format = 0; format < formats; format++) {
                        byte[] bytes = eraBlock(era, auxiliaryFormats[format]);
                        for (Block result : throughBothSyncPaths(bytes)) {
                            assertThat(result.getEra()).isEqualTo(EraUtil.getEra(era));
                            var aux = result.getAuxiliaryDataMap().get(0);
                            assertThat(aux.getMetadataCbor()).isEqualTo(metadata);
                            assertThat(aux.getMetadataJson()).isNull();
                            assertThat(aux.getMetadataParseError()).isNotNull();
                            if (format > 0) assertThat(aux.getNativeScripts()).hasSize(1);
                            assertThat(result.getCbor()).isEqualTo(full ? HexUtil.encodeHexString(bytes) : null);
                            assertThat(result.getTransactionBodies()).hasSize(1);
                            byte[] tx = CborSlice.arrayItem(bytes, 1, 1, 0).bytes();
                            assertThat(result.getTransactionBodies().get(0).getTxHash()).isEqualTo(Datum.cborToHash(tx));
                        }
                    }
                }
            }
        } finally {
            YaciConfig.INSTANCE.setReturnBlockCbor(fullBlock);
        }
    }

    @Test
    void agentReportsMalformedDeepDataWithoutOverflowingAgainInHeaderDiagnostics() throws Exception {
        byte[] original = CborLoader.getHexBytes("block/preprod292683.txt");
        byte[] corrupt = DatumMetadataIsolationTest.blockWithData(original,
                "a100".repeat(5000) + "1e", "a10000");
        AtomicReference<Block> received = new AtomicReference<>();
        AtomicReference<BlockParseRuntimeException> error = new AtomicReference<>();
        BlockfetchAgent agent = agent(received, error);
        onSmallStack(() -> { agent.processResponse(new MsgBlock(corrupt)); return null; });
        assertThat(received.get()).isNull();
        assertThat(error.get()).isNotNull();
        // A malformed message must not poison the agent's ability to deliver the next healthy block.
        error.set(null);
        onSmallStack(() -> { agent.processResponse(new MsgBlock(original)); return null; });
        assertThat(error.get()).isNull();
        assertThat(received.get()).isNotNull();
        error.set(null);
        onSmallStack(() -> { agent.processResponse(new MsgBlock(new byte[0])); return null; });
        assertThat(error.get()).isNotNull(); // Even malformed era prefixes now reach the error callback.
    }

    /** Parse through block-fetch and local chain-sync, checking that both deliver a block without errors. */
    static List<Block> throughBothSyncPaths(byte[] bytes) throws Exception {
        AtomicReference<Block> received = new AtomicReference<>();
        AtomicReference<BlockParseRuntimeException> error = new AtomicReference<>();
        BlockfetchAgent agent = agent(received, error);
        onSmallStack(() -> { agent.processResponse(new MsgBlock(bytes)); return null; });
        assertThat(error.get()).isNull();
        assertThat(received.get()).isNotNull();
        byte[] message = rollForwardMessage(bytes);
        Block local = onSmallStack(() -> LocalRollForwardSerializer.INSTANCE.deserialize(message).getBlock());
        assertThat(local).isNotNull();
        return Arrays.asList(received.get(), local);
    }

    private static byte[] rollForwardMessage(byte[] bytes) {
        ByteString wrappedBlock = new ByteString(bytes);
        wrappedBlock.setTag(24);
        Array tip = new Array().add(new Array().add(new UnsignedInteger(1)).add(new ByteString(new byte[32])))
                .add(new UnsignedInteger(1));
        return CborSerializationUtil.serialize(new Array().add(new UnsignedInteger(2))
                .add(wrappedBlock).add(tip), false);
    }

    private static BlockfetchAgent agent(AtomicReference<Block> result,
                                        AtomicReference<BlockParseRuntimeException> error) {
        BlockfetchAgent agent = new BlockfetchAgent();
        agent.addListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) { result.set(block); }
            @Override
            public void onParsingError(BlockParseRuntimeException exception) { error.set(exception); }
        });
        return agent;
    }

    /** Synthetic parser fixtures: retain the header layout for each era, without claiming ledger validity. */
    static byte[] eraBlock(int era, String auxiliary) throws Exception {
        Array sample = (Array) CborSerializationUtil.deserializeOne(CborLoader.getHexBytes("block/preprod292683.txt"));
        Array header = (Array) ((Array) sample.getDataItems().get(1)).getDataItems().get(0);
        if (era < 6) {
            List<DataItem> modern = ((Array) header.getDataItems().get(0)).getDataItems();
            Array old = new Array();
            for (int i = 0; i < 5; i++) old.add(modern.get(i));
            old.add(modern.get(5)).add(modern.get(5)); // Separate nonce and leader VRF certificates before Babbage.
            old.add(modern.get(6)).add(modern.get(7));
            for (DataItem cert : ((Array) modern.get(8)).getDataItems()) old.add(cert);
            for (DataItem version : ((Array) modern.get(9)).getDataItems()) old.add(version);
            header = new Array().add(old).add(header.getDataItems().get(1));
        }
        Map tx = new Map().put(new UnsignedInteger(0), new Array()).put(new UnsignedInteger(1), new Array())
                .put(new UnsignedInteger(2), new UnsignedInteger(0));
        Array body = new Array().add(header).add(new Array().add(tx)).add(new Array().add(new Map()))
                .add(new Map().put(new UnsignedInteger(0), new Map()));
        if (era >= 5) body.add(new Array()); // Alonzo introduced the invalid-transaction index list.
        CborSlice root = CborSlice.of(CborSerializationUtil.serialize(
                new Array().add(new UnsignedInteger(era)).add(body), false));
        CborSlice slot = root.items(MajorType.ARRAY).get(1).items(MajorType.ARRAY).get(3).items(MajorType.MAP).get(1);
        return root.replacing(Arrays.asList(slot), Arrays.asList(HexUtil.decodeHexString(auxiliary)));
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> { T get() throws Exception; }

    private static <T> T onSmallStack(CheckedSupplier<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try { value.set(action.get()); }
            catch (Throwable error) { failure.set(error); }
        }, "sync-isolation-test", 512 * 1024);
        thread.setDaemon(true);
        thread.start();
        thread.join(10000);
        assertThat(thread.isAlive()).as("Parsing must terminate").isFalse();
        if (failure.get() != null) throw new AssertionError("Sync path failed on a small stack", failure.get());
        return value.get();
    }
}
