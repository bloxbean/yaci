package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.node.api.plugin.UtxoFilterContext;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AddressUtxoFilterTest {

    private UtxoFilterContext ctx(String address, String paymentCred) {
        return new UtxoFilterContext(address, paymentCred, 1_000_000L, List.of(), 100L, 1L, "aa".repeat(32), 0);
    }

    @Test
    void emptyConfig_acceptsAll() {
        var filter = new AddressUtxoFilter(Set.of(), Set.of());
        assertTrue(filter.acceptUtxoOutput(ctx("addr1_any", null), null, null));
    }

    @Test
    void nullConfig_acceptsAll() {
        var filter = new AddressUtxoFilter(null, null);
        assertTrue(filter.acceptUtxoOutput(ctx("addr1_any", null), null, null));
    }

    @Test
    void matchingAddress_accepted() {
        var filter = new AddressUtxoFilter(Set.of("addr1_match"), Set.of());
        assertTrue(filter.acceptUtxoOutput(ctx("addr1_match", null), null, null));
    }

    @Test
    void nonMatchingAddress_rejected() {
        var filter = new AddressUtxoFilter(Set.of("addr1_match"), Set.of());
        assertFalse(filter.acceptUtxoOutput(ctx("addr1_other", null), null, null));
    }

    @Test
    void matchingPaymentCred_accepted() {
        var filter = new AddressUtxoFilter(Set.of(), Set.of("abcd1234"));
        assertTrue(filter.acceptUtxoOutput(ctx("addr1_any", "abcd1234"), null, null));
    }

    @Test
    void nonMatchingPaymentCred_rejected() {
        var filter = new AddressUtxoFilter(Set.of(), Set.of("abcd1234"));
        assertFalse(filter.acceptUtxoOutput(ctx("addr1_any", "eeee5678"), null, null));
    }

    @Test
    void addressOrPaymentCred_eitherMatchAccepts() {
        var filter = new AddressUtxoFilter(Set.of("addr1_match"), Set.of("cred1234"));
        // Match by address
        assertTrue(filter.acceptUtxoOutput(ctx("addr1_match", "other_cred"), null, null));
        // Match by cred
        assertTrue(filter.acceptUtxoOutput(ctx("addr1_other", "cred1234"), null, null));
        // Match neither
        assertFalse(filter.acceptUtxoOutput(ctx("addr1_other", "other_cred"), null, null));
    }

    @Test
    void nullAddress_inContext_handledGracefully() {
        var filter = new AddressUtxoFilter(Set.of("addr1"), Set.of());
        assertFalse(filter.acceptUtxoOutput(ctx(null, null), null, null));
    }

    @Test
    void priority_isLowerThanDefault() {
        var filter = new AddressUtxoFilter(Set.of(), Set.of());
        assertTrue(filter.priority() < 100);
    }
}
