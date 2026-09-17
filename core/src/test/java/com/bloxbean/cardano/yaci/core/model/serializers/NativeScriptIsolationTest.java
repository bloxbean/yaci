package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.NativeScript;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NativeScriptIsolationTest {
    @Test
    void deeplyNestedScriptsAreIsolatedForAllCompositeTypes() {
        for (int type = 1; type <= 3; type++) {
            NativeScript script = WitnessesSerializer.INSTANCE.deserializeNativeScript(nestedScript(type, 1000));
            assertThat(script.getType()).isEqualTo(type);
            assertThat(script.getContent()).isNull();
            assertThat(script.getParseError()).contains("nesting exceeds");
        }
    }

    @Test
    void ordinaryNestedScriptsKeepTheirJson() {
        for (int type = 1; type <= 3; type++) {
            NativeScript script = WitnessesSerializer.INSTANCE.deserializeNativeScript(nestedScript(type, 5));
            assertThat(script.getParseError()).isNull();
            assertThat(script.getContent()).contains("keyHash", "scripts");
        }
    }

    @Test
    void malformedScriptDoesNotDiscardOtherWitnesses() {
        Map witnesses = new Map();
        witnesses.put(new UnsignedInteger(1), new Array().add(new Array().add(new UnsignedInteger(0)))
                .add(pubkeyScript()));
        witnesses.put(new UnsignedInteger(0), new Array().add(new Array()
                .add(new ByteString(new byte[32])).add(new ByteString(new byte[64]))));

        var result = WitnessesSerializer.INSTANCE.deserializeDI(witnesses);

        assertThat(result.getNativeScripts()).hasSize(2);
        assertThat(result.getNativeScripts().get(0).getParseError()).isNotNull();
        assertThat(result.getNativeScripts().get(1).getContent()).contains("keyHash");
        assertThat(result.getVkeyWitnesses()).hasSize(1);
    }

    @Test
    void auxiliaryDataRetainsMetadataAndOtherScripts() {
        Map metadata = new Map().put(new UnsignedInteger(1), new UnsignedInteger(42));
        Array scripts = new Array().add(nestedScript(1, 1000)).add(pubkeyScript());
        Map alonzo = new Map().put(new UnsignedInteger(0), metadata).put(new UnsignedInteger(1), scripts);
        alonzo.setTag(259);

        for (var data : new co.nstant.in.cbor.model.DataItem[]{alonzo, new Array().add(metadata).add(scripts)}) {
            var result = AuxDataSerializer.INSTANCE.deserializeDI(data);
            assertThat(result.getMetadataJson()).contains("42");
            assertThat(result.getNativeScripts()).hasSize(2);
            assertThat(result.getNativeScripts().get(0).getParseError()).isNotNull();
            assertThat(result.getNativeScripts().get(1).getContent()).contains("keyHash");
        }
    }

    @Test
    void blockRetainsTransactionsAndWitnessesWhenOneScriptExceedsDepth() throws Exception {
        byte[] original = CborLoader.getHexBytes("block/preprod292683.txt");
        Block expected = BlockSerializer.INSTANCE.deserialize(original);
        Array root = (Array) CborSerializationUtil.deserializeOne(original);
        Array block = (Array) root.getDataItems().get(1);
        Array witnesses = (Array) block.getDataItems().get(2);
        Map firstWitness = (Map) witnesses.getDataItems().get(0);
        firstWitness.put(new UnsignedInteger(1), new Array().add(nestedScript(1, 1000)).add(pubkeyScript()));

        Block result = BlockSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(root, false));

        assertThat(result.getHeader().getHeaderBody().getBlockHash()).isEqualTo(expected.getHeader().getHeaderBody().getBlockHash());
        assertThat(result.getTransactionBodies()).hasSize(expected.getTransactionBodies().size());
        for (int i = 0; i < expected.getTransactionBodies().size(); i++) {
            assertThat(result.getTransactionBodies().get(i).getTxHash())
                    .isEqualTo(expected.getTransactionBodies().get(i).getTxHash());
        }
        assertThat(result.getTransactionWitness()).hasSize(expected.getTransactionWitness().size());
        var scripts = result.getTransactionWitness().get(0).getNativeScripts();
        assertThat(scripts).hasSize(2);
        assertThat(scripts.get(0).getParseError()).isNotNull();
        assertThat(scripts.get(1).getContent()).contains("keyHash");
        assertThat(result.getAuxiliaryDataMap().keySet()).isEqualTo(expected.getAuxiliaryDataMap().keySet());
    }

    private Array nestedScript(int type, int depth) {
        Array script = pubkeyScript();
        for (int i = 0; i < depth; i++) {
            Array parent = new Array().add(new UnsignedInteger(type));
            if (type == 3) {
                parent.add(new UnsignedInteger(1));
            }
            script = parent.add(new Array().add(script));
        }
        return script;
    }

    private Array pubkeyScript() {
        return new Array().add(new UnsignedInteger(0)).add(new ByteString(new byte[28]));
    }
}
