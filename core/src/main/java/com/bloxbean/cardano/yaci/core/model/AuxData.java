package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
public class AuxData {
    private String metadataCbor;
    private String metadataJson;

    private List<NativeScript> nativeScripts;
    private List<PlutusScript> plutusV1Scripts;
    private List<PlutusScript> plutusV2Scripts;
}
