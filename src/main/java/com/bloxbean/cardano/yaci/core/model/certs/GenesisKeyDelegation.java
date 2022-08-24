package com.bloxbean.cardano.yaci.core.model.certs;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class GenesisKeyDelegation implements Certificate {
    private String genesisHash;
    private String genesisDelegateHash;
    private String vrfKeyHash;
}
