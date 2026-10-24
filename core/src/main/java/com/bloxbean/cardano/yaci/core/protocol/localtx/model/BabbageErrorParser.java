package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.Era;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.protocol.localtx.model.ErrorParserUtil.*;

/**
 * Parser for Babbage era ledger errors.
 * <p>
 * Babbage wraps Alonzo errors and adds a few Babbage-specific ones.
 * LEDGER failure: same as Shelley top-level (tag 0 = UtxowFailure, tag 1 = DelegsFailure).
 * UTXOW: tags 1-5 with tag 1 wrapping Alonzo UTXOW.
 * UTXO: tags 1-4 with tag 1 wrapping Alonzo UTXO.
 */
@Slf4j
class BabbageErrorParser {
    private static final Era ERA = Era.Babbage;

    // ---- LEDGER (Babbage) ----

    static TxSubmissionError parseLedgerFailure(DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(ERA, "LEDGER", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(ERA, "LEDGER", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0: { // UtxowFailure
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parseUtxowFailure(inner) : unknownError(ERA, "UTXOW", tag, di);
                return wrapError(ERA, "LEDGER", "UtxowFailure", tag, "UTXOW failure", Collections.singletonList(child));
            }
            case 1: { // DelegsFailure
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? ShelleyErrorParser.parseDelegsFailure(ERA, inner)
                        : unknownError(ERA, "DELEGS", tag, di);
                return wrapError(ERA, "LEDGER", "DelegsFailure", tag, "Delegation failure", Collections.singletonList(child));
            }
            default:
                return unknownError(ERA, "LEDGER", tag, di);
        }
    }

    // ---- UTXOW (Babbage) ----

    static TxSubmissionError parseUtxowFailure(DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(ERA, "UTXOW", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(ERA, "UTXOW", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0: { // UtxoFailure -> recurse to Babbage UTXO
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parseUtxoFailure(inner) : unknownError(ERA, "UTXO", tag, di);
                return wrapError(ERA, "UTXOW", "UtxoFailure", tag, "UTXO failure", Collections.singletonList(child));
            }
            case 1: { // AlonzoInBabbageUtxowPredFailure -> wraps Alonzo UTXOW
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? AlonzoErrorParser.parseUtxowFailure(ERA, inner)
                        : unknownError(ERA, "UTXOW", tag, di);
                return wrapError(ERA, "UTXOW", "AlonzoInBabbageUtxowPredFailure", tag,
                        "Alonzo UTXOW failure", Collections.singletonList(child));
            }
            case 2: { // UtxoFailure (alternative) - delegate to Babbage UTXO
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parseUtxoFailure(inner) : unknownError(ERA, "UTXO", tag, di);
                return wrapError(ERA, "UTXOW", "UtxoFailure", tag, "UTXO failure", Collections.singletonList(child));
            }
            case 3: // MalformedScriptWitnesses
                return leafError(ERA, "UTXOW", "MalformedScriptWitnesses", tag, "Malformed script witnesses");
            case 4: // MalformedReferenceScripts
                return leafError(ERA, "UTXOW", "MalformedReferenceScripts", tag, "Malformed reference scripts");
            case 5: { // ScriptIntegrityHashMismatch
                String expected = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String actual = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(ERA, "UTXOW", "ScriptIntegrityHashMismatch", tag,
                        "Script integrity hash mismatch",
                        detail("expected", expected, "actual", actual));
            }
            default:
                return unknownError(ERA, "UTXOW", tag, di);
        }
    }

    // ---- UTXO (Babbage) ----

    static TxSubmissionError parseUtxoFailure(DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(ERA, "UTXO", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(ERA, "UTXO", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0: { // UtxosFailure -> recurse to Alonzo UTXOS
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? AlonzoErrorParser.parseUtxosFailure(ERA, inner)
                        : unknownError(ERA, "UTXOS", tag, di);
                return wrapError(ERA, "UTXO", "UtxosFailure", tag, "UTXOS failure", Collections.singletonList(child));
            }
            case 1: { // AlonzoInBabbageUtxoPredFailure -> wraps Alonzo UTXO
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? AlonzoErrorParser.parseUtxoFailure(ERA, inner)
                        : unknownError(ERA, "UTXO", tag, di);
                return wrapError(ERA, "UTXO", "AlonzoInBabbageUtxoPredFailure", tag,
                        "Alonzo UTXO failure", Collections.singletonList(child));
            }
            case 2: { // OutputTooBigUTxO
                String outputs = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                return leafError(ERA, "UTXO", "OutputTooBigUTxO", tag,
                        "Output too big (exceeds max value size)", detail("outputs", outputs));
            }
            case 3: { // InsufficientCollateral
                long required = items.size() > 1 ? toLongSafe(items.get(1)) : -1;
                long provided = items.size() > 2 ? toLongSafe(items.get(2)) : -1;
                return leafError(ERA, "UTXO", "InsufficientCollateral", tag,
                        "Insufficient collateral: required " + required + ", provided " + provided,
                        detail("required", required, "provided", provided));
            }
            case 4: { // NonDisjointRefInputs
                List<String> inputs = items.size() > 1 ? extractTxInputList(items.get(1)) : Collections.emptyList();
                return leafError(ERA, "UTXO", "NonDisjointRefInputs", tag,
                        "Reference inputs overlap with regular inputs", detail("inputs", inputs));
            }
            default:
                return unknownError(ERA, "UTXO", tag, di);
        }
    }
}
