package com.bloxbean.cardano.yaci.node.runtime.utxo;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.node.runtime.chain.DirectRocksDBChainState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReconcileByronEbNoopTest {
    private File tempDir;
    private DirectRocksDBChainState chain;
    private ClassicUtxoStore store;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("yaci-utxo-eb-test").toFile();
        chain = new DirectRocksDBChainState(tempDir.getAbsolutePath());
        Logger log = LoggerFactory.getLogger(ReconcileByronEbNoopTest.class);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("yaci.node.utxo.enabled", true);
        cfg.put("yaci.node.utxo.pruneDepth", 3);
        cfg.put("yaci.node.utxo.rollbackWindow", 4);
        cfg.put("yaci.node.utxo.pruneBatchSize", 100);
        store = new ClassicUtxoStore(chain, log, cfg);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (chain != null) chain.close();
        if (tempDir != null) deleteRecursively(tempDir);
    }

    private void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) deleteRecursively(c);
        }
        f.delete();
    }

    private static byte[] buildMinimalByronEbCbor(long epoch, long absoluteSlot) {
        // headerArr = [protocolMagic, prevBlockId(bstr), bodyProof(any), consensus([epoch, [difficulty]]), extraData(any)]
        Array headerArr = new Array();
        headerArr.add(new UnsignedInteger(764824073)); // protocol magic (mainnet)
        headerArr.add(new ByteString(new byte[32])); // prev block id
        headerArr.add(new ByteString(new byte[]{0x01})); // body proof placeholder
        Array cons = new Array();
        cons.add(new UnsignedInteger(epoch));
        Array diff = new Array();
        diff.add(new UnsignedInteger(absoluteSlot)); // use slot as difficulty placeholder
        cons.add(diff);
        headerArr.add(cons);
        headerArr.add(new ByteString(new byte[]{0x02})); // extra data placeholder

        // bodyArr (unused by serializer)
        Array bodyArr = new Array();

        Array mainBlkArray = new Array();
        mainBlkArray.add(headerArr);
        mainBlkArray.add(bodyArr);

        Array top = new Array();
        top.add(new UnsignedInteger(Era.Byron.getValue())); // era value (Byron)
        top.add(mainBlkArray);

        return CborSerializationUtil.serialize(top);
    }

    @Test
    void reconcile_withByronEbBlock_doesNothing() throws Exception {
        long slot = 10;
        long blockNo = 1;
        byte[] ebb = buildMinimalByronEbCbor(0, slot);

        // Compute EB block hash the same way deserializer does: blake2b256(serialize([0, headerArr]))
        Array top = (Array) CborSerializationUtil.deserializeOne(ebb);
        Array main = (Array) top.getDataItems().get(1);
        Array headerArr = (Array) main.getDataItems().get(0);
        Array hashArr = new Array();
        hashArr.add(new UnsignedInteger(0));
        hashArr.add(headerArr);
        byte[] hashBytes = Blake2bUtil.blake2bHash256(CborSerializationUtil.serialize(hashArr));

        chain.storeBlock(hashBytes, blockNo, slot, ebb);

        // Before reconcile: store has no UTXOs
        assertTrue(store.getUtxosByAddress("addr_test1vpzeroutxo", 1, 10).isEmpty());

        store.reconcile(chain);

        // After reconcile: still no UTXOs (EB is a no-op)
        assertTrue(store.getUtxosByAddress("addr_test1vpzeroutxo", 1, 10).isEmpty());
    }
}

