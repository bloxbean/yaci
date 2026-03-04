package com.bloxbean.cardano.yaci.node.api.utxo.model;

import java.util.List;

/**
 * Proof for an MMR leaf. Path contains sibling hashes (hex) from height=0 upward.
 * rootHex is the bag-of-peaks root at the time of proof request.
 */
public record MmrProof(long leafIndex, List<String> pathHex, String rootHex) {}

