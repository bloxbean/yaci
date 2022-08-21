package com.bloxbean.cardano.yaci.core.model.byron;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronMainBlock implements ByronBlock {
    private ByronBlockHead header;
    private ByronBlockBody body;
}
