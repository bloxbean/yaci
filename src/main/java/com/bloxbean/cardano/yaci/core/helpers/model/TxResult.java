package com.bloxbean.cardano.yaci.core.helpers.model;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@Builder
public class TxResult {
    private String txHash;
    private boolean accepted;
    private String errorCbor;
}
