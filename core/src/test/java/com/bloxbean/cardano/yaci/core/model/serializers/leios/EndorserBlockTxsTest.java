package com.bloxbean.cardano.yaci.core.model.serializers.leios;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.raw.RawEbReferences;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchStateBase;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlock;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxs;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.TxCborUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Musashi rc-2026w42 (README in {@code leios/w42}): block 10026 announces EB {@code f951f865…}, block 10027 certifies it,
 * and the relay served the EB and its 237 transactions. All read through CCL and next's mappers (ADR 0016 D1, D3a).
 */
class EndorserBlockTxsTest {
    private static final String EB = "f951f865a92d7a26817cce411b9d75b4e6e86315649602808f581e97f80675e5";

    @AfterEach
    void reset() {
        YaciConfig.INSTANCE.setReturnTxBodyCbor(false);
        YaciConfig.INSTANCE.setReturnFullTxCbor(false);
    }

    @Test
    void theAnnouncedEndorserBlockIsReadAsReceived() throws IOException {
        Block announcing = BlockSerializer.INSTANCE.deserialize(hex("block-10026.hex"));
        MsgLeiosBlock message = (MsgLeiosBlock) LeiosFetchStateBase.deserialize(hex("msg-leios-block-f951f865.hex"));
        byte[] references = message.getEndorserBlock().getCbor();

        assertThat(announcing.getHeader().getHeaderBody().getEbAnnouncement().getHash()).isEqualTo(EB);
        assertThat(HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(references))).isEqualTo(EB);
        assertThat((long) references.length)
                .isEqualTo(announcing.getHeader().getHeaderBody().getEbAnnouncement().getSize());
        assertThat(RawEbReferences.of(references).entries()).hasSize(237);
    }

    @Test
    void theCertifyingBlockGetsTheEndorserBlockTransactionsFirst() throws IOException {
        Block certifying = BlockSerializer.INSTANCE.deserialize(hex("block-10027.hex"));
        List<byte[]> transactions = transactions();
        List<String> dbSyncIds = dbSyncIds();
        RawEbReferences references = RawEbReferences.of(
                ((MsgLeiosBlock) LeiosFetchStateBase.deserialize(hex("msg-leios-block-f951f865.hex")))
                        .getEndorserBlock().getCbor());
        for (int i = 0; i < transactions.size(); i++) {
            byte[] tx = transactions.get(i);
            assertThat(Blake2bUtil.blake2bHash256(tx)).isEqualTo(references.entries().get(i).txHash());
            assertThat((long) tx.length).isEqualTo(references.entries().get(i).size());
        }

        Block complete = BlockSerializer.withEndorserBlockTxs(certifying, ref(), transactions);

        assertThat(certifying.getTransactionBodies()).isEmpty();
        assertThat(complete.getTransactionBodies()).extracting(TransactionBody::getTxHash).containsExactlyElementsOf(dbSyncIds);
        assertThat(complete.getTransactionWitness()).hasSize(237);
        assertThat(complete.getInvalidTransactions()).isEmpty();
        assertThat(complete.getLeiosEndorserBlock())
                .isEqualTo(new LeiosEndorserBlockRef(EB, 204753, 8534, 237));
        // what the block carries itself is kept
        assertThat(complete.getHeader()).isEqualTo(certifying.getHeader());
        assertThat(complete.getLeiosCertificate()).isEqualTo(certifying.getLeiosCertificate());
        assertThat(complete.getCbor()).isEqualTo(certifying.getCbor());
        assertThat(complete.getAuxiliaryDataMap()).hasSize((int) transactions.stream()
                .filter(tx -> !CborSpan.of(tx).get(2).isNull()).count());
    }

    @Test
    void validityComesFromEachTransactionAndFullCborKeepsNextsForm() throws IOException {
        YaciConfig.INSTANCE.setReturnTxBodyCbor(true);
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);
        List<byte[]> transactions = new ArrayList<>(transactions());
        byte[] first = transactions.get(0);
        // the last byte of [body, witnesses, aux, true] is f5; f4 makes it invalid
        assertThat(first[first.length - 1]).isEqualTo((byte) 0xf5);
        byte[] invalid = Arrays.copyOf(first, first.length);
        invalid[invalid.length - 1] = (byte) 0xf4;
        transactions.set(0, invalid);

        Block complete = BlockSerializer.withEndorserBlockTxs(
                BlockSerializer.INSTANCE.deserialize(hex("block-10027.hex")), ref(), transactions);

        assertThat(complete.getInvalidTransactions()).containsExactly(0);
        CborSpan tx = CborSpan.of(first);
        Witnesses witnesses = complete.getTransactionWitness().get(0);
        AuxData auxData = complete.getAuxiliaryDataMap().get(0);
        assertThat(witnesses.getCbor()).isEqualTo(HexUtil.encodeHexString(tx.get(1).bytes()));
        byte[] fullTx = TxCborUtil.assembleTxCbor(Era.Dijkstra,
                HexUtil.decodeHexString(complete.getTransactionBodies().get(0).getCbor()),
                HexUtil.decodeHexString(witnesses.getCbor()),
                auxData == null ? null : HexUtil.decodeHexString(auxData.getCbor()), false);
        // next's contract for a Dijkstra transaction: the submission form [body, witnesses, aux / null]
        assertThat(CborSpan.of(fullTx).size()).isEqualTo(3);
        assertThat(CborSpan.of(fullTx).get(0).bytes()).isEqualTo(tx.get(0).bytes());
    }

    @Test
    void ownTransactionsComeAfterWithTheirIndexesMoved() throws IOException {
        // block 9091 has its own transactions; give it the first two EB transactions, one of them invalid
        Block block = BlockSerializer.INSTANCE.deserialize(hex("block-9091.hex"));
        List<byte[]> transactions = new ArrayList<>(transactions().subList(0, 2));
        byte[] second = Arrays.copyOf(transactions.get(1), transactions.get(1).length);
        second[second.length - 1] = (byte) 0xf4;
        transactions.set(1, second);
        Block withInvalidOwn = block.toBuilder().invalidTransactions(List.of(3)).build();

        Block complete = BlockSerializer.withEndorserBlockTxs(withInvalidOwn, ref(), transactions);

        int own = block.getTransactionBodies().size();
        assertThat(complete.getTransactionBodies()).hasSize(own + 2);
        assertThat(complete.getTransactionBodies().subList(2, own + 2)).isEqualTo(block.getTransactionBodies());
        assertThat(complete.getTransactionWitness().subList(2, own + 2)).isEqualTo(block.getTransactionWitness());
        assertThat(complete.getInvalidTransactions()).containsExactly(1, 5);
        for (Map.Entry<Integer, AuxData> entry : block.getAuxiliaryDataMap().entrySet())
            assertThat(complete.getAuxiliaryDataMap().get(entry.getKey() + 2)).isEqualTo(entry.getValue());
        assertThat(complete.getLeiosEndorserBlock().getTxCount()).isEqualTo(2);
    }

    @Test
    void aTransactionThatIsNotTheBlockFormIsRejected() throws IOException {
        Block certifying = BlockSerializer.INSTANCE.deserialize(hex("block-10027.hex"));
        CborSpan tx = CborSpan.of(transactions().get(0));
        // the mempool form [body, witnesses, aux]
        byte[] threeItems = concat(new byte[]{(byte) 0x83}, tx.get(0).bytes(), tx.get(1).bytes(), tx.get(2).bytes());

        assertThatThrownBy(() -> BlockSerializer.withEndorserBlockTxs(certifying, ref(), List.of(threeItems)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> BlockSerializer.withEndorserBlockTxs(certifying, ref(), List.of(new byte[]{0x01, 0x02})))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void aBlockJsonWithoutTheReferenceIsUnchanged() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Block certifying = BlockSerializer.INSTANCE.deserialize(hex("block-10027.hex"));
        assertThat(mapper.writeValueAsString(certifying)).doesNotContain("leiosEndorserBlock");
        assertThat(mapper.writeValueAsString(BlockSerializer.withEndorserBlockTxs(certifying, ref(), transactions())))
                .contains("\"leiosEndorserBlock\":{\"ebHash\":\"" + EB + "\",\"ebSlot\":204753,\"ebSize\":8534,\"txCount\":237}");
    }

    private static LeiosEndorserBlockRef ref() {
        return LeiosEndorserBlockRef.builder().ebHash(EB).ebSlot(204753).ebSize(8534).build();
    }

    private static List<byte[]> transactions() throws IOException {
        return ((MsgLeiosBlockTxs) LeiosFetchStateBase.deserialize(hex("msg-leios-block-txs-f951f865.hex")))
                .getTransactions();
    }

    private static List<String> dbSyncIds() throws IOException {
        return text("dbsync-10027-tx-hashes.txt").lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    static byte[] hex(String name) throws IOException {
        return HexUtil.decodeHexString(text(name).trim());
    }

    private static String text(String name) throws IOException {
        try (InputStream in = EndorserBlockTxsTest.class.getResourceAsStream("/leios/w42/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] all = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, all, at, part.length);
            at += part.length;
        }
        return all;
    }
}
