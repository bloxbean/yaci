package com.bloxbean.cardano.yaci.helper.model.leios;

import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Reports that Yaci could not (yet) get the transactions of an endorser block that a ranking block certifies.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class EndorserBlockUnavailableEvent {
    /** The certified endorser block (slot = slot of the announcing block). Null if its parent was not found. */
    private LeiosPoint endorserBlock;
    private long certifyingBlockNumber;
    private long certifyingSlot;
    private String certifyingBlockHash;
    private String reason;
    /** How many times Yaci has tried so far. */
    private int attempt;
    /**
     * True: Yaci waits and tries again; the block is delivered only with its endorser block transactions.
     * False: the block is delivered now without them ({@code LeiosConfig.skipUnavailableEndorserBlocks}).
     */
    private boolean willRetry;
}
