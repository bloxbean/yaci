package com.bloxbean.cardano.yaci.core.model.byron;

import lombok.*;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronTxPayload {
    private ByronTx transaction;
    private List<ByronTxWit> witnesses;
}
