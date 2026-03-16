package com.bloxbean.cardano.yaci.node.scalusbridge

/**
 * Result of ledger validation (transit).
 * Java-facing — no Scala types exposed.
 */
class TransitResult(
    val isSuccess: Boolean,
    val errorMessage: String,
    val errorClassName: String
)
