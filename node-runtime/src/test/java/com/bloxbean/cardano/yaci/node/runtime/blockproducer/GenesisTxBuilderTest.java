package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GenesisTxBuilderTest {

    @Test
    void emptyFunds_returnsEmptyList() {
        assertThat(GenesisTxBuilder.buildGenesisTransactions(Map.of())).isEmpty();
        assertThat(GenesisTxBuilder.buildGenesisTransactions(null)).isEmpty();
    }

    @Test
    void singleFund_producesOneTx() {
        // Use a 57-byte Shelley address hex (enterprise address, network tag 0x60 + 28 bytes key hash)
        String addrHex = "60" + "aa".repeat(28);
        Map<String, BigInteger> funds = Map.of(addrHex, BigInteger.valueOf(10_000_000_000L));

        List<byte[]> txs = GenesisTxBuilder.buildGenesisTransactions(funds);
        assertThat(txs).hasSize(1);

        // Verify CBOR structure: [body, witnesses, is_valid, aux_data]
        DataItem di = CborSerializationUtil.deserializeOne(txs.get(0));
        Array txArray = (Array) di;
        assertThat(txArray.getDataItems()).hasSize(4);

        // body is a map
        co.nstant.in.cbor.model.Map txBody = (co.nstant.in.cbor.model.Map) txArray.getDataItems().get(0);

        // Check outputs (key 1)
        Array outputs = (Array) txBody.get(new UnsignedInteger(1));
        assertThat(outputs.getDataItems()).hasSize(1);

        // Check output address matches
        co.nstant.in.cbor.model.Map output = (co.nstant.in.cbor.model.Map) outputs.getDataItems().get(0);
        ByteString addrBytes = (ByteString) output.get(new UnsignedInteger(0));
        assertThat(HexUtil.encodeHexString(addrBytes.getBytes())).isEqualTo(addrHex);

        // Check amount
        UnsignedInteger amount = (UnsignedInteger) output.get(new UnsignedInteger(1));
        assertThat(amount.getValue().longValue()).isEqualTo(10_000_000_000L);

        // Check fee is 0
        UnsignedInteger fee = (UnsignedInteger) txBody.get(new UnsignedInteger(2));
        assertThat(fee.getValue().longValue()).isEqualTo(0L);
    }

    @Test
    void multipleFunds_allInOneTx() {
        Map<String, BigInteger> funds = new LinkedHashMap<>();
        funds.put("60" + "aa".repeat(28), BigInteger.valueOf(5_000_000_000L));
        funds.put("60" + "bb".repeat(28), BigInteger.valueOf(3_000_000_000L));

        List<byte[]> txs = GenesisTxBuilder.buildGenesisTransactions(funds);
        assertThat(txs).hasSize(1);

        DataItem di = CborSerializationUtil.deserializeOne(txs.get(0));
        co.nstant.in.cbor.model.Map txBody = (co.nstant.in.cbor.model.Map) ((Array) di).getDataItems().get(0);
        Array outputs = (Array) txBody.get(new UnsignedInteger(1));
        assertThat(outputs.getDataItems()).hasSize(2);
    }

    @Test
    void moreThanMaxOutputs_splitsIntoMultipleTxs() {
        Map<String, BigInteger> funds = new LinkedHashMap<>();
        for (int i = 0; i < GenesisTxBuilder.MAX_OUTPUTS_PER_TX + 5; i++) {
            String addrHex = "60" + String.format("%02x", i % 256).repeat(28);
            funds.put(addrHex + String.format("%04d", i), BigInteger.valueOf(1_000_000L));
        }

        List<byte[]> txs = GenesisTxBuilder.buildGenesisTransactions(funds);
        assertThat(txs).hasSize(2);

        // First tx should have MAX_OUTPUTS_PER_TX outputs
        DataItem di1 = CborSerializationUtil.deserializeOne(txs.get(0));
        co.nstant.in.cbor.model.Map body1 = (co.nstant.in.cbor.model.Map) ((Array) di1).getDataItems().get(0);
        Array outputs1 = (Array) body1.get(new UnsignedInteger(1));
        assertThat(outputs1.getDataItems()).hasSize(GenesisTxBuilder.MAX_OUTPUTS_PER_TX);

        // Second tx should have the remaining 5
        DataItem di2 = CborSerializationUtil.deserializeOne(txs.get(1));
        co.nstant.in.cbor.model.Map body2 = (co.nstant.in.cbor.model.Map) ((Array) di2).getDataItems().get(0);
        Array outputs2 = (Array) body2.get(new UnsignedInteger(1));
        assertThat(outputs2.getDataItems()).hasSize(5);
    }

    @Test
    void genesisTxRoundTripsThroughBlockSerializer() {
        String addrHex = "60" + "cc".repeat(28);
        Map<String, BigInteger> funds = Map.of(addrHex, BigInteger.valueOf(2_000_000_000L));

        List<byte[]> txs = GenesisTxBuilder.buildGenesisTransactions(funds);

        // Build a block containing the genesis transaction
        DevnetBlockBuilder blockBuilder = new DevnetBlockBuilder();
        var result = blockBuilder.buildBlock(0, 0, null, txs);

        // Verify BlockSerializer can parse the block
        Block block = BlockSerializer.INSTANCE.deserialize(result.blockCbor());
        assertThat(block).isNotNull();
        assertThat(block.getTransactionBodies()).hasSize(1);
    }

    @Test
    void genesisTx_usesZeroHashInput() {
        String addrHex = "60" + "dd".repeat(28);
        Map<String, BigInteger> funds = Map.of(addrHex, BigInteger.valueOf(1_000_000L));

        List<byte[]> txs = GenesisTxBuilder.buildGenesisTransactions(funds);
        DataItem di = CborSerializationUtil.deserializeOne(txs.get(0));
        co.nstant.in.cbor.model.Map txBody = (co.nstant.in.cbor.model.Map) ((Array) di).getDataItems().get(0);

        // Check input uses zero hash
        Array inputs = (Array) txBody.get(new UnsignedInteger(0));
        Array input = (Array) inputs.getDataItems().get(0);
        ByteString txHash = (ByteString) input.getDataItems().get(0);
        assertThat(txHash.getBytes()).isEqualTo(new byte[32]);
    }
}
