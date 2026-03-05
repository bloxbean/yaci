package com.bloxbean.cardano.yaci.node.scalusbridge

import scalus.cardano.ledger.SlotConfig

/**
 * Result of ledger validation (transit).
 * Java-facing — no Scala types exposed.
 */
class TransitResult(
    val isSuccess: Boolean,
    val errorMessage: String,
    val errorClassName: String
)

/**
 * Opaque handle wrapping Scalus SlotConfig.
 * Java code treats this as an opaque token.
 */
class SlotConfigHandle private[scalusbridge] (private[scalusbridge] val inner: SlotConfig)
