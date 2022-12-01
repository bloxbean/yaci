package com.bloxbean.cardano.yaci.helper.model;

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
