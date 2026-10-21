package com.bloxbean.cardano.yaci.core.model.serializers.leios;

import co.nstant.in.cbor.model.MajorType;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.leios.EndorserBlock;
import com.bloxbean.cardano.yaci.core.model.leios.EndorserBlockTx;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosEndorserBlockRef;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.DijkstraBlockBodySerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.model.serializers.util.DijkstraTransactionExtractor;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.LocalRollForward;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.LocalRollForwardSerializer;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchStateBase;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlock;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxs;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.TxCborUtil;
import com.bloxbean.cardano.yaci.core.util.TxUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live data from the public Musashi relay after the respin to ouroboros-leios {@code rc-2026w42}
 * (see {@code src/test/resources/leios/w42/README.md}). Block #10027 certifies EB f951f865… (237 transactions,
 * announced by #10026); the expected transaction ids are db-sync's (KleioScan).
 */
class MusashiW42FixturesTest {
    private static final String EB_HASH = "f951f865a92d7a26817cce411b9d75b4e6e86315649602808f581e97f80675e5";

    @Test
    void parsesLiveW42Blocks() throws Exception {
        int announced = 0;
        int certifying = 0;
        int withTransactions = 0;
        for (long number : new long[]{9091, 10026, 10027, 10428}) {
            byte[] rawBlock = fixture("block-" + number + ".hex");

            Block parsed = BlockSerializer.INSTANCE.deserialize(rawBlock);

            assertThat(parsed.getEra()).isEqualTo(Era.Dijkstra);
            assertThat(parsed.getHeader().getHeaderBody().getBlockNumber()).isEqualTo(number);
            //header_version_info, same [uint, uint] shape as w40a's protocol_version
            assertThat(parsed.getHeader().getHeaderBody().getProtocolVersion().get_1()).isEqualTo(12);
            DijkstraTransactionExtractor.BlockBodySlice slice = DijkstraTransactionExtractor.extractBlockBody(rawBlock);
            assertThat(parsed.getTransactionBodies()).hasSameSizeAs(slice.transactions());
            for (int i = 0; i < slice.transactions().size(); i++) {
                var tx = slice.transactions().get(i);
                assertThat(parsed.getTransactionBodies().get(i).getTxHash())
                        .isEqualTo(TxUtil.calculateTxHash(tx.bodyBytes()));
                assertThat(TxCborUtil.assembleTxCbor(Era.Dijkstra, tx.bodyBytes(), tx.witnessesBytes(),
                        tx.auxiliaryDataBytes(), tx.valid())).isEqualTo(tx.transactionBytes());
            }
            if (Boolean.TRUE.equals(parsed.getHeader().getHeaderBody().getLeiosCertified())) {
                assertThat(parsed.getLeiosCertificate()).isNotNull();
                certifying++;
            }
            if (parsed.getHeader().getHeaderBody().getLeiosAnnouncement() != null) announced++;
            if (!parsed.getTransactionBodies().isEmpty()) withTransactions++;
        }
        assertThat(certifying).as("10026 and 10027").isEqualTo(2);
        assertThat(announced).as("9091, 10026 and 10027").isEqualTo(3);
        assertThat(withTransactions).as("9091 (354 txs) and 10428 (1 tx)").isEqualTo(2);
    }

    @Test
    void announcementReferencesTheEndorserBlock() throws Exception {
        //#10026 announces the EB that #10027 certifies: [eb hash, size of the EB bytes]
        var announcement = BlockSerializer.INSTANCE.deserialize(fixture("block-10026.hex"))
                .getHeader().getHeaderBody().getLeiosAnnouncement();
        EndorserBlock eb = endorserBlock();

        assertThat(announcement.getEbHash()).isEqualTo(EB_HASH);
        assertThat(eb.getComputedHash()).isEqualTo(EB_HASH);
        assertThat(announcement.getEbSize()).isEqualTo(eb.getCbor().length() / 2).isEqualTo(8534);
        assertThat(eb.txCount()).isEqualTo(237);
    }

    @Test
    void leiosFetchMessagesKeepTheirWireBytes() throws Exception {
        byte[] blockMsg = fixture("msg-leios-block-f951f865.hex");
        byte[] txsMsg = fixture("msg-leios-block-txs-f951f865.hex");

        MsgLeiosBlock block = (MsgLeiosBlock) LeiosFetchStateBase.deserialize(blockMsg);
        MsgLeiosBlockTxs txs = (MsgLeiosBlockTxs) LeiosFetchStateBase.deserialize(txsMsg);

        assertThat(block.serialize()).isEqualTo(blockMsg);
        assertThat(txs.serialize()).isEqualTo(txsMsg);
    }

    @Test
    void endorserBlockTransactionsMatchTheirReferences() throws Exception {
        //w42: [* #6.24(bytes .cbor block_transaction)]; the EB key is blake2b-256 of the wrapped bytes, the value
        //their length
        List<EndorserBlockTx> txs = endorserBlockTxs();
        EndorserBlock eb = endorserBlock();

        assertThat(txs).hasSize(237);
        for (int i = 0; i < txs.size(); i++) {
            assertThat(txs.get(i).isParsed()).isTrue();
            assertThat(txs.get(i).getTxHash()).isEqualTo(eb.getTxRefs().get(i).getTxHash());
            assertThat(txs.get(i).getTxCbor().length() / 2L).isEqualTo(eb.getTxRefs().get(i).getTxSize());
            //block encoding: [body, witnesses, auxiliary_data / nil, is_valid]
            assertThat(CborSlice.of(HexUtil.decodeHexString(txs.get(i).getTxCbor())).items(MajorType.ARRAY))
                    .hasSize(4);
        }
    }

    @Test
    void certifyingBlockIsCompletedWithTheEndorserBlockTransactions() throws Exception {
        Block certifying = BlockSerializer.INSTANCE.deserialize(fixture("block-10027.hex"));
        assertThat(certifying.getTransactionBodies()).isEmpty();

        Block complete = DijkstraBlockBodySerializer.INSTANCE.mergeEndorserBlockTransactions(certifying,
                LeiosEndorserBlockRef.builder().ebHash(EB_HASH).build(),
                endorserBlockTxs().stream().map(tx -> HexUtil.decodeHexString(tx.getTxCbor())).toList());

        assertThat(complete.getTransactionBodies()).extracting(TransactionBody::getTxHash)
                .containsExactlyElementsOf(dbSyncTxHashes());
        assertThat(complete.getTransactionWitness()).hasSize(237);
        assertThat(complete.getInvalidTransactions()).isEmpty();
        assertThat(complete.getLeiosEndorserBlock().getTxCount()).isEqualTo(237);
        assertThat(complete.getHeader()).isEqualTo(certifying.getHeader());
    }

    @Test
    void nodeToClientInlinedCertifyingBlock() throws Exception {
        //Synthetic N2C shape (as for w40a, ouroboros-consensus inlineLeiosClosure): the certifying block's header and
        //certificate with the EB transactions as its body, in a LocalChainSync MsgRollForward with a dummy tip
        List<CborSlice> block = CborSlice.of(fixture("block-10027.hex")).items(MajorType.ARRAY).get(1)
                .items(MajorType.ARRAY);
        List<CborSlice> body = block.get(1).items(MajorType.ARRAY);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.writeBytes(HexUtil.decodeHexString("820882"));
        raw.writeBytes(block.get(0).bytes());
        raw.write(0x83);
        raw.write(0x9f);
        for (EndorserBlockTx tx : endorserBlockTxs()) raw.writeBytes(HexUtil.decodeHexString(tx.getTxCbor()));
        raw.write(0xff);
        raw.writeBytes(body.get(1).bytes());
        raw.writeBytes(body.get(2).bytes());

        LocalRollForward rollForward = LocalRollForwardSerializer.INSTANCE.deserialize(rollForward(raw.toByteArray()));

        assertThat(rollForward.getBlock().getTransactionBodies()).extracting(TransactionBody::getTxHash)
                .containsExactlyElementsOf(dbSyncTxHashes());
        assertThat(rollForward.getBlock().getLeiosCertificate()).isNotNull();
    }

    private static EndorserBlock endorserBlock() throws Exception {
        MsgLeiosBlock msg = (MsgLeiosBlock) LeiosFetchStateBase.deserialize(fixture("msg-leios-block-f951f865.hex"));
        return EndorserBlockSerializer.INSTANCE.deserialize(msg.getEndorserBlock().getCbor());
    }

    private static List<EndorserBlockTx> endorserBlockTxs() throws Exception {
        MsgLeiosBlockTxs msg = (MsgLeiosBlockTxs) LeiosFetchStateBase.deserialize(
                fixture("msg-leios-block-txs-f951f865.hex"));
        return EndorserBlockTxListSerializer.INSTANCE.deserialize(msg.getTxList().getCbor());
    }

    private static List<String> dbSyncTxHashes() throws Exception {
        return Files.readAllLines(path("dbsync-10027-tx-hashes.txt")).stream().filter(line -> !line.isBlank()).toList();
    }

    /** LocalChainSync MsgRollForward: [2, #6.24(bytes .cbor block), tip]. */
    private static byte[] rollForward(byte[] block) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(HexUtil.decodeHexString("8302d818"));
        if (block.length < 65536) {
            out.writeBytes(new byte[]{0x59, (byte) (block.length >> 8), (byte) block.length});
        } else {
            out.writeBytes(new byte[]{0x5a, (byte) (block.length >> 24), (byte) (block.length >> 16),
                    (byte) (block.length >> 8), (byte) block.length});
        }
        out.writeBytes(block);
        out.writeBytes(HexUtil.decodeHexString("82821a00031fe95820" + "00".repeat(32) + "19272b")); //tip
        return out.toByteArray();
    }

    private static byte[] fixture(String name) throws Exception {
        return HexUtil.decodeHexString(Files.readString(path(name)).trim());
    }

    private static Path path(String name) throws Exception {
        return Path.of(MusashiW42FixturesTest.class.getResource("/leios/w42/" + name).toURI());
    }
}
