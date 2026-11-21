package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * D4 read-ahead (ADR 0017, being evaluated): several endorser block sources, each with its own side connection, so
 * that several certifying blocks' fetches run at once. Each call borrows a free source for its attempt.
 */
final class PooledEndorserBlockSource implements CompleteBlockAssembler.EndorserBlockSource {
    private static final long POLL_MILLIS = 100;

    private final List<CompleteBlockAssembler.EndorserBlockSource> sources;
    private final BlockingQueue<CompleteBlockAssembler.EndorserBlockSource> free;

    PooledEndorserBlockSource(List<CompleteBlockAssembler.EndorserBlockSource> sources) {
        this.sources = List.copyOf(sources);
        this.free = new ArrayBlockingQueue<>(this.sources.size(), true, this.sources);
    }

    @Override
    public Block complete(Block certifying, Point knownParent, AttemptDeadline deadline) throws Exception {
        CompleteBlockAssembler.EndorserBlockSource source = null;
        while (source == null) {
            deadline.check("while waiting for a free side connection");
            source = free.poll(Math.min(POLL_MILLIS, Math.max(1, deadline.remainingMillis())), TimeUnit.MILLISECONDS);
        }
        try {
            return source.complete(certifying, knownParent, deadline);
        } finally {
            free.add(source);
        }
    }

    @Override
    public void close() {
        sources.forEach(CompleteBlockAssembler.EndorserBlockSource::close);
    }
}
