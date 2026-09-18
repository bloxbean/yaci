package com.bloxbean.cardano.yaci.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class AuxData {
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

    /** Preserve the 0.3.x constructor, with no metadata conversion error supplied. */
    public AuxData(String metadataCbor,
                   String metadataJson,
                   List<NativeScript> nativeScripts,
                   List<PlutusScript> plutusV1Scripts,
                   List<PlutusScript> plutusV2Scripts,
                   List<PlutusScript> plutusV3Scripts) {
        this(metadataCbor, metadataJson, null, nativeScripts, plutusV1Scripts, plutusV2Scripts, plutusV3Scripts);
    }
}
