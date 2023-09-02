package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class OperationalCert {
    private String hotVKey;
    private Integer sequenceNumber;
    private Integer kesPeriod;
    private String sigma;
}
