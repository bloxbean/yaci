package com.bloxbean.cardano.yaci.core.protocol.localstate.api;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@ToString
public class DefaultQueryResult implements QueryResult {
    private String resultCbor;
}
