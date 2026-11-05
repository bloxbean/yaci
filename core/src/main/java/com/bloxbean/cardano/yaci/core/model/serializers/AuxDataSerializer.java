package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.AuxDataField;
import com.bloxbean.cardano.client.transaction.raw.EraSpec;
import com.bloxbean.cardano.client.transaction.raw.RawAuxData;
import com.bloxbean.cardano.client.transaction.raw.RawItems;
import com.bloxbean.cardano.client.transaction.raw.RawScript;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.PlutusScript;
import com.bloxbean.cardano.yaci.core.model.PlutusScriptType;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps aux data read by {@link RawAuxData} to Yaci's model (ADR 0003 D8). Metadata is converted from its source bytes
 * by {@link DataItemIsolation}. A list of scripts is null when the aux data has no such field, and so is an empty list
 * of native scripts in the array form.
 */
public enum AuxDataSerializer implements Serializer<AuxData> {
    INSTANCE;

    /**
     * @param bytes aux data, exactly one CBOR item
     * @return the aux data, read in the newest era; null for empty input
     */
    @Override
    public AuxData deserialize(byte[] bytes) {
        return bytes.length == 0 ? null : read(CborSpan.of(bytes), EraSpec.newest(), true);
    }

    /**
     * A decoded item is read from its encoding, which is not the source, so native scripts get no hash.
     *
     * @deprecated Decoded aux data has lost its source encoding. Use {@link #deserialize(byte[])} with its bytes, or
     * {@link BlockSerializer} for a block.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public AuxData deserializeDI(DataItem di) {
        return read(CborSerializationUtil.span(di), EraSpec.newest(), false);
    }

    /**
     * @param hashed whether the span is the source, of which native script hashes are taken
     */
    AuxData read(CborSpan span, Era era, boolean hashed) {
        RawAuxData aux = RawAuxData.tolerant(span, era);
        AuxData.AuxDataBuilder builder = aux.metadata().map(DataItemIsolation::metadata).map(AuxData::toBuilder)
                .orElseGet(AuxData::builder);
        List<RawScript> nativeScripts = aux.nativeScripts();
        if (aux.has(AuxDataField.NATIVE_SCRIPTS) || !nativeScripts.isEmpty())
            builder.nativeScripts(WitnessesSerializer.nativeScripts(nativeScripts, hashed));
        if (aux.has(AuxDataField.PLUTUS_V1_SCRIPTS))
            builder.plutusV1Scripts(plutusScripts(PlutusScriptType.PlutusScriptV1, aux.plutusV1Scripts()));
        if (aux.has(AuxDataField.PLUTUS_V2_SCRIPTS))
            builder.plutusV2Scripts(plutusScripts(PlutusScriptType.PlutusScriptV2, aux.plutusV2Scripts()));
        if (aux.has(AuxDataField.PLUTUS_V3_SCRIPTS))
            builder.plutusV3Scripts(plutusScripts(PlutusScriptType.PlutusScriptV3, aux.plutusV3Scripts()));
        return builder.build();
    }

    /** A script's content is its bytes, which a script in a witness set does not share. */
    private static List<PlutusScript> plutusScripts(PlutusScriptType type, List<RawScript> scripts) {
        List<PlutusScript> parsed = new ArrayList<>(scripts.size());
        for (RawScript script : scripts)
            parsed.add(new PlutusScript(type, HexUtil.encodeHexString(RawItems.bytes(script.span()))));
        return parsed;
    }
}
