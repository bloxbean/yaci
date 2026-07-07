package com.bloxbean.cardano.yaci.helper.model.leios;

import com.bloxbean.cardano.yaci.core.model.leios.EndorserBlock;
import com.bloxbean.cardano.yaci.core.model.leios.EndorserBlockTx;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * A resolved Endorser Block closure: the EB body plus the transactions it references.
 * Returned by {@link com.bloxbean.cardano.yaci.helper.LeiosEndorserBlockFetcher}.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class EndorserBlockClosure {
    private LeiosPoint point;
    private EndorserBlock endorserBlock;
    @Builder.Default
    private List<EndorserBlockTx> transactions = new ArrayList<>();
    private boolean txsComplete;
}
