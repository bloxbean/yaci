package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.byron.ByronAddress;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockBody;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTx;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTxOut;
import com.bloxbean.cardano.yaci.core.model.byron.payload.ByronTxPayload;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UtxoTxNormalizerTest {

    @Test
    void fromShelley_normalizesInputsOutputs() {
        // Build a simple Shelley block with one valid TX
        TransactionInput in = TransactionInput.builder().transactionId("11".repeat(32)).index(0).build();
        Amount lovelace = Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(10)).build();
        Amount ma = Amount.builder().policyId("aa".repeat(28)).assetNameBytes(new byte[]{0x01, 0x02}).quantity(BigInteger.valueOf(5)).build();
        TransactionOutput out = TransactionOutput.builder()
                .address("addr_test1...xyz")
                .amounts(List.of(lovelace, ma))
                .datumHash(null)
                .inlineDatum(null)
                .scriptRef(null)
                .build();
        TransactionBody tx = TransactionBody.builder()
                .txHash("22".repeat(32))
                .inputs(Set.of(in))
                .outputs(List.of(out))
                .build();

        BlockHeader header = BlockHeader.builder()
                .headerBody(HeaderBody.builder().slot(100).blockNumber(1).blockHash("33".repeat(32)).build())
                .build();
        Block block = Block.builder().era(Era.Babbage).header(header).transactionBodies(List.of(tx)).build();

        MultiEraBlockTxs nb = UtxoTxNormalizer.fromShelley(Era.Babbage, 100, 1, "33".repeat(32), block);

        assertNotNull(nb);
        assertEquals(100, nb.slot);
        assertEquals(1, nb.blockNumber);
        assertEquals("33".repeat(32), nb.blockHash);
        assertEquals(1, nb.txs.size());
        MultiEraTx nt = nb.txs.get(0);
        assertFalse(nt.invalid);
        assertEquals("22".repeat(32), nt.txHash);
        assertEquals(1, nt.inputs.size());
        assertEquals("11".repeat(32), nt.inputs.get(0).txHash);
        assertEquals(0, nt.inputs.get(0).index);
        assertEquals(1, nt.outputs.size());
        MultiEraOutput no = nt.outputs.get(0);
        assertEquals("addr_test1...xyz", no.address);
        assertEquals(BigInteger.valueOf(10), no.lovelace);
        assertEquals(1, no.assets.size()); // lovelace stripped, only multi-asset remains
        assertEquals("aa".repeat(28), no.assets.get(0).getPolicyId());
    }

    @Test
    void fromByron_normalizesInputsOutputs() {
        ByronTx btx = ByronTx.builder()
                .txHash("44".repeat(32))
                .inputs(List.of(
                        com.bloxbean.cardano.yaci.core.model.byron.ByronTxIn.builder().txId("55".repeat(32)).index(1).build()
                ))
                .outputs(List.of(
                        ByronTxOut.builder().address(ByronAddress.builder().base58Raw("Ae2tdPwUPEZByronAddressMock").build()).amount(BigInteger.valueOf(1234)).build()
                ))
                .build();

        ByronTxPayload payload = ByronTxPayload.builder().transaction(btx).build();
        ByronBlockBody body = ByronBlockBody.builder().txPayload(List.of(payload)).build();
        ByronMainBlock byron = ByronMainBlock.builder().body(body).build();

        MultiEraBlockTxs nb = UtxoTxNormalizer.fromByron(10, 2, "66".repeat(32), byron);
        assertNotNull(nb);
        assertEquals(10, nb.slot);
        assertEquals(2, nb.blockNumber);
        assertEquals(1, nb.txs.size());
        MultiEraTx nt = nb.txs.get(0);
        assertEquals("44".repeat(32), nt.txHash);
        assertEquals(1, nt.inputs.size());
        assertEquals("55".repeat(32), nt.inputs.get(0).txHash);
        assertEquals(1, nt.inputs.get(0).index);
        assertEquals(1, nt.outputs.size());
        MultiEraOutput no = nt.outputs.get(0);
        assertEquals("Ae2tdPwUPEZByronAddressMock", no.address);
        assertEquals(BigInteger.valueOf(1234), no.lovelace);
        assertTrue(no.assets == null || no.assets.isEmpty());
    }

    @Test
    void fromShelley_handlesInvalidTx_collateralOnly() {
        // Build an invalid Shelley tx with collateral inputs and a collateral return output
        TransactionInput collIn = TransactionInput.builder().transactionId("77".repeat(32)).index(2).build();
        Amount lovelace = Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(777)).build();
        TransactionOutput collRet = TransactionOutput.builder()
                .address("addr_test1...collret")
                .amounts(List.of(lovelace))
                .build();

        TransactionBody txInvalid = TransactionBody.builder()
                .txHash("88".repeat(32))
                .collateralInputs(Set.of(collIn))
                .collateralReturn(collRet)
                .build();

        BlockHeader header = BlockHeader.builder()
                .headerBody(HeaderBody.builder().slot(200).blockNumber(5).blockHash("99".repeat(32)).build())
                .build();
        Block block = Block.builder()
                .era(Era.Babbage)
                .header(header)
                .transactionBodies(List.of(txInvalid))
                .invalidTransactions(List.of(0))
                .build();

        MultiEraBlockTxs nb = UtxoTxNormalizer.fromShelley(Era.Babbage, 200, 5, "99".repeat(32), block);

        assertNotNull(nb);
        assertEquals(1, nb.txs.size());
        MultiEraTx nt = nb.txs.get(0);
        assertTrue(nt.invalid);
        // Should not include regular inputs/outputs for invalid tx
        assertTrue(nt.inputs == null || nt.inputs.isEmpty());
        assertTrue(nt.outputs == null || nt.outputs.isEmpty());
        // Collateral inputs present
        assertNotNull(nt.collateralInputs);
        assertEquals(1, nt.collateralInputs.size());
        assertEquals("77".repeat(32), nt.collateralInputs.get(0).txHash);
        assertEquals(2, nt.collateralInputs.get(0).index);
        // Collateral return populated
        assertNotNull(nt.collateralReturn);
        assertEquals("addr_test1...collret", nt.collateralReturn.address);
        assertEquals(BigInteger.valueOf(777), nt.collateralReturn.lovelace);
        assertTrue(nt.collateralReturn.collateralReturn);
    }
}
