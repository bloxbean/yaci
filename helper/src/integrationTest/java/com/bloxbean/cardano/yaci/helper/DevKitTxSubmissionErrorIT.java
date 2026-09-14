package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.UtxoByAddressQuery;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.UtxoByAddressQueryResult;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionError;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionRequest;
import com.bloxbean.cardano.yaci.helper.model.TxResult;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static com.bloxbean.cardano.client.common.ADAConversionUtil.adaToLovelace;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for tx submission error parsing against a Yaci DevKit devnet.
 * <p>
 * Requires a running Yaci DevKit instance with:
 * <ul>
 *   <li>N2C socat relay at localhost:3333</li>
 *   <li>Cluster API at localhost:10000 (for topup)</li>
 * </ul>
 */
@Slf4j
class DevKitTxSubmissionErrorIT {

    private static final String DEVKIT_HOST = "localhost";
    private static final int DEVKIT_PORT = 3333;
    private static final long DEVKIT_PROTOCOL_MAGIC = 42;
    private static final String DEVKIT_TOPUP_URL = "http://localhost:10000/local-cluster/api/addresses/topup";
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    /**
     * Submit a transaction with fee=1 lovelace.
     * Should be rejected with FeeTooSmallUTxO containing minimumFee and actualFee detail.
     */
    @SneakyThrows
    @Test
    void submitTx_feeTooSmall() {
        Account account = new Account(Networks.testnet());
        String senderAddr = account.baseAddress();
        topup(senderAddr, 100);

        LocalClientProvider localClientProvider = new LocalClientProvider(DEVKIT_HOST, DEVKIT_PORT, DEVKIT_PROTOCOL_MAGIC);
        LocalTxSubmissionClient localTxSubmissionClient = localClientProvider.getTxSubmissionClient();
        localClientProvider.start();

        Mono<UtxoByAddressQueryResult> utxoMono = localClientProvider.getLocalStateQueryClient()
                .executeQuery(new UtxoByAddressQuery(Era.Conway, new Address(senderAddr)));
        UtxoByAddressQueryResult utxoQueryResult = utxoMono.block(Duration.ofSeconds(10));

        var utxo = findAdaOnlyUtxo(utxoQueryResult);

        BigInteger fee = BigInteger.ONE; // 1 lovelace - way too small
        var outputAmount = utxo.getAmount().get(0).getQuantity().subtract(fee);

        TransactionBody transactionBody = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex())))
                .outputs(List.of(
                        TransactionOutput.builder()
                                .address(senderAddr)
                                .value(Value.builder().coin(outputAmount).build())
                                .build()
                ))
                .fee(fee)
                .build();

        Transaction transaction = Transaction.builder().body(transactionBody).build();
        var signedTx = account.sign(transaction);

        var txSubmissionRequest = new TxSubmissionRequest(TxBodyType.CONWAY, signedTx.serialize());
        var txResult = localTxSubmissionClient.submitTx(txSubmissionRequest).block(Duration.ofSeconds(20));

        System.out.println("Error Message: " + txResult.getErrorMessage());
        for (TxSubmissionError error : txResult.getErrors()) {
            System.out.println("Full error chain:\n" + error.toFullMessage());
        }

        assertThat(txResult.isAccepted()).isFalse();
        assertThat(txResult.getErrors()).isNotEmpty();

        var leafErrors = txResult.getErrors().get(0).getLeafErrors();
        assertThat(leafErrors).anyMatch(e -> "FeeTooSmallUTxO".equals(e.getErrorName()));

        var feeTooSmall = leafErrors.stream()
                .filter(e -> "FeeTooSmallUTxO".equals(e.getErrorName()))
                .findFirst().get();
        assertThat(feeTooSmall.getDetail()).containsKey("minimumFee");
        assertThat(feeTooSmall.getDetail()).containsKey("actualFee");
        assertThat(feeTooSmall.getMessage()).contains("Fee too small");

        localClientProvider.shutdown();
    }

    /**
     * Submit a transaction signed with the wrong key.
     * Should be rejected with MissingVKeyWitnessesUTXOW.
     */
    @SneakyThrows
    @Test
    void submitTx_missingWitnesses() {
        Account fundedAccount = new Account(Networks.testnet());
        Account wrongAccount = new Account(Networks.testnet()); // different key
        String senderAddr = fundedAccount.baseAddress();
        topup(senderAddr, 100);

        LocalClientProvider localClientProvider = new LocalClientProvider(DEVKIT_HOST, DEVKIT_PORT, DEVKIT_PROTOCOL_MAGIC);
        LocalTxSubmissionClient localTxSubmissionClient = localClientProvider.getTxSubmissionClient();
        localClientProvider.start();

        Mono<UtxoByAddressQueryResult> utxoMono = localClientProvider.getLocalStateQueryClient()
                .executeQuery(new UtxoByAddressQuery(Era.Conway, new Address(senderAddr)));
        UtxoByAddressQueryResult utxoQueryResult = utxoMono.block(Duration.ofSeconds(10));

        var utxo = findAdaOnlyUtxo(utxoQueryResult);

        BigInteger fee = adaToLovelace(0.5);
        var outputAmount = utxo.getAmount().get(0).getQuantity().subtract(fee);

        TransactionBody transactionBody = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex())))
                .outputs(List.of(
                        TransactionOutput.builder()
                                .address(senderAddr)
                                .value(Value.builder().coin(outputAmount).build())
                                .build()
                ))
                .fee(fee)
                .build();

        // Sign with the WRONG key - the witness won't match the input's payment credential
        Transaction transaction = Transaction.builder().body(transactionBody).build();
        var wrongSignedTx = wrongAccount.sign(transaction);

        var txSubmissionRequest = new TxSubmissionRequest(TxBodyType.CONWAY, wrongSignedTx.serialize());
        var txResult = localTxSubmissionClient.submitTx(txSubmissionRequest).block(Duration.ofSeconds(20));

        System.out.println("Error Message: " + txResult.getErrorMessage());
        for (TxSubmissionError error : txResult.getErrors()) {
            System.out.println("Full error chain:\n" + error.toFullMessage());
        }

        assertThat(txResult.isAccepted()).isFalse();
        assertThat(txResult.getErrors()).isNotEmpty();

        var leafErrors = txResult.getErrors().get(0).getLeafErrors();
        assertThat(leafErrors).anyMatch(e ->
                "MissingVKeyWitnessesUTXOW".equals(e.getErrorName())
                        || "InvalidWitnessesUTXOW".equals(e.getErrorName()));

        localClientProvider.shutdown();
    }

    /**
     * Submit a transaction with an expired TTL (slot 1, way in the past).
     * Should be rejected with OutsideValidityIntervalUTxO.
     */
    @SneakyThrows
    @Test
    void submitTx_outsideValidityInterval() {
        Account account = new Account(Networks.testnet());
        String senderAddr = account.baseAddress();
        topup(senderAddr, 100);

        LocalClientProvider localClientProvider = new LocalClientProvider(DEVKIT_HOST, DEVKIT_PORT, DEVKIT_PROTOCOL_MAGIC);
        LocalTxSubmissionClient localTxSubmissionClient = localClientProvider.getTxSubmissionClient();
        localClientProvider.start();

        Mono<UtxoByAddressQueryResult> utxoMono = localClientProvider.getLocalStateQueryClient()
                .executeQuery(new UtxoByAddressQuery(Era.Conway, new Address(senderAddr)));
        UtxoByAddressQueryResult utxoQueryResult = utxoMono.block(Duration.ofSeconds(10));

        var utxo = findAdaOnlyUtxo(utxoQueryResult);

        BigInteger fee = adaToLovelace(0.5);
        var outputAmount = utxo.getAmount().get(0).getQuantity().subtract(fee);

        TransactionBody transactionBody = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex())))
                .outputs(List.of(
                        TransactionOutput.builder()
                                .address(senderAddr)
                                .value(Value.builder().coin(outputAmount).build())
                                .build()
                ))
                .fee(fee)
                .ttl(1L) // Slot 1 - expired long ago
                .build();

        Transaction transaction = Transaction.builder().body(transactionBody).build();
        var signedTx = account.sign(transaction);

        var txSubmissionRequest = new TxSubmissionRequest(TxBodyType.CONWAY, signedTx.serialize());
        var txResult = localTxSubmissionClient.submitTx(txSubmissionRequest).block(Duration.ofSeconds(20));

        System.out.println("Error Message: " + txResult.getErrorMessage());
        for (TxSubmissionError error : txResult.getErrors()) {
            System.out.println("Full error chain:\n" + error.toFullMessage());
        }

        assertThat(txResult.isAccepted()).isFalse();
        assertThat(txResult.getErrors()).isNotEmpty();

        var leafErrors = txResult.getErrors().get(0).getLeafErrors();
        assertThat(leafErrors).anyMatch(e -> "OutsideValidityIntervalUTxO".equals(e.getErrorName()));

        localClientProvider.shutdown();
    }

    /**
     * Submit a transaction where output value exceeds input value.
     * Should be rejected with ValueNotConservedUTxO.
     */
    @SneakyThrows
    @Test
    void submitTx_valueNotConserved() {
        Account account = new Account(Networks.testnet());
        String senderAddr = account.baseAddress();
        topup(senderAddr, 100);

        LocalClientProvider localClientProvider = new LocalClientProvider(DEVKIT_HOST, DEVKIT_PORT, DEVKIT_PROTOCOL_MAGIC);
        LocalTxSubmissionClient localTxSubmissionClient = localClientProvider.getTxSubmissionClient();
        localClientProvider.start();

        Mono<UtxoByAddressQueryResult> utxoMono = localClientProvider.getLocalStateQueryClient()
                .executeQuery(new UtxoByAddressQuery(Era.Conway, new Address(senderAddr)));
        UtxoByAddressQueryResult utxoQueryResult = utxoMono.block(Duration.ofSeconds(10));

        var utxo = findAdaOnlyUtxo(utxoQueryResult);

        BigInteger fee = adaToLovelace(0.5);
        // Output exceeds input by 500 ADA
        var outputAmount = utxo.getAmount().get(0).getQuantity().add(adaToLovelace(500));

        TransactionBody transactionBody = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex())))
                .outputs(List.of(
                        TransactionOutput.builder()
                                .address(senderAddr)
                                .value(Value.builder().coin(outputAmount).build())
                                .build()
                ))
                .fee(fee)
                .build();

        Transaction transaction = Transaction.builder().body(transactionBody).build();
        var signedTx = account.sign(transaction);

        var txSubmissionRequest = new TxSubmissionRequest(TxBodyType.CONWAY, signedTx.serialize());
        var txResult = localTxSubmissionClient.submitTx(txSubmissionRequest).block(Duration.ofSeconds(20));

        System.out.println("Error Message: " + txResult.getErrorMessage());
        for (TxSubmissionError error : txResult.getErrors()) {
            System.out.println("Full error chain:\n" + error.toFullMessage());
        }

        assertThat(txResult.isAccepted()).isFalse();
        assertThat(txResult.getErrors()).isNotEmpty();

        var leafErrors = txResult.getErrors().get(0).getLeafErrors();
        assertThat(leafErrors).anyMatch(e -> "ValueNotConservedUTxO".equals(e.getErrorName()));

        var valueError = leafErrors.stream()
                .filter(e -> "ValueNotConservedUTxO".equals(e.getErrorName()))
                .findFirst().get();
        assertThat(valueError.getDetail()).containsKey("consumed");
        assertThat(valueError.getDetail()).containsKey("produced");
        assertThat(valueError.getMessage()).contains("Value not conserved");

        localClientProvider.shutdown();
    }

    // ========== Script error tests ==========

    /**
     * Lock ADA at a PlutusV3 script address, then attempt to spend it without collateral
     * and with a placeholder scriptDataHash. The node validates multiple rules simultaneously,
     * so we verify that script-related UTXOW/UTXO errors are properly parsed.
     */
    @SneakyThrows
    @Test
    void submitTx_scriptSpend_noCollateral() {
        // 1. Setup: always-succeeds PlutusV3 script
        PlutusV3Script alwaysSucceeds = PlutusV3Script.builder()
                .type("PlutusScriptV3")
                .cborHex("46450101002499")
                .build();
        String scriptAddr = AddressProvider.getEntAddress(alwaysSucceeds, Networks.testnet()).toBech32();
        PlutusData datum = BigIntPlutusData.of(42);

        Account account = new Account(Networks.testnet());
        String senderAddr = account.baseAddress();
        topup(senderAddr, 200);

        LocalClientProvider provider = new LocalClientProvider(DEVKIT_HOST, DEVKIT_PORT, DEVKIT_PROTOCOL_MAGIC);
        LocalTxSubmissionClient txClient = provider.getTxSubmissionClient();
        provider.start();

        // 2. Lock ADA at script address with inline datum
        TxResult lockResult = lockAdaAtScript(provider, txClient, account, senderAddr, scriptAddr, datum, adaToLovelace(50));
        System.out.println("Lock tx: " + lockResult.getTxHash() + ", accepted: " + lockResult.isAccepted());
        assertThat(lockResult.isAccepted()).isTrue();

        // Shut down and create a new provider to get fresh state after the lock tx block
        provider.shutdown();
        Thread.sleep(3000); // wait for block

        LocalClientProvider provider2 = new LocalClientProvider(DEVKIT_HOST, DEVKIT_PORT, DEVKIT_PROTOCOL_MAGIC);
        LocalTxSubmissionClient txClient2 = provider2.getTxSubmissionClient();
        provider2.start();

        // 3. Query script UTxO and sender UTxO for fee
        var scriptUtxoResult = queryUtxos(provider2, scriptAddr);
        var scriptUtxo = findAdaOnlyUtxo(scriptUtxoResult);

        var senderUtxoResult = queryUtxos(provider2, senderAddr);
        var feeUtxo = findAdaOnlyUtxo(senderUtxoResult);

        // 4. Build script spend tx WITHOUT collateral and placeholder scriptDataHash
        BigInteger fee = adaToLovelace(2);
        BigInteger totalInput = scriptUtxo.getAmount().get(0).getQuantity()
                .add(feeUtxo.getAmount().get(0).getQuantity());
        BigInteger outputAmount = totalInput.subtract(fee);

        Redeemer redeemer = Redeemer.builder()
                .tag(RedeemerTag.Spend)
                .index(BigInteger.ZERO)
                .data(BigIntPlutusData.of(42))
                .exUnits(ExUnits.builder()
                        .mem(BigInteger.valueOf(500000))
                        .steps(BigInteger.valueOf(200000000))
                        .build())
                .build();

        TransactionBody spendBody = TransactionBody.builder()
                .inputs(List.of(
                        new TransactionInput(scriptUtxo.getTxHash(), scriptUtxo.getOutputIndex()),
                        new TransactionInput(feeUtxo.getTxHash(), feeUtxo.getOutputIndex())
                ))
                .outputs(List.of(
                        TransactionOutput.builder()
                                .address(senderAddr)
                                .value(Value.builder().coin(outputAmount).build())
                                .build()
                ))
                .fee(fee)
                // No collateral + placeholder scriptDataHash -> multiple script-related errors
                .scriptDataHash(new byte[32])
                .build();

        TransactionWitnessSet witnessSet = new TransactionWitnessSet();
        witnessSet.setPlutusV3Scripts(List.of(alwaysSucceeds));
        witnessSet.setRedeemers(List.of(redeemer));
        witnessSet.setPlutusDataList(List.of(datum));

        Transaction spendTx = Transaction.builder()
                .body(spendBody)
                .witnessSet(witnessSet)
                .isValid(true)
                .build();
        var signedSpendTx = account.sign(spendTx);

        var txSubmissionRequest = new TxSubmissionRequest(TxBodyType.CONWAY, signedSpendTx.serialize());
        var txResult = txClient2.submitTx(txSubmissionRequest).block(Duration.ofSeconds(20));

        System.out.println("Error Message: " + txResult.getErrorMessage());
        for (TxSubmissionError error : txResult.getErrors()) {
            System.out.println("Full error chain:\n" + error.toFullMessage());
        }

        assertThat(txResult.isAccepted()).isFalse();
        assertThat(txResult.getErrors()).isNotEmpty();

        // The node returns multiple UTXOW/UTXO errors for malformed script transactions.
        // Verify errors are properly parsed with era, rule, error name, and message.
        var allLeafErrors = txResult.getErrors().stream()
                .flatMap(e -> e.getLeafErrors().stream())
                .collect(Collectors.toList());

        System.out.println("Leaf errors:");
        for (TxSubmissionError leaf : allLeafErrors) {
            System.out.println("  - " + leaf.getErrorName() + " (" + leaf.getRule() + "): " + leaf.getMessage());
        }

        // Expect script-related errors (PPViewHashesDontMatch, NotAllowedSupplementalDatums, etc.)
        assertThat(allLeafErrors).allSatisfy(e -> {
            assertThat(e.getErrorName()).isNotNull();
            assertThat(e.getMessage()).isNotNull();
            assertThat(e.getEra()).isNotNull();
        });
        // At least one UTXOW-level error (script integrity hash, datums, etc.)
        assertThat(allLeafErrors).anyMatch(e ->
                "UTXOW".equals(e.getRule()) || "UTXO".equals(e.getRule()));

        provider2.shutdown();
    }

    /**
     * Lock ADA at a PlutusV3 script address, then attempt to spend with absurdly high ExUnits,
     * collateral, and placeholder scriptDataHash. The node rejects with script-related errors
     * (ScriptsNotPaidUTxO, PPViewHashesDontMatch, etc.) which we verify are properly parsed.
     */
    @SneakyThrows
    @Test
    void submitTx_scriptSpend_exUnitsTooBig() {
        // 1. Setup: always-succeeds PlutusV3 script
        PlutusV3Script alwaysSucceeds = PlutusV3Script.builder()
                .type("PlutusScriptV3")
                .cborHex("46450101002499")
                .build();
        String scriptAddr = AddressProvider.getEntAddress(alwaysSucceeds, Networks.testnet()).toBech32();
        PlutusData datum = BigIntPlutusData.of(42);

        Account account = new Account(Networks.testnet());
        String senderAddr = account.baseAddress();
        topup(senderAddr, 200);

        LocalClientProvider provider = new LocalClientProvider(DEVKIT_HOST, DEVKIT_PORT, DEVKIT_PROTOCOL_MAGIC);
        LocalTxSubmissionClient txClient = provider.getTxSubmissionClient();
        provider.start();

        // 2. Lock ADA at script address
        TxResult lockResult = lockAdaAtScript(provider, txClient, account, senderAddr, scriptAddr, datum, adaToLovelace(50));
        System.out.println("Lock tx: " + lockResult.getTxHash() + ", accepted: " + lockResult.isAccepted());
        assertThat(lockResult.isAccepted()).isTrue();

        // Shut down and create a new provider to get fresh state after the lock tx block
        provider.shutdown();
        Thread.sleep(3000);

        LocalClientProvider provider2 = new LocalClientProvider(DEVKIT_HOST, DEVKIT_PORT, DEVKIT_PROTOCOL_MAGIC);
        LocalTxSubmissionClient txClient2 = provider2.getTxSubmissionClient();
        provider2.start();

        // 3. Query UTxOs
        var scriptUtxoResult = queryUtxos(provider2, scriptAddr);
        var scriptUtxo = findAdaOnlyUtxo(scriptUtxoResult);

        var senderUtxoResult = queryUtxos(provider2, senderAddr);
        var feeUtxo = findAdaOnlyUtxo(senderUtxoResult);

        // 4. Build script spend tx with absurdly high ExUnits
        BigInteger fee = adaToLovelace(2);
        BigInteger totalInput = scriptUtxo.getAmount().get(0).getQuantity()
                .add(feeUtxo.getAmount().get(0).getQuantity());
        BigInteger outputAmount = totalInput.subtract(fee);

        // ExUnits way above protocol limits (max is typically ~10B steps, ~14M mem)
        Redeemer redeemer = Redeemer.builder()
                .tag(RedeemerTag.Spend)
                .index(BigInteger.ZERO)
                .data(BigIntPlutusData.of(42))
                .exUnits(ExUnits.builder()
                        .mem(BigInteger.valueOf(Long.MAX_VALUE))
                        .steps(BigInteger.valueOf(Long.MAX_VALUE))
                        .build())
                .build();

        TransactionBody spendBody = TransactionBody.builder()
                .inputs(List.of(
                        new TransactionInput(scriptUtxo.getTxHash(), scriptUtxo.getOutputIndex()),
                        new TransactionInput(feeUtxo.getTxHash(), feeUtxo.getOutputIndex())
                ))
                .outputs(List.of(
                        TransactionOutput.builder()
                                .address(senderAddr)
                                .value(Value.builder().coin(outputAmount).build())
                                .build()
                ))
                .fee(fee)
                .collateral(List.of(new TransactionInput(feeUtxo.getTxHash(), feeUtxo.getOutputIndex())))
                .scriptDataHash(new byte[32]) // placeholder
                .build();

        TransactionWitnessSet witnessSet = new TransactionWitnessSet();
        witnessSet.setPlutusV3Scripts(List.of(alwaysSucceeds));
        witnessSet.setRedeemers(List.of(redeemer));
        witnessSet.setPlutusDataList(List.of(datum));

        Transaction spendTx = Transaction.builder()
                .body(spendBody)
                .witnessSet(witnessSet)
                .isValid(true)
                .build();
        var signedSpendTx = account.sign(spendTx);

        var txSubmissionRequest = new TxSubmissionRequest(TxBodyType.CONWAY, signedSpendTx.serialize());
        var txResult = txClient2.submitTx(txSubmissionRequest).block(Duration.ofSeconds(20));

        System.out.println("Error Message: " + txResult.getErrorMessage());
        for (TxSubmissionError error : txResult.getErrors()) {
            System.out.println("Full error chain:\n" + error.toFullMessage());
        }

        assertThat(txResult.isAccepted()).isFalse();
        assertThat(txResult.getErrors()).isNotEmpty();

        // The node returns script-related errors (ScriptsNotPaidUTxO, PPViewHashesDontMatch, etc.)
        var allLeafErrors = txResult.getErrors().stream()
                .flatMap(e -> e.getLeafErrors().stream())
                .collect(Collectors.toList());

        System.out.println("Leaf errors:");
        for (TxSubmissionError leaf : allLeafErrors) {
            System.out.println("  - " + leaf.getErrorName() + " (" + leaf.getRule() + "): " + leaf.getMessage());
        }

        // Verify errors are properly parsed with meaningful content
        assertThat(allLeafErrors).allSatisfy(e -> {
            assertThat(e.getErrorName()).isNotNull();
            assertThat(e.getMessage()).isNotNull();
            assertThat(e.getEra()).isNotNull();
        });
        // At least one UTXO-level script-related error
        assertThat(allLeafErrors).anyMatch(e ->
                "UTXOW".equals(e.getRule()) || "UTXO".equals(e.getRule()));

        provider2.shutdown();
    }

    // ========== Helpers ==========

    /**
     * Lock ADA at a script address with an inline datum. Returns the TxResult.
     */
    private TxResult lockAdaAtScript(LocalClientProvider provider, LocalTxSubmissionClient txClient,
                                     Account account, String senderAddr, String scriptAddr,
                                     PlutusData datum, BigInteger lockAmount) throws Exception {
        var utxoResult = queryUtxos(provider, senderAddr);
        var fundUtxo = findAdaOnlyUtxo(utxoResult);

        BigInteger fee = adaToLovelace(0.5);
        BigInteger changeAmount = fundUtxo.getAmount().get(0).getQuantity().subtract(lockAmount).subtract(fee);

        TransactionBody lockBody = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(fundUtxo.getTxHash(), fundUtxo.getOutputIndex())))
                .outputs(List.of(
                        TransactionOutput.builder()
                                .address(scriptAddr)
                                .value(Value.builder().coin(lockAmount).build())
                                .inlineDatum(datum)
                                .build(),
                        TransactionOutput.builder()
                                .address(senderAddr)
                                .value(Value.builder().coin(changeAmount).build())
                                .build()
                ))
                .fee(fee)
                .build();

        Transaction lockTx = Transaction.builder().body(lockBody).build();
        var signedLockTx = account.sign(lockTx);

        return txClient.submitTx(new TxSubmissionRequest(TxBodyType.CONWAY, signedLockTx.serialize()))
                .block(Duration.ofSeconds(20));
    }

    private UtxoByAddressQueryResult queryUtxos(LocalClientProvider provider, String addr) {
        Mono<UtxoByAddressQueryResult> mono = provider.getLocalStateQueryClient()
                .executeQuery(new UtxoByAddressQuery(Era.Conway, new Address(addr)));
        return mono.block(Duration.ofSeconds(10));
    }

    private com.bloxbean.cardano.client.api.model.Utxo findAdaOnlyUtxo(UtxoByAddressQueryResult result) {
        return result.getUtxoList().stream()
                .filter(u -> u.getAmount().size() == 1)
                .filter(u -> u.getAmount().get(0).getQuantity().longValue() > 2000000L)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No suitable ADA-only UTxO found"));
    }

    private void topup(String address, double adaAmount) throws Exception {
        String body = String.format("{\"address\":\"%s\",\"adaAmount\":%s}", address, adaAmount);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DEVKIT_TOPUP_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("Topup response: " + response.body());
        // Wait for DevKit to produce a block containing the topup tx
        Thread.sleep(3000);
    }
}
