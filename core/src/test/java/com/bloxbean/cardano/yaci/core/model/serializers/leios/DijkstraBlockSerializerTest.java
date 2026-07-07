package com.bloxbean.cardano.yaci.core.model.serializers.leios;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.util.DijkstraTransactionExtractor;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.TxUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    void parsesW27DijkstraNestedTransactionsAndCertificates() {
        boolean previousReturnBlockCbor = YaciConfig.INSTANCE.isReturnBlockCbor();
        YaciConfig.INSTANCE.setReturnBlockCbor(true);
        try {
            byte[] announcedEbHash = bytes(32, 31);
            DataItem tx0Body = transactionBody(10);
            DataItem tx1Body = transactionBody(11);
            Array tx0 = transaction(tx0Body, SimpleValue.NULL);
            Array tx1 = transaction(tx1Body, auxiliaryData());
            Array transactions = new Array();
            transactions.add(tx0);
            transactions.add(tx1);
            Array certificate = certificate();
            ByteString perasCertificate = new ByteString(bytes(4, 91));

            Array block = dijkstraBlock(
                    headerArray(SimpleValue.TRUE, announcementArray(announcedEbHash, 88)),
                    blockBody(taggedInvalidTransactions(1), transactions, certificate, perasCertificate));
            byte[] rawBlock = CborSerializationUtil.serialize(block, false);

            Block parsed = BlockSerializer.INSTANCE.deserialize(rawBlock);

            assertEquals(Era.Dijkstra, parsed.getEra());
            assertEquals(Boolean.TRUE, parsed.getHeader().getHeaderBody().getLeiosCertified());
            assertEquals(HexUtil.encodeHexString(announcedEbHash),
                    parsed.getHeader().getHeaderBody().getLeiosAnnouncement().getEbHash());
            assertEquals(2, parsed.getTransactionBodies().size());
            assertEquals(2, parsed.getTransactionWitness().size());
            assertTrue(parsed.getAuxiliaryDataMap().containsKey(1));
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
    void parsesEmptyW27DijkstraBlockWithNilOptionalFields() {
        Array transactions = new Array();
        Array block = dijkstraBlock(headerArray(SimpleValue.FALSE, SimpleValue.NULL),
                blockBody(SimpleValue.NULL, transactions, SimpleValue.NULL, SimpleValue.NULL));

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
        Array body = blockBody(SimpleValue.NULL, transactions, SimpleValue.NULL, SimpleValue.NULL);
        body.add(new UnsignedInteger(99));
        Array block = dijkstraBlock(headerArray(SimpleValue.FALSE, SimpleValue.NULL), body);

        Block parsed = BlockSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(block, false));

        assertEquals(Era.Dijkstra, parsed.getEra());
        assertTrue(parsed.getTransactionBodies().isEmpty());
    }

    @Test
    void rejectsTooShortW27DijkstraBlockBody() {
        Array shortBody = new Array();
        shortBody.add(SimpleValue.NULL);
        shortBody.add(new Array());
        shortBody.add(SimpleValue.NULL);
        Array block = dijkstraBlock(headerArray(SimpleValue.FALSE, SimpleValue.NULL), shortBody);

        assertThrows(IllegalArgumentException.class,
                () -> BlockSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(block, false)));
    }

    @Test
    void rejectsInvalidTransactionIndexOutsideTransactionList() {
        Array transactions = new Array();
        transactions.add(transaction(transactionBody(12), SimpleValue.NULL));
        Array block = dijkstraBlock(headerArray(SimpleValue.FALSE, SimpleValue.NULL),
                blockBody(taggedInvalidTransactions(1), transactions, SimpleValue.NULL, SimpleValue.NULL));

        assertThrows(IllegalArgumentException.class,
                () -> BlockSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(block, false)));
    }

    @Test
    void decodesMalformedCertificateAsRawOnly() {
        Array certificate = new Array();
        certificate.add(new ByteString(bytes(8, 1)));
        certificate.add(new ByteString(bytes(47, 2)));

        Array transactions = new Array();
        Array block = dijkstraBlock(headerArray(SimpleValue.FALSE, SimpleValue.NULL),
                blockBody(SimpleValue.NULL, transactions, certificate, SimpleValue.NULL));

        Block parsed = BlockSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(block, false));

        assertNotNull(parsed.getLeiosCertificate());
        assertEquals(HexUtil.encodeHexString(CborSerializationUtil.serialize(certificate, false)),
                parsed.getLeiosCertificate().getCbor());
        assertNull(parsed.getLeiosCertificate().getSigners());
        assertNull(parsed.getLeiosCertificate().getAggregatedSignature());
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

    private Array blockBody(DataItem invalidTransactions, Array transactions, DataItem certificate, DataItem peras) {
        Array blockBody = new Array();
        blockBody.add(invalidTransactions);
        blockBody.add(transactions);
        blockBody.add(certificate);
        blockBody.add(peras);
        return blockBody;
    }

    private Array transaction(DataItem body, DataItem auxiliaryData) {
        Array transaction = new Array();
        transaction.add(body);
        transaction.add(new co.nstant.in.cbor.model.Map());
        transaction.add(auxiliaryData);
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

    private Array taggedInvalidTransactions(int index) {
        Array invalidTransactions = new Array();
        invalidTransactions.setTag(258L);
        invalidTransactions.add(new UnsignedInteger(index));
        return invalidTransactions;
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
