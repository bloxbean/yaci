package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockClosure;

import java.util.List;
import java.util.Map;

/**
 * Synchronous batch resolution of certified Endorser Block closures — the pull API that
 * bulk/initial-sync pipelines (e.g. yaci-store's batch assembly, one call per block batch
 * before parallel processing) code against. One logical call resolves many EBs;
 * implementations may chunk large requests internally.
 *
 * <p><b>Status: placeholder — not implementable on the current Musashi prototype.</b>
 * Resolving arbitrary (historical) Endorser Blocks requires the final CIP-0164
 * {@code MsgLeiosMultiBlockRequest} — the bulk "EBs plus all referenced transactions"
 * fetch designed for catch-up. The prototype's leios-fetch protocol has only offer-gated
 * single-point requests, its batch/range messages are commented out of the pinned CDDL,
 * and it has no error/not-found response: requesting an EB a peer never offered risks a
 * stalled agent or a connection reset, and peer retention of old EBs is unspecified.
 * See ADR 0012 (Phase 2).
 *
 * <p>TODO: implement over {@code MsgLeiosMultiBlockRequest} (or the blueprint range
 * messages) once a target network ships them, then wire into range-mode sync per
 * ADR 0012 Phase 2.
 */
public interface LeiosEndorserBlockFetcher {

    /**
     * Resolve the closures (EB body + referenced transactions) for the given EB points.
     *
     * @param points points of the certified Endorser Blocks to resolve
     * @return map keyed by EB hash (hex). An <b>absent key means that EB could not be
     *         resolved</b> — callers decide per their completeness mode (strict: fail the
     *         batch; observe: record the gap). Implementations never return partial/empty
     *         closures for unresolved EBs.
     */
    Map<String, EndorserBlockClosure> fetchClosures(List<LeiosPoint> points);

    /**
     * The only available instance until the final protocol lands: always throws
     * {@link UnsupportedOperationException} with the protocol rationale. Deliberately not
     * a silent no-op — a strict-mode pipeline wired against this fails loudly instead of
     * committing batches with empty closures.
     */
    static LeiosEndorserBlockFetcher unsupported() {
        return points -> {
            throw new UnsupportedOperationException(
                    "Batch Endorser Block closure fetch requires the final CIP-0164 "
                            + "MsgLeiosMultiBlockRequest (or blueprint range messages). The Musashi "
                            + "prototype's leios-fetch protocol has only offer-gated single-point "
                            + "requests and no error/not-found response, so historical EB resolution "
                            + "is not safely implementable. See ADR 0012 (Phase 2).");
        };
    }
}
