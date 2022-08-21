package com.bloxbean.cardano.yaci.core.model.byron;

import lombok.*;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronBlockBody {
    //Using the same class. But most of the fields are not applicable for Byron
    private List<ByronTx> transactionBodies;
}
