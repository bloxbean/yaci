package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.Proposal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

@Getter
@ToString
@AllArgsConstructor
public class GetProposalQueryResult implements QueryResult {
    private List<Proposal> proposals;
}
