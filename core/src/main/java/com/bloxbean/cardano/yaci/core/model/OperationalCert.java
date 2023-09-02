package com.bloxbean.cardano.yaci.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperationalCert {
    private String hotVKey;
    private Integer sequenceNumber;
    private Integer kesPeriod;
    private String sigma;
}
