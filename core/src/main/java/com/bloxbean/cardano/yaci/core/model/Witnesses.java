package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
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
