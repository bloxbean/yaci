package com.bloxbean.cardano.yaci.core.model;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder(toBuilder = true)
public class Datum {
    private static final ObjectWriter JSON_WRITER = new ObjectMapper().writerWithDefaultPrettyPrinter();

    private String hash;
    private String cbor;
    /** Optional display JSON; null when conversion fails. CBOR and hash remain available. */
    private String json;
    /** Reason JSON conversion failed, or null on success. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String parseError;

    public Datum(String hash, String cbor, String json) {
        this(hash, cbor, json, null);
    }

    public static Datum from(DataItem plutusDataDI)
            throws CborDeserializationException, CborException {
        if (plutusDataDI == null) {
            return null;
        }

        // Preserve the existing CBOR/hash path independently of optional JSON conversion.
        // BlockSerializer subsequently applies its existing original-byte extraction corrections.
        byte[] cbor = CborSerializationUtil.serialize(plutusDataDI, false);
        DatumBuilder result = Datum.builder()
                .hash(cborToHash(cbor))
                .cbor(HexUtil.encodeHexString(cbor));
        try {
            // Structural deserialization exceptions still propagate; only representation failures
            // and stack exhaustion in recursive client-library conversion are isolated here.
            PlutusData plutusData = PlutusData.deserialize(plutusDataDI);
            if (plutusData == null) {
                return null;
            }
            // JsonUtil falls back to recursive toString() on failure; use Jackson directly instead.
            result.json(JSON_WRITER.writeValueAsString(plutusData));
        } catch (JsonProcessingException e) {
            result.parseError("Datum JSON conversion failed: " + e.getClass().getSimpleName());
        } catch (StackOverflowError e) {
            result.parseError("Datum JSON conversion exceeded the available stack");
        }
        return result.build();
    }

    /**
     * Parses an independently bounded datum while retaining its exact source bytes and hash.
     * Structural CBOR/Plutus errors propagate. Stack exhaustion leaves only optional JSON unavailable.
     */
    public static Datum fromCbor(byte[] bytes) throws CborException, CborDeserializationException {
        CborSlice.of(bytes); // Verify the complete boundary without recursive decoding first.
        // Hash the source before decoding. Re-encoding can change CBOR ordering/length encodings
        // and therefore the hash, even when it represents the same datum.
        String cbor = HexUtil.encodeHexString(bytes);
        String hash = cborToHash(bytes);
        try {
            Datum result = from(CborSerializationUtil.deserializeOne(bytes));
            // from(DataItem) re-encodes for legacy callers; this byte-based entry point retains the source.
            return result.toBuilder().cbor(cbor).hash(hash).build();
        } catch (StackOverflowError e) {
            return new Datum(hash, cbor, null, "Datum decoding exceeded the available stack; JSON unavailable");
        }
    }

    public static String cborToHash(byte[] cborByte) {
        return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(cborByte));
    }

}
