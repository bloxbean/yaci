package com.bloxbean.cardano.yaci.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode
@ToString
@Builder(toBuilder = true)
public class Witnesses {
    private String cbor;

    private List<VkeyWitness> vkeyWitnesses = new ArrayList<>();

    private List<NativeScript> nativeScripts = new ArrayList<>();

    private List<BootstrapWitness> bootstrapWitnesses = new ArrayList<>();

    //Alonzo
    private List<PlutusScript> plutusV1Scripts = new ArrayList<>();

    private List<Datum> datums = new ArrayList<>();

    private List<Redeemer> redeemers = new ArrayList<>();

    private List<PlutusScript> plutusV2Scripts = new ArrayList<>();
    private List<PlutusScript> plutusV3Scripts = new ArrayList<>();

    /**
     * The Plutus V4 scripts (key 8); empty if absent, and then left out of JSON.
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @Builder.Default
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<PlutusScript> plutusV4Scripts = new ArrayList<>();

    /** Kept for source and binary compatibility: the Plutus V4 scripts are empty. Use the builder for them. */
    public Witnesses(String cbor,
                     List<VkeyWitness> vkeyWitnesses,
                     List<NativeScript> nativeScripts,
                     List<BootstrapWitness> bootstrapWitnesses,
                     List<PlutusScript> plutusV1Scripts,
                     List<Datum> datums,
                     List<Redeemer> redeemers,
                     List<PlutusScript> plutusV2Scripts,
                     List<PlutusScript> plutusV3Scripts) {
        this(cbor, vkeyWitnesses, nativeScripts, bootstrapWitnesses, plutusV1Scripts, datums, redeemers,
                plutusV2Scripts, plutusV3Scripts, new ArrayList<>());
    }

    public Witnesses(List<VkeyWitness> vkeyWitnesses,
                     List<NativeScript> nativeScripts,
                     List<BootstrapWitness> bootstrapWitnesses,
                     List<PlutusScript> plutusV1Scripts,
                     List<Datum> datums,
                     List<Redeemer> redeemers,
                     List<PlutusScript> plutusV2Scripts,
                     List<PlutusScript> plutusV3Scripts) {
        this(null, vkeyWitnesses, nativeScripts, bootstrapWitnesses, plutusV1Scripts,
                datums, redeemers, plutusV2Scripts, plutusV3Scripts);
    }
}
