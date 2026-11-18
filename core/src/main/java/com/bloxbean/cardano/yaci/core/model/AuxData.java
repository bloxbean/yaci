package com.bloxbean.cardano.yaci.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.List;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class AuxData {
    private String cbor;

    /** The metadata as encoded on chain: map key order and repeated keys are kept. */
    private String metadataCbor;
    /** Optional display JSON; null when conversion fails. See metadataCbor for the data. */
    private String metadataJson;
    /** Reason metadata JSON conversion failed, or null on success. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String metadataParseError;

    private List<NativeScript> nativeScripts;
    private List<PlutusScript> plutusV1Scripts;
    private List<PlutusScript> plutusV2Scripts;
    private List<PlutusScript> plutusV3Scripts;
    /**
     * The Plutus V4 scripts (key 5); null if absent, as the other script lists are.
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<PlutusScript> plutusV4Scripts;

    /**
     * The metadata's entries in the order received, a repeated label included, each with its bytes as received; null
     * without metadata, and for a decoded tree given to the deprecated {@code deserializeDI}, which has no source bytes.
     * Use these, not a re-encoding of {@link #metadataCbor}'s decoded value, for a label's CBOR.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<MetadataLabel> metadataLabels;

    /** Kept for source and binary compatibility: the Plutus V4 scripts and the labels are null. Use the builder. */
    public AuxData(String cbor, String metadataCbor, String metadataJson, String metadataParseError,
                   List<NativeScript> nativeScripts, List<PlutusScript> plutusV1Scripts,
                   List<PlutusScript> plutusV2Scripts, List<PlutusScript> plutusV3Scripts) {
        this(cbor, metadataCbor, metadataJson, metadataParseError, nativeScripts, plutusV1Scripts, plutusV2Scripts,
                plutusV3Scripts, null, null);
    }

    public AuxData(String cbor, String metadataCbor, String metadataJson,
                   List<NativeScript> nativeScripts, List<PlutusScript> plutusV1Scripts,
                   List<PlutusScript> plutusV2Scripts, List<PlutusScript> plutusV3Scripts) {
        this(cbor, metadataCbor, metadataJson, null, nativeScripts, plutusV1Scripts, plutusV2Scripts, plutusV3Scripts);
    }

    public AuxData(String metadataCbor,
                   String metadataJson,
                   List<NativeScript> nativeScripts,
                   List<PlutusScript> plutusV1Scripts,
                   List<PlutusScript> plutusV2Scripts,
                   List<PlutusScript> plutusV3Scripts) {
        this(null, metadataCbor, metadataJson, nativeScripts, plutusV1Scripts, plutusV2Scripts, plutusV3Scripts);
    }
}
