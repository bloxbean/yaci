package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.NativeScript;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.MsgBlock;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** yaci#191. Expected hashes were computed outside Yaci and CCL, as Blake2b-224 of 0x00 and the script's bytes. */
class NativeScriptHashTest {
    private static final byte[] BLOCK = CborLoader.getHexBytes("block/preprod292683.txt");
    private static final String KEY_HASH = "581c" + "11".repeat(28);
    private static final String SIG = "8200" + KEY_HASH;
    private static final String SIG_HASH = "5334c8c55ec6518f2e262da617d3170bf0722f43813d64dbaf0ff0b9";

    /** The issue's regression: preprod block 5183974, 5,384 nested script nodes in 16,181 bytes. */
    private static final String DEEP_WITNESS = "block/witness-preprod-f90dce57.txt";
    private static final String DEEP_HASH = "ff3efca65569f6b0b868a3d34abdb1ad8eccf745e0da71fa94fb4f18";

    @Test
    void eachEncodingOfAScriptHashesItsOwnBytes() {
        assertThat(witnessScripts("a10181" + SIG).get(0).getScriptHash()).isEqualTo(SIG_HASH);
        assertThat(witnessScripts("a10181" + "821800" + KEY_HASH).get(0).getScriptHash())
                .isEqualTo("777590530185cb60b24f19d76b4fcbdd43aa9c8641a43877eb90125c");
        assertThat(witnessScripts("a10181" + "9f00" + KEY_HASH + "ff").get(0).getScriptHash())
                .isEqualTo("f188502cd4c4d89962632ec8b9da1ae77805fd31f33f699829825110");
        assertThat(witnessScripts("a10181" + "980200" + KEY_HASH).get(0).getScriptHash())
                .isEqualTo("aca29e19d4130d5b9d8f8f72b2fa46b364d5ba76d7b77a16a44ea954");
    }

    @Test
    void theContainerIsNotPartOfTheHash() {
        for (String witness : new String[]{"a10181" + SIG, "a101d9010281" + SIG, "a1019f" + SIG + "ff",
                "a101d901029f" + SIG + "ff", "a2" + "0180" + "0181" + SIG}) {
            assertThat(witnessScripts(witness).get(0).getScriptHash()).as(witness).isEqualTo(SIG_HASH);
        }
        String nested = "a10181" + "8201d9010281" + SIG;
        assertThat(witnessScripts(nested).get(0).getScriptHash())
                .isEqualTo("9ee89906404c9dc39067699f22e5e0b7124d023b59fbdd178ee89af5");
    }

    /** Unknown types are kept with a parse error and their own hashes, where they were left out (ADR 0003 D4a). */
    @Test
    void unknownScriptTypesAreKeptWithAParseErrorAndDoNotShiftTheHashes() {
        List<NativeScript> scripts = witnessScripts("a10183" + "8107" + SIG + "8107");
        assertThat(scripts).hasSize(3);
        assertThat(scripts.get(1).getScriptHash()).isEqualTo(SIG_HASH);
        for (int i : new int[]{0, 2}) {
            assertThat(scripts.get(i).getParseError()).contains("unknown native script type 7");
            assertThat(scripts.get(i).getScriptHash()).isNotNull().isNotEqualTo(SIG_HASH);
        }
    }

    @Test
    void auxiliaryDataInEveryFormatThatHoldsScripts() {
        for (String aux : new String[]{"82a081" + SIG, "d90103a10181" + SIG, "d90103a2" + "00a0" + "0181" + SIG}) {
            List<NativeScript> scripts = AuxDataSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(aux))
                    .getNativeScripts();
            assertThat(scripts.get(0).getScriptHash()).as(aux).isEqualTo(SIG_HASH);
        }
    }

    /** The real regression block's witness, on a small stack, directly and through both sync paths. */
    @Test
    void deepScriptFromTheIssueOnASmallStack() throws Exception {
        byte[] witness = CborLoader.getHexBytes(DEEP_WITNESS);
        AtomicReference<String> hash = new AtomicReference<>();
        Thread thread = new Thread(null, () -> hash.set(WitnessesSerializer.INSTANCE.deserialize(witness)
                .getNativeScripts().get(0).getScriptHash()), "native-script-hash", 512 * 1024);
        thread.start();
        thread.join(10000);
        assertThat(hash.get()).isEqualTo(DEEP_HASH);

        byte[] block = withWitness(HexUtil.encodeHexString(witness));
        for (Block parsed : SyncDataIsolationTest.throughBothSyncPaths(block)) {
            NativeScript script = parsed.getTransactionWitness().get(0).getNativeScripts().get(0);
            assertThat(script.getScriptHash()).isEqualTo(DEEP_HASH);
            assertThat(script.getContent()).isNotNull();
            assertThat(parsed.getTransactionBodies()).hasSize(3);
        }
    }

    /** Fixture transactions 1 and 2 mint under the native script in their witness set: the policy id is the hash. */
    @Test
    void realScriptsHashToThePolicyTheyMintUnder() {
        Block block = BlockSerializer.INSTANCE.deserialize(BLOCK);
        for (int tx : new int[]{1, 2}) {
            String hash = block.getTransactionWitness().get(tx).getNativeScripts().get(0).getScriptHash();
            assertThat(block.getTransactionBodies().get(tx).getMint()).as("tx %d", tx)
                    .anyMatch(amount -> hash.equals(amount.getPolicyId()));
        }
    }

    @Test
    void blockAuxiliaryScriptsUseTheirOwnBytes() {
        String aux = "d90103a10181" + SIG;
        CborSpan auxSlot = CborSpan.of(BLOCK).get(1).get(3).entries().get(0).getValue();
        byte[] bytes = CborSpan.of(BLOCK).replacing(List.of(auxSlot), List.of(HexUtil.decodeHexString(aux)));
        AuxData parsed = BlockSerializer.INSTANCE.deserialize(bytes).getAuxiliaryDataMap().get(1);
        assertThat(parsed.getNativeScripts().get(0).getScriptHash()).isEqualTo(SIG_HASH);
    }

    /** The recovery path, entered when a datum or metadata fails, gives the same hashes. */
    @Test
    void recoveryPathsKeepTheHash() {
        String deepDatum = "81".repeat(8000) + "00";
        Witnesses witness = BlockSerializer.INSTANCE.deserialize(withWitness("a2" + "0181" + SIG + "0481" + deepDatum))
                .getTransactionWitness().get(0);
        assertThat(witness.getDatums().get(0).getParseError()).isNotNull();
        assertThat(witness.getNativeScripts().get(0).getScriptHash()).isEqualTo(SIG_HASH);

        String deepMetadata = "a100".repeat(3000) + "00";
        String aux = "d90103a2" + "00" + deepMetadata + "0181" + SIG;
        AuxData direct = AuxDataSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(aux));
        assertThat(direct.getMetadataParseError()).isNotNull();
        assertThat(direct.getNativeScripts().get(0).getScriptHash()).isEqualTo(SIG_HASH);

        CborSpan auxSlot = CborSpan.of(BLOCK).get(1).get(3).entries().get(0).getValue();
        byte[] block = CborSpan.of(BLOCK).replacing(List.of(auxSlot), List.of(HexUtil.decodeHexString(aux)));
        AuxData inBlock = BlockSerializer.INSTANCE.deserialize(block).getAuxiliaryDataMap().get(1);
        assertThat(inBlock.getMetadataParseError()).isNotNull();
        assertThat(inBlock.getNativeScripts().get(0).getScriptHash()).isEqualTo(SIG_HASH);
    }

    /** An entry point taking a decoded item has no original bytes, so it reports no hash rather than a wrong one. */
    @Test
    void decodedItemEntryPointsHaveNoHash() {
        Witnesses witness = WitnessesSerializer.INSTANCE.deserializeDI(
                CborSerializationUtil.deserializeOne(HexUtil.decodeHexString("a10181" + SIG)));
        assertThat(witness.getNativeScripts().get(0).getScriptHash()).isNull();
    }

    /**
     * An unknown type does not hide a malformed known node after it: block fetch still reports a parsing error, as when
     * the unknown child was left out. Alone, the unknown child gives its script a parse error and the block arrives.
     */
    @Test
    void anUnknownChildDoesNotHideAMalformedNodeFromBlockFetch() {
        String unknownThenMalformedTimeScript = "a10181" + "8201" + "82" + "8107" + "8104";
        assertThat(throughBlockFetch(withWitness(unknownThenMalformedTimeScript)))
                .isInstanceOf(BlockParseRuntimeException.class);

        Object delivered = throughBlockFetch(withWitness("a10181" + "8201" + "81" + "8107"));
        assertThat(delivered).isInstanceOf(Block.class);
        NativeScript script = ((Block) delivered).getTransactionWitness().get(0).getNativeScripts().get(0);
        assertThat(script.getType()).isEqualTo(1);
        assertThat(script.getContent()).isNull();
        assertThat(script.getParseError()).contains("unknown native script type 7");
    }

    @Test
    void existingConstructorsStay() {
        assertThat(new NativeScript(1, "{}").getScriptHash()).isNull();
        assertThat(new NativeScript(1, "{}", "error").getParseError()).isEqualTo("error");
    }

    private static List<NativeScript> witnessScripts(String witness) {
        return WitnessesSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(witness)).getNativeScripts();
    }

    /** The block block fetch delivers, or the parsing error it reports. */
    private static Object throughBlockFetch(byte[] block) {
        AtomicReference<Object> result = new AtomicReference<>();
        BlockfetchAgent agent = new BlockfetchAgent();
        agent.addListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block found) { result.set(found); }
            @Override
            public void onParsingError(BlockParseRuntimeException error) { result.set(error); }
        });
        agent.processResponse(new MsgBlock(block));
        return result.get();
    }

    private static byte[] withWitness(String witness) {
        CborSpan slot = CborSpan.of(BLOCK).get(1).get(2).get(0);
        return CborSpan.of(BLOCK).replacing(List.of(slot), List.of(HexUtil.decodeHexString(witness)));
    }
}
