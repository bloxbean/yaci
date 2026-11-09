package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.ByteString;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.transaction.raw.RawBlock;
import com.bloxbean.cardano.client.transaction.raw.RawCertificate;
import com.bloxbean.cardano.client.transaction.raw.RawTx;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.RollForwardSerializer;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.TxCborUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR 0003 D4a (M7a): Yaci delivers Dijkstra blocks to listeners with its existing models. The inputs are real
 * Dijkstra data in {@code block/dijkstra}, whose {@code SOURCES.txt} records each file's source and licence; the
 * expected header values and transaction ids are the ones recorded there. Where a source records no id, the id is
 * taken from the input bytes with CCL's {@link CborSpan} and BouncyCastle's Blake2b used directly, so these tests do
 * not rest on the code they test.
 */
class DijkstraBlockTest {
    private static final int TRANSACTIONS = 0;
    private static final int AUX_DATA = 2;
    private static final int IS_VALID = 3;
    private static final int BLS_SLOT = 3;
    private static final int CONWAY_VARIANT = 6;
    private static final int DIJKSTRA_VARIANT = 7;

    private final boolean originalFullTx = YaciConfig.INSTANCE.isReturnFullTxCbor();

    @AfterEach
    void restoreConfig() {
        YaciConfig.INSTANCE.setReturnFullTxCbor(originalFullTx);
    }

    /** {@code txCount} is null where the source records none. */
    private record Vector(String name, long blockNumber, long slot, String blockHash, Integer txCount) {
        byte[] bytes() {
            byte[] cbor = CborLoader.getHexBytes("block/dijkstra/" + name + ".txt");
            // The DijkstraNet block was recorded without its [era, block] envelope.
            return cbor[1] == 8 ? cbor : HexUtil.decodeHexString("8208" + HexUtil.encodeHexString(cbor));
        }
    }

    private static final Vector MUSASHI_EMPTY = new Vector("musashi-dijkstra1", 4255, 86463,
            "d0c2a26a0192baf397b75cd38137987d82036c269089362842888279f3e19daf", 0);
    private static final Vector MUSASHI_ONE_TX = new Vector("musashi-dijkstra2", 4277, 86855,
            "adb23531ebb61891912e6a4bdabcbaaa053223d2de342eedbaa9b6af4fb526f3", 1);
    private static final Vector MUSASHI_THREE_TXS = new Vector("musashi-dijkstra5", 14534, 299514,
            "7b7c9f48ac331106e9f9ef03856090bc6275f09fca5bb4c789068d04c54b079a", 3);
    private static final Vector MUSASHI_LEIOS_CERTIFICATE = new Vector("musashi-dijkstra8", 17403, 371723,
            "33a48eee693522320891dd4d1da8ee33c288c854eb25337802dbc4c912571d07", 0);
    private static final Vector DIJKSTRANET_FIRST = new Vector("dijkstranet-first-block", 64739, 1296023,
            "060b416fd53c32e94b6e45a2b03dd01dcdc914702cefe8993d4a205f471a476a", null);
    private static final String DIJKSTRANET_PREV_HASH =
            "f14caba43c84e11a6c79b1063ea4d00bcc02fb766e3212c06ab466e84fd20541";
    private static final List<Vector> BLOCKS = List.of(MUSASHI_EMPTY, MUSASHI_ONE_TX, MUSASHI_THREE_TXS,
            MUSASHI_LEIOS_CERTIFICATE, DIJKSTRANET_FIRST);

    @Test
    void dijkstraIsEraEightAndTxBodyTypeSeven() {
        assertThat(EraUtil.getEra(8)).isEqualTo(Era.Dijkstra);
        assertThat(Era.Dijkstra.getValue()).isEqualTo(8);
        assertThat(TxBodyType.DIJKSTRA.getValue()).isEqualTo(7);
        assertThat(TxBodyType.DIJKSTRA.getEra().getValue()).isEqualTo(7);
    }

    /** Block fetch delivers each block through blockFound, and local chain sync as its roll forward. */
    @Test
    void everyBlockReachesListenersWithItsRecordedValues() throws Exception {
        int auxData = 0;
        for (Vector vector : BLOCKS) {
            byte[] bytes = vector.bytes();
            for (Block block : SyncDataIsolationTest.throughBothSyncPaths(bytes)) {
                assertBlock(block, vector, bytes);
                auxData += block.getAuxiliaryDataMap().size();
            }
        }
        assertThat(auxData).isPositive();
    }

    /** Its pool registration has a BLS key slot after the VRF key hash, which only a Dijkstra read accepts. */
    @Test
    void aPoolRegistrationWithABlsKeySlotIsRead() {
        CborSpan poolRegistration = poolRegistration();
        assertThat(poolRegistration.size()).isEqualTo(11);
        assertThatThrownBy(() -> RawCertificate.of(poolRegistration)).isInstanceOf(CborRuntimeException.class);

        Block block = BlockSerializer.INSTANCE.deserialize(MUSASHI_ONE_TX.bytes());
        assertThat(block.getTransactionBodies().get(0).getCertificates())
                .hasAtLeastOneElementOfType(PoolRegistration.class);
    }

    /**
     * A Dijkstra pool registration reads as the same registration without its BLS key slot does in Conway, with the
     * models' tolerance: an item after a relay's or the metadata's last position is ignored, and a negative margin is
     * read as it is.
     */
    @Test
    void aDijkstraPoolRegistrationIsToleratedAsInConway() {
        CborSpan dijkstra = poolRegistration();
        List<String> items = new ArrayList<>(dijkstra.items().stream().map(DijkstraBlockTest::hex).toList());
        String[][] replacements = {
                {},
                {"9", "81" + "85" + "00f6f6f6" + "00"},
                {"10", "83" + "6161" + "5820" + "00".repeat(32) + "00"},
                {"6", "d81e82" + "20" + "02"},
        };
        for (String[] replacement : replacements) {
            List<String> variant = new ArrayList<>(items);
            if (replacement.length > 0) variant.set(Integer.parseInt(replacement[0]), replacement[1]);
            List<String> withoutBls = new ArrayList<>(variant);
            withoutBls.remove(BLS_SLOT);
            Certificate inDijkstra = CertificateSerializer.INSTANCE.read(array(variant),
                    com.bloxbean.cardano.client.spec.Era.Dijkstra);
            Certificate inConway = CertificateSerializer.INSTANCE.read(array(withoutBls),
                    com.bloxbean.cardano.client.spec.Era.Conway);
            // M7c (D4b): the Dijkstra registration also carries its BLS key, which the Conway one has no place for.
            assertThat(inDijkstra).isInstanceOf(PoolRegistration.class).usingRecursiveComparison()
                    .ignoringFields("poolParams.blsKey").isEqualTo(inConway);
            assertThat(((PoolRegistration) inDijkstra).getPoolParams().getBlsKey()).isNotNull();
            assertThat(((PoolRegistration) inConway).getPoolParams().getBlsKey()).isNull();
        }
    }

    /**
     * Synthetic: musashi-dijkstra5 with its first transaction body's fee repeated, last 1000. The tolerant read takes the
     * last value, as the same body gets from Conway's parallel arrays; CCL's RawTx view rejects the repeated key. The
     * header's body hash and size then no longer match the body; Yaci does not check them.
     */
    @Test
    void aRepeatedBodyKeyIsReadTolerantlyAsInConway() throws Exception {
        CborSpan original = CborSpan.of(MUSASHI_THREE_TXS.bytes());
        CborSpan body = transactions(original).get(0).get(0);
        int entries = body.entries().size();
        assertThat(body.isIndefinite()).isFalse();
        assertThat(entries).isLessThan(23);
        String repeated = String.format("%02x", 0xa0 + entries + 1) + hex(body).substring(2) + "02" + "1903e8";
        byte[] bytes = original.replacing(List.of(body), List.of(HexUtil.decodeHexString(repeated)));
        assertThatThrownBy(() -> RawBlock.of(bytes).tx(0)).isInstanceOf(CborRuntimeException.class);

        TransactionBody inConway = TransactionBodySerializer.INSTANCE.read(HexUtil.decodeHexString(repeated),
                com.bloxbean.cardano.client.spec.Era.Conway);
        assertThat(inConway.getFee()).isEqualTo(BigInteger.valueOf(1000));
        for (Block block : SyncDataIsolationTest.throughBothSyncPaths(bytes)) {
            TransactionBody read = block.getTransactionBodies().get(0);
            assertThat(read.getFee()).isEqualTo(BigInteger.valueOf(1000));
            assertThat(read.getTxHash()).isEqualTo(IndependentCbor.blake2b256(HexUtil.decodeHexString(repeated)));
        }
    }

    /** The pool registration of musashi-dijkstra2, with a BLS key slot after its VRF key hash. */
    private static CborSpan poolRegistration() {
        CborSpan body = transactions(CborSpan.of(MUSASHI_ONE_TX.bytes())).get(0).get(0);
        CborSpan certificates = body.field(4).orElseThrow().untagIf(258);
        return certificates.items().stream().filter(cert -> cert.get(0).asLong() == 3).findFirst().orElseThrow();
    }

    private static CborSpan array(List<String> items) {
        String header = String.format("%02x", 0x80 + items.size());
        return CborSpan.of(HexUtil.decodeHexString(header + String.join("", items)));
    }

    private static String hex(CborSpan span) {
        return HexUtil.encodeHexString(span.bytes());
    }

    /** ADR 0003 I14: the era-less entry points read as Conway, where key 14 is required signers, not guards. */
    @Test
    void eraLessEntryPointsReadAsConway() {
        String signer = "00".repeat(28);
        byte[] body = HexUtil.decodeHexString("a4" + "0080" + "0180" + "0200" + "0e81581c" + signer);
        @SuppressWarnings("removal")
        TransactionBody read = TransactionBodySerializer.INSTANCE.deserializeDI(
                CborSerializationUtil.deserializeOne(body), body);
        assertThat(read.getRequiredSigners()).containsExactly(signer);
    }

    @Test
    void theDijkstraNetBlockCarriesItsPreviousHashAndVersionPair() {
        HeaderBody header = BlockSerializer.INSTANCE.deserialize(DIJKSTRANET_FIRST.bytes()).getHeader()
                .getHeaderBody();
        assertThat(header.getPrevHash()).isEqualTo(DIJKSTRANET_PREV_HASH);
        // The pair is the highest supported major version and the software tag (ADR 0003 D4a).
        assertThat(header.getProtocolVersion().get_1()).isEqualTo(12);
    }

    /**
     * Chain sync wraps a header in [variant, #6.24(header)], whose HFC variant is 7 for Dijkstra and 6 for Conway. A
     * header is read in its variant's layouts: 10 items in both, 12 only in Dijkstra.
     */
    @Test
    void chainSyncHeadersOfTenAndTwelveItemsDecodeInTheirEra() {
        byte[] tenItems = CborLoader.getHexBytes("block/dijkstra/dijkstranet-first-header.txt");
        assertThat(CborSpan.of(tenItems).get(0).size()).isEqualTo(10);
        for (int variant : new int[]{CONWAY_VARIANT, DIJKSTRA_VARIANT}) {
            BlockHeader header = throughChainSync(tenItems, variant);
            assertHeader(header, DIJKSTRANET_FIRST);
            assertThat(header.getHeaderBody().getPrevHash()).isEqualTo(DIJKSTRANET_PREV_HASH);
            assertThat(header.getHeaderBody().getProtocolVersion().get_1()).isEqualTo(12);
        }

        for (Vector vector : List.of(MUSASHI_ONE_TX, MUSASHI_LEIOS_CERTIFICATE)) {
            byte[] twelveItems = CborSpan.of(vector.bytes()).get(1).get(0).bytes();
            assertThat(CborSpan.of(twelveItems).get(0).size()).as(vector.name()).isEqualTo(12);
            assertHeader(throughChainSync(twelveItems, DIJKSTRA_VARIANT), vector);
            assertThatThrownBy(() -> throughChainSync(twelveItems, CONWAY_VARIANT)).as(vector.name())
                    .isInstanceOf(com.bloxbean.cardano.yaci.core.exception.CborRuntimeException.class);
        }
    }

    /** A variant this code does not know, even the largest int, reads the header in any layout, as before. */
    @Test
    void anUnknownChainSyncVariantReadsTheHeaderInAnyLayout() {
        byte[] tenItems = CborLoader.getHexBytes("block/dijkstra/dijkstranet-first-header.txt");
        byte[] twelveItems = CborSpan.of(MUSASHI_ONE_TX.bytes()).get(1).get(0).bytes();
        for (String variant : new String[]{"08", "1a7fffffff"}) {
            assertHeader(throughChainSync(tenItems, variant), DIJKSTRANET_FIRST);
            assertHeader(throughChainSync(twelveItems, variant), MUSASHI_ONE_TX);
        }
    }

    /** The submitted form, [body, witnesses, aux / null], is CCL's RawBlock.txBytes(i), and keeps the id. */
    @Test
    void fullTransactionCborIsTheThreeItemForm() {
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        int checked = 0;
        for (Vector vector : BLOCKS) {
            byte[] bytes = vector.bytes();
            Block block = BlockSerializer.INSTANCE.deserialize(bytes);
            RawBlock rawBlock = RawBlock.of(bytes);
            for (int i = 0; i < block.getTransactionBodies().size(); i++) {
                byte[] txCbor = fullTxCbor(block, i);
                assertThat(txCbor[0]).isEqualTo((byte) 0x83);
                assertThat(txCbor).isEqualTo(rawBlock.txBytes(i));
                assertThat(HexUtil.encodeHexString(RawTx.of(txCbor).txId())).isEqualTo(txIds(bytes).get(i));
                checked++;
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(4);
    }

    /**
     * Synthetic: musashi-dijkstra2 with its transaction replaced by musashi-subtx, a real block transaction with a
     * sub-transaction. The header's body hash and size then no longer match the body; Yaci does not check them.
     */
    @Test
    void aTransactionWithASubTransactionKeepsItsId() throws Exception {
        byte[] subTx = CborLoader.getHexBytes("block/dijkstra/musashi-subtx.txt");
        CborSpan original = CborSpan.of(MUSASHI_ONE_TX.bytes());
        byte[] bytes = original.replacing(List.of(transactions(original).get(0)), List.of(subTx));
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        for (Block block : SyncDataIsolationTest.throughBothSyncPaths(bytes)) {
            assertThat(block.getEra()).isEqualTo(Era.Dijkstra);
            assertThat(block.getTransactionBodies()).extracting(TransactionBody::getTxHash)
                    .containsExactly("74e2116ca6e0c809f156aa062a9d4ee8b156322618846f1bdea5d9ad7a206a12");
            assertThat(block.getInvalidTransactions()).isEmpty();
            byte[] txCbor = fullTxCbor(block, 0);
            assertThat(txCbor).isEqualTo(RawBlock.of(bytes).txBytes(0));
            assertThat(HexUtil.encodeHexString(RawTx.of(txCbor).txId()))
                    .isEqualTo("74e2116ca6e0c809f156aa062a9d4ee8b156322618846f1bdea5d9ad7a206a12");
        }
    }

    /**
     * Synthetic: musashi-dijkstra5 with the flag of its second transaction changed from true to false. The header's
     * body hash then no longer matches the body; Yaci does not check it.
     */
    @Test
    void aTransactionWhoseFlagIsFalseIsReportedInvalid() throws Exception {
        CborSpan original = CborSpan.of(MUSASHI_THREE_TXS.bytes());
        CborSpan flag = transactions(original).get(1).get(IS_VALID);
        assertThat(flag.asBoolean()).isTrue();
        byte[] bytes = original.replacing(List.of(flag), List.of(new byte[]{(byte) 0xf4}));
        for (Block block : SyncDataIsolationTest.throughBothSyncPaths(bytes)) {
            assertThat(block.getEra()).isEqualTo(Era.Dijkstra);
            assertThat(block.getInvalidTransactions()).containsExactly(1);
            assertThat(block.getTransactionBodies()).extracting(TransactionBody::getTxHash)
                    .containsExactlyElementsOf(txIds(bytes));
        }
    }

    private static void assertBlock(Block block, Vector vector, byte[] bytes) {
        String name = vector.name();
        assertThat(block.getEra()).as(name).isEqualTo(Era.Dijkstra);
        assertHeader(block.getHeader(), vector);
        List<CborSpan> transactions = transactions(CborSpan.of(bytes));
        if (vector.txCount() != null) assertThat(transactions).as(name).hasSize(vector.txCount());
        assertThat(block.getTransactionBodies()).extracting(TransactionBody::getTxHash).as(name)
                .containsExactlyElementsOf(txIds(bytes));
        assertThat(block.getTransactionWitness()).as(name).hasSize(transactions.size());
        List<Integer> invalid = new ArrayList<>();
        List<Integer> withAuxData = new ArrayList<>();
        for (int i = 0; i < transactions.size(); i++) {
            if (!transactions.get(i).get(IS_VALID).asBoolean()) invalid.add(i);
            if (!transactions.get(i).get(AUX_DATA).isNull()) withAuxData.add(i);
        }
        assertThat(block.getInvalidTransactions()).as(name).isEqualTo(invalid);
        assertThat(block.getAuxiliaryDataMap()).as(name).containsOnlyKeys(withAuxData);
    }

    private static void assertHeader(BlockHeader header, Vector vector) {
        HeaderBody body = header.getHeaderBody();
        assertThat(body.getBlockNumber()).as(vector.name()).isEqualTo(vector.blockNumber());
        assertThat(body.getSlot()).as(vector.name()).isEqualTo(vector.slot());
        assertThat(body.getBlockHash()).as(vector.name()).isEqualTo(vector.blockHash());
    }

    private static BlockHeader throughChainSync(byte[] header, int variant) {
        return throughChainSync(header, "0" + variant);
    }

    // variantCbor: the HFC variant as encoded, such as "07" or "1a7fffffff"
    private static BlockHeader throughChainSync(byte[] header, String variantCbor) {
        String wrapped = HexUtil.encodeHexString(CborSerializationUtil.serialize(new ByteString(header)));
        byte[] message = HexUtil.decodeHexString("8302" + "82" + variantCbor + "d818" + wrapped
                + "8282" + "01" + "5820" + "00".repeat(32) + "01");
        return RollForwardSerializer.INSTANCE.deserialize(message).getBlockHeader();
    }

    /** As the helper's block listener assembles it from the block's parts. */
    private static byte[] fullTxCbor(Block block, int i) {
        AuxData auxData = block.getAuxiliaryDataMap().get(i);
        return TxCborUtil.assembleTxCbor(block.getEra(),
                HexUtil.decodeHexString(block.getTransactionBodies().get(i).getCbor()),
                HexUtil.decodeHexString(block.getTransactionWitness().get(i).getCbor()),
                auxData == null ? null : HexUtil.decodeHexString(auxData.getCbor()),
                !block.getInvalidTransactions().contains(i));
    }

    /** [era, [header, [transactions, leios_certificate / null, peras_certificate / null]]] */
    private static List<CborSpan> transactions(CborSpan envelope) {
        return envelope.get(1).get(1).get(TRANSACTIONS).items();
    }

    private static List<String> txIds(byte[] bytes) {
        List<String> ids = new ArrayList<>();
        for (CborSpan tx : transactions(CborSpan.of(bytes)))
            ids.add(IndependentCbor.blake2b256(tx.get(0).bytes()));
        return ids;
    }
}
