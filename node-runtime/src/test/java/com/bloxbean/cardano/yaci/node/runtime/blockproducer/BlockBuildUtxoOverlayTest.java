package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class BlockBuildUtxoOverlayTest {

    private SimpleUtxoState utxoState;
    private BlockBuildUtxoOverlay overlay;

    @BeforeEach
    void setUp() {
        utxoState = new SimpleUtxoState();
        overlay = new BlockBuildUtxoOverlay(utxoState);
    }

    @Test
    void resolver_returnsUtxo_whenNotSpent() {
        Outpoint op = new Outpoint("abc123", 0);
        utxoState.put(op, new com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo(
                op, "addr_test1...", BigInteger.valueOf(5_000_000),
                List.of(), null, null, null, false, 10, 1, "bh"
        ));

        Function<Outpoint, Utxo> resolver = overlay.resolver();
        Utxo result = resolver.apply(op);

        assertThat(result).isNotNull();
        assertThat(result.getTxHash()).isEqualTo("abc123");
    }

    @Test
    void resolver_returnsNull_whenSpent() {
        Outpoint op = new Outpoint("abc123", 0);
        utxoState.put(op, new com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo(
                op, "addr_test1...", BigInteger.valueOf(5_000_000),
                List.of(), null, null, null, false, 10, 1, "bh"
        ));

        // First resolve succeeds
        Function<Outpoint, Utxo> resolver = overlay.resolver();
        assertThat(resolver.apply(op)).isNotNull();

        // Build a minimal tx CBOR that spends this outpoint
        byte[] txCbor = buildTxSpending("abc123", 0);
        overlay.markSpent(txCbor);

        // After marking spent, resolver should return null
        assertThat(resolver.apply(op)).isNull();
    }

    @Test
    void resolver_returnsNull_whenUtxoNotInState() {
        Outpoint op = new Outpoint("notfound", 0);

        Function<Outpoint, Utxo> resolver = overlay.resolver();
        assertThat(resolver.apply(op)).isNull();
    }

    @Test
    void reset_clearsSpentTracking() {
        Outpoint op = new Outpoint("abc123", 0);
        utxoState.put(op, new com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo(
                op, "addr_test1...", BigInteger.valueOf(5_000_000),
                List.of(), null, null, null, false, 10, 1, "bh"
        ));

        byte[] txCbor = buildTxSpending("abc123", 0);
        overlay.markSpent(txCbor);

        Function<Outpoint, Utxo> resolver = overlay.resolver();
        assertThat(resolver.apply(op)).isNull();

        overlay.reset();
        assertThat(resolver.apply(op)).isNotNull();
    }

    /**
     * Build a minimal valid CCL Transaction CBOR that has one input spending (txHash, index).
     */
    private byte[] buildTxSpending(String txHash, int index) {
        try {
            var input = com.bloxbean.cardano.client.transaction.spec.TransactionInput.builder()
                    .transactionId(txHash)
                    .index(index)
                    .build();
            var output = com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                    .address("addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp")
                    .value(new com.bloxbean.cardano.client.transaction.spec.Value(BigInteger.valueOf(1_000_000), null))
                    .build();
            var body = com.bloxbean.cardano.client.transaction.spec.TransactionBody.builder()
                    .inputs(List.of(input))
                    .outputs(List.of(output))
                    .fee(BigInteger.valueOf(200_000))
                    .build();
            var tx = com.bloxbean.cardano.client.transaction.spec.Transaction.builder()
                    .body(body)
                    .build();
            return tx.serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Simple in-memory UtxoState for testing (avoids Mockito issues with Java 21).
     */
    private static class SimpleUtxoState implements UtxoState {
        private final Map<Outpoint, com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo> store = new HashMap<>();

        void put(Outpoint op, com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo utxo) {
            store.put(op, utxo);
        }

        @Override
        public List<com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo> getUtxosByAddress(String addr, int page, int pageSize) {
            return List.of();
        }

        @Override
        public List<com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo> getUtxosByPaymentCredential(String cred, int page, int pageSize) {
            return List.of();
        }

        @Override
        public Optional<com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo> getUtxo(Outpoint outpoint) {
            return Optional.ofNullable(store.get(outpoint));
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}
