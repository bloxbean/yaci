package com.bloxbean.cardano.yaci.core.model.serializers.util;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.MajorType;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a Dijkstra block into byte-exact slices, without building a CBOR tree (so deep nesting cannot overflow
 * the stack here). Layout pinned to the deployed Musashi network, prototype-2026w40a
 * (cardano-blueprint {@code src/ledger/eras/dijkstra.cddl} @ 131cc95a):
 * <pre>
 * [era, [header, block_body]]
 * block_body        = [transactions : [* block_transaction], leios_certificate / nil, peras_certificate / nil]
 * block_transaction = [transaction_body, transaction_witness_set, auxiliary_data / nil, is_valid : bool]
 * </pre>
 * The same {@code block_transaction} encoding is used for endorser block transactions, so
 * {@link #readTransaction(int, byte[])} serves both ranking blocks and endorser blocks.
 */
public final class DijkstraTransactionExtractor {
    private static final byte CBOR_NULL = (byte) 0xf6;
    private static final byte CBOR_TRUE = (byte) 0xf5;
    private static final byte CBOR_FALSE = (byte) 0xf4;

    private DijkstraTransactionExtractor() {
    }

    public static BlockBodySlice extractBlockBody(byte[] rawBlockBytes) throws CborException {
        List<CborSlice> envelope = CborSlice.of(rawBlockBytes).items(MajorType.ARRAY);
        require(envelope.size() == 2, "Dijkstra block must be [era, block]");
        List<CborSlice> block = envelope.get(1).items(MajorType.ARRAY);
        require(block.size() == 2, "Dijkstra block must be [header, block_body]");
        List<CborSlice> body = block.get(1).items(MajorType.ARRAY);
        require(body.size() >= 3, "Dijkstra block_body must have 3 items, found " + body.size());

        List<CborSlice> txItems = body.get(0).items(MajorType.ARRAY);
        List<TransactionSlice> transactions = new ArrayList<>(txItems.size());
        for (int i = 0; i < txItems.size(); i++) {
            transactions.add(readTransaction(i, txItems.get(i)));
        }
        return new BlockBodySlice(block.get(0).bytes(), transactions,
                nilToNull(body.get(1)), nilToNull(body.get(2)), body.size() - 3);
    }

    public static TransactionSlice readTransaction(int index, byte[] blockTransactionBytes) throws CborException {
        return readTransaction(index, CborSlice.of(blockTransactionBytes));
    }

    private static TransactionSlice readTransaction(int index, CborSlice transaction) throws CborException {
        List<CborSlice> parts = transaction.items(MajorType.ARRAY);
        require(parts.size() == 4, "block_transaction must be [body, witness_set, auxiliary_data, is_valid], found "
                + parts.size() + " items");
        return new TransactionSlice(index, transaction.bytes(), parts.get(0).bytes(), parts.get(1).bytes(),
                nilToNull(parts.get(2)), isValid(parts.get(3)));
    }

    private static boolean isValid(CborSlice slice) throws CborException {
        byte[] bytes = slice.bytes();
        if (bytes.length == 1 && bytes[0] == CBOR_TRUE) return true;
        if (bytes.length == 1 && bytes[0] == CBOR_FALSE) return false;
        throw new CborException("block_transaction is_valid must be a bool");
    }

    private static byte[] nilToNull(CborSlice slice) {
        byte[] bytes = slice.bytes();
        return bytes.length == 1 && bytes[0] == CBOR_NULL ? null : bytes;
    }

    private static void require(boolean condition, String message) throws CborException {
        if (!condition) throw new CborException(message);
    }

    /**
     * @param headerBytes            the encoded header
     * @param leiosCertificateBytes  null when the block certifies no endorser block
     * @param perasCertificateBytes  null when absent
     * @param extraItems             block_body items after the three known ones (ignored)
     */
    public record BlockBodySlice(byte[] headerBytes,
                                 List<TransactionSlice> transactions,
                                 byte[] leiosCertificateBytes,
                                 byte[] perasCertificateBytes,
                                 int extraItems) {
    }

    /**
     * @param auxiliaryDataBytes null when the transaction has no auxiliary data
     * @param valid              false for a phase-2 invalid transaction (collateral is spent instead)
     */
    public record TransactionSlice(int index,
                                   byte[] transactionBytes,
                                   byte[] bodyBytes,
                                   byte[] witnessesBytes,
                                   byte[] auxiliaryDataBytes,
                                   boolean valid) {
    }
}
