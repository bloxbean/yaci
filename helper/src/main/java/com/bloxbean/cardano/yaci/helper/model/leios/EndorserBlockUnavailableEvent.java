package com.bloxbean.cardano.yaci.helper.model.leios;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Leios (experimental): an attempt to get the transactions of the endorser block (EB) that a block certifies failed.
 * See {@code BlockChainDataListener.onEndorserBlockUnavailable}.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class EndorserBlockUnavailableEvent {
    /** The slot of the certifying block. */
    private long certifyingSlot;
    /** The hash of the certifying block, in hex. */
    private String certifyingBlockHash;
    /** The number of the certifying block. */
    private long certifyingBlockNumber;
    /** The hash of the certified EB, in hex, or null if the parent's announcement was not found. */
    private String ebHash;
    /** Why the attempt failed. */
    private String reason;
    /** The attempt number, from 1, within the block's budget. */
    private int attempt;
    /** What happens next. */
    private Outcome outcome;

    public enum Outcome {
        /** Yaci tries again within the block's budget. */
        RETRYING,
        /** The budget ran out; the block is delivered as received, without the EB's transactions. */
        DELIVERED_WITHOUT_EB_TXS,
        /** The budget ran out; the block is not delivered and the sync stops (see {@code isRunning()}). */
        SYNC_HALTED
    }
}
