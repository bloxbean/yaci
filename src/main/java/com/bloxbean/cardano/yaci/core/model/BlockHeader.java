package com.bloxbean.cardano.yaci.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlockHeader {
    private HeaderBody headerBody;
    private String bodySignature;

    @Override
    public String toString() {
        return "BlockHeader{" +
                "headerBody=" + headerBody +
                '}';
    }
}
