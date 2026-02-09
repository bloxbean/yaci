package com.bloxbean.cardano.yaci.node.api.utxo.model;

import java.math.BigInteger;

/**
 * A single asset quantity. For ADA, use unit "lovelace" with policyId/assetName null.
 */
public record AssetAmount(String policyId, String assetName, BigInteger quantity) {}

