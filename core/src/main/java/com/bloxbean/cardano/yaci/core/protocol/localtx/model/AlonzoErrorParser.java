package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.Era;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.protocol.localtx.model.ErrorParserUtil.*;

/**
 * Parser for Alonzo era ledger errors.
 * <p>
 * Alonzo wraps Shelley errors (at tag 0) and adds script-related errors.
 * Also used by Babbage when it delegates to Alonzo-era errors.
 */
@Slf4j
class AlonzoErrorParser {
    private static final Era DEFAULT_ERA = Era.Alonzo;

    // ---- LEDGER (Alonzo) ----

    static TxSubmissionError parseLedgerFailure(DataItem di) {
        return parseLedgerFailure(DEFAULT_ERA, di);
    }

    static TxSubmissionError parseLedgerFailure(Era era, DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(era, "LEDGER", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(era, "LEDGER", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0: { // UtxowFailure
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parseUtxowFailure(era, inner) : unknownError(era, "UTXOW", tag, di);
                return wrapError(era, "LEDGER", "UtxowFailure", tag, "UTXOW failure", Collections.singletonList(child));
            }
            case 1: { // DelegsFailure
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? ShelleyErrorParser.parseDelegsFailure(era, inner)
                        : unknownError(era, "DELEGS", tag, di);
                return wrapError(era, "LEDGER", "DelegsFailure", tag, "Delegation failure", Collections.singletonList(child));
            }
            default:
                return unknownError(era, "LEDGER", tag, di);
        }
    }

    // ---- UTXOW (Alonzo) ----

    static TxSubmissionError parseUtxowFailure(DataItem di) {
        return parseUtxowFailure(DEFAULT_ERA, di);
    }

    static TxSubmissionError parseUtxowFailure(Era era, DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(era, "UTXOW", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(era, "UTXOW", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0: { // ShelleyInAlonzoUtxowPredFailure -> wraps Shelley UTXOW
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? ShelleyErrorParser.parseUtxowFailure(era, inner)
                        : unknownError(era, "UTXOW", tag, di);
                return wrapError(era, "UTXOW", "ShelleyInAlonzoUtxowPredFailure", tag,
                        "Shelley UTXOW failure", Collections.singletonList(child));
            }
            case 1: { // MissingRedeemers
                String raw = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                return leafError(era, "UTXOW", "MissingRedeemers", tag, "Missing redeemers", detail("redeemers", raw));
            }
            case 2: { // MissingRequiredDatums
                String required = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String provided = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(era, "UTXOW", "MissingRequiredDatums", tag,
                        "Missing required datums", detail("required", required, "provided", provided));
            }
            case 3: { // NotAllowedSupplementalDatums
                String notAllowed = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String acceptable = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(era, "UTXOW", "NotAllowedSupplementalDatums", tag,
                        "Supplemental datums not allowed", detail("notAllowed", notAllowed, "acceptable", acceptable));
            }
            case 4: { // PPViewHashesDontMatch
                String expected = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String actual = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(era, "UTXOW", "PPViewHashesDontMatch", tag,
                        "Protocol parameter view hashes don't match", detail("expected", expected, "actual", actual));
            }
            case 5: { // MissingRequiredSigners
                List<String> hashes = items.size() > 1 ? extractHashList(items.get(1)) : Collections.emptyList();
                return leafError(era, "UTXOW", "MissingRequiredSigners", tag,
                        "Missing required signers", detail("keyHashes", hashes));
            }
            case 6: { // UnspendableUTxONoDatumHash
                List<String> inputs = items.size() > 1 ? extractTxInputList(items.get(1)) : Collections.emptyList();
                return leafError(era, "UTXOW", "UnspendableUTxONoDatumHash", tag,
                        "Unspendable UTxO (no datum hash)", detail("txInputs", inputs));
            }
            case 7: { // ExtraRedeemers
                String raw = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                return leafError(era, "UTXOW", "ExtraRedeemers", tag, "Extra redeemers", detail("redeemers", raw));
            }
            default:
                return unknownError(era, "UTXOW", tag, di);
        }
    }

    // ---- UTXO (Alonzo) ----

    static TxSubmissionError parseUtxoFailure(DataItem di) {
        return parseUtxoFailure(DEFAULT_ERA, di);
    }

    static TxSubmissionError parseUtxoFailure(Era era, DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(era, "UTXO", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(era, "UTXO", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0: { // UtxosFailure
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parseUtxosFailure(era, inner) : unknownError(era, "UTXOS", tag, di);
                return wrapError(era, "UTXO", "UtxosFailure", tag, "UTXOS failure", Collections.singletonList(child));
            }
            default: {
                // Tags 1-20: common UTXO errors (shared with Conway)
                TxSubmissionError common = parseCommonUtxoError(era, tag, items);
                if (common != null) return common;
                return unknownError(era, "UTXO", tag, di);
            }
        }
    }

    // ---- UTXOS (Alonzo) ----

    static TxSubmissionError parseUtxosFailure(DataItem di) {
        return parseUtxosFailure(DEFAULT_ERA, di);
    }

    static TxSubmissionError parseUtxosFailure(Era era, DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(era, "UTXOS", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(era, "UTXOS", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0: { // ValidationTagMismatch
                String isValid = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String desc = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(era, "UTXOS", "ValidationTagMismatch", tag,
                        "Validation tag mismatch", detail("isValid", isValid, "description", desc));
            }
            case 1: { // CollectErrors
                String errors = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                return leafError(era, "UTXOS", "CollectErrors", tag,
                        "Collect errors (script execution setup)", detail("errors", errors));
            }
            case 2: // UpdateFailure (Alonzo-specific)
                return leafError(era, "UTXOS", "UpdateFailure", tag, "Update failure");
            default:
                return unknownError(era, "UTXOS", tag, di);
        }
    }
}
