package com.bloxbean.cardano.yaci.node.ledgerstate.governance.model;

import java.math.BigInteger;

/**
 * Stored DRep registration state in RocksDB (prefix 0x62).
 *
 * @param deposit                       DRep deposit amount (lovelace)
 * @param anchorUrl                     Optional metadata URL
 * @param anchorHash                    Optional metadata hash (hex)
 * @param registeredAtEpoch             Epoch of registration
 * @param lastInteractionEpoch          Epoch of last vote or update (null if no interaction since registration)
 * @param expiryEpoch                   Computed expiry epoch (updated at epoch boundary)
 * @param active                        Whether the DRep is currently active
 * @param registeredAtSlot              Slot of registration (for v9 bonus calculation)
 * @param protocolVersionAtRegistration Protocol major version at time of registration
 * @param previousDeregistrationSlot    Slot of previous deregistration (null if never deregistered).
 *                                      Required for v9 re-registration bug compatibility.
 *                                      See Amaru backward_compatibility.rs lines 18-71.
 */
public record DRepStateRecord(
        BigInteger deposit,
        String anchorUrl,
        String anchorHash,
        int registeredAtEpoch,
        Integer lastInteractionEpoch,
        int expiryEpoch,
        boolean active,
        long registeredAtSlot,
        int protocolVersionAtRegistration,
        Long previousDeregistrationSlot
) {
    /**
     * Create an updated copy with a new last interaction epoch.
     */
    public DRepStateRecord withLastInteraction(int epoch) {
        return new DRepStateRecord(deposit, anchorUrl, anchorHash, registeredAtEpoch,
                epoch, expiryEpoch, active, registeredAtSlot, protocolVersionAtRegistration,
                previousDeregistrationSlot);
    }

    /**
     * Create an updated copy with new expiry and active status.
     */
    public DRepStateRecord withExpiry(int newExpiry, boolean isActive) {
        return new DRepStateRecord(deposit, anchorUrl, anchorHash, registeredAtEpoch,
                lastInteractionEpoch, newExpiry, isActive, registeredAtSlot, protocolVersionAtRegistration,
                previousDeregistrationSlot);
    }

    /**
     * Create an updated copy with a new anchor.
     */
    public DRepStateRecord withAnchor(String url, String hash) {
        return new DRepStateRecord(deposit, url, hash, registeredAtEpoch,
                lastInteractionEpoch, expiryEpoch, active, registeredAtSlot, protocolVersionAtRegistration,
                previousDeregistrationSlot);
    }
}
