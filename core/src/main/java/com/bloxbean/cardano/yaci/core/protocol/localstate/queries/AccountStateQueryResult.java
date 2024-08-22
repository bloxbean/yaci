package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.math.BigInteger;

@Getter
@AllArgsConstructor
@ToString
public class AccountStateQueryResult implements QueryResult {
    private BigInteger treasury;
    private BigInteger reserves;
}
