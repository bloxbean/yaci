package com.bloxbean.cardano.yaci.node.api.appmsg;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Authentication proof for an app-layer message.
 */
@Getter
@AllArgsConstructor
public class AuthProof {
    private final int authMethod;
    private final byte[] proofBytes;
}
