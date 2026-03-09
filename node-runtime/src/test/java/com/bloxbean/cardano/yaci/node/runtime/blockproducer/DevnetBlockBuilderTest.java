package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class DevnetBlockBuilderTest {

    private DevnetBlockBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new DevnetBlockBuilder();
    }

    @Test
    void buildGenesisBlock_shouldBeDeserializableByBlockSerializer() {
        var result = builder.buildBlock(0, 0, null, List.of());

        assertNotNull(result.blockCbor());
        assertNotNull(result.blockHash());
        assertThat(result.blockHash()).hasSize(32);
        assertEquals(0, result.blockNumber());
        assertEquals(0, result.slot());

        // Verify BlockSerializer can parse it
        Block block = BlockSerializer.INSTANCE.deserialize(result.blockCbor());
        assertNotNull(block);
        assertNotNull(block.getHeader());
        assertEquals(0, block.getHeader().getHeaderBody().getBlockNumber());
        assertEquals(0, block.getHeader().getHeaderBody().getSlot());
        assertNull(block.getHeader().getHeaderBody().getPrevHash());
        assertThat(block.getTransactionBodies()).isEmpty();
    }

    @Test
    void buildGenesisBlock_wrappedHeaderShouldBeDeserializableByBlockHeaderSerializer() {
        var result = builder.buildBlock(0, 0, null, List.of());

        // Wrapped header is [era, h'<serialized_header>']
        // BlockHeaderSerializer.deserializeDI() expects the ByteString element
        var wrappedDI = CborSerializationUtil.deserializeOne(result.wrappedHeaderCbor());
        Array wrappedArray = (Array) wrappedDI;
        // Element 1 is the ByteString header
        var headerByteString = wrappedArray.getDataItems().get(1);

        BlockHeader header = BlockHeaderSerializer.INSTANCE.deserializeDI(headerByteString);
        assertNotNull(header);
        assertEquals(0, header.getHeaderBody().getBlockNumber());
        assertEquals(0, header.getHeaderBody().getSlot());
        assertNull(header.getHeaderBody().getPrevHash());
    }

    @Test
    void buildBlock_blockHashMatchesHeaderSerializerDerivedHash() {
        var result = builder.buildBlock(1, 100, new byte[32], List.of());

        // Parse using BlockSerializer and check that the derived block hash matches
        Block block = BlockSerializer.INSTANCE.deserialize(result.blockCbor());
        String derivedHash = block.getHeader().getHeaderBody().getBlockHash();
        String expectedHash = HexUtil.encodeHexString(result.blockHash());

        assertEquals(expectedHash, derivedHash);
    }

    @Test
    void buildBlock_chainContinuity() {
        // Build genesis
        var genesis = builder.buildBlock(0, 0, null, List.of());

        // Build block 1 with genesis hash as prevHash
        var block1 = builder.buildBlock(1, 10, genesis.blockHash(), List.of());

        // Parse block 1 and verify prevHash matches genesis block hash
        Block parsed = BlockSerializer.INSTANCE.deserialize(block1.blockCbor());
        String prevHash = parsed.getHeader().getHeaderBody().getPrevHash();
        String genesisHash = HexUtil.encodeHexString(genesis.blockHash());

        assertEquals(genesisHash, prevHash);
    }

    @Test
    void buildBlock_withTransaction() throws Exception {
        // Build a minimal but valid-looking transaction CBOR: [body, witnesses, true, null]
        byte[] txCbor = buildSampleTxCbor();

        var result = builder.buildBlock(1, 50, new byte[32], List.of(txCbor));

        // Verify BlockSerializer can parse it and finds one transaction
        Block block = BlockSerializer.INSTANCE.deserialize(result.blockCbor());
        assertThat(block.getTransactionBodies()).hasSize(1);
    }

    @Test
    void buildBlock_withMultipleTransactions() throws Exception {
        byte[] tx1 = buildSampleTxCbor();
        byte[] tx2 = buildSampleTxCbor();

        var result = builder.buildBlock(2, 100, new byte[32], List.of(tx1, tx2));

        Block block = BlockSerializer.INSTANCE.deserialize(result.blockCbor());
        assertThat(block.getTransactionBodies()).hasSize(2);
    }

    @Test
    void buildBlock_differentBlocksHaveDifferentHashes() {
        var block1 = builder.buildBlock(1, 10, new byte[32], List.of());
        var block2 = builder.buildBlock(2, 20, block1.blockHash(), List.of());

        assertFalse(Arrays.equals(block1.blockHash(), block2.blockHash()));
    }

    @Test
    void buildBlock_conwayEra() {
        var result = builder.buildBlock(0, 0, null, List.of());

        // Verify era tag is 7 (Conway)
        var di = CborSerializationUtil.deserializeOne(result.blockCbor());
        Array arr = (Array) di;
        int era = ((UnsignedInteger) arr.getDataItems().get(0)).getValue().intValue();
        assertEquals(7, era);
    }

    /**
     * Build a minimal sample transaction CBOR: [body_map, witnesses_map, true, null]
     */
    private byte[] buildSampleTxCbor() throws Exception {
        // Minimal tx body: {0: [[h'...', 0]], 1: [{0: h'...', 1: 1000000}], 2: 200000}
        Map txBody = new Map();
        // inputs: [[txhash, index]]
        Array inputs = new Array();
        Array input = new Array();
        input.add(new ByteString(new byte[32])); // tx hash
        input.add(new UnsignedInteger(0));       // index
        inputs.add(input);
        txBody.put(new UnsignedInteger(0), inputs);

        // outputs: [{0: address, 1: amount}]
        Array outputs = new Array();
        Map output = new Map();
        output.put(new UnsignedInteger(0), new ByteString(new byte[28])); // address hash
        output.put(new UnsignedInteger(1), new UnsignedInteger(1000000));
        outputs.add(output);
        txBody.put(new UnsignedInteger(1), outputs);

        // fee
        txBody.put(new UnsignedInteger(2), new UnsignedInteger(200000));

        // witnesses: empty map
        Map witnesses = new Map();

        // Build: [body, witnesses, true, null]
        Array tx = new Array();
        tx.add(txBody);
        tx.add(witnesses);
        tx.add(co.nstant.in.cbor.model.SimpleValue.TRUE);
        tx.add(co.nstant.in.cbor.model.SimpleValue.NULL);

        return CborSerializationUtil.serialize(tx);
    }
}
