package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadata;
import com.bloxbean.cardano.client.transaction.raw.RawExUnits;
import com.bloxbean.cardano.client.transaction.raw.RawItems;
import com.bloxbean.cardano.client.transaction.raw.RawRedeemer;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Datum;
import com.bloxbean.cardano.yaci.core.model.ExUnits;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.RedeemerTag;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.SneakyThrows;

import java.util.List;

/**
 * Datums, redeemer data and metadata, each converted from its own source bytes: one too deep for the stack, or whose
 * JSON fails, keeps its CBOR and hash with a parse error, and nothing else in its block changes. Structural errors
 * propagate.
 */
final class DataItemIsolation {
    private DataItemIsolation() {
    }

    @SneakyThrows
    static Datum datum(CborSpan data) {
        return Datum.fromCbor(data.bytes());
    }

    /**
     * The CBOR of a redeemer of the array form is its source. That of the map form is the four-item array Yaci has
     * always given, re-encoded; from the source items if the data failed, as re-encoding needs the data decoded.
     */
    static Redeemer redeemer(RawRedeemer raw) {
        Datum data = datum(raw.data());
        RawExUnits units = raw.units();
        Redeemer redeemer = Redeemer.builder().tag(tag(raw.tag())).index((int) raw.index()).data(data)
                .exUnits(new ExUnits(units.mem(), units.steps())).build();
        if (raw.key() == null) {
            redeemer.setCbor(hex(raw.span()));
            return redeemer;
        }
        List<CborSpan> key = RawItems.array(raw.key(), "redeemer key");
        StringBuilder source = new StringBuilder("84");
        key.forEach(item -> source.append(hex(item)));
        redeemer.setCbor(source.append(hex(raw.data())).append(hex(raw.exUnits())).toString());
        if (data.getParseError() == null) {
            try {
                Array legacy = new Array();
                key.forEach(item -> legacy.add(decode(item)));
                legacy.add(decode(raw.data())).add(decode(raw.exUnits()));
                redeemer.setCbor(HexUtil.encodeHexString(CborSerializationUtil.serialize(legacy, false)));
            } catch (StackOverflowError e) {
                redeemer.setData(data.toBuilder().json(null)
                        .parseError("Redeemer CBOR conversion exceeded the available stack; JSON unavailable").build());
            }
        }
        return redeemer;
    }

    private static RedeemerTag tag(int value) {
        for (RedeemerTag tag : RedeemerTag.values()) {
            if (tag.value == value)
                return tag;
        }
        return null;
    }

    /**
     * Metadata keeps its source bytes, as on chain: map key order and repeated keys stay, so a label's CBOR matches what
     * other indexers serve (yaci-store#1215). The JSON is converted from the decoded value, as before.
     *
     * @return aux data with only its metadata fields set
     */
    static AuxData metadata(CborSpan metadata) {
        String json = null;
        String error = null;
        try {
            CBORMetadata decoded = CBORMetadata.deserialize((Map) decode(metadata));
            byte[] encoded = null;
            try {
                encoded = decoded.serialize();
            } catch (StackOverflowError e) {
                // The canonical encoder is recursive; JSON is skipped for this oversized tree.
                error = "Metadata JSON conversion skipped: recursive CBOR serialization exceeded the available stack";
            }
            if (encoded != null) {
                try {
                    json = MetadataToJsonNoSchemaConverter.cborBytesToJson(encoded);
                } catch (Exception e) {
                    error = "Metadata JSON conversion failed: " + e.getClass().getSimpleName();
                } catch (StackOverflowError e) {
                    error = "Metadata JSON conversion exceeded the available stack";
                }
            }
        } catch (StackOverflowError e) {
            error = "Metadata decoding exceeded the available stack; JSON unavailable";
        }
        return AuxData.builder().metadataCbor(hex(metadata)).metadataJson(json).metadataParseError(error).build();
    }

    private static DataItem decode(CborSpan item) {
        return CborSerializationUtil.deserializeOne(item.bytes());
    }

    private static String hex(CborSpan item) {
        return HexUtil.encodeHexString(item.bytes());
    }
}
