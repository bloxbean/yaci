package com.bloxbean.cardano.yaci.helper.model;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class MempoolStatus {
    private int capacityInBytes;
    private int sizeInBytes;
    private int numberOfTxs;
}
