package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.yaci.node.api.utxo.model.AssetAmount;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UtxoMapperTest {

    @Test
    void toCclUtxo_convertsLovelaceAndAssets() {
        Utxo yaciUtxo = new Utxo(
                new Outpoint("abc123", 0),
                "addr_test1qz...",
                BigInteger.valueOf(5_000_000),
                List.of(
                        new AssetAmount("aabb00", "546f6b656e", BigInteger.valueOf(100))
                ),
                null, null, null, null, false, 10, 1, "blockhash"
        );

        var ccl = UtxoMapper.toCclUtxo(yaciUtxo);

        assertThat(ccl.getTxHash()).isEqualTo("abc123");
        assertThat(ccl.getOutputIndex()).isEqualTo(0);
        assertThat(ccl.getAddress()).isEqualTo("addr_test1qz...");
        assertThat(ccl.getAmount()).hasSize(2);

        Amount lovelace = ccl.getAmount().get(0);
        assertThat(lovelace.getUnit()).isEqualTo("lovelace");
        assertThat(lovelace.getQuantity()).isEqualTo(BigInteger.valueOf(5_000_000));

        Amount asset = ccl.getAmount().get(1);
        assertThat(asset.getUnit()).isEqualTo("aabb00546f6b656e");
        assertThat(asset.getQuantity()).isEqualTo(BigInteger.valueOf(100));
    }

    @Test
    void toCclUtxo_convertsDatumAndScriptRef() {
        byte[] datumCbor = new byte[]{(byte) 0xd8, 0x79, (byte) 0xa0};
        Utxo yaciUtxo = new Utxo(
                new Outpoint("def456", 1),
                "addr_test1...",
                BigInteger.valueOf(2_000_000),
                List.of(),
                "datumhash123",
                datumCbor,
                null,
                "scripthash456",
                false, 20, 2, "blockhash2"
        );

        var ccl = UtxoMapper.toCclUtxo(yaciUtxo);

        assertThat(ccl.getDataHash()).isEqualTo("datumhash123");
        assertThat(ccl.getInlineDatum()).isEqualTo("d879a0");
        assertThat(ccl.getReferenceScriptHash()).isEqualTo("scripthash456");
    }

    @Test
    void toCclUtxo_nullReturnsNull() {
        assertThat(UtxoMapper.toCclUtxo(null)).isNull();
    }

    @Test
    void toCclUtxo_noAssets() {
        Utxo yaciUtxo = new Utxo(
                new Outpoint("aaa111", 2),
                "addr_test1...",
                BigInteger.valueOf(10_000_000),
                null,
                null, null, null, null, false, 0, 0, ""
        );

        var ccl = UtxoMapper.toCclUtxo(yaciUtxo);

        assertThat(ccl.getAmount()).hasSize(1);
        assertThat(ccl.getAmount().get(0).getUnit()).isEqualTo("lovelace");
    }
}
