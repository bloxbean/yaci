package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.RawBootstrapWitness;
import com.bloxbean.cardano.client.transaction.raw.RawDatum;
import com.bloxbean.cardano.client.transaction.raw.RawRedeemer;
import com.bloxbean.cardano.client.transaction.raw.RawScript;
import com.bloxbean.cardano.client.transaction.raw.RawVkeyWitness;
import com.bloxbean.cardano.client.transaction.raw.RawWitnessSet;
import com.bloxbean.cardano.yaci.core.model.BootstrapWitness;
import com.bloxbean.cardano.yaci.core.model.Datum;
import com.bloxbean.cardano.yaci.core.model.NativeScript;
import com.bloxbean.cardano.yaci.core.model.PlutusScript;
import com.bloxbean.cardano.yaci.core.model.PlutusScriptType;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.VkeyWitness;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Maps a witness set read by {@link RawWitnessSet} to Yaci's model (ADR 0003 D8). Datums and redeemers are converted
 * one at a time from their source bytes by {@link DataItemIsolation}.
 */
public enum WitnessesSerializer implements Serializer<Witnesses> {
    INSTANCE;

    /**
     * @param bytes a witness set, exactly one CBOR item
     * @return the witness set, read as Conway; null for empty input
     */
    @Override
    public Witnesses deserialize(byte[] bytes) {
        return bytes.length == 0 ? null : read(CborSpan.of(bytes), Era.Conway, true);
    }

    /**
     * A decoded item is read from its encoding, which is not the source, so native scripts get no hash.
     *
     * @deprecated A decoded witness set has lost its source encoding. Use {@link #deserialize(byte[])} with its bytes,
     * or {@link BlockSerializer} for a block.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public Witnesses deserializeDI(DataItem di) {
        return read(CborSerializationUtil.span(di), Era.Conway, false);
    }

    /**
     * @param hashed whether the span is the source, of which native script hashes are taken
     */
    Witnesses read(CborSpan span, Era era, boolean hashed) {
        RawWitnessSet set = RawWitnessSet.tolerant(span, era);
        List<VkeyWitness> vkeyWitnesses = new ArrayList<>();
        for (RawVkeyWitness witness : set.vkeyWitnesses().orElse(List.of()))
            vkeyWitnesses.add(new VkeyWitness(hex(witness.vkey()), hex(witness.signature())));
        List<NativeScript> nativeScripts = nativeScripts(set.nativeScripts().orElse(List.of()), hashed);
        List<BootstrapWitness> bootstrapWitnesses = new ArrayList<>();
        for (RawBootstrapWitness witness : set.bootstrapWitnesses().orElse(List.of())) {
            bootstrapWitnesses.add(new BootstrapWitness(hex(witness.publicKey()), hex(witness.signature()),
                    hex(witness.chainCode()), hex(witness.attributes())));
        }
        List<PlutusScript> plutusV1Scripts = plutusScripts(PlutusScriptType.PlutusScriptV1, set.plutusV1Scripts());
        List<Datum> datums = new ArrayList<>();
        for (RawDatum datum : set.datums().orElse(List.of()))
            datums.add(DataItemIsolation.datum(datum.span()));
        List<Redeemer> redeemers = new ArrayList<>();
        for (RawRedeemer redeemer : set.redeemers().orElse(List.of()))
            redeemers.add(DataItemIsolation.redeemer(redeemer));
        List<PlutusScript> plutusV2Scripts = plutusScripts(PlutusScriptType.PlutusScriptV2, set.plutusV2Scripts());
        List<PlutusScript> plutusV3Scripts = plutusScripts(PlutusScriptType.PlutusScriptV3, set.plutusV3Scripts());
        return new Witnesses(vkeyWitnesses, nativeScripts, bootstrapWitnesses, plutusV1Scripts, datums, redeemers,
                plutusV2Scripts, plutusV3Scripts);
    }

    /** A script of an unknown type is kept, with no JSON and a parse error. */
    static List<NativeScript> nativeScripts(List<RawScript> scripts, boolean hashed) {
        List<NativeScript> parsed = new ArrayList<>(scripts.size());
        for (RawScript script : scripts) {
            NativeScript json = NativeScriptJson.parse(
                    (Array) CborSerializationUtil.deserializeOne(script.span().bytes()));
            parsed.add(hashed ? new NativeScript(json.getType(), json.getContent(), json.getParseError(),
                    hex(script.hash())) : json);
        }
        return parsed;
    }

    /** A script's content is its encoding, as received. */
    private static List<PlutusScript> plutusScripts(PlutusScriptType type, Optional<List<RawScript>> scripts) {
        List<PlutusScript> parsed = new ArrayList<>();
        for (RawScript script : scripts.orElse(List.of()))
            parsed.add(new PlutusScript(type, hex(script.span().bytes())));
        return parsed;
    }

    private static String hex(byte[] bytes) {
        return HexUtil.encodeHexString(bytes);
    }

    /**
     * @deprecated A native script is read with its witness set or aux data, from their bytes: use
     * {@link #deserialize(byte[])} or {@link AuxDataSerializer#deserialize(byte[])}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public NativeScript deserializeNativeScript(Array nativeScriptArray) {
        return NativeScriptJson.parse(nativeScriptArray);
    }
}
