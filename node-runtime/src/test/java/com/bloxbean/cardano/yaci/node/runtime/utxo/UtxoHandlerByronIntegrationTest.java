package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.byron.ByronAddress;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockBody;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTx;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTxOut;
import com.bloxbean.cardano.yaci.core.model.byron.payload.ByronTxPayload;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yaci.node.runtime.events.ByronMainBlockAppliedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UtxoHandlerByronIntegrationTest {

    static class SpyWriter implements UtxoStoreWriter {
        MultiEraBlockTxs last;
        int applyCount;
        @Override public void apply(MultiEraBlockTxs blockTxs) { last = blockTxs; applyCount++; }
        @Override public void rollbackTo(com.bloxbean.cardano.yaci.node.runtime.events.RollbackEvent e) {}
        @Override public void reconcile(com.bloxbean.cardano.yaci.core.storage.ChainState chainState) {}
        @Override public boolean isEnabled() { return true; }
    }

    @Test
    void handler_normalizesByronMain_andCallsApply() {
        // Build a simple Byron main block with one tx, one output
        ByronTx btx = ByronTx.builder()
                .txHash("aa".repeat(32))
                .outputs(List.of(
                        ByronTxOut.builder()
                                .address(ByronAddress.builder().base58Raw("Ae2tdPwUPEZByronAddrSpy").build())
                                .amount(BigInteger.valueOf(4242))
                                .build()
                ))
                .build();
        ByronTxPayload payload = ByronTxPayload.builder().transaction(btx).build();
        ByronMainBlock byron = ByronMainBlock.builder()
                .body(ByronBlockBody.builder().txPayload(List.of(payload)).build())
                .build();

        SpyWriter spy = new SpyWriter();
        EventBus bus = new SimpleEventBus();
        new UtxoEventHandler(bus, spy);

        long slot = 1000L;
        long blockNo = 500L;
        String hash = "bb".repeat(32);

        bus.publish(new ByronMainBlockAppliedEvent(slot, blockNo, hash, byron),
                EventMetadata.builder().origin("test").slot(slot).blockNo(blockNo).blockHash(hash).build(),
                PublishOptions.builder().build());

        assertEquals(1, spy.applyCount);
        assertNotNull(spy.last);
        assertEquals(slot, spy.last.slot);
        assertEquals(blockNo, spy.last.blockNumber);
        assertEquals(hash, spy.last.blockHash);
        assertEquals(1, spy.last.txs.size());
        MultiEraTx nt = spy.last.txs.get(0);
        assertEquals("aa".repeat(32), nt.txHash);
        assertEquals(1, nt.outputs.size());
        assertEquals("Ae2tdPwUPEZByronAddrSpy", nt.outputs.get(0).address);
        assertEquals(BigInteger.valueOf(4242), nt.outputs.get(0).lovelace);
    }
}

