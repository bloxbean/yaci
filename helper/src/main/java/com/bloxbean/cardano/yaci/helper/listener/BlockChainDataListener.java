package com.bloxbean.cardano.yaci.helper.listener;

import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockUnavailableEvent;

import java.util.List;

public interface BlockChainDataListener {
    default void onByronBlock(ByronMainBlock byronBlock) {}
    default void onByronEbBlock(ByronEbBlock byronEbBlock) {}

    default void onBlock(Era era, Block block, List<Transaction> transactions) {}

    /**
     * Leios (experimental): {@link com.bloxbean.cardano.yaci.helper.BlockSync} and
     * {@link com.bloxbean.cardano.yaci.helper.BlockRangeSync} could not get the transactions of the endorser block
     * that a block certifies. Called on the connection's thread, once per failed attempt; an exception thrown here is
     * logged and ignored.
     * <ul>
     *   <li>{@code RETRYING}: Yaci tries again within the block's 45 s budget.</li>
     *   <li>{@code DELIVERED_WITHOUT_EB_TXS}: the budget ran out and
     *       {@code LeiosConfig.skipUnavailableEndorserBlocks} is set: the block
     *       is delivered as received, without the endorser block's transactions.</li>
     *   <li>{@code SYNC_HALTED}: the budget ran out (strict, the default). The block is not delivered, nothing more
     *       is delivered from this start (a halted {@code BlockRangeSync} sends no {@code batchDone}), the connection
     *       is closed and {@code isRunning()} is false. This event is the signal to restart from the application's
     *       own cursor ({@code startSync}, or {@code restart} then {@code fetch}). Do that from another thread: like
     *       {@code stop()} from {@code onBlock}, calling it on this thread closes the connection from its own event
     *       loop.</li>
     * </ul>
     * A stop, restart or startSync while a certifying block waits for its endorser block cancels the wait: that block
     * and the blocks behind it on that connection are not delivered, and nothing is reported for it.
     * A {@code RETRYING} report may arrive while a stop is in progress.
     *
     * @param event what failed, the attempt number and the outcome
     */
    default void onEndorserBlockUnavailable(EndorserBlockUnavailableEvent event) {}

    default void onRollback(Point point) {}

    /**
     * Called when a batch fetch starts (Block Fetch protocol)
     */
    default void batchStarted() {}

    /**
     * Called when a batch fetch completes (Block Fetch protocol)
     */
    default void batchDone() {}
    default void noBlockFound(Point from, Point to) {}

    /**
     * Called when an intersection is found. This is available only for {@link com.bloxbean.cardano.yaci.helper.BlockSync} (Block sync protocol)
     * @param tip
     * @param point
     */
    default void intersactFound(Tip tip, Point point) {}

    /**
     * Called when an intersection is not found. This is available only for {@link com.bloxbean.cardano.yaci.helper.BlockSync} (Block sync protocol)
     * @param tip
     */
    default void intersactNotFound(Tip tip) {}

    default void onDisconnect() {}

    default void onParsingError(BlockParseRuntimeException e) {}


    default void rollforward(Tip tip, BlockHeader blockHeader, byte[] originalHeaderBytes) {

    }

    default void rollforwardByronEra(Tip tip, ByronBlockHead byronBlockHead, byte[] originalHeaderBytes) {

    }

    default void rollforwardByronEra(Tip tip, ByronEbHead byronEbHead, byte[] originalHeaderBytes) {

    }
}
