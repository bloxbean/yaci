package com.bloxbean.cardano.yaci.node.scalusbridge;

/**
 * Result entry from Plutus script evaluation.
 * Contains the redeemer tag, index, and computed execution units.
 *
 * @param tag    redeemer tag (e.g., "spend", "mint", "cert", "reward", "voting", "proposing")
 * @param index  redeemer index
 * @param memory computed memory units
 * @param steps  computed CPU steps
 */
public record EvaluationEntry(String tag, int index, long memory, long steps) {}
