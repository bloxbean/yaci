package com.bloxbean.cardano.yaci.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
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
    //Derived value
    private String blockHash;

    /**
     * Whether the block body holds a Leios certificate, from a 12-item Dijkstra header; null in any other header.
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean leiosCertInBody;

    /**
     * The endorser block this header announces, from a 12-item Dijkstra header; null if it announces none.
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private EbAnnouncement ebAnnouncement;

    /** Kept for source and binary compatibility: the Dijkstra fields are null. Use the builder for them. */
    public HeaderBody(long blockNumber, long slot, String prevHash, String issuerVkey, String vrfVkey, VrfCert nonceVrf,
                      VrfCert leaderVrf, VrfCert vrfResult, long blockBodySize, String blockBodyHash,
                      OperationalCert operationalCert, ProtocolVersion protocolVersion, String blockHash) {
        this(blockNumber, slot, prevHash, issuerVkey, vrfVkey, nonceVrf, leaderVrf, vrfResult, blockBodySize,
                blockBodyHash, operationalCert, protocolVersion, blockHash, null, null);
    }
}

