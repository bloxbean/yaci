package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.Era;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.protocol.localtx.model.ErrorParserUtil.*;

/**
 * Parses CBOR-encoded transaction submission errors from the Cardano node
 * into structured {@link TxSubmissionError} objects.
 * <p>
 * This parser handles the HFC (Hard Fork Combinator) envelope, era dispatch,
 * and delegates to era-specific parsers for the detailed error tree.
 * <p>
 * The parser is designed to never throw exceptions -- on any parse failure,
 * it degrades gracefully to an unparsed result containing the raw CBOR hex.
 *
 * <p>Wire format after stripping protocol envelope [2, payload]:</p>
 * <pre>
 * Multi-era (normal):  [[era_index, ApplyTxError_CBOR]]   -- list-of-1 wrapping
 * Era mismatch:        [era1_info, era2_info]              -- list-of-2
 * </pre>
 */
@Slf4j
public class TxSubmissionErrorParser {

    /**
     * Parse a CBOR error payload from MsgRejectTx into structured errors.
     *
     * @param errorPayloadDI the DataItem at index 1 of the MsgRejectTx array [2, payload]
     * @param rawCborHex     the full raw CBOR hex string (always preserved)
     * @return a ParsedRejection, never null, never throws
     */
    public static ParsedRejection parse(DataItem errorPayloadDI, String rawCborHex) {
        try {
            return parseInternal(errorPayloadDI, rawCborHex);
        } catch (Exception e) {
            log.warn("Failed to parse tx submission error CBOR", e);
            return ParsedRejection.unparsed(rawCborHex);
        }
    }

    private static ParsedRejection parseInternal(DataItem errorPayloadDI, String rawCborHex) {
        if (!(errorPayloadDI instanceof Array)) {
            log.debug("Error payload is not an array, returning unparsed");
            return ParsedRejection.unparsed(rawCborHex);
        }

        Array outerArray = (Array) errorPayloadDI;
        List<DataItem> outerItems = outerArray.getDataItems();

        if (outerItems.isEmpty()) {
            return ParsedRejection.unparsed(rawCborHex);
        }

        // Check for era mismatch (Left case): list-of-2 where items are era info
        // vs normal case (Right case): list-of-1 containing [era_index, error]
        if (outerItems.size() == 2 && isEraMismatch(outerItems)) {
            return parseEraMismatch(outerItems, rawCborHex);
        }

        // Normal case: [[era_index, ApplyTxError]]
        if (outerItems.size() == 1 && outerItems.get(0) instanceof Array) {
            Array eraAndError = (Array) outerItems.get(0);
            List<DataItem> eraItems = eraAndError.getDataItems();
            if (eraItems.size() >= 2) {
                int eraIndex = toIntSafe(eraItems.get(0));
                DataItem errorDI = eraItems.get(1);
                Era era = wireIndexToEra(eraIndex);
                if (era == null) {
                    // Unknown era index, return generic error
                    TxSubmissionError error = TxSubmissionError.builder()
                            .errorName("UnknownEra")
                            .tag(eraIndex)
                            .message("Unknown era index: " + eraIndex)
                            .rawCborHex(serializeToHex(errorDI))
                            .build();
                    return ParsedRejection.parsed(Collections.singletonList(error), rawCborHex);
                }
                List<TxSubmissionError> errors = parseApplyTxError(era, errorDI);
                return ParsedRejection.parsed(errors, rawCborHex);
            }
        }

        // Fallback: try treating as direct error array (some node versions)
        return ParsedRejection.unparsed(rawCborHex);
    }

    /**
     * Check if the two-element array represents an era mismatch.
     * Era mismatch has structure: [era1_info, era2_info] where era info items are arrays.
     */
    private static boolean isEraMismatch(List<DataItem> items) {
        // In the era mismatch case, each item is an array representing era info
        return items.get(0) instanceof Array && items.get(1) instanceof Array;
    }

    private static ParsedRejection parseEraMismatch(List<DataItem> items, String rawCborHex) {
        try {
            // Each era info is [era_index] or similar structure
            Array era1 = (Array) items.get(0);
            Array era2 = (Array) items.get(1);
            String ledgerEra = wireIndexToEraName(toIntSafe(era1.getDataItems().get(0)));
            String otherEra = wireIndexToEraName(toIntSafe(era2.getDataItems().get(0)));
            return ParsedRejection.eraMismatch(ledgerEra, otherEra, rawCborHex);
        } catch (Exception e) {
            log.debug("Failed to parse era mismatch details", e);
            return ParsedRejection.eraMismatch("Unknown", "Unknown", rawCborHex);
        }
    }

    /**
     * Parse ApplyTxError which is a NonEmpty list of predicate failures.
     */
    static List<TxSubmissionError> parseApplyTxError(Era era, DataItem errorDI) {
        List<TxSubmissionError> errors = new ArrayList<>();
        List<DataItem> failureItems = getArrayItems(errorDI);

        if (failureItems.isEmpty()) {
            // Single failure, not wrapped in array
            errors.add(parseLedgerFailure(era, errorDI));
            return errors;
        }

        for (DataItem failureDI : failureItems) {
            errors.add(parseLedgerFailure(era, failureDI));
        }
        return errors;
    }

    /**
     * Dispatch to era-specific LEDGER failure parser.
     */
    private static TxSubmissionError parseLedgerFailure(Era era, DataItem failureDI) {
        try {
            switch (era) {
                case Conway:
                    return ConwayErrorParser.parseLedgerFailure(failureDI);
                case Babbage:
                    return BabbageErrorParser.parseLedgerFailure(failureDI);
                case Alonzo:
                    return AlonzoErrorParser.parseLedgerFailure(failureDI);
                case Shelley:
                case Allegra:
                case Mary:
                    return ShelleyErrorParser.parseLedgerFailure(era, failureDI);
                case Byron:
                    return parseByronFailure(failureDI);
                default:
                    return unknownError(era, "LEDGER", -1, failureDI);
            }
        } catch (Exception e) {
            log.debug("Failed to parse ledger failure for era {}", era, e);
            return unknownError(era, "LEDGER", -1, failureDI);
        }
    }

    private static TxSubmissionError parseByronFailure(DataItem failureDI) {
        return TxSubmissionError.builder()
                .era(Era.Byron)
                .rule("LEDGER")
                .errorName("ByronTxValidationError")
                .tag(-1)
                .message("Byron era transaction validation error")
                .rawCborHex(serializeToHex(failureDI))
                .build();
    }
}
