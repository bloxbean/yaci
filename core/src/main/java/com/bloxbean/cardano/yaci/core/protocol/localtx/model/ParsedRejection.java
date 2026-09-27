package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.Collections;
import java.util.List;

/**
 * Container for the parsed result of a transaction rejection.
 * <p>
 * Exactly one of these will be populated:
 * <ul>
 *   <li>{@code errors} - parsed era-specific ledger errors</li>
 *   <li>{@code eraMismatch} - era mismatch between tx and ledger</li>
 * </ul>
 * {@code rawCborHex} is always set as a fallback.
 */
@Getter
@Builder
@ToString(exclude = "rawCborHex")
public class ParsedRejection {
    @Builder.Default
    private final List<TxSubmissionError> errors = Collections.emptyList();
    private final TxSubmissionEraMismatchError eraMismatch;
    private final String rawCborHex;

    public static ParsedRejection parsed(List<TxSubmissionError> errors, String rawCbor) {
        return ParsedRejection.builder()
                .errors(errors != null ? errors : Collections.emptyList())
                .rawCborHex(rawCbor)
                .build();
    }

    public static ParsedRejection eraMismatch(String ledgerEra, String otherEra, String rawCbor) {
        String msg = "Era mismatch: ledger is in " + ledgerEra + " era, but transaction is for " + otherEra + " era";
        return ParsedRejection.builder()
                .eraMismatch(new TxSubmissionEraMismatchError(ledgerEra, otherEra, msg))
                .rawCborHex(rawCbor)
                .build();
    }

    public static ParsedRejection unparsed(String rawCbor) {
        return ParsedRejection.builder()
                .rawCborHex(rawCbor)
                .build();
    }

    /**
     * Convenience method: returns the first leaf error's message, or the era mismatch message,
     * or falls back to raw CBOR hex.
     */
    public String getErrorMessage() {
        if (eraMismatch != null) {
            return eraMismatch.getMessage();
        }
        if (!errors.isEmpty()) {
            List<TxSubmissionError> leaves = errors.get(0).getLeafErrors();
            if (!leaves.isEmpty() && leaves.get(0).getMessage() != null) {
                return leaves.get(0).getMessage();
            }
            if (errors.get(0).getMessage() != null) {
                return errors.get(0).getMessage();
            }
        }
        return rawCborHex;
    }
}
