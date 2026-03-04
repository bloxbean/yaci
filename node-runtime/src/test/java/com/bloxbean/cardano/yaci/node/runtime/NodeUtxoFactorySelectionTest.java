package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.events.api.config.EventsOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.config.RuntimeOptions;
import com.bloxbean.cardano.yaci.node.api.config.YaciNodeConfig;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.runtime.utxo.UtxoStatusProvider;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class NodeUtxoFactorySelectionTest {
    @Test
    void factorySelectsMmrBackend() throws Exception {
        File temp = Files.createTempDirectory("yaci-node-factory-test").toFile();
        try {
            YaciNodeConfig cfg = YaciNodeConfig.builder()
                    .enableClient(false)
                    .enableServer(true)
                    .serverPort(18080)
                    .useRocksDB(true)
                    .rocksDBPath(temp.getAbsolutePath())
                    .build();
            cfg.validate();

            var globals = new HashMap<String, Object>();
            globals.put("yaci.node.utxo.enabled", true);
            globals.put("yaci.node.utxo.store", "mmr");
            RuntimeOptions rt = new RuntimeOptions(new EventsOptions(true, 1024, SubscriptionOptions.Overflow.BLOCK),
                    com.bloxbean.cardano.yaci.node.api.config.PluginsOptions.defaults(), globals);

            NodeAPI node = new YaciNode(cfg, rt);
            UtxoState utxo = node.getUtxoState();
            assertNotNull(utxo);
            assertTrue(utxo.isEnabled());
            assertTrue(utxo instanceof UtxoStatusProvider);
            assertEquals("mmr", ((UtxoStatusProvider) utxo).storeType());
        } finally {
            deleteRecursively(temp);
        }
    }

    private void deleteRecursively(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) deleteRecursively(c);
        }
        f.delete();
    }
}
