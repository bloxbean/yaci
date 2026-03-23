package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.VrfCert;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yaci.node.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.api.events.RollbackEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Evolves {@link EpochNonceState} from synced blocks received via the event bus.
 * Handles rollbacks by restoring from checkpoint ring buffer.
 * <p>
 * Registered via {@code AnnotationListenerRegistrar.register(eventBus, listener, defaults)}
 * in {@code YaciNode.startSlotLeaderBlockProducer()}.
 */
@Slf4j
public class NonceEvolutionListener {

    private final EpochNonceState nonceState;
    private final NonceStateStore nonceStore;  // nullable
    private final String ownIssuerVkey;        // hex, to skip own produced blocks (nullable)

    /**
     * @param nonceState     shared nonce state to evolve
     * @param nonceStore     optional persistence (null if not using RocksDB)
     * @param ownIssuerVkey  hex-encoded issuer vkey of this node's pool (null if not producing)
     */
    public NonceEvolutionListener(EpochNonceState nonceState, NonceStateStore nonceStore,
                                   String ownIssuerVkey) {
        this.nonceState = nonceState;
        this.nonceStore = nonceStore;
        this.ownIssuerVkey = ownIssuerVkey;
    }

    @DomainEventListener(order = 50)
    public void onBlockApplied(BlockAppliedEvent event) {
        Block block = event.block();
        if (block == null || block.getHeader() == null) return;

        Era era = event.era();
        HeaderBody hb = block.getHeader().getHeaderBody();
        if (hb == null) return;

        // Skip blocks we produced ourselves (SignedBlockBuilder already evolved nonce)
        if (ownIssuerVkey != null && ownIssuerVkey.equals(hb.getIssuerVkey())) {
            return;
        }

        // Extract VRF output: Babbage+ uses vrfResult, pre-Babbage uses nonceVrf
        VrfCert vrfCert = hb.getVrfResult();
        if (vrfCert == null) {
            vrfCert = hb.getNonceVrf();
        }
        if (vrfCert == null || vrfCert.get_1() == null) return;

        byte[] vrfOutput = HexUtil.decodeHexString(vrfCert.get_1());
        byte[] prevHash = hb.getPrevHash() != null
                ? HexUtil.decodeHexString(hb.getPrevHash()) : null;
        long slot = event.slot();

        // Detect Shelley start slot from first non-Byron era block
        if (era != Era.Byron && !nonceState.isShelleyStartSlotSet()) {
            nonceState.setShelleyStartSlot(slot);
        }

        // Debug logging for first blocks to trace VRF data flow
        if (log.isDebugEnabled() && slot < 500) {
            String vrfSource = hb.getVrfResult() != null ? "vrfResult" : "nonceVrf";
            log.debug("NonceEvolve BEFORE slot={} era={} vrfSource={} vrfOutput={} prevHash={}",
                    slot, era, vrfSource,
                    HexUtil.encodeHexString(vrfOutput).substring(0, Math.min(32, vrfOutput.length * 2)),
                    prevHash != null ? HexUtil.encodeHexString(prevHash).substring(0, 16) : "null");
        }

        // Evolve nonce state (era-aware)
        int epochBefore = nonceState.getCurrentEpoch();
        nonceState.advanceEpochIfNeeded(slot);
        int epochAfter = nonceState.getCurrentEpoch();
        nonceState.onBlockObserved(slot, prevHash, vrfOutput, era);

        // Debug logging after evolution
        if (log.isDebugEnabled() && slot < 500) {
            log.debug("NonceEvolve AFTER  slot={} epoch={} evolvingNonce={} candidateNonce={}",
                    slot, nonceState.getCurrentEpoch(),
                    HexUtil.encodeHexString(nonceState.getEvolvingNonce()).substring(0, 16),
                    HexUtil.encodeHexString(nonceState.getCandidateNonce()).substring(0, 16));
        }

        // Log epoch transition with the new epoch nonce
        if (epochAfter > epochBefore) {
            log.info("Epoch nonce for epoch {}: {}",
                    epochAfter, HexUtil.encodeHexString(nonceState.getEpochNonce()));
        }

        // Serialize once, reuse for both checkpoint and persistence
        byte[] serialized = nonceState.serialize();
        nonceState.saveCheckpoint(slot, serialized);

        if (nonceStore != null) {
            nonceStore.storeEpochNonceState(serialized);
        }

        if (log.isTraceEnabled()) {
            log.trace("Nonce evolved at slot {} (epoch {}), evolvingNonce={}",
                    slot, nonceState.getCurrentEpoch(),
                    HexUtil.encodeHexString(nonceState.getEvolvingNonce()));
        }
    }

    @DomainEventListener(order = 50)
    public void onRollback(RollbackEvent event) {
        long targetSlot = event.target().getSlot();
        boolean restored = nonceState.rollbackTo(targetSlot);
        if (restored) {
            log.info("Nonce state rolled back to slot {}", targetSlot);
            if (nonceStore != null) {
                nonceStore.storeEpochNonceState(nonceState.serialize());
            }
        } else {
            log.warn("No nonce checkpoint found for rollback to slot {}. "
                    + "Nonce state may be incorrect until next epoch boundary.", targetSlot);
        }
    }
}
