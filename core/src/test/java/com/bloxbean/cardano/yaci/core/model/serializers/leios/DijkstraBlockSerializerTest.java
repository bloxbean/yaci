package com.bloxbean.cardano.yaci.core.model.serializers.leios;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Datum;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.util.DijkstraTransactionExtractor;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.TxCborUtil;
import com.bloxbean.cardano.yaci.core.util.TxUtil;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DijkstraBlockSerializerTest {

    @Test
    void eraUtilMapsDijkstraBlockWireTag() {
        assertEquals(Era.Dijkstra, EraUtil.getEra(8));
    }

    @Test
    void parsesPreW27MusashiHeaderAnnouncementExtension() {
        byte[] ebHash = bytes(32, 9);
        Array headerArray = headerArray(announcementArray(ebHash, 1234));

        BlockHeader blockHeader = BlockHeaderSerializer.INSTANCE.getBlockHeaderFromHeaderArray(headerArray);

        assertNull(blockHeader.getHeaderBody().getLeiosCertified());
        assertNotNull(blockHeader.getHeaderBody().getLeiosAnnouncement());
        assertEquals(HexUtil.encodeHexString(ebHash),
                blockHeader.getHeaderBody().getLeiosAnnouncement().getEbHash());
        assertEquals(1234, blockHeader.getHeaderBody().getLeiosAnnouncement().getEbSize());
    }

    @Test
    void parsesW27HeaderCertifiedAndAnnouncementSlots() {
        byte[] ebHash = bytes(32, 12);
        Array headerArray = headerArray(SimpleValue.TRUE, announcementArray(ebHash, 777));

        BlockHeader blockHeader = BlockHeaderSerializer.INSTANCE.getBlockHeaderFromHeaderArray(headerArray);

        assertEquals(Boolean.TRUE, blockHeader.getHeaderBody().getLeiosCertified());
        assertNotNull(blockHeader.getHeaderBody().getLeiosAnnouncement());
        assertEquals(HexUtil.encodeHexString(ebHash),
                blockHeader.getHeaderBody().getLeiosAnnouncement().getEbHash());
        assertEquals(777, blockHeader.getHeaderBody().getLeiosAnnouncement().getEbSize());
    }

    @Test
    void parsesW27HeaderCertifiedAndNilAnnouncement() {
        Array headerArray = headerArray(SimpleValue.FALSE, SimpleValue.NULL);

        BlockHeader blockHeader = BlockHeaderSerializer.INSTANCE.getBlockHeaderFromHeaderArray(headerArray);

        assertEquals(Boolean.FALSE, blockHeader.getHeaderBody().getLeiosCertified());
        assertNull(blockHeader.getHeaderBody().getLeiosAnnouncement());
    }

    @Test
    void futureTrailingUintDoesNotRouteToPreBabbageHeader() {
        Array headerBody = headerBody();
        headerBody.add(new ByteString(bytes(32, 10)));
        headerBody.add(new UnsignedInteger(42));

        Array headerArray = new Array();
        headerArray.add(headerBody);
        headerArray.add(new ByteString(bytes(64, 11)));

        BlockHeader blockHeader = BlockHeaderSerializer.INSTANCE.getBlockHeaderFromHeaderArray(headerArray);

        assertEquals(100, blockHeader.getHeaderBody().getBlockNumber());
    }

    @Test
    void parsesW40aDijkstraTransactionsAndCertificates() throws Exception {
        boolean previousReturnBlockCbor = YaciConfig.INSTANCE.isReturnBlockCbor();
        YaciConfig.INSTANCE.setReturnBlockCbor(true);
        try {
            byte[] announcedEbHash = bytes(32, 31);
            DataItem tx0Body = transactionBody(10);
            DataItem tx1Body = transactionBody(11);
            Array tx0 = transaction(tx0Body, SimpleValue.NULL, true);
            Array tx1 = transaction(tx1Body, auxiliaryData(), false); //phase-2 invalid
            Array transactions = new Array();
            transactions.add(tx0);
            transactions.add(tx1);
            Array certificate = certificate();
            ByteString perasCertificate = new ByteString(bytes(4, 91));

            Array block = dijkstraBlock(
                    headerArray(SimpleValue.TRUE, announcementArray(announcedEbHash, 88)),
                    blockBody(transactions, certificate, perasCertificate));
            byte[] rawBlock = CborSerializationUtil.serialize(block, false);

            Block parsed = BlockSerializer.INSTANCE.deserialize(rawBlock);

            assertEquals(Era.Dijkstra, parsed.getEra());
            assertEquals(Boolean.TRUE, parsed.getHeader().getHeaderBody().getLeiosCertified());
            assertEquals(HexUtil.encodeHexString(announcedEbHash),
                    parsed.getHeader().getHeaderBody().getLeiosAnnouncement().getEbHash());
            assertEquals(2, parsed.getTransactionBodies().size());
            assertEquals(2, parsed.getTransactionWitness().size());
            assertTrue(parsed.getAuxiliaryDataMap().containsKey(1));
            //is_valid = false on the second transaction
            assertIterableEquals(List.of(1), parsed.getInvalidTransactions());
            assertEquals(TxUtil.calculateTxHash(CborSerializationUtil.serialize(tx0Body, false)),
                    parsed.getTransactionBodies().get(0).getTxHash());
            assertEquals(HexUtil.encodeHexString(rawBlock), parsed.getCbor());

            assertNotNull(parsed.getLeiosCertificate());
            assertEquals(HexUtil.encodeHexString(CborSerializationUtil.serialize(certificate, false)),
                    parsed.getLeiosCertificate().getCbor());
            assertEquals(HexUtil.encodeHexString(bytes(8, 71)), parsed.getLeiosCertificate().getSigners());
            assertEquals(HexUtil.encodeHexString(bytes(48, 72)),
                    parsed.getLeiosCertificate().getAggregatedSignature());
            assertEquals(HexUtil.encodeHexString(CborSerializationUtil.serialize(perasCertificate, false)),
                    parsed.getPerasCertCbor());

            DijkstraTransactionExtractor.BlockBodySlice bodySlice =
                    DijkstraTransactionExtractor.extractBlockBody(rawBlock);
            assertEquals(HexUtil.encodeHexString(CborSerializationUtil.serialize(tx0, false)),
                    HexUtil.encodeHexString(bodySlice.transactions().get(0).transactionBytes()));
            assertEquals(HexUtil.encodeHexString(CborSerializationUtil.serialize(tx0Body, false)),
                    HexUtil.encodeHexString(bodySlice.transactions().get(0).bodyBytes()));
        } finally {
            YaciConfig.INSTANCE.setReturnBlockCbor(previousReturnBlockCbor);
        }
    }

    @Test
    void parsesEmptyDijkstraBlockWithNilOptionalFields() {
        Array transactions = new Array();
        Array block = dijkstraBlock(headerArray(SimpleValue.FALSE, SimpleValue.NULL),
                blockBody(transactions, SimpleValue.NULL, SimpleValue.NULL));

        Block parsed = BlockSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(block, false));

        assertEquals(Era.Dijkstra, parsed.getEra());
        assertEquals(Boolean.FALSE, parsed.getHeader().getHeaderBody().getLeiosCertified());
        assertTrue(parsed.getTransactionBodies().isEmpty());
        assertTrue(parsed.getInvalidTransactions().isEmpty());
        assertNull(parsed.getLeiosCertificate());
        assertNull(parsed.getPerasCertCbor());
    }

    @Test
    void ignoresFutureTrailingDijkstraBlockBodyItems() {
        Array transactions = new Array();
        Array body = blockBody(transactions, SimpleValue.NULL, SimpleValue.NULL);
        body.add(new UnsignedInteger(99));
        Array block = dijkstraBlock(headerArray(SimpleValue.FALSE, SimpleValue.NULL), body);

        Block parsed = BlockSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(block, false));

        assertEquals(Era.Dijkstra, parsed.getEra());
        assertTrue(parsed.getTransactionBodies().isEmpty());
    }

    @Test
    void rejectsTooShortDijkstraBlockBody() {
        Array shortBody = new Array();
        shortBody.add(new Array());
        shortBody.add(SimpleValue.NULL);
        Array block = dijkstraBlock(headerArray(SimpleValue.FALSE, SimpleValue.NULL), shortBody);

        assertThrows(CborException.class,
                () -> BlockSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(block, false)));
    }

    @Test
    void rejectsW27ThreeItemTransactions() {
        //w27 put is_valid in a separate invalid_transactions list; w40a carries it inside each transaction
        Array w27Transaction = new Array();
        w27Transaction.add(transactionBody(12));
        w27Transaction.add(new co.nstant.in.cbor.model.Map());
        w27Transaction.add(SimpleValue.NULL);
        Array transactions = new Array();
        transactions.add(w27Transaction);
        Array block = dijkstraBlock(headerArray(SimpleValue.FALSE, SimpleValue.NULL),
                blockBody(transactions, SimpleValue.NULL, SimpleValue.NULL));

        assertThrows(CborException.class,
                () -> BlockSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(block, false)));
    }

    @Test
    void decodesMalformedCertificateAsRawOnly() {
        Array certificate = new Array();
        certificate.add(new ByteString(bytes(8, 1)));
        certificate.add(new ByteString(bytes(47, 2)));

        Array transactions = new Array();
        Array block = dijkstraBlock(headerArray(SimpleValue.FALSE, SimpleValue.NULL),
                blockBody(transactions, certificate, SimpleValue.NULL));

        Block parsed = BlockSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(block, false));

        assertNotNull(parsed.getLeiosCertificate());
        assertEquals(HexUtil.encodeHexString(CborSerializationUtil.serialize(certificate, false)),
                parsed.getLeiosCertificate().getCbor());
        assertNull(parsed.getLeiosCertificate().getSigners());
        assertNull(parsed.getLeiosCertificate().getAggregatedSignature());
    }

    @Test
    void deeplyNestedDatumOnlyAffectsItsOwnWitnessOnASmallStack() throws Exception {
        //Same data as SyncDataIsolationTest: nesting that overflows a 512 KB stack in a recursive decoder
        String[] deepDatums = {"81".repeat(8000) + "00", "a100".repeat(5000) + "00", "d87981".repeat(5000) + "00"};
        for (String deepDatum : deepDatums) {
            byte[] body0 = CborSerializationUtil.serialize(transactionBody(20), false);
            byte[] body1 = CborSerializationUtil.serialize(transactionBody(21), false);
            //tx0 witness {4: [deep datum]}, tx1 a plain witness: built by hand so the deep value is never re-encoded
            byte[] tx0 = concat(hex("84"), body0, hex("a10481" + deepDatum), hex("f6f5"));
            byte[] tx1 = concat(hex("84"), body1, hex("a0f6f5"));
            byte[] header = CborSerializationUtil.serialize(headerArray(SimpleValue.FALSE, SimpleValue.NULL), false);
            byte[] rawBlock = concat(hex("820882"), header, hex("8382"), tx0, tx1, hex("f6f6"));

            Block parsed = onSmallStack(() -> BlockSerializer.INSTANCE.deserialize(rawBlock));

            Datum datum = parsed.getTransactionWitness().get(0).getDatums().get(0);
            assertEquals(deepDatum, datum.getCbor());
            assertEquals(Datum.cborToHash(HexUtil.decodeHexString(deepDatum)), datum.getHash());
            assertNotNull(datum.getParseError());
            assertEquals(2, parsed.getTransactionBodies().size());
            assertEquals(TxUtil.calculateTxHash(body1), parsed.getTransactionBodies().get(1).getTxHash());
            assertTrue(parsed.getTransactionWitness().get(1).getDatums().isEmpty());
        }
    }

    @Test
    void keepsOriginalWitnessAndAuxiliaryBytesForFullTxCbor() {
        boolean previous = YaciConfig.INSTANCE.isReturnFullTxCbor();
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        try {
            //Non-minimal encodings that a decode/re-encode round trip would change
            byte[] witness = hex("bf0080ff");                 //indefinite map {0: []}
            byte[] aux = hex("d90103a100a1190064" + "6161"); //259({0: {100: "a"}})
            co.nstant.in.cbor.model.Map body = (co.nstant.in.cbor.model.Map) transactionBody(30);
            body.put(new UnsignedInteger(7), new ByteString(Blake2bUtil.blake2bHash256(aux)));
            byte[] bodyBytes = CborSerializationUtil.serialize(body, false);
            byte[] tx = concat(hex("84"), bodyBytes, witness, aux, hex("f5"));
            byte[] header = CborSerializationUtil.serialize(headerArray(SimpleValue.FALSE, SimpleValue.NULL), false);
            byte[] rawBlock = concat(hex("820882"), header, hex("8381"), tx, hex("f6f6"));

            Block parsed = BlockSerializer.INSTANCE.deserialize(rawBlock);

            assertEquals("bf0080ff", parsed.getTransactionWitness().get(0).getCbor());
            assertEquals(HexUtil.encodeHexString(aux), parsed.getAuxiliaryDataMap().get(0).getCbor());
        } finally {
            YaciConfig.INSTANCE.setReturnFullTxCbor(previous);
        }
    }

    @Test
    void parsesLiveW40aMusashiBlocks() throws Exception {
        //Captured from the public Musashi relay (prototype-2026w40a) with :helper:captureW27Fixtures
        List<Path> fixtures;
        try (var files = Files.list(Path.of(getClass().getResource("/leios/w40a").toURI()))) {
            fixtures = files.filter(f -> f.getFileName().toString().startsWith("block-")).sorted().toList();
        }
        assertEquals(5, fixtures.size(), "captured w40a blocks");
        int announced = 0;
        int withTransactions = 0;
        for (Path fixture : fixtures) {
            byte[] rawBlock = HexUtil.decodeHexString(Files.readString(fixture).trim());
            long expectedBlockNumber = Long.parseLong(fixture.getFileName().toString()
                    .replace("block-", "").replace(".hex", ""));

            Block parsed = BlockSerializer.INSTANCE.deserialize(rawBlock);

            assertEquals(Era.Dijkstra, parsed.getEra(), fixture.toString());
            assertEquals(expectedBlockNumber, parsed.getHeader().getHeaderBody().getBlockNumber());
            DijkstraTransactionExtractor.BlockBodySlice slice = DijkstraTransactionExtractor.extractBlockBody(rawBlock);
            assertEquals(slice.transactions().size(), parsed.getTransactionBodies().size());
            for (int i = 0; i < slice.transactions().size(); i++) {
                var tx = slice.transactions().get(i);
                //tx id = blake2b-256 of the original body bytes
                assertEquals(TxUtil.calculateTxHash(tx.bodyBytes()), parsed.getTransactionBodies().get(i).getTxHash());
                //full tx CBOR rebuilt from the segments is byte-identical to the transaction in the block
                assertEquals(HexUtil.encodeHexString(tx.transactionBytes()), HexUtil.encodeHexString(
                        TxCborUtil.assembleTxCbor(Era.Dijkstra, tx.bodyBytes(), tx.witnessesBytes(),
                                tx.auxiliaryDataBytes(), tx.valid())));
            }
            if (Boolean.TRUE.equals(parsed.getHeader().getHeaderBody().getLeiosCertified())) {
                assertNotNull(parsed.getLeiosCertificate(), "certifying block must carry a certificate");
            }
            if (parsed.getHeader().getHeaderBody().getLeiosAnnouncement() != null) announced++;
            if (!parsed.getTransactionBodies().isEmpty()) withTransactions++;
        }
        assertEquals(3, announced, "blocks 162998, 163000 and 163013 announce an EB");
        assertEquals(3, withTransactions, "blocks 163000, 163003 and 163013 carry their own transactions");
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private static <T> T onSmallStack(CheckedSupplier<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                value.set(action.get());
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "dijkstra-isolation-test", 512 * 1024);
        thread.setDaemon(true);
        thread.start();
        thread.join(10000);
        assertTrue(!thread.isAlive(), "parsing must terminate");
        if (failure.get() != null) throw new AssertionError("parsing failed on a small stack", failure.get());
        return value.get();
    }

    private static byte[] hex(String hex) {
        return HexUtil.decodeHexString(hex);
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    private Array dijkstraBlock(Array header, Array blockBody) {
        Array innerBlock = new Array();
        innerBlock.add(header);
        innerBlock.add(blockBody);

        Array block = new Array();
        block.add(new UnsignedInteger(8));
        block.add(innerBlock);
        return block;
    }

    //w40a block_body = [transactions, leios_certificate / nil, peras_certificate / nil]
    private Array blockBody(Array transactions, DataItem certificate, DataItem peras) {
        Array blockBody = new Array();
        blockBody.add(transactions);
        blockBody.add(certificate);
        blockBody.add(peras);
        return blockBody;
    }

    //w40a block_transaction = [body, witness_set, auxiliary_data / nil, is_valid]
    private Array transaction(DataItem body, DataItem auxiliaryData, boolean valid) {
        Array transaction = new Array();
        transaction.add(body);
        transaction.add(new co.nstant.in.cbor.model.Map());
        transaction.add(auxiliaryData);
        transaction.add(valid ? SimpleValue.TRUE : SimpleValue.FALSE);
        return transaction;
    }

    private DataItem transactionBody(long fee) {
        co.nstant.in.cbor.model.Map body = new co.nstant.in.cbor.model.Map();
        body.put(new UnsignedInteger(0), new Array());
        body.put(new UnsignedInteger(1), new Array());
        body.put(new UnsignedInteger(2), new UnsignedInteger(fee));
        return body;
    }

    private co.nstant.in.cbor.model.Map auxiliaryData() {
        co.nstant.in.cbor.model.Map auxiliaryData = new co.nstant.in.cbor.model.Map();
        auxiliaryData.setTag(259L);
        return auxiliaryData;
    }

    private Array certificate() {
        Array certificate = new Array();
        certificate.add(new ByteString(bytes(8, 71)));
        certificate.add(new ByteString(bytes(48, 72)));
        return certificate;
    }

    private Array headerArray(DataItem... extensions) {
        Array headerBody = headerBody();
        for (DataItem extension : extensions) {
            headerBody.add(extension);
        }

        Array headerArray = new Array();
        headerArray.add(headerBody);
        headerArray.add(new ByteString(bytes(448, 8)));
        return headerArray;
    }

    private Array headerBody() {
        Array headerBody = new Array();
        headerBody.add(new UnsignedInteger(100));
        headerBody.add(new UnsignedInteger(200));
        headerBody.add(SimpleValue.NULL);
        headerBody.add(new ByteString(bytes(32, 1)));
        headerBody.add(new ByteString(bytes(32, 2)));
        headerBody.add(vrfCert(3));
        headerBody.add(new UnsignedInteger(0));
        headerBody.add(new ByteString(bytes(32, 4)));
        headerBody.add(operationalCert());
        headerBody.add(protocolVersion());
        return headerBody;
    }

    private Array announcementArray(byte[] ebHash, long ebSize) {
        Array announcement = new Array();
        announcement.add(new ByteString(ebHash));
        announcement.add(new UnsignedInteger(ebSize));
        return announcement;
    }

    private Array vrfCert(int seed) {
        Array vrfCert = new Array();
        vrfCert.add(new ByteString(bytes(32, seed)));
        vrfCert.add(new ByteString(bytes(80, seed + 1)));
        return vrfCert;
    }

    private Array operationalCert() {
        Array operationalCert = new Array();
        operationalCert.add(new ByteString(bytes(32, 5)));
        operationalCert.add(new UnsignedInteger(1));
        operationalCert.add(new UnsignedInteger(2));
        operationalCert.add(new ByteString(bytes(64, 6)));
        return operationalCert;
    }

    private Array protocolVersion() {
        Array protocolVersion = new Array();
        protocolVersion.add(new UnsignedInteger(10));
        protocolVersion.add(new UnsignedInteger(0));
        return protocolVersion;
    }

    private byte[] bytes(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }
}
