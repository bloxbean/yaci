package com.bloxbean.cardano.yaci.node.runtime.utxo;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.address.util.AddressUtil;
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
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class ReconcileShelleyApplyTest {
    private File tempDir;
    private DirectRocksDBChainState chain;
    private ClassicUtxoStore store;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("yaci-utxo-shelley-reconcile").toFile();
        chain = new DirectRocksDBChainState(tempDir.getAbsolutePath());
        Logger log = LoggerFactory.getLogger(ReconcileShelleyApplyTest.class);
        java.util.Map<String, Object> cfg = new HashMap<>();
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

    private static byte[] buildSimpleShelleyBlockBytes(long slot, long blockNo, String bech32Addr, BigInteger lovelace) throws Exception {
        // Build a minimal Babbage-like block with one tx and one output
        // Header body (post-babbage)
        Array headerBody = new Array();
        headerBody.add(new UnsignedInteger(blockNo)); // block number
        headerBody.add(new UnsignedInteger(slot)); // slot
        headerBody.add(SimpleValue.NULL); // prev hash
        headerBody.add(new ByteString(new byte[]{0x01})); // issuerVkey
        headerBody.add(new ByteString(new byte[]{0x02})); // vrfVkey
        Array vrfRes = new Array();
        vrfRes.add(new ByteString(new byte[]{0x03}));
        vrfRes.add(new ByteString(new byte[]{0x04}));
        headerBody.add(vrfRes);
        headerBody.add(new UnsignedInteger(0)); // block body size (not validated)
        headerBody.add(new ByteString(new byte[32])); // block body hash placeholder
        Array opCert = new Array();
        opCert.add(new ByteString(new byte[]{0x05}));
        opCert.add(new UnsignedInteger(0));
        opCert.add(new UnsignedInteger(0));
        opCert.add(new ByteString(new byte[]{0x06}));
        headerBody.add(opCert);
        Array protoVer = new Array();
        protoVer.add(new UnsignedInteger(7));
        protoVer.add(new UnsignedInteger(0));
        headerBody.add(protoVer);

        Array headerArr = new Array();
        headerArr.add(headerBody);
        headerArr.add(new ByteString(new byte[]{0x0A})); // body signature

        // Build a single tx body map with 1 output (post-Alonzo Map form)
        Map txBody = new Map();
        txBody.put(new UnsignedInteger(0), new Array()); // inputs []
        // Output map: {0: addr(bytes), 1: value(lovelace)}
        Map outMap = new Map();
        byte[] addrBytes = AddressUtil.addressToBytes(bech32Addr);
        outMap.put(new UnsignedInteger(0), new ByteString(addrBytes));
        outMap.put(new UnsignedInteger(1), new UnsignedInteger(lovelace));
        Array outputs = new Array();
        outputs.add(outMap);
        txBody.put(new UnsignedInteger(1), outputs);

        Array txBodies = new Array();
        txBodies.add(txBody);

        Array witnesses = new Array(); // []
        Map aux = new Map(); // {}
        Array invalid = new Array(); // []

        Array blockArray = new Array();
        blockArray.add(headerArr);
        blockArray.add(txBodies);
        blockArray.add(witnesses);
        blockArray.add(aux);
        blockArray.add(invalid);

        Array top = new Array();
        top.add(new UnsignedInteger(Era.Babbage.getValue()));
        top.add(blockArray);

        return CborSerializationUtil.serialize(top);
    }

    @Test
    void reconcile_appliesShelleyBlock_fromStoredBytes() throws Exception {
        long slot1 = 10L, blockNo1 = 1L;
        // store a dummy first block to satisfy continuity
        byte[] dummy = new byte[]{0x55};
        byte[] dummyHash = Blake2bUtil.blake2bHash256("dummy".getBytes(StandardCharsets.UTF_8));
        chain.storeBlock(dummyHash, blockNo1, slot1, dummy);

        // Build and store a minimal Shelley block #2 with one UTXO
        String addr = "addr_test1vpfwv0ezc5g8a4mkku8hhy3y3vp92t7s3ul8g778g5yegsgalc6gc";
        BigInteger lovelace = BigInteger.valueOf(1500);
        long slot2 = 20L, blockNo2 = 2L;
        byte[] block2 = buildSimpleShelleyBlockBytes(slot2, blockNo2, addr, lovelace);

        // Compute header-derived block hash for consistency
        Array top = (Array) CborSerializationUtil.deserializeOne(block2);
        Array blockArray = (Array) top.getDataItems().get(1);
        Array headerArr = (Array) blockArray.getDataItems().get(0);
        byte[] blockHash = Blake2bUtil.blake2bHash256(CborSerializationUtil.serialize(headerArr));
        chain.storeBlock(blockHash, blockNo2, slot2, block2);

        // Now reconcile
        store.reconcile(chain);

        var list = store.getUtxosByAddress(addr, 1, 10);
        assertEquals(1, list.size(), "expected one UTXO created by stored Shelley block");
        assertEquals(lovelace, list.get(0).lovelace());
        assertEquals(addr, list.get(0).address());
        assertEquals(blockNo2, list.get(0).blockNumber());
    }
}

