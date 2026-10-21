package com.bloxbean.cardano.yaci.core.model.leios;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Dijkstra Leios certificate as embedded in ranking block bodies.
 * The raw CBOR is kept so downstream code can re-parse it if the prototype shape drifts.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class LeiosCertificate {
    private String cbor;
    private String signers;
    private String aggregatedSignature;
}
