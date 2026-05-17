package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.Era;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.bloxbean.cardano.yaci.core.protocol.localtx.model.ErrorParserUtil.*;

/**
 * Parser for Shelley, Allegra, and Mary era ledger errors.
 * <p>
 * Shelley LEDGER tags 0-3, UTXOW tags 0-10, UTXO tags 0-10 (Shelley) or 0-12 (Allegra/Mary).
 * DELEGS -&gt; DELPL -&gt; DELEG/POOL chains.
 * Also used by Alonzo and Babbage for wrapped Shelley-era errors.
 */
@Slf4j
class ShelleyErrorParser {

    // ---- LEDGER (Shelley/Allegra/Mary) ----

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
                TxSubmissionError child = inner != null ? parseDelegsFailure(era, inner) : unknownError(era, "DELEGS", tag, di);
                return wrapError(era, "LEDGER", "DelegsFailure", tag, "Delegation failure", Collections.singletonList(child));
            }
            default:
                return unknownError(era, "LEDGER", tag, di);
        }
    }

    // ---- UTXOW (Shelley) ----

    static TxSubmissionError parseUtxowFailure(Era era, DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(era, "UTXOW", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(era, "UTXOW", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0: { // UtxoFailure -> recurse UTXO
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parseUtxoFailure(era, inner) : unknownError(era, "UTXO", tag, di);
                return wrapError(era, "UTXOW", "UtxoFailure", tag, "UTXO failure", Collections.singletonList(child));
            }
            default: {
                // Tags 1-9: common witness errors (shared with Conway)
                TxSubmissionError common = parseCommonUtxowWitnessError(era, tag, items);
                if (common != null) return common;
                return unknownError(era, "UTXOW", tag, di);
            }
        }
    }

    // ---- UTXO (Shelley/Allegra/Mary) ----
    // Shelley uses ExpiredUTxO (tag 1), Allegra/Mary use OutsideValidityIntervalUTxO.
    // Allegra/Mary add TriesToForgeADA (tag 11) and OutputTooBigUTxO (tag 12).

    static TxSubmissionError parseUtxoFailure(Era era, DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(era, "UTXO", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(era, "UTXO", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0: { // BadInputsUTxO
                List<String> inputs = items.size() > 1 ? extractTxInputList(items.get(1)) : Collections.emptyList();
                return leafError(era, "UTXO", "BadInputsUTxO", tag, "Bad inputs (UTxOs not found)", detail("inputs", inputs));
            }
            case 1: { // ExpiredUTxO (Shelley) or OutsideValidityIntervalUTxO (Allegra/Mary)
                if (era == Era.Allegra || era == Era.Mary) {
                    String interval = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                    long currentSlot = items.size() > 2 ? toLongSafe(items.get(2)) : -1;
                    return leafError(era, "UTXO", "OutsideValidityIntervalUTxO", tag,
                            "Transaction outside validity interval (current slot: " + currentSlot + ")",
                            detail("validityInterval", interval, "currentSlot", currentSlot));
                } else {
                    long ttl = items.size() > 1 ? toLongSafe(items.get(1)) : -1;
                    long currentSlot = items.size() > 2 ? toLongSafe(items.get(2)) : -1;
                    Map<String, Object> d = detail("ttl", ttl, "currentSlot", currentSlot);
                    return leafError(era, "UTXO", "ExpiredUTxO", tag,
                            "Transaction expired: TTL " + ttl + ", current slot " + currentSlot, d);
                }
            }
            case 2: { // MaxTxSizeUTxO
                long actualSize = items.size() > 1 ? toLongSafe(items.get(1)) : -1;
                long maxSize = items.size() > 2 ? toLongSafe(items.get(2)) : -1;
                return leafError(era, "UTXO", "MaxTxSizeUTxO", tag,
                        "Transaction size too large: actual " + actualSize + ", max " + maxSize,
                        detail("actualSize", actualSize, "maxSize", maxSize));
            }
            case 3: // InputSetEmptyUTxO
                return leafError(era, "UTXO", "InputSetEmptyUTxO", tag, "Transaction has no inputs");
            case 4: { // FeeTooSmallUTxO
                long minimumFee = items.size() > 1 ? toLongSafe(items.get(1)) : -1;
                long actualFee = items.size() > 2 ? toLongSafe(items.get(2)) : -1;
                Map<String, Object> d = detail("minimumFee", minimumFee, "actualFee", actualFee);
                return leafError(era, "UTXO", "FeeTooSmallUTxO", tag,
                        "Fee too small: minimum " + minimumFee + ", actual " + actualFee, d);
            }
            case 5: { // ValueNotConservedUTxO
                String consumed = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String produced = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(era, "UTXO", "ValueNotConservedUTxO", tag,
                        "Value not conserved", detail("consumed", consumed, "produced", produced));
            }
            case 6: { // WrongNetwork
                String expectedNetwork = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String addresses = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(era, "UTXO", "WrongNetwork", tag,
                        "Wrong network in output address", detail("expectedNetwork", expectedNetwork, "addresses", addresses));
            }
            case 7: { // WrongNetworkWithdrawal
                String expectedNetwork = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String accounts = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(era, "UTXO", "WrongNetworkWithdrawal", tag,
                        "Wrong network in withdrawal address", detail("expectedNetwork", expectedNetwork, "accounts", accounts));
            }
            case 8: { // OutputTooSmallUTxO
                String outputs = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                return leafError(era, "UTXO", "OutputTooSmallUTxO", tag,
                        "Output too small (below minimum ADA)", detail("outputs", outputs));
            }
            case 9: // UpdateFailure
                return leafError(era, "UTXO", "UpdateFailure", tag, "Update failure");
            case 10: { // OutputBootAddrAttrsTooBig
                String outputs = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                return leafError(era, "UTXO", "OutputBootAddrAttrsTooBig", tag,
                        "Byron address attributes too big", detail("outputs", outputs));
            }
            case 11: // TriesToForgeADA (Allegra/Mary)
                return leafError(era, "UTXO", "TriesToForgeADA", tag, "Transaction tries to forge ADA");
            case 12: { // OutputTooBigUTxO (Allegra/Mary)
                String outputs = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                return leafError(era, "UTXO", "OutputTooBigUTxO", tag,
                        "Output too big (exceeds max value size)", detail("outputs", outputs));
            }
            default:
                return unknownError(era, "UTXO", tag, di);
        }
    }

    // ---- DELEGS (Shelley/Allegra/Mary) ----

    static TxSubmissionError parseDelegsFailure(Era era, DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(era, "DELEGS", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(era, "DELEGS", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0: { // DelegateeNotRegisteredDELEG
                return leafError(era, "DELEGS", "DelegateeNotRegisteredDELEG", tag, "Delegatee not registered");
            }
            case 1: { // WithdrawNotDelegatedDELEG
                return leafError(era, "DELEGS", "WithdrawNotDelegatedDELEG", tag, "Withdrawal not delegated");
            }
            case 2: { // DelplFailure -> recurse DELPL
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parseDelplFailure(era, inner) : unknownError(era, "DELPL", tag, di);
                return wrapError(era, "DELEGS", "DelplFailure", tag, "DELPL failure", Collections.singletonList(child));
            }
            default:
                return unknownError(era, "DELEGS", tag, di);
        }
    }

    private static TxSubmissionError parseDelplFailure(Era era, DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(era, "DELPL", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(era, "DELPL", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0: { // PoolFailure
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parsePoolFailure(era, inner) : unknownError(era, "POOL", tag, di);
                return wrapError(era, "DELPL", "PoolFailure", tag, "Pool failure", Collections.singletonList(child));
            }
            case 1: { // DelegFailure
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parseDelegFailure(era, inner) : unknownError(era, "DELEG", tag, di);
                return wrapError(era, "DELPL", "DelegFailure", tag, "Delegation failure", Collections.singletonList(child));
            }
            default:
                return unknownError(era, "DELPL", tag, di);
        }
    }

    private static TxSubmissionError parseDelegFailure(Era era, DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(era, "DELEG", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(era, "DELEG", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0:
                return leafError(era, "DELEG", "StakeKeyAlreadyRegisteredDELEG", tag, "Stake key already registered");
            case 1:
                return leafError(era, "DELEG", "StakeKeyNotRegisteredDELEG", tag, "Stake key not registered");
            case 2:
                return leafError(era, "DELEG", "StakeKeyNonZeroAccountBalanceDELEG", tag,
                        "Stake key has non-zero account balance");
            case 3:
                return leafError(era, "DELEG", "StakeDelegationImpossibleDELEG", tag,
                        "Stake delegation impossible (pool not registered)");
            case 4:
                return leafError(era, "DELEG", "WrongCertificateTypeDELEG", tag, "Wrong certificate type");
            case 5:
                return leafError(era, "DELEG", "GenesisKeyNotInMappingDELEG", tag,
                        "Genesis key not in mapping");
            case 6:
                return leafError(era, "DELEG", "DuplicateGenesisDelegateDELEG", tag,
                        "Duplicate genesis delegate");
            case 7:
                return leafError(era, "DELEG", "InsufficientForInstantaneousRewardsDELEG", tag,
                        "Insufficient for instantaneous rewards");
            case 8:
                return leafError(era, "DELEG", "MIRCertificateTooLateinEpochDELEG", tag,
                        "MIR certificate too late in epoch");
            case 9:
                return leafError(era, "DELEG", "DuplicateGenesisVRFDELEG", tag,
                        "Duplicate genesis VRF");
            case 10:
                return leafError(era, "DELEG", "MIRTransferNotCurrentlyAllowed", tag,
                        "MIR transfer not currently allowed");
            case 11:
                return leafError(era, "DELEG", "MIRNegativesNotCurrentlyAllowed", tag,
                        "MIR negatives not currently allowed");
            case 12:
                return leafError(era, "DELEG", "InsufficientForTransferDELEG", tag,
                        "Insufficient for transfer");
            case 13:
                return leafError(era, "DELEG", "MIRProducesNegativeUpdate", tag,
                        "MIR produces negative update");
            case 14:
                return leafError(era, "DELEG", "MIRNegativeTransfer", tag,
                        "MIR negative transfer");
            default:
                return unknownError(era, "DELEG", tag, di);
        }
    }

    static TxSubmissionError parsePoolFailure(Era era, DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(era, "POOL", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(era, "POOL", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0:
                return leafError(era, "POOL", "StakePoolNotRegisteredOnKeyPOOL", tag, "Stake pool not registered");
            case 1:
                return leafError(era, "POOL", "StakePoolRetirementWrongEpochPOOL", tag, "Pool retirement wrong epoch");
            case 2:
                return leafError(era, "POOL", "StakePoolCostTooLowPOOL", tag, "Pool cost too low");
            case 3:
                return leafError(era, "POOL", "WrongNetworkPOOL", tag, "Wrong network in pool registration");
            case 4:
                return leafError(era, "POOL", "PoolMetadataHashTooBig", tag, "Pool metadata hash too big");
            default:
                return unknownError(era, "POOL", tag, di);
        }
    }
}
