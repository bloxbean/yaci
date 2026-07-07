package com.bloxbean.cardano.yaci.core.model;

import com.bloxbean.cardano.yaci.core.model.leios.LeiosAnnouncement;
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
    private OperationalCert operationalCert;
    private ProtocolVersion protocolVersion;
    private LeiosAnnouncement leiosAnnouncement;
    /**
     * Dijkstra/Leios header flag indicating that this RB certifies the previous EB announcement.
     * Null for non-Dijkstra headers and older Musashi fixtures that do not carry the fixed slot.
     */
    private Boolean leiosCertified;
    //Derived value
    private String blockHash;
}
