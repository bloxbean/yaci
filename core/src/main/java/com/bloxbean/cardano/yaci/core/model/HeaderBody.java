package com.bloxbean.cardano.yaci.core.model;

import com.bloxbean.cardano.yaci.core.model.leios.LeiosAnnouncement;
import com.fasterxml.jackson.annotation.JsonInclude;
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
    @JsonInclude(JsonInclude.Include.NON_NULL) //absent from JSON for pre-Leios blocks, as before
    private LeiosAnnouncement leiosAnnouncement;
    /**
     * Dijkstra/Leios header flag indicating that this RB certifies the previous EB announcement.
     * Null for non-Dijkstra headers and older Musashi fixtures that do not carry the fixed slot.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL) //absent from JSON for pre-Leios blocks, as before
    private Boolean leiosCertified;
    //Derived value
    private String blockHash;

    /**
     * Pre-Leios constructor, kept so existing callers compile and behave unchanged.
     */
    public HeaderBody(long blockNumber, long slot, String prevHash, String issuerVkey, String vrfVkey,
                      VrfCert nonceVrf, VrfCert leaderVrf, VrfCert vrfResult, long blockBodySize,
                      String blockBodyHash, OperationalCert operationalCert, ProtocolVersion protocolVersion,
                      String blockHash) {
        this(blockNumber, slot, prevHash, issuerVkey, vrfVkey, nonceVrf, leaderVrf, vrfResult, blockBodySize,
                blockBodyHash, operationalCert, protocolVersion, null, null, blockHash);
    }
}
