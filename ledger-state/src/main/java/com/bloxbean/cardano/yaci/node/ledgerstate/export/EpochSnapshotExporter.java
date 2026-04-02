package com.bloxbean.cardano.yaci.node.ledgerstate.export;

import java.math.BigInteger;
import java.util.List;

/**
 * SPI for exporting epoch boundary data to external files for debugging and verification.
 * <p>
 * Implementations are discovered via {@link java.util.ServiceLoader}. When no implementation
 * is on the classpath or the export flag is disabled, {@link #NOOP} is used (zero overhead).
 * <p>
 * Each method is called synchronously at the exact point where data is available during
 * epoch boundary processing. Implementations MUST catch all exceptions internally —
 * export failures must never crash the sync.
 */
public interface EpochSnapshotExporter {

    /**
     * Called after delegation snapshot is created.
     *
     * @param epoch   The epoch for which the snapshot was taken
     * @param entries All stake delegations with amounts
     */
    void exportStakeSnapshot(int epoch, List<StakeEntry> entries);

    /**
     * Called after DRep distribution is calculated (Conway era).
     *
     * @param epoch   The epoch at boundary (newEpoch)
     * @param entries DRep distribution entries (type 0=key, 1=script; excludes virtual DReps)
     */
    void exportDRepDistribution(int epoch, List<DRepDistEntry> entries);

    /**
     * Called after AdaPot is finalized (post-governance adjustment).
     *
     * @param epoch The epoch for which the AdaPot was computed
     * @param entry The final AdaPot values
     */
    void exportAdaPot(int epoch, AdaPotEntry entry);

    /**
     * Called after governance ratification completes (Conway era).
     *
     * @param epoch   The epoch at boundary (newEpoch)
     * @param entries Status of all evaluated proposals
     */
    void exportProposalStatus(int epoch, List<ProposalStatusEntry> entries);

    /**
     * Configure the output directory. Called after ServiceLoader instantiation.
     * Default implementation is a no-op (for NOOP and implementations that don't need it).
     */
    default void setOutputDir(String dir) {}

    /**
     * Set the network magic for address derivation (764824073=mainnet, 1=preprod, 2=preview).
     * Called after ServiceLoader instantiation.
     */
    default void setNetworkMagic(long magic) {}

    /** No-op implementation — zero overhead when export is disabled. */
    EpochSnapshotExporter NOOP = new EpochSnapshotExporter() {
        @Override public void exportStakeSnapshot(int e, List<StakeEntry> s) {}
        @Override public void exportDRepDistribution(int e, List<DRepDistEntry> d) {}
        @Override public void exportAdaPot(int e, AdaPotEntry a) {}
        @Override public void exportProposalStatus(int e, List<ProposalStatusEntry> r) {}
    };

    // ===== Data records — plain types only, no domain dependencies =====

    record StakeEntry(int credType, String credHash, String poolHash, BigInteger amount) {}

    record DRepDistEntry(int drepType, String drepHash, BigInteger amount) {}

    record AdaPotEntry(int epoch, BigInteger treasury, BigInteger reserves, BigInteger deposits,
                       BigInteger fees, BigInteger distributed, BigInteger undistributed,
                       BigInteger rewardsPot, BigInteger poolRewardsPot) {}

    record ProposalStatusEntry(String txHash, int govActionIndex, String actionType,
                               String status, BigInteger deposit, String returnAddress,
                               int submittedEpoch, int expiresAfter) {}
}
