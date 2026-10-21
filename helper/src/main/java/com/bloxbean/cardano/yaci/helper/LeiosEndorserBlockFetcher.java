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
 * <p><b>Status: placeholder, superseded by ADR 0013.</b> ADR 0013 resolves certified EBs inside Yaci and delivers
 * complete blocks through {@code BlockChainDataListener.onBlock}, so applications do not need this pull API.
 * A live probe on Musashi {@code prototype-2026w40a} (2026-10-19, {@code adr/leios-evidence/leios_fetch_probe.py})
 * showed that peers serve old certified EBs and their transactions on request without an offer
 * (tested up to 4 weeks back); uncertified EBs come back empty.
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
