package com.bloxbean.cardano.yaci.node.ledgerstate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves pointer addresses to stake credentials.
 * <p>
 * Pointer addresses encode (slot, txIndex, certIndex) instead of a credential hash.
 * This resolver builds a lookup from stored registration certificates and resolves
 * pointer addresses during UTXO balance aggregation.
 * <p>
 * Conway era removes pointer address support — only matters for pre-Conway replay.
 * Less than 1% of mainnet UTXOs use pointer addresses — negligible performance impact.
 */
public class PointerAddressResolver {
    private static final Logger log = LoggerFactory.getLogger(PointerAddressResolver.class);

    /**
     * Certificate pointer: identifies a specific certificate in the chain.
     */
    public record CertificatePointer(long slot, int txIndex, int certIndex) {}

    /**
     * Resolved stake credential from a pointer address.
     */
    public record StakeCredential(int credType, String credHash) {}

    // Map from pointer → credential, built from registration certificates
    private final Map<CertificatePointer, StakeCredential> pointerMap = new HashMap<>();

    /**
     * Register a stake credential at a certificate pointer location.
     * Called when processing STAKE_REGISTRATION and REG_CERT certificates.
     */
    public void registerPointer(long slot, int txIndex, int certIndex,
                                int credType, String credHash) {
        pointerMap.put(new CertificatePointer(slot, txIndex, certIndex),
                new StakeCredential(credType, credHash));
    }

    /**
     * Resolve a pointer address to a stake credential.
     *
     * @param slot      the slot from the pointer address
     * @param txIndex   the transaction index from the pointer address
     * @param certIndex the certificate index from the pointer address
     * @return the resolved credential, or null if not found
     */
    public StakeCredential resolve(long slot, int txIndex, int certIndex) {
        return pointerMap.get(new CertificatePointer(slot, txIndex, certIndex));
    }

    /**
     * Number of registered pointers.
     */
    public int size() {
        return pointerMap.size();
    }

    /**
     * Clear all registered pointers.
     */
    public void clear() {
        pointerMap.clear();
    }
}
