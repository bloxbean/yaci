package com.bloxbean.cardano.yaci.core.model.byron;

import lombok.*;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronTx {
    private List<ByronTxIn> inputs;
    private List<ByronTxOut> outputs;

    //TODO -- Attributes
}
