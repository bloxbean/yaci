package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class Block {
    private Era era;
    private BlockHeader header;

    @Builder.Default
    private List<TransactionBody> transactionBodies = new ArrayList<>();
}
