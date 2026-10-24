package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * Represents an era mismatch error where the transaction era doesn't match the ledger era.
 */
@Getter
@AllArgsConstructor
@ToString
public class TxSubmissionEraMismatchError {
    private final String ledgerEraName;
    private final String otherEraName;
    private final String message;
}
