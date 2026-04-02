package com.bloxbean.cardano.client.ledger.slice;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;

import java.util.Optional;

/**
 * Provides read/write access to the UTxO set during validation.
 * <p>
 * CCL provides a simple in-memory implementation for single-transaction validation.
 * Yaci provides a storage-backed implementation with intra-block chaining support.
 */
public interface UtxoSlice {

    Optional<TransactionOutput> lookup(TransactionInput input);

    void consume(TransactionInput input);

    void produce(TransactionInput input, TransactionOutput output);
}
