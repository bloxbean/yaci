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
 * Parser for Conway era ledger errors.
 * <p>
 * Conway LEDGER failure tags 1-9, UTXOW tags 0-18, UTXO tags 0-22,
 * UTXOS tags 0-1, CERTS, and GOV errors.
 */
@Slf4j
class ConwayErrorParser {
    private static final Era ERA = Era.Conway;

    // ---- LEDGER (Conway) ----

    static TxSubmissionError parseLedgerFailure(DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(ERA, "LEDGER", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(ERA, "LEDGER", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 1: { // ConwayUtxowFailure -> recurse UTXOW
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parseUtxowFailure(inner) : unknownError(ERA, "UTXOW", tag, di);
                return wrapError(ERA, "LEDGER", "ConwayUtxowFailure", tag, "UTXOW failure", Collections.singletonList(child));
            }
            case 2: { // ConwayCertsFailure -> recurse CERTS
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parseCertsFailure(inner) : unknownError(ERA, "CERTS", tag, di);
                return wrapError(ERA, "LEDGER", "ConwayCertsFailure", tag, "Certificate failure", Collections.singletonList(child));
            }
            case 3: { // ConwayGovFailure -> recurse GOV
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parseGovFailure(inner) : unknownError(ERA, "GOV", tag, di);
                return wrapError(ERA, "LEDGER", "ConwayGovFailure", tag, "Governance failure", Collections.singletonList(child));
            }
            case 4: { // ConwayWdrlNotDelegatedToDRep
                List<String> hashes = items.size() > 1 ? extractHashList(items.get(1)) : Collections.emptyList();
                Map<String, Object> d = detail("keyHashes", hashes);
                return leafError(ERA, "LEDGER", "ConwayWdrlNotDelegatedToDRep", tag, "Withdrawals not delegated to any DRep", d);
            }
            case 5: { // ConwayTreasuryValueMismatch
                long expected = items.size() > 1 ? toLongSafe(items.get(1)) : -1;
                long actual = items.size() > 2 ? toLongSafe(items.get(2)) : -1;
                Map<String, Object> d = detail("expected", expected, "actual", actual);
                return leafError(ERA, "LEDGER", "ConwayTreasuryValueMismatch", tag,
                        "Treasury value mismatch: expected " + expected + ", actual " + actual, d);
            }
            case 6: { // ConwayTxRefScriptsSizeTooBig
                long max = items.size() > 1 ? toLongSafe(items.get(1)) : -1;
                long actual = items.size() > 2 ? toLongSafe(items.get(2)) : -1;
                Map<String, Object> d = detail("max", max, "actual", actual);
                return leafError(ERA, "LEDGER", "ConwayTxRefScriptsSizeTooBig", tag,
                        "Reference scripts total size too big: max " + max + ", actual " + actual, d);
            }
            case 7: { // ConwayMempoolFailure
                String text = items.size() > 1 ? toStringSafe(items.get(1)) : "";
                Map<String, Object> d = detail("text", text);
                return leafError(ERA, "LEDGER", "ConwayMempoolFailure", tag, text, d);
            }
            case 8: // ConwayWithdrawalsMissingAccounts
                return leafError(ERA, "LEDGER", "ConwayWithdrawalsMissingAccounts", tag, "Withdrawals reference missing accounts");
            case 9: // ConwayIncompleteWithdrawals
                return leafError(ERA, "LEDGER", "ConwayIncompleteWithdrawals", tag, "Incomplete withdrawals");
            default:
                return unknownError(ERA, "LEDGER", tag, di);
        }
    }

    // ---- UTXOW (Conway) ----

    static TxSubmissionError parseUtxowFailure(DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(ERA, "UTXOW", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(ERA, "UTXOW", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0: { // UtxoFailure -> recurse UTXO
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parseUtxoFailure(inner) : unknownError(ERA, "UTXO", tag, di);
                return wrapError(ERA, "UTXOW", "UtxoFailure", tag, "UTXO failure", Collections.singletonList(child));
            }
            default: {
                // Tags 1-9: common witness errors (shared with Shelley)
                TxSubmissionError witness = parseCommonUtxowWitnessError(ERA, tag, items);
                if (witness != null) return witness;
                // Tags 10-15: common script/datum errors
                TxSubmissionError script = parseCommonUtxowScriptError(ERA, tag, items);
                if (script != null) return script;
                // Conway-specific UTXOW tags 16-18
                switch (tag) {
                    case 16: { // MalformedScriptWitnesses
                        List<String> hashes = items.size() > 1 ? extractHashList(items.get(1)) : Collections.emptyList();
                        return leafError(ERA, "UTXOW", "MalformedScriptWitnesses", tag,
                                "Malformed script witnesses", detail("scriptHashes", hashes));
                    }
                    case 17: { // MalformedReferenceScripts
                        List<String> hashes = items.size() > 1 ? extractHashList(items.get(1)) : Collections.emptyList();
                        return leafError(ERA, "UTXOW", "MalformedReferenceScripts", tag,
                                "Malformed reference scripts", detail("scriptHashes", hashes));
                    }
                    case 18: { // ScriptIntegrityHashMismatch
                        String expected = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                        String actual = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                        return leafError(ERA, "UTXOW", "ScriptIntegrityHashMismatch", tag,
                                "Script integrity hash mismatch", detail("expected", expected, "actual", actual));
                    }
                    default:
                        return unknownError(ERA, "UTXOW", tag, di);
                }
            }
        }
    }

    // ---- UTXO (Conway) ----

    static TxSubmissionError parseUtxoFailure(DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(ERA, "UTXO", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(ERA, "UTXO", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0: { // UtxosFailure -> recurse UTXOS
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parseUtxosFailure(inner) : unknownError(ERA, "UTXOS", tag, di);
                return wrapError(ERA, "UTXO", "UtxosFailure", tag, "UTXOS failure", Collections.singletonList(child));
            }
            default: {
                // Tags 1-20: common UTXO errors (shared with Alonzo)
                TxSubmissionError common = parseCommonUtxoError(ERA, tag, items);
                if (common != null) return common;
                // Conway-specific UTXO tags 21-22
                switch (tag) {
                    case 21: { // NonDisjointRefInputs
                        List<String> inputs = items.size() > 1 ? extractTxInputList(items.get(1)) : Collections.emptyList();
                        return leafError(ERA, "UTXO", "NonDisjointRefInputs", tag,
                                "Reference inputs overlap with regular inputs", detail("inputs", inputs));
                    }
                    case 22: { // TotalCollateralMismatch
                        long declared = items.size() > 1 ? toLongSafe(items.get(1)) : -1;
                        long computed = items.size() > 2 ? toLongSafe(items.get(2)) : -1;
                        return leafError(ERA, "UTXO", "TotalCollateralMismatch", tag,
                                "Total collateral mismatch: declared " + declared + ", computed " + computed,
                                detail("declared", declared, "computed", computed));
                    }
                    default:
                        return unknownError(ERA, "UTXO", tag, di);
                }
            }
        }
    }

    // ---- UTXOS (Conway) ----

    static TxSubmissionError parseUtxosFailure(DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(ERA, "UTXOS", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(ERA, "UTXOS", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0: { // ValidationTagMismatch
                String isValid = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                String desc = items.size() > 2 ? serializeToHex(items.get(2)) : "";
                return leafError(ERA, "UTXOS", "ValidationTagMismatch", tag,
                        "Validation tag mismatch", detail("isValid", isValid, "description", desc));
            }
            case 1: { // CollectErrors
                String errors = items.size() > 1 ? serializeToHex(items.get(1)) : "";
                return leafError(ERA, "UTXOS", "CollectErrors", tag,
                        "Collect errors (script execution setup)", detail("errors", errors));
            }
            default:
                return unknownError(ERA, "UTXOS", tag, di);
        }
    }

    // ---- CERTS (Conway) ----

    static TxSubmissionError parseCertsFailure(DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(ERA, "CERTS", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(ERA, "CERTS", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0: { // WithdrawalsNotInRewardsCERTS
                return leafError(ERA, "CERTS", "WithdrawalsNotInRewardsCERTS", tag,
                        "Withdrawals not in rewards accounts");
            }
            case 1: { // CertFailure -> recurse
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parseCertFailure(inner) : unknownError(ERA, "CERT", tag, di);
                return wrapError(ERA, "CERTS", "CertFailure", tag, "Certificate failure", Collections.singletonList(child));
            }
            default:
                return unknownError(ERA, "CERTS", tag, di);
        }
    }

    private static TxSubmissionError parseCertFailure(DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(ERA, "CERT", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(ERA, "CERT", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0: { // ConwayDelegFailure -> recurse DELEG
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parseDelegFailure(inner) : unknownError(ERA, "DELEG", tag, di);
                return wrapError(ERA, "CERT", "ConwayDelegFailure", tag, "Delegation failure", Collections.singletonList(child));
            }
            case 1: { // ConwayPoolFailure -> recurse POOL
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parsePoolFailure(inner) : unknownError(ERA, "POOL", tag, di);
                return wrapError(ERA, "CERT", "ConwayPoolFailure", tag, "Pool failure", Collections.singletonList(child));
            }
            case 2: { // ConwayGovCertFailure -> recurse GOVCERT
                DataItem inner = items.size() > 1 ? items.get(1) : null;
                TxSubmissionError child = inner != null ? parseGovCertFailure(inner) : unknownError(ERA, "GOVCERT", tag, di);
                return wrapError(ERA, "CERT", "ConwayGovCertFailure", tag, "Governance certificate failure", Collections.singletonList(child));
            }
            default:
                return unknownError(ERA, "CERT", tag, di);
        }
    }

    private static TxSubmissionError parseDelegFailure(DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(ERA, "DELEG", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(ERA, "DELEG", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0:
                return leafError(ERA, "DELEG", "DelegateeNotRegisteredDELEG", tag, "Delegatee not registered");
            case 1:
                return leafError(ERA, "DELEG", "StakeKeyNotRegisteredDELEG", tag, "Stake key not registered");
            case 2:
                return leafError(ERA, "DELEG", "StakeKeyRegisteredDELEG", tag, "Stake key already registered");
            case 3:
                return leafError(ERA, "DELEG", "StakeKeyHasNonZeroRewardAccountBalanceDELEG", tag,
                        "Stake key has non-zero reward account balance");
            case 4:
                return leafError(ERA, "DELEG", "DRepAlreadyRegisteredForStakeKeyDELEG", tag,
                        "DRep already registered for this stake key");
            case 5:
                return leafError(ERA, "DELEG", "WrongCertificateTypeDELEG", tag, "Wrong certificate type");
            default:
                return unknownError(ERA, "DELEG", tag, di);
        }
    }

    private static TxSubmissionError parsePoolFailure(DataItem di) {
        return ShelleyErrorParser.parsePoolFailure(ERA, di);
    }

    private static TxSubmissionError parseGovCertFailure(DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(ERA, "GOVCERT", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(ERA, "GOVCERT", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0:
                return leafError(ERA, "GOVCERT", "ConwayDRepAlreadyRegistered", tag, "DRep already registered");
            case 1:
                return leafError(ERA, "GOVCERT", "ConwayDRepNotRegistered", tag, "DRep not registered");
            case 2:
                return leafError(ERA, "GOVCERT", "ConwayDRepIncorrectDeposit", tag, "DRep incorrect deposit");
            case 3:
                return leafError(ERA, "GOVCERT", "ConwayCommitteeHasPreviouslyResigned", tag,
                        "Committee member has previously resigned");
            case 4:
                return leafError(ERA, "GOVCERT", "ConwayDRepIncorrectRefund", tag, "DRep incorrect refund");
            case 5:
                return leafError(ERA, "GOVCERT", "ConwayCommitteeIsUnknown", tag, "Committee member is unknown");
            default:
                return unknownError(ERA, "GOVCERT", tag, di);
        }
    }

    // ---- GOV (Conway) ----

    static TxSubmissionError parseGovFailure(DataItem di) {
        if (!(di instanceof Array)) {
            return unknownError(ERA, "GOV", -1, di);
        }
        Array arr = (Array) di;
        List<DataItem> items = arr.getDataItems();
        if (items.isEmpty()) return unknownError(ERA, "GOV", -1, di);

        int tag = toIntSafe(items.get(0));
        switch (tag) {
            case 0:
                return leafError(ERA, "GOV", "GovActionsDoNotExist", tag, "Governance actions do not exist");
            case 1:
                return leafError(ERA, "GOV", "MalformedProposal", tag, "Malformed proposal");
            case 2:
                return leafError(ERA, "GOV", "ProposalProcedureNetworkIdMismatch", tag,
                        "Proposal procedure network ID mismatch");
            case 3:
                return leafError(ERA, "GOV", "TreasuryWithdrawalsNetworkIdMismatch", tag,
                        "Treasury withdrawals network ID mismatch");
            case 4: { // ProposalDepositIncorrect
                long expected = items.size() > 1 ? toLongSafe(items.get(1)) : -1;
                long actual = items.size() > 2 ? toLongSafe(items.get(2)) : -1;
                return leafError(ERA, "GOV", "ProposalDepositIncorrect", tag,
                        "Proposal deposit incorrect: expected " + expected + ", actual " + actual,
                        detail("expected", expected, "actual", actual));
            }
            case 5:
                return leafError(ERA, "GOV", "DisallowedVoters", tag, "Disallowed voters");
            case 6:
                return leafError(ERA, "GOV", "ConflictingCommitteeUpdate", tag, "Conflicting committee update");
            case 7:
                return leafError(ERA, "GOV", "ExpirationEpochTooSmall", tag, "Expiration epoch too small");
            case 8:
                return leafError(ERA, "GOV", "InvalidPrevGovActionId", tag, "Invalid previous governance action ID");
            case 9:
                return leafError(ERA, "GOV", "VotingOnExpiredGovAction", tag, "Voting on expired governance action");
            case 10:
                return leafError(ERA, "GOV", "ProposalCantFollow", tag, "Proposal can't follow previous action");
            case 11:
                return leafError(ERA, "GOV", "InvalidPolicyHash", tag, "Invalid policy hash");
            case 12:
                return leafError(ERA, "GOV", "DisallowedProposalDuringBootstrap", tag,
                        "Disallowed proposal during bootstrap");
            case 13:
                return leafError(ERA, "GOV", "DisallowedVotesDuringBootstrap", tag,
                        "Disallowed votes during bootstrap");
            case 14:
                return leafError(ERA, "GOV", "VotersDoNotExist", tag, "Voters do not exist");
            case 15:
                return leafError(ERA, "GOV", "ZeroTreasuryWithdrawals", tag, "Zero treasury withdrawals");
            case 16:
                return leafError(ERA, "GOV", "ProposalReturnAccountDoesNotExist", tag,
                        "Proposal return account does not exist");
            case 17:
                return leafError(ERA, "GOV", "TreasuryWithdrawalReturnAccountsDoNotExist", tag,
                        "Treasury withdrawal return accounts do not exist");
            default:
                return unknownError(ERA, "GOV", tag, di);
        }
    }
}
