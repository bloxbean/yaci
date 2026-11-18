package com.bloxbean.cardano.yaci.core.model.serializers.leios;

import co.nstant.in.cbor.model.MajorType;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.LocalRollForward;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.LocalRollForwardSerializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.TxUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Node-to-client: for a certifying block the Musashi w40a node splices the certified EB's transactions into the
 * block body (ouroboros-consensus cbd10033: the ChainSync server's {@code resolve} calls {@code inlineLeiosClosure}
 * from {@code Shelley/Ledger/Leios.hs}; the body's transaction list is replaced, the header and certificate are
 * kept) and serves it over LocalChainSync.
 * This test rebuilds that message from live data: block #156613 and the 345 transactions of the EB it certifies
 * (3bca1a2b…), and checks Yaci delivers them all with the transaction ids db-sync shows.
 */
class N2CInlinedCertifyingBlockTest {

    @Test
    void parsesCertifyingBlockWithInlinedEndorserBlockTransactions() throws Exception {
        byte[] wireBlock = fixture("certifying-block-156613.hex");
        byte[] txList = fixture("eb-3bca1a2b-txs.hex"); //LeiosFetch tx list: [* bytes(mempool tx)]
        Block onWire = BlockSerializer.INSTANCE.deserialize(wireBlock);
        assertTrue(onWire.getTransactionBodies().isEmpty()); //over node-to-node the certifying block has no txs

        LocalRollForward rollForward = LocalRollForwardSerializer.INSTANCE.deserialize(
                rollForward(inline(wireBlock, txList)));
        Block block = rollForward.getBlock();

        assertEquals(Era.Dijkstra, block.getEra());
        assertEquals(156613, block.getHeader().getHeaderBody().getBlockNumber());
        assertEquals(Boolean.TRUE, block.getHeader().getHeaderBody().getLeiosCertified());
        assertNotNull(block.getLeiosCertificate());
        assertEquals(345, block.getTransactionBodies().size());
        assertEquals(345, block.getTransactionWitness().size());
        //db-sync (KleioScan): block 156613 index 343 and 344
        assertEquals("69dd28ca8b67d4ef3ecbd83707122cc54e0d81ca6f29fc466bac1a69d763b51c",
                block.getTransactionBodies().get(343).getTxHash());
        assertEquals("e13b3defba2d8346dc7c4933ee1ac9888112c1ac662ce1c3e6eb5c145221df0e",
                block.getTransactionBodies().get(344).getTxHash());
        assertTrue(block.getInvalidTransactions().isEmpty());
    }

    /** Builds the block the node serves over node-to-client: same header and certificate, EB txs as the tx list. */
    private static byte[] inline(byte[] wireBlock, byte[] txList) throws Exception {
        List<CborSlice> block = CborSlice.of(wireBlock).items(MajorType.ARRAY).get(1).items(MajorType.ARRAY);
        List<CborSlice> body = block.get(1).items(MajorType.ARRAY);
        List<CborSlice> ebTxs = CborSlice.of(txList).items(MajorType.ARRAY);

        //The node writes a list of more than 23 transactions as an indefinite-length array (encodeFoldableEncoder)
        ByteArrayOutputStream txs = new ByteArrayOutputStream();
        txs.write(0x9f);
        for (CborSlice ebTx : ebTxs) {
            //mempool [body, witnesses, aux] -> block_transaction [body, witnesses, aux, is_valid = true]
            List<CborSlice> parts = CborSlice.of(innerBytes(ebTx.bytes())).items(MajorType.ARRAY);
            assertEquals(3, parts.size());
            txs.write(0x84);
            for (CborSlice part : parts) txs.writeBytes(part.bytes());
            txs.write(0xf5);
        }
        txs.write(0xff);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(HexUtil.decodeHexString("820882"));
        out.writeBytes(block.get(0).bytes());                                   //header, unchanged
        out.write(0x83);
        out.writeBytes(txs.toByteArray());
        out.writeBytes(body.get(1).bytes());                                    //leios certificate
        out.writeBytes(body.get(2).bytes());                                    //peras certificate
        return out.toByteArray();
    }

    /** LocalChainSync MsgRollForward: [2, #6.24(bytes .cbor block), tip]. */
    private static byte[] rollForward(byte[] block) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(HexUtil.decodeHexString("8302d818"));
        out.writeBytes(byteStringHeader(block.length));
        out.writeBytes(block);
        out.writeBytes(HexUtil.decodeHexString("82821a0035575f5820" + "00".repeat(32) + "1a000263e5")); //tip
        return out.toByteArray();
    }

    private static byte[] innerBytes(byte[] byteString) throws Exception {
        //a definite byte string: skip its header
        int ai = byteString[0] & 0x1f;
        int header = ai < 24 ? 1 : ai == 24 ? 2 : ai == 25 ? 3 : ai == 26 ? 5 : 9;
        return java.util.Arrays.copyOfRange(byteString, header, byteString.length);
    }

    private static byte[] byteStringHeader(int n) {
        return n < 24 ? new byte[]{(byte) (0x40 | n)} : n < 256 ? new byte[]{0x58, (byte) n}
                : n < 65536 ? new byte[]{0x59, (byte) (n >> 8), (byte) n}
                : new byte[]{0x5a, (byte) (n >> 24), (byte) (n >> 16), (byte) (n >> 8), (byte) n};
    }

    private static byte[] fixture(String name) throws Exception {
        return HexUtil.decodeHexString(Files.readString(
                Path.of(N2CInlinedCertifyingBlockTest.class.getResource("/leios/w40a/" + name).toURI())).trim());
    }
}
