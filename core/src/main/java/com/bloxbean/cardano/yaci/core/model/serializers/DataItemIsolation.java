package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Datum;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.Tuple;
import lombok.SneakyThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Fallback for decoding overflow or optional JSON failure. Temporary empty values let existing serializers
 * parse required fields; every datum/redeemer/metadata value is then restored from its original bytes.
 * The temporary encoding must never be returned to callers or used as a transaction's source bytes.
 * Only the optional data is isolated: errors in signatures, scripts, or execution units still propagate.
 */
final class DataItemIsolation {
    private static final byte EMPTY_ARRAY = (byte) 0x80;
    private static final byte EMPTY_MAP = (byte) 0xa0;
    // Transaction-witness map keys, used from Alonzo onward.
    private static final int DATUMS = 4;
    private static final int REDEEMERS = 5;
    private static final int AUXILIARY_DATA_TAG = 259;
    private static final int METADATA = 0; // Field key inside a tag-259 auxiliary-data map.

    private DataItemIsolation() {
    }

    /**
     * A JSON failure also needs recovery so the result retains source bytes rather than re-encoded CBOR.
     */
    static boolean hasDataError(Witnesses witness) {
        for (Datum datum : witness.getDatums()) {
            if (datum != null && datum.getParseError() != null) return true;
        }
        for (Redeemer redeemer : witness.getRedeemers()) {
            if (redeemer.getData() != null && redeemer.getData().getParseError() != null) return true;
        }
        return false;
    }

    /**
     * Remove datum/redeemer containers from a temporary witness, parse all other fields normally,
     * then restore each datum independently. Tagged sets and indefinite arrays are handled by CborSlice.
     * For example, {@code {4: [datum], 5: redeemers, 0: signatures}} is temporarily parsed as
     * {@code {4: [], 5: [], 0: signatures}}. Only the first two values are restored afterward.
     */
    @SneakyThrows
    static Witnesses witness(byte[] bytes) {
        CborSlice root = CborSlice.of(bytes);
        List<CborSlice> fields = root.items(MajorType.MAP);
        List<CborSlice> replacements = new ArrayList<>();
        CborSlice datums = null;
        CborSlice redeemers = null;
        for (int i = 0; i < fields.size(); i += 2) {
            int key = unsigned(fields.get(i));
            if (key == DATUMS) {
                datums = fields.get(i + 1);
                replacements.add(datums);
            } else if (key == REDEEMERS) {
                redeemers = fields.get(i + 1);
                replacements.add(redeemers);
            }
        }
        // An empty redeemer array is accepted by the existing serializer for both wire formats.
        byte[] placeholders = new byte[replacements.size()];
        Arrays.fill(placeholders, EMPTY_ARRAY);
        Witnesses skeleton = WitnessesSerializer.INSTANCE.deserializeDI(
                CborSerializationUtil.deserializeOne(root.replacing(replacements, placeholders)));
        List<Datum> datumValues = new ArrayList<>();
        if (datums != null) {
            for (CborSlice datum : datums.items(MajorType.ARRAY)) {
                datumValues.add(Datum.fromCbor(datum.bytes()));
            }
        }
        return skeleton.toBuilder().datums(datumValues)
                .redeemers(redeemers == null ? new ArrayList<>() : redeemers(redeemers)).build();
    }

    /**
     * Preserve tags, indexes, and execution units while decoding only each redeemer's data in isolation.
     * Conway maps use the same decoded-key equality as the normal decoder: first key position,
     * last value. Collapse duplicates before interpreting values so recovery cannot add redeemers.
     * <pre>
     * {[0, 9]: [oldData, unitsA], [1, 1]: [otherData, unitsB], [0, 9]: [newData, unitsC]}
     * becomes [Spend/9 with newData and unitsC, Mint/1 with otherData and unitsB].
     * Keys 820009 and 980218001809 both decode to [0, 9] and must also collapse.
     * </pre>
     * Retain the first key's source fields and the last value's source fields when assembling
     * fallback CBOR; healthy values still use the legacy synthesized encoding below.
     */
    @SneakyThrows
    private static List<Redeemer> redeemers(CborSlice source) {
        List<Redeemer> result = new ArrayList<>();
        if (source.type() == MajorType.ARRAY) {
            // Alonzo/Babbage, also accepted in Conway: [[tag, index, data, execution_units], ...].
            for (CborSlice entry : source.items(MajorType.ARRAY)) {
                List<CborSlice> fields = entry.items(MajorType.ARRAY);
                requireSize(fields, 4);
                // Integer zero is a valid temporary datum; the original data and CBOR replace it below.
                Redeemer redeemer = Redeemer.deserializePreConway(new Array()
                        .add(decode(fields.get(0))).add(decode(fields.get(1)))
                        .add(new UnsignedInteger(0)).add(decode(fields.get(3))));
                redeemer.setData(Datum.fromCbor(fields.get(2).bytes()));
                redeemer.setCbor(HexUtil.encodeHexString(entry.bytes()));
                result.add(redeemer);
            }
        } else {
            // Conway map form: {[tag, index]: [data, execution_units], ...}.
            List<CborSlice> entries = source.items(MajorType.MAP);
            var uniqueEntries = new LinkedHashMap<DataItem, Tuple<CborSlice, CborSlice>>();
            for (int i = 0; i < entries.size(); i += 2) {
                CborSlice rawKey = entries.get(i);
                DataItem decodedKey = decode(rawKey);
                var previous = uniqueEntries.get(decodedKey);
                uniqueEntries.put(decodedKey, new Tuple<>(previous == null ? rawKey : previous._1,
                        entries.get(i + 1)));
            }
            for (var entry : uniqueEntries.values()) {
                List<CborSlice> key = entry._1.items(MajorType.ARRAY);
                List<CborSlice> value = entry._2.items(MajorType.ARRAY);
                requireSize(key, 2);
                requireSize(value, 2);
                Redeemer redeemer = Redeemer.deserialize(new Array().add(decode(key.get(0))).add(decode(key.get(1))),
                        new Array().add(new UnsignedInteger(0)).add(decode(value.get(1))));
                redeemer.setData(Datum.fromCbor(value.get(0).bytes()));
                // Preserve the existing four-element representation of Conway redeemers, using source data bytes.
                redeemer.setCbor("84" + HexUtil.encodeHexString(key.get(0).bytes())
                        + HexUtil.encodeHexString(key.get(1).bytes()) + HexUtil.encodeHexString(value.get(0).bytes())
                        + HexUtil.encodeHexString(value.get(1).bytes()));
                if (redeemer.getData().getParseError() == null) {
                    try {
                        // The normal Conway path re-encodes this synthetic array (e.g. merging byte-string
                        // chunks). Preserve that output for healthy siblings; datum bytes/hash stay original.
                        Array legacy = new Array().add(decode(key.get(0))).add(decode(key.get(1)))
                                .add(decode(value.get(0))).add(decode(value.get(1)));
                        redeemer.setCbor(HexUtil.encodeHexString(CborSerializationUtil.serialize(legacy, false)));
                    } catch (StackOverflowError e) {
                        // Re-encoding can itself overflow. Keep the source representation already set above.
                        redeemer.setData(redeemer.getData().toBuilder().json(null)
                                .parseError("Redeemer CBOR conversion exceeded the available stack; JSON unavailable")
                                .build());
                    }
                }
                result.add(redeemer);
            }
        }
        return result;
    }

    /**
     * Locate metadata in all three auxiliary-data encodings: Shelley metadata-only maps,
     * Allegra/Mary arrays, and Alonzo-or-later tag-259 maps. Newer eras can still use older encodings.
     * Scripts are parsed by the existing serializer while only metadata is isolated.
     * <pre>
     * a1 ...             = metadata map                              (Shelley)
     * 82 a1 ... 81 ...   = [metadata, native scripts]                 (Allegra/Mary)
     * d9 01 03 a2 00 ... = 259({0: metadata, 1: native scripts, ...})  (Alonzo onward)
     * </pre>
     */
    @SneakyThrows
    static AuxData auxiliary(byte[] bytes) {
        CborSlice root = CborSlice.of(bytes);
        CborSlice metadata = null;
        if (root.type() == MajorType.MAP && root.tag() == AUXILIARY_DATA_TAG) {
            // Tag 259 distinguishes the auxiliary-data field map from a plain metadata map.
            List<CborSlice> fields = root.items(MajorType.MAP);
            for (int i = 0; i < fields.size(); i += 2) {
                if (unsigned(fields.get(i)) == METADATA) metadata = fields.get(i + 1);
            }
        } else if (root.type() == MajorType.MAP) {
            metadata = root; // Shelley metadata-only auxiliary data.
        } else if (root.type() == MajorType.ARRAY) {
            List<CborSlice> fields = root.items(MajorType.ARRAY);
            requireSize(fields, 2);
            metadata = fields.get(0); // Shelley-MA [metadata, native scripts].
        }
        if (metadata == null) {
            // For example, a script-only tagged map: there is no metadata to isolate.
            return AuxDataSerializer.INSTANCE.deserializeDI(decode(root));
        }
        AuxData skeleton = AuxDataSerializer.INSTANCE.deserializeDI(CborSerializationUtil.deserializeOne(
                root.replacing(Collections.singletonList(metadata), EMPTY_MAP)));
        AuxData converted;
        try {
            // A bare metadata map can use the Shelley decoder regardless of its enclosing era.
            converted = AuxDataSerializer.INSTANCE.deserializeDI(decode(metadata));
            if (converted.getMetadataParseError() != null) {
                // Normal metadata keeps its legacy canonical representation. Failed metadata keeps
                // the exact source encoding so recovery never depends on another encode succeeding.
                converted = converted.toBuilder().metadataCbor(HexUtil.encodeHexString(metadata.bytes())).build();
            }
        } catch (StackOverflowError e) {
            // Boundaries were validated before decoding, so these bytes remain safe to extract.
            converted = AuxData.builder().metadataCbor(HexUtil.encodeHexString(metadata.bytes()))
                    .metadataParseError("Metadata decoding exceeded the available stack; JSON unavailable").build();
        }
        return skeleton.toBuilder().metadataCbor(converted.getMetadataCbor())
                .metadataJson(converted.getMetadataJson())
                .metadataParseError(converted.getMetadataParseError()).build();
    }

    private static DataItem decode(CborSlice source) {
        return CborSerializationUtil.deserializeOne(source.bytes());
    }

    static int unsigned(CborSlice source) {
        return ((UnsignedInteger) decode(source)).getValue().intValueExact();
    }

    private static void requireSize(List<?> values, int expected) throws CborException {
        if (values.size() != expected) throw new CborException("Invalid CBOR field count; expected " + expected);
    }
}
