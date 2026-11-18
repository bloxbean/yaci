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
 * Observational Endorser Block event emitted beside the normal ranking-block stream.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class EndorserBlockEvent {
    private LeiosPoint point;
    private long announcedEbSize;
    private EndorserBlock endorserBlock;
    @Builder.Default
    private List<EndorserBlockTx> transactions = new ArrayList<>();
    private boolean txsComplete;
    private String announcementCbor;
}
