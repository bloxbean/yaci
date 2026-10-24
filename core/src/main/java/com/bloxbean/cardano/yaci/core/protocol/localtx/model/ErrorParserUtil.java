package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared helper methods for all era-specific error parsers.
 */
class ErrorParserUtil {

    static TxSubmissionError unknownError(Era era, String rule, int tag, DataItem di) {
        return TxSubmissionError.builder()
                .era(era)
                .rule(rule)
                .errorName("UnknownError")
                .tag(tag)
                .message("Unknown error (tag " + tag + ")")
                .rawCborHex(serializeToHex(di))
                .build();
    }

    static TxSubmissionError wrapError(Era era, String rule, String errorName, int tag, String message,
                                       List<TxSubmissionError> children) {
        return TxSubmissionError.builder()
                .era(era)
                .rule(rule)
                .errorName(errorName)
                .tag(tag)
                .message(message)
                .children(children != null ? children : Collections.emptyList())
                .build();
    }

    static TxSubmissionError leafError(Era era, String rule, String errorName, int tag, String message) {
        return TxSubmissionError.builder()
                .era(era)
                .rule(rule)
                .errorName(errorName)
                .tag(tag)
                .message(message)
                .build();
    }

    static TxSubmissionError leafError(Era era, String rule, String errorName, int tag, String message,
                                       Map<String, Object> detail) {
        return TxSubmissionError.builder()
                .era(era)
                .rule(rule)
                .errorName(errorName)
                .tag(tag)
                .message(message)
                .detail(detail != null ? detail : Collections.emptyMap())
                .build();
    }

    static String serializeToHex(DataItem di) {
        if (di == null) return null;
        try {
            return HexUtil.encodeHexString(CborSerializationUtil.serialize(di));
        } catch (Exception e) {
            return null;
        }
    }

    static int toIntSafe(DataItem di) {
        try {
            return CborSerializationUtil.toInt(di);
        } catch (Exception e) {
            return -1;
        }
    }

    static long toLongSafe(DataItem di) {
        try {
            return CborSerializationUtil.toLong(di);
        } catch (Exception e) {
            return -1;
        }
    }

    static String toStringSafe(DataItem di) {
        if (di == null) return "";
        try {
            if (di instanceof UnicodeString) {
                return ((UnicodeString) di).getString();
            } else if (di instanceof ByteString) {
                return HexUtil.encodeHexString(((ByteString) di).getBytes());
            } else {
                return di.toString();
            }
        } catch (Exception e) {
            return "";
        }
    }

    static String toHexSafe(DataItem di) {
        if (di == null) return "";
        try {
            if (di instanceof ByteString) {
                return HexUtil.encodeHexString(((ByteString) di).getBytes());
            }
            return serializeToHex(di);
        } catch (Exception e) {
            return "";
        }
    }

    static List<DataItem> getArrayItems(DataItem di) {
        if (di instanceof Array) {
            return ((Array) di).getDataItems();
        }
        return Collections.emptyList();
    }

    static List<String> extractHashList(DataItem di) {
        List<String> hashes = new ArrayList<>();
        if (di instanceof Array) {
            for (DataItem item : ((Array) di).getDataItems()) {
                hashes.add(toHexSafe(item));
            }
        } else if (di instanceof ByteString) {
            hashes.add(toHexSafe(di));
        }
        return hashes;
    }

    static List<String> extractTxInputList(DataItem di) {
        List<String> inputs = new ArrayList<>();
        if (di instanceof Array) {
            for (DataItem item : ((Array) di).getDataItems()) {
                if (item instanceof Array) {
                    List<DataItem> parts = ((Array) item).getDataItems();
                    if (parts.size() >= 2) {
                        inputs.add(toHexSafe(parts.get(0)) + "#" + toIntSafe(parts.get(1)));
                    }
                }
            }
        }
        return inputs;
    }

    static Map<String, Object> detail(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    static Map<String, Object> detail(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    static Map<String, Object> detail(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        return map;
    }

    /**
     * Map wire era index (0-6) to model Era enum.
     */
    static Era wireIndexToEra(int index) {
        switch (index) {
            case 0: return Era.Byron;
            case 1: return Era.Shelley;
            case 2: return Era.Allegra;
            case 3: return Era.Mary;
            case 4: return Era.Alonzo;
            case 5: return Era.Babbage;
            case 6: return Era.Conway;
            default: return null;
        }
    }

    static String wireIndexToEraName(int index) {
        Era era = wireIndexToEra(index);
        return era != null ? era.name() : "Unknown(" + index + ")";
    }

    // ---- Shared UTXO error parsing (tags 1-20) ----
    // Common across Alonzo and Conway (same tag assignments).

    /**
     * Parse common UTXO predicate failures (tags 1-20) shared across Alonzo and Conway.
     *
     * @return parsed error, or {@code null} if the tag is not in the common range 1-20
     */
    static TxSubmissionError parseCommonUtxoError(Era era, int tag, List<DataItem> items) {
        switch (tag) {
            case 1: { // BadInputsUTxO
                List<String> inputs = items.size() > 1 ? extractTxInputList(items.get(1)) : Collections.emptyList();
                return leafError(era, "UTXO", "BadInputsUTxO", tag, "Bad inputs (UTxOs not found)", detail("inputs", inputs));
            }
            case 2: { // OutsideValidityIntervalUTxO
                String interval = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                long currentSlot = items.size() > 2 ? toLongSafe(items.get(2)) : -1;
                return leafError(era, "UTXO", "OutsideValidityIntervalUTxO", tag,
                        "Transaction outside validity interval (current slot: " + currentSlot + ")",
                        detail("validityInterval", interval, "currentSlot", currentSlot));
            }
            case 3: { // MaxTxSizeUTxO
                long actualSize = items.size() > 1 ? toLongSafe(items.get(1)) : -1;
                long maxSize = items.size() > 2 ? toLongSafe(items.get(2)) : -1;
                return leafError(era, "UTXO", "MaxTxSizeUTxO", tag,
                        "Transaction size too large: actual " + actualSize + ", max " + maxSize,
                        detail("actualSize", actualSize, "maxSize", maxSize));
            }
            case 4: // InputSetEmptyUTxO
                return leafError(era, "UTXO", "InputSetEmptyUTxO", tag, "Transaction has no inputs");
            case 5: { // FeeTooSmallUTxO
                long minimumFee = items.size() > 1 ? toLongSafe(items.get(1)) : -1;
                long actualFee = items.size() > 2 ? toLongSafe(items.get(2)) : -1;
                return leafError(era, "UTXO", "FeeTooSmallUTxO", tag,
                        "Fee too small: minimum " + minimumFee + ", actual " + actualFee,
                        detail("minimumFee", minimumFee, "actualFee", actualFee));
            }
            case 6: { // ValueNotConservedUTxO
                String consumed = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String produced = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(era, "UTXO", "ValueNotConservedUTxO", tag,
                        "Value not conserved", detail("consumed", consumed, "produced", produced));
            }
            case 7: { // OutputTooSmallUTxO
                String outputs = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                return leafError(era, "UTXO", "OutputTooSmallUTxO", tag,
                        "Output too small (below minimum ADA)", detail("outputs", outputs));
            }
            case 8: { // WrongNetwork
                String expectedNetwork = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String addresses = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(era, "UTXO", "WrongNetwork", tag,
                        "Wrong network in output address", detail("expectedNetwork", expectedNetwork, "addresses", addresses));
            }
            case 9: { // WrongNetworkWithdrawal
                String expectedNetwork = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String accounts = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(era, "UTXO", "WrongNetworkWithdrawal", tag,
                        "Wrong network in withdrawal address", detail("expectedNetwork", expectedNetwork, "accounts", accounts));
            }
            case 10: { // OutputBootAddrAttrsTooBig
                String outputs = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                return leafError(era, "UTXO", "OutputBootAddrAttrsTooBig", tag,
                        "Byron address attributes too big", detail("outputs", outputs));
            }
            case 11: // TriesToForgeADA
                return leafError(era, "UTXO", "TriesToForgeADA", tag, "Transaction tries to forge ADA");
            case 12: { // OutputTooBigUTxO
                String outputs = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                return leafError(era, "UTXO", "OutputTooBigUTxO", tag,
                        "Output too big (exceeds max value size)", detail("outputs", outputs));
            }
            case 13: { // InsufficientCollateral
                long required = items.size() > 1 ? toLongSafe(items.get(1)) : -1;
                long provided = items.size() > 2 ? toLongSafe(items.get(2)) : -1;
                return leafError(era, "UTXO", "InsufficientCollateral", tag,
                        "Insufficient collateral: required " + required + ", provided " + provided,
                        detail("required", required, "provided", provided));
            }
            case 14: { // ScriptsNotPaidUTxO
                String utxos = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                return leafError(era, "UTXO", "ScriptsNotPaidUTxO", tag, "Scripts not paid", detail("utxos", utxos));
            }
            case 15: { // ExUnitsTooBigUTxO
                String maxUnits = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String actualUnits = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(era, "UTXO", "ExUnitsTooBigUTxO", tag,
                        "Execution units too big", detail("maxUnits", maxUnits, "actualUnits", actualUnits));
            }
            case 16: // CollateralContainsNonADA
                return leafError(era, "UTXO", "CollateralContainsNonADA", tag, "Collateral contains non-ADA");
            case 17: { // WrongNetworkInTxBody
                String expected = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String actual = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(era, "UTXO", "WrongNetworkInTxBody", tag,
                        "Wrong network in tx body", detail("expected", expected, "actual", actual));
            }
            case 18: { // OutsideForecast
                long slot = items.size() > 1 ? toLongSafe(items.get(1)) : -1;
                return leafError(era, "UTXO", "OutsideForecast", tag,
                        "Validity interval outside forecast (slot " + slot + ")", detail("slot", slot));
            }
            case 19: { // TooManyCollateralInputs
                long max = items.size() > 1 ? toLongSafe(items.get(1)) : -1;
                long actual = items.size() > 2 ? toLongSafe(items.get(2)) : -1;
                return leafError(era, "UTXO", "TooManyCollateralInputs", tag,
                        "Too many collateral inputs: max " + max + ", actual " + actual,
                        detail("max", max, "actual", actual));
            }
            case 20: // NoCollateralInputs
                return leafError(era, "UTXO", "NoCollateralInputs", tag, "No collateral inputs");
            default:
                return null;
        }
    }

    // ---- Shared UTXOW witness error parsing (tags 1-9) ----
    // Common across Shelley, Allegra, Mary, and Conway (same tag assignments).

    /**
     * Parse common UTXOW witness predicate failures (tags 1-9) shared across Shelley and Conway.
     *
     * @return parsed error, or {@code null} if the tag is not in the common range 1-9
     */
    static TxSubmissionError parseCommonUtxowWitnessError(Era era, int tag, List<DataItem> items) {
        switch (tag) {
            case 1: { // InvalidWitnessesUTXOW
                List<String> witnesses = items.size() > 1 ? extractHashList(items.get(1)) : Collections.emptyList();
                return leafError(era, "UTXOW", "InvalidWitnessesUTXOW", tag,
                        "Invalid witnesses", detail("witnesses", witnesses));
            }
            case 2: { // MissingVKeyWitnessesUTXOW
                List<String> hashes = items.size() > 1 ? extractHashList(items.get(1)) : Collections.emptyList();
                return leafError(era, "UTXOW", "MissingVKeyWitnessesUTXOW", tag,
                        "Missing verification key witnesses: " + hashes.size() + " key(s)", detail("keyHashes", hashes));
            }
            case 3: { // MissingScriptWitnessesUTXOW
                List<String> hashes = items.size() > 1 ? extractHashList(items.get(1)) : Collections.emptyList();
                return leafError(era, "UTXOW", "MissingScriptWitnessesUTXOW", tag,
                        "Missing script witnesses", detail("scriptHashes", hashes));
            }
            case 4: { // ScriptWitnessNotValidatingUTXOW
                List<String> hashes = items.size() > 1 ? extractHashList(items.get(1)) : Collections.emptyList();
                return leafError(era, "UTXOW", "ScriptWitnessNotValidatingUTXOW", tag,
                        "Script witness not validating", detail("scriptHashes", hashes));
            }
            case 5: { // MissingTxBodyMetadataHash
                String hash = items.size() > 1 ? toHexSafe(items.get(1)) : "";
                return leafError(era, "UTXOW", "MissingTxBodyMetadataHash", tag,
                        "Missing tx body metadata hash", detail("hash", hash));
            }
            case 6: { // MissingTxMetadata
                String hash = items.size() > 1 ? toHexSafe(items.get(1)) : "";
                return leafError(era, "UTXOW", "MissingTxMetadata", tag,
                        "Missing tx metadata", detail("hash", hash));
            }
            case 7: { // ConflictingMetadataHash
                String expected = items.size() > 1 ? toHexSafe(items.get(1)) : "";
                String actual = items.size() > 2 ? toHexSafe(items.get(2)) : "";
                return leafError(era, "UTXOW", "ConflictingMetadataHash", tag,
                        "Conflicting metadata hash", detail("expected", expected, "actual", actual));
            }
            case 8: // InvalidMetadata
                return leafError(era, "UTXOW", "InvalidMetadata", tag, "Invalid metadata");
            case 9: { // ExtraneousScriptWitnessesUTXOW
                List<String> hashes = items.size() > 1 ? extractHashList(items.get(1)) : Collections.emptyList();
                return leafError(era, "UTXOW", "ExtraneousScriptWitnessesUTXOW", tag,
                        "Extraneous script witnesses", detail("scriptHashes", hashes));
            }
            default:
                return null;
        }
    }

    // ---- Shared UTXOW script/datum error parsing (tags 10-15, Conway numbering) ----

    /**
     * Parse common UTXOW script/datum predicate failures (tags 10-15) as numbered in Conway.
     * These errors also appear in Alonzo at different tag offsets (1-7).
     *
     * @return parsed error, or {@code null} if the tag is not in the range 10-15
     */
    static TxSubmissionError parseCommonUtxowScriptError(Era era, int tag, List<DataItem> items) {
        switch (tag) {
            case 10: { // MissingRedeemers
                String raw = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                return leafError(era, "UTXOW", "MissingRedeemers", tag,
                        "Missing redeemers", detail("redeemers", raw));
            }
            case 11: { // MissingRequiredDatums
                String required = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String provided = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(era, "UTXOW", "MissingRequiredDatums", tag,
                        "Missing required datums", detail("required", required, "provided", provided));
            }
            case 12: { // NotAllowedSupplementalDatums
                String notAllowed = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String acceptable = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(era, "UTXOW", "NotAllowedSupplementalDatums", tag,
                        "Supplemental datums not allowed", detail("notAllowed", notAllowed, "acceptable", acceptable));
            }
            case 13: { // PPViewHashesDontMatch
                String expected = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String actual = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(era, "UTXOW", "PPViewHashesDontMatch", tag,
                        "Protocol parameter view hashes don't match", detail("expected", expected, "actual", actual));
            }
            case 14: { // UnspendableUTxONoDatumHash
                List<String> inputs = items.size() > 1 ? extractTxInputList(items.get(1)) : Collections.emptyList();
                return leafError(era, "UTXOW", "UnspendableUTxONoDatumHash", tag,
                        "Unspendable UTxO (no datum hash)", detail("txInputs", inputs));
            }
            case 15: { // ExtraRedeemers
                String raw = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                return leafError(era, "UTXOW", "ExtraRedeemers", tag,
                        "Extra redeemers", detail("redeemers", raw));
            }
            default:
                return null;
        }
    }
}
