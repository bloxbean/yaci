package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class HeaderBody {
    private long blockNumber;
    private long slot;
    private String prevHash;
    private String issuerVkey;
    private String vrfVkey;
    private VrfCert nonceVrf; //removed in babbage
    private VrfCert leaderVrf; //removed in babbage
    private VrfCert vrfResult; //babbage
    private long blockBodySize;
    private String blockBodyHash;
    private ProtocolVersion protocolVersion;
    //Derived value
    private String blockHash;
}

