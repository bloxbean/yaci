package com.bloxbean.cardano.yaci.core.model.serializers.util;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.serializers.leios.LeiosCborReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts byte-exact slices from the w27 Dijkstra block layout:
 * {@code [era, [header, [invalid_transactions, transactions, leios_certificate, peras_certificate]]]}.
 */
public final class DijkstraTransactionExtractor {

    private DijkstraTransactionExtractor() {
    }

    public static BlockBodySlice extractBlockBody(byte[] rawBlockBytes) {
        LeiosCborReader reader = new LeiosCborReader(rawBlockBytes);
        long outerLength = reader.readLength(LeiosCborReader.MAJOR_TYPE_ARRAY);
        reader.readDataItem(); // era
        LeiosCborReader.DecodedItem blockItem = reader.readDataItem();
        requireArrayEnd(reader, outerLength, 2, "era-wrapped block");
        reader.requireEnd();

        LeiosCborReader blockReader = new LeiosCborReader(blockItem.rawBytes());
        long blockLength = blockReader.readLength(LeiosCborReader.MAJOR_TYPE_ARRAY);
        blockReader.readDataItem(); // header
        LeiosCborReader.DecodedItem blockBodyItem = blockReader.readDataItem();
        requireArrayEnd(blockReader, blockLength, 2, "Dijkstra block");
        blockReader.requireEnd();

        return readBlockBody(blockBodyItem);
    }

    private static BlockBodySlice readBlockBody(LeiosCborReader.DecodedItem blockBodyItem) {
        LeiosCborReader bodyReader = new LeiosCborReader(blockBodyItem.rawBytes());
        long blockBodyLength = bodyReader.readLength(LeiosCborReader.MAJOR_TYPE_ARRAY);
        if (blockBodyLength != LeiosCborReader.INDEFINITE && blockBodyLength < 4) {
            throw new IllegalArgumentException("Dijkstra block_body must have at least 4 items");
        }

        LeiosCborReader.DecodedItem invalidTransactions = readRequiredItem(bodyReader, "Dijkstra block_body");
        LeiosCborReader.DecodedItem transactions = readRequiredItem(bodyReader, "Dijkstra block_body");
        LeiosCborReader.DecodedItem leiosCertificate = readRequiredItem(bodyReader, "Dijkstra block_body");
        LeiosCborReader.DecodedItem perasCertificate = readRequiredItem(bodyReader, "Dijkstra block_body");
        int extraItems = consumeArrayExtras(bodyReader, blockBodyLength, 4);
        bodyReader.requireEnd();

        return new BlockBodySlice(
                invalidTransactions.dataItem(),
                invalidTransactions.rawBytes(),
                readTransactions(transactions),
                leiosCertificate.dataItem(),
                leiosCertificate.rawBytes(),
                perasCertificate.dataItem(),
                perasCertificate.rawBytes(),
                extraItems);
    }

    private static List<TransactionSlice> readTransactions(LeiosCborReader.DecodedItem transactionsItem) {
        LeiosCborReader txListReader = new LeiosCborReader(transactionsItem.rawBytes());
        long txListLength = txListReader.readLength(LeiosCborReader.MAJOR_TYPE_ARRAY);
        List<TransactionSlice> transactions = new ArrayList<>();
        int index = 0;
        if (txListLength == LeiosCborReader.INDEFINITE) {
            while (!txListReader.nextIsBreak()) {
                transactions.add(readTransaction(index++, txListReader.readDataItem()));
            }
            txListReader.readBreak();
        } else {
            for (long i = 0; i < txListLength; i++) {
                transactions.add(readTransaction(index++, txListReader.readDataItem()));
            }
        }
        txListReader.requireEnd();
        return transactions;
    }

    private static TransactionSlice readTransaction(int index, LeiosCborReader.DecodedItem transactionItem) {
        LeiosCborReader transactionReader = new LeiosCborReader(transactionItem.rawBytes());
        long transactionLength = transactionReader.readLength(LeiosCborReader.MAJOR_TYPE_ARRAY);
        if (transactionLength != LeiosCborReader.INDEFINITE && transactionLength != 3) {
            throw new IllegalArgumentException("Dijkstra block transaction must have exactly 3 items");
        }

        LeiosCborReader.DecodedItem body = readRequiredItem(transactionReader, "Dijkstra block transaction");
        LeiosCborReader.DecodedItem witnesses = readRequiredItem(transactionReader, "Dijkstra block transaction");
        LeiosCborReader.DecodedItem auxiliaryData = readRequiredItem(transactionReader, "Dijkstra block transaction");
        requireArrayEnd(transactionReader, transactionLength, 3, "Dijkstra block transaction");
        transactionReader.requireEnd();

        return new TransactionSlice(
                index,
                transactionItem.dataItem(),
                transactionItem.rawBytes(),
                body.dataItem(),
                body.rawBytes(),
                witnesses.dataItem(),
                witnesses.rawBytes(),
                auxiliaryData.dataItem(),
                auxiliaryData.rawBytes());
    }

    private static int consumeArrayExtras(LeiosCborReader reader, long length, int consumed) {
        int extraItems = 0;
        if (length == LeiosCborReader.INDEFINITE) {
            while (!reader.nextIsBreak()) {
                reader.readDataItem();
                extraItems++;
            }
            reader.readBreak();
            return extraItems;
        }

        for (long i = consumed; i < length; i++) {
            reader.readDataItem();
            extraItems++;
        }
        return extraItems;
    }

    private static LeiosCborReader.DecodedItem readRequiredItem(LeiosCborReader reader, String name) {
        if (reader.nextIsBreak()) {
            throw new IllegalArgumentException(name + " ended before all required items were present");
        }
        return reader.readDataItem();
    }

    private static void requireArrayEnd(LeiosCborReader reader, long length, int consumed, String name) {
        if (length == LeiosCborReader.INDEFINITE) {
            if (!reader.nextIsBreak()) {
                throw new IllegalArgumentException(name + " has more than " + consumed + " items");
            }
            reader.readBreak();
        } else if (length != consumed) {
            throw new IllegalArgumentException(name + " must have " + consumed + " items");
        }
    }

    public record BlockBodySlice(DataItem invalidTransactions,
                                 byte[] invalidTransactionsBytes,
                                 List<TransactionSlice> transactions,
                                 DataItem leiosCertificate,
                                 byte[] leiosCertificateBytes,
                                 DataItem perasCertificate,
                                 byte[] perasCertificateBytes,
                                 int extraItems) {
    }

    public record TransactionSlice(int index,
                                   DataItem transaction,
                                   byte[] transactionBytes,
                                   DataItem body,
                                   byte[] bodyBytes,
                                   DataItem witnesses,
                                   byte[] witnessesBytes,
                                   DataItem auxiliaryData,
                                   byte[] auxiliaryDataBytes) {
    }
}
