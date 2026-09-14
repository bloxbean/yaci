package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Era;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TxSubmissionErrorParserTest {

    // Helper to build a CBOR array from DataItems
    private static Array arr(DataItem... items) {
        Array array = new Array();
        for (DataItem item : items) {
            array.add(item);
        }
        return array;
    }

    private static UnsignedInteger uint(int value) {
        return new UnsignedInteger(value);
    }

    // ---- Conway FeeTooSmallUTxO ----
    // Wire: [[6, [[1, [0, [5, 200000, 170000]]]]]]
    // Breakdown: [eraIndex=6, ApplyTxError=[LedgerFailure=[tag=1(ConwayUtxowFailure), [tag=0(UtxoFailure), [tag=5(FeeTooSmall), min, actual]]]]]
    @Test
    void conwayFeeTooSmallUTxO() {
        // Build innermost: [5, 200000, 170000]
        Array feeTooSmall = arr(uint(5), uint(200000), uint(170000));
        // UTXO failure wrapper: [0, feeTooSmall] -> UTXOW UtxoFailure
        Array utxoFailure = arr(uint(0), feeTooSmall);
        // UTXOW failure: [1, utxoFailure] -> LEDGER ConwayUtxowFailure
        Array utxowWrapper = arr(uint(1), utxoFailure);
        // ApplyTxError: [utxowWrapper]
        Array applyTxError = arr(utxowWrapper);
        // HFC wrapper: [[6, applyTxError]]
        Array eraAndError = arr(uint(6), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "deadbeef");

        assertThat(result).isNotNull();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getEraMismatch()).isNull();
        assertThat(result.getRawCborHex()).isEqualTo("deadbeef");

        TxSubmissionError topError = result.getErrors().get(0);
        assertThat(topError.getEra()).isEqualTo(Era.Conway);
        assertThat(topError.getRule()).isEqualTo("LEDGER");
        assertThat(topError.getErrorName()).isEqualTo("ConwayUtxowFailure");
        assertThat(topError.getChildren()).hasSize(1);

        // Navigate to leaf
        List<TxSubmissionError> leaves = topError.getLeafErrors();
        assertThat(leaves).hasSize(1);
        TxSubmissionError leaf = leaves.get(0);
        assertThat(leaf.getErrorName()).isEqualTo("FeeTooSmallUTxO");
        assertThat(leaf.getRule()).isEqualTo("UTXO");
        assertThat(leaf.getTag()).isEqualTo(5);
        assertThat(leaf.getMessage()).contains("200000");
        assertThat(leaf.getMessage()).contains("170000");
        assertThat(leaf.getDetail()).containsEntry("minimumFee", 200000L);
        assertThat(leaf.getDetail()).containsEntry("actualFee", 170000L);

        // Test getErrorMessage convenience
        assertThat(result.getErrorMessage()).contains("200000");
    }

    // ---- Conway MissingVKeyWitnesses ----
    @Test
    void conwayMissingVKeyWitnesses() {
        // [2, [hash_bytes]] -- MissingVKeyWitnessesUTXOW
        co.nstant.in.cbor.model.ByteString hash = new co.nstant.in.cbor.model.ByteString(new byte[]{0x01, 0x02, 0x03});
        Array hashSet = arr(hash);
        Array utxowFailure = arr(uint(2), hashSet);
        // Wrap: LEDGER [1, utxowFailure]
        Array ledgerFailure = arr(uint(1), utxowFailure);
        Array applyTxError = arr(ledgerFailure);
        Array eraAndError = arr(uint(6), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "aabb");

        assertThat(result.getErrors()).hasSize(1);
        List<TxSubmissionError> leaves = result.getErrors().get(0).getLeafErrors();
        assertThat(leaves).hasSize(1);
        assertThat(leaves.get(0).getErrorName()).isEqualTo("MissingVKeyWitnessesUTXOW");
        assertThat(leaves.get(0).getMessage()).contains("Missing verification key witnesses");
    }

    // ---- Conway MempoolFailure ----
    @Test
    void conwayMempoolFailure() {
        // [[6, [[7, "some mempool failure text"]]]]
        Array mempoolFailure = arr(uint(7), new UnicodeString("some mempool failure text"));
        Array applyTxError = arr(mempoolFailure);
        Array eraAndError = arr(uint(6), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "ccdd");

        assertThat(result.getErrors()).hasSize(1);
        TxSubmissionError error = result.getErrors().get(0);
        assertThat(error.getErrorName()).isEqualTo("ConwayMempoolFailure");
        assertThat(error.getMessage()).isEqualTo("some mempool failure text");
        assertThat(error.getDetail()).containsEntry("text", "some mempool failure text");
    }

    // ---- Babbage FeeTooSmall (through wrapping chain) ----
    // Wire: [[5, [[0, [1, [0, [0, [5, 300000, 250000]]]]]]]]
    // Babbage LEDGER [0, UtxowFailure] -> Babbage UTXOW [1, AlonzoInBabbage] -> Alonzo UTXOW [0, ShelleyInAlonzo]
    //   -> Shelley UTXOW [0, UtxoFailure] -> Shelley UTXO [4, FeeTooSmall, min, actual]
    @Test
    void babbageFeeTooSmallThroughWrappingChain() {
        // Shelley UTXO: [4, 300000, 250000] -- FeeTooSmallUTxO (Shelley tag is 4)
        Array shelleyUtxo = arr(uint(4), uint(300000), uint(250000));
        // Shelley UTXOW: [0, shelleyUtxo] -- UtxoFailure
        Array shelleyUtxow = arr(uint(0), shelleyUtxo);
        // Alonzo UTXOW: [0, shelleyUtxow] -- ShelleyInAlonzoUtxowPredFailure
        Array alonzoUtxow = arr(uint(0), shelleyUtxow);
        // Babbage UTXOW: [1, alonzoUtxow] -- AlonzoInBabbageUtxowPredFailure
        Array babbageUtxow = arr(uint(1), alonzoUtxow);
        // Babbage LEDGER: [0, babbageUtxow] -- UtxowFailure
        Array babbageLedger = arr(uint(0), babbageUtxow);
        // ApplyTxError
        Array applyTxError = arr(babbageLedger);
        // HFC: [[5, applyTxError]]
        Array eraAndError = arr(uint(5), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "eeff");

        assertThat(result.getErrors()).hasSize(1);
        List<TxSubmissionError> leaves = result.getErrors().get(0).getLeafErrors();
        assertThat(leaves).hasSize(1);
        TxSubmissionError leaf = leaves.get(0);
        assertThat(leaf.getErrorName()).isEqualTo("FeeTooSmallUTxO");
        assertThat(leaf.getDetail()).containsEntry("minimumFee", 300000L);
        assertThat(leaf.getDetail()).containsEntry("actualFee", 250000L);

        // Verify full message shows the chain
        String fullMsg = result.getErrors().get(0).toFullMessage();
        assertThat(fullMsg).contains("UtxowFailure");
        assertThat(fullMsg).contains("FeeTooSmallUTxO");
    }

    // ---- Era mismatch ----
    @Test
    void eraMismatch() {
        // [era1_info, era2_info] where each is [era_index]
        Array era1 = arr(uint(6)); // Conway
        Array era2 = arr(uint(5)); // Babbage
        Array outerArray = arr(era1, era2);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "1122");

        assertThat(result.getEraMismatch()).isNotNull();
        assertThat(result.getEraMismatch().getLedgerEraName()).isEqualTo("Conway");
        assertThat(result.getEraMismatch().getOtherEraName()).isEqualTo("Babbage");
        assertThat(result.getErrorMessage()).contains("Era mismatch");
    }

    // ---- Unknown era index ----
    @Test
    void unknownEraIndex() {
        Array innerError = arr(uint(0));
        Array applyTxError = arr(innerError);
        Array eraAndError = arr(uint(99), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "3344");

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getErrorName()).isEqualTo("UnknownEra");
        assertThat(result.getErrors().get(0).getTag()).isEqualTo(99);
    }

    // ---- Unknown tag ----
    @Test
    void unknownTag() {
        // Conway ledger with unknown tag 99
        Array unknownFailure = arr(uint(99));
        Array applyTxError = arr(unknownFailure);
        Array eraAndError = arr(uint(6), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "5566");

        assertThat(result.getErrors()).hasSize(1);
        TxSubmissionError error = result.getErrors().get(0);
        assertThat(error.getErrorName()).isEqualTo("UnknownError");
        assertThat(error.getRule()).isEqualTo("LEDGER");
        assertThat(error.getMessage()).contains("99");
    }

    // ---- Malformed CBOR (non-array) ----
    @Test
    void malformedCborNonArray() {
        DataItem nonArray = uint(42);

        ParsedRejection result = TxSubmissionErrorParser.parse(nonArray, "7788");

        assertThat(result).isNotNull();
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getEraMismatch()).isNull();
        assertThat(result.getRawCborHex()).isEqualTo("7788");
    }

    // ---- Null payload ----
    @Test
    void nullPayload() {
        ParsedRejection result = TxSubmissionErrorParser.parse(null, "9900");

        assertThat(result).isNotNull();
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getRawCborHex()).isEqualTo("9900");
    }

    // ---- Multiple errors in ApplyTxError ----
    @Test
    void multipleErrors() {
        // Two Conway ledger failures in the ApplyTxError array
        Array failure1 = arr(uint(8)); // ConwayWithdrawalsMissingAccounts
        Array failure2 = arr(uint(9)); // ConwayIncompleteWithdrawals
        Array applyTxError = arr(failure1, failure2);
        Array eraAndError = arr(uint(6), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "aabbcc");

        assertThat(result.getErrors()).hasSize(2);
        assertThat(result.getErrors().get(0).getErrorName()).isEqualTo("ConwayWithdrawalsMissingAccounts");
        assertThat(result.getErrors().get(1).getErrorName()).isEqualTo("ConwayIncompleteWithdrawals");
    }

    // ---- Conway GOV failure ----
    @Test
    void conwayGovFailure() {
        // LEDGER [3, GOV_failure] -> GOV [0, GovActionsDoNotExist]
        Array govFailure = arr(uint(0));
        Array ledgerFailure = arr(uint(3), govFailure);
        Array applyTxError = arr(ledgerFailure);
        Array eraAndError = arr(uint(6), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "ddee");

        assertThat(result.getErrors()).hasSize(1);
        List<TxSubmissionError> leaves = result.getErrors().get(0).getLeafErrors();
        assertThat(leaves).hasSize(1);
        assertThat(leaves.get(0).getErrorName()).isEqualTo("GovActionsDoNotExist");
        assertThat(leaves.get(0).getRule()).isEqualTo("GOV");
    }

    // ---- Conway CERTS -> DELEG failure ----
    @Test
    void conwayCertsDelegFailure() {
        // DELEG: [1, StakeKeyNotRegisteredDELEG]
        Array delegFailure = arr(uint(1));
        // CERT: [0, ConwayDelegFailure, delegFailure]
        Array certFailure = arr(uint(0), delegFailure);
        // CERTS: [1, CertFailure, certFailure]
        Array certsFailure = arr(uint(1), certFailure);
        // LEDGER: [2, ConwayCertsFailure, certsFailure]
        Array ledgerFailure = arr(uint(2), certsFailure);
        Array applyTxError = arr(ledgerFailure);
        Array eraAndError = arr(uint(6), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "ff00");

        assertThat(result.getErrors()).hasSize(1);
        List<TxSubmissionError> leaves = result.getErrors().get(0).getLeafErrors();
        assertThat(leaves).hasSize(1);
        assertThat(leaves.get(0).getErrorName()).isEqualTo("StakeKeyNotRegisteredDELEG");
        assertThat(leaves.get(0).getRule()).isEqualTo("DELEG");
    }

    // ---- Conway UTXOS CollectErrors ----
    @Test
    void conwayUtxosCollectErrors() {
        // UTXOS: [1, errors_payload]
        Array utxosFailure = arr(uint(1), arr());
        // UTXO: [0, utxosFailure]
        Array utxoFailure = arr(uint(0), utxosFailure);
        // UTXOW: [0, utxoFailure]
        Array utxowFailure = arr(uint(0), utxoFailure);
        // LEDGER: [1, utxowFailure]
        Array ledgerFailure = arr(uint(1), utxowFailure);
        Array applyTxError = arr(ledgerFailure);
        Array eraAndError = arr(uint(6), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "1234");

        assertThat(result.getErrors()).hasSize(1);
        List<TxSubmissionError> leaves = result.getErrors().get(0).getLeafErrors();
        assertThat(leaves).hasSize(1);
        assertThat(leaves.get(0).getErrorName()).isEqualTo("CollectErrors");
        assertThat(leaves.get(0).getRule()).isEqualTo("UTXOS");
    }

    // ---- Shelley LEDGER FeeTooSmall ----
    @Test
    void shelleyFeeTooSmall() {
        // Shelley UTXO: [4, 180000, 160000]
        Array utxoFailure = arr(uint(4), uint(180000), uint(160000));
        // Shelley UTXOW: [0, utxoFailure]
        Array utxowFailure = arr(uint(0), utxoFailure);
        // Shelley LEDGER: [0, utxowFailure]
        Array ledgerFailure = arr(uint(0), utxowFailure);
        Array applyTxError = arr(ledgerFailure);
        // HFC: [[1, applyTxError]] (era index 1 = Shelley)
        Array eraAndError = arr(uint(1), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "5678");

        assertThat(result.getErrors()).hasSize(1);
        List<TxSubmissionError> leaves = result.getErrors().get(0).getLeafErrors();
        assertThat(leaves).hasSize(1);
        TxSubmissionError leaf = leaves.get(0);
        assertThat(leaf.getEra()).isEqualTo(Era.Shelley);
        assertThat(leaf.getErrorName()).isEqualTo("FeeTooSmallUTxO");
        assertThat(leaf.getDetail()).containsEntry("minimumFee", 180000L);
        assertThat(leaf.getDetail()).containsEntry("actualFee", 160000L);
    }

    // ---- Alonzo LEDGER with script error ----
    @Test
    void alonzoMissingRedeemers() {
        // Alonzo UTXOW: [1, redeemers_data] -- MissingRedeemers
        Array utxowFailure = arr(uint(1), arr());
        // Alonzo LEDGER: [0, utxowFailure]
        Array ledgerFailure = arr(uint(0), utxowFailure);
        Array applyTxError = arr(ledgerFailure);
        // HFC: [[4, applyTxError]] (era index 4 = Alonzo)
        Array eraAndError = arr(uint(4), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "9abc");

        assertThat(result.getErrors()).hasSize(1);
        List<TxSubmissionError> leaves = result.getErrors().get(0).getLeafErrors();
        assertThat(leaves).hasSize(1);
        assertThat(leaves.get(0).getErrorName()).isEqualTo("MissingRedeemers");
    }

    // ---- Conway InputSetEmpty ----
    @Test
    void conwayInputSetEmpty() {
        // UTXO: [4] -- InputSetEmptyUTxO
        Array utxoFailure = arr(uint(4));
        // UTXOW: [0, utxoFailure]
        Array utxowFailure = arr(uint(0), utxoFailure);
        // LEDGER: [1, utxowFailure]
        Array ledgerFailure = arr(uint(1), utxowFailure);
        Array applyTxError = arr(ledgerFailure);
        Array eraAndError = arr(uint(6), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "def0");

        List<TxSubmissionError> leaves = result.getErrors().get(0).getLeafErrors();
        assertThat(leaves.get(0).getErrorName()).isEqualTo("InputSetEmptyUTxO");
        assertThat(leaves.get(0).getMessage()).isEqualTo("Transaction has no inputs");
    }

    // ---- toFullMessage ----
    @Test
    void toFullMessageShowsChain() {
        Array feeTooSmall = arr(uint(5), uint(200000), uint(170000));
        Array utxoFailure = arr(uint(0), feeTooSmall);
        Array utxowWrapper = arr(uint(1), utxoFailure);
        Array applyTxError = arr(utxowWrapper);
        Array eraAndError = arr(uint(6), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "abcd");

        String fullMsg = result.getErrors().get(0).toFullMessage();
        assertThat(fullMsg).contains("[LEDGER] ConwayUtxowFailure");
        assertThat(fullMsg).contains("[UTXOW] UtxoFailure");
        assertThat(fullMsg).contains("[UTXO] FeeTooSmallUTxO");
    }

    // ---- Conway MaxTxSizeUTxO ----
    @Test
    void conwayMaxTxSize() {
        // UTXO: [3, 20000, 16384]
        Array utxoFailure = arr(uint(3), uint(20000), uint(16384));
        Array utxowFailure = arr(uint(0), utxoFailure);
        Array ledgerFailure = arr(uint(1), utxowFailure);
        Array applyTxError = arr(ledgerFailure);
        Array eraAndError = arr(uint(6), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "aabb");

        List<TxSubmissionError> leaves = result.getErrors().get(0).getLeafErrors();
        assertThat(leaves.get(0).getErrorName()).isEqualTo("MaxTxSizeUTxO");
        assertThat(leaves.get(0).getDetail()).containsEntry("actualSize", 20000L);
        assertThat(leaves.get(0).getDetail()).containsEntry("maxSize", 16384L);
    }

    // ---- Conway TriesToForgeADA ----
    @Test
    void conwayTriesToForgeADA() {
        Array utxoFailure = arr(uint(11));
        Array utxowFailure = arr(uint(0), utxoFailure);
        Array ledgerFailure = arr(uint(1), utxowFailure);
        Array applyTxError = arr(ledgerFailure);
        Array eraAndError = arr(uint(6), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "ccdd");

        List<TxSubmissionError> leaves = result.getErrors().get(0).getLeafErrors();
        assertThat(leaves.get(0).getErrorName()).isEqualTo("TriesToForgeADA");
        assertThat(leaves.get(0).getMessage()).isEqualTo("Transaction tries to forge ADA");
    }

    // ---- Shelley Delegation failure chain ----
    @Test
    void shelleyDelegationChain() {
        // POOL: [0] -- StakePoolNotRegisteredOnKeyPOOL
        Array poolFailure = arr(uint(0));
        // DELPL: [0, poolFailure]
        Array delplFailure = arr(uint(0), poolFailure);
        // DELEGS: [2, delplFailure]
        Array delegsFailure = arr(uint(2), delplFailure);
        // LEDGER: [1, delegsFailure]
        Array ledgerFailure = arr(uint(1), delegsFailure);
        Array applyTxError = arr(ledgerFailure);
        Array eraAndError = arr(uint(1), applyTxError);
        Array outerArray = arr(eraAndError);

        ParsedRejection result = TxSubmissionErrorParser.parse(outerArray, "eeff");

        List<TxSubmissionError> leaves = result.getErrors().get(0).getLeafErrors();
        assertThat(leaves).hasSize(1);
        assertThat(leaves.get(0).getErrorName()).isEqualTo("StakePoolNotRegisteredOnKeyPOOL");
        assertThat(leaves.get(0).getRule()).isEqualTo("POOL");
    }
}
