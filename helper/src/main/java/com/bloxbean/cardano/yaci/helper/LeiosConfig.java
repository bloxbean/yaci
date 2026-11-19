package com.bloxbean.cardano.yaci.helper;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * Leios settings of {@link BlockSync} and {@link BlockRangeSync} (experimental: Dijkstra is not final).
 * <p>
 * A block whose header has {@code leiosCertInBody} certifies an endorser block (EB). With the defaults, such a block
 * reaches {@code BlockChainDataListener.onBlock} complete: the EB's transactions first, in EB order, then the block's
 * own, and {@code Block.leiosEndorserBlock} set. Yaci fetches them over a separate node-to-node connection to the same
 * peer, within the {@code onBlock} call, for at most 45 s. That side connection stays open, idle, after a
 * successful fetch; it is reused or replaced at the next certifying block, and closed when the sync stops, restarts
 * or halts.
 * <p>
 * One attempt waits at most 15 s for each reply (the endorser block, then all its transactions): a stuck connection is
 * replaced rather than waited out. This also sets a throughput floor: a link that cannot carry one endorser block's
 * transactions within 15 s, about 1 MB/s for a 15 MB transactions reply, never gets them, and the block halts the
 * sync (or is delivered without them in skip mode).
 */
@Getter
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class LeiosConfig {
    /**
     * True (default): deliver a certifying block with its EB's transactions. False: deliver it as received, and open
     * no side connection.
     */
    @Builder.Default
    private boolean inlineEndorserBlockTxs = true;

    /**
     * What to do when a certifying block's EB transactions cannot be had within 45 s. False (default): halt the sync
     * ({@code SYNC_HALTED}); the block is not delivered and the application restarts from its own cursor. True:
     * deliver the block as received, without the EB's transactions ({@code DELIVERED_WITHOUT_EB_TXS}).
     */
    @Builder.Default
    private boolean skipUnavailableEndorserBlocks = false;

    /**
     * @return the default settings: inline EB transactions, and halt when they cannot be had
     */
    public static LeiosConfig defaultConfig() {
        return LeiosConfig.builder().build();
    }
}
