package com.bloxbean.cardano.yaci.core.model.governance;

import lombok.*;

@Getter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
public class Drep {
    private DrepType type;
    private String hash; //key hash or script hash

    public static Drep addrKeyHash(String addrKeyHash) {
        Drep drep = new Drep();
        drep.type = DrepType.ADDR_KEYHASH;
        drep.hash = addrKeyHash;

        return drep;
    }

    public static Drep scriptHash(String scriptHash) {
        Drep drep = new Drep();
        drep.type = DrepType.SCRIPTHASH;
        drep.hash = scriptHash;

        return drep;
    }

    public static Drep abstain() {
        Drep drep = new Drep();
        drep.type = DrepType.ABSTAIN;

        return drep;
    }

    public static Drep noConfidence() {
        Drep drep = new Drep();
        drep.type = DrepType.NO_CONFIDENCE;

        return drep;
    }
}
