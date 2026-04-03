package com.bloxbean.cardano.yaci.node.api.model;

import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;

/**
 * Reusable credential identifier: type (key hash or script hash) + hash.
 * Use as a map key instead of string concatenation ({@code credType + ":" + credHash}).
 */
public record CredentialKey(StakeCredType type, String hash) {

    /** Returns 0 for ADDR_KEYHASH, 1 for SCRIPTHASH. */
    public int typeInt() {
        return type == StakeCredType.ADDR_KEYHASH ? 0 : 1;
    }

    /** Create from raw int type (0=key, 1=script) and hex hash. */
    public static CredentialKey of(int credType, String hash) {
        return new CredentialKey(
                credType == 0 ? StakeCredType.ADDR_KEYHASH : StakeCredType.SCRIPTHASH, hash);
    }
}
