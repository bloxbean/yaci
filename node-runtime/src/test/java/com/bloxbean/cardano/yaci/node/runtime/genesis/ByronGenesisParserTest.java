package com.bloxbean.cardano.yaci.node.runtime.genesis;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ByronGenesisParserTest {

    @Test
    void parse_extractsNonAvvmBalances() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/genesis/test-byron-genesis.json")) {
            ByronGenesisData data = ByronGenesisParser.parse(in);

            // Only non-zero balances should be included
            assertThat(data.nonAvvmBalances()).hasSize(1);
            assertThat(data.nonAvvmBalances())
                    .containsEntry("FHnt4NL7yPXuYUxBF33VX5dZMBDAab2kvSNLRzCskvuKNCSDknzrQvKeQhGUw5a",
                            30000000000000000L);
        }
    }

    @Test
    void parse_extractsMetadata() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/genesis/test-byron-genesis.json")) {
            ByronGenesisData data = ByronGenesisParser.parse(in);

            assertThat(data.startTime()).isEqualTo(1654041600L);
            assertThat(data.protocolMagic()).isEqualTo(1L);
        }
    }

    @Test
    void parse_extractsSlotDurationAndK() throws IOException {
        // test-byron-genesis.json has slotDuration "20000" (ms) and k=2160 (preprod values)
        try (InputStream in = getClass().getResourceAsStream("/genesis/test-byron-genesis.json")) {
            ByronGenesisData data = ByronGenesisParser.parse(in);

            assertThat(data.slotDuration()).isEqualTo(20L); // 20000ms / 1000 = 20s
            assertThat(data.k()).isEqualTo(2160L);
            assertThat(data.epochLength()).isEqualTo(21600L); // k * 10
        }
    }

    @Test
    void parse_mainnetValues() throws IOException {
        // Mainnet-like: slotDuration=20000ms, k=2160
        String json = """
                {
                  "startTime": 1506203091,
                  "nonAvvmBalances": {},
                  "blockVersionData": {
                    "slotDuration": "20000"
                  },
                  "protocolConsts": {
                    "k": 2160,
                    "protocolMagic": 764824073
                  }
                }
                """;
        try (InputStream in = new ByteArrayInputStream(json.getBytes())) {
            ByronGenesisData data = ByronGenesisParser.parse(in);

            assertThat(data.startTime()).isEqualTo(1506203091L);
            assertThat(data.slotDuration()).isEqualTo(20L);
            assertThat(data.k()).isEqualTo(2160L);
            assertThat(data.protocolMagic()).isEqualTo(764824073L);
        }
    }

    @Test
    void parse_devnetValues() throws IOException {
        // Devnet: slotDuration=1000ms (1s), k=10
        String json = """
                {
                  "startTime": 1700000000,
                  "nonAvvmBalances": {},
                  "blockVersionData": {
                    "slotDuration": "1000"
                  },
                  "protocolConsts": {
                    "k": 10,
                    "protocolMagic": 42
                  }
                }
                """;
        try (InputStream in = new ByteArrayInputStream(json.getBytes())) {
            ByronGenesisData data = ByronGenesisParser.parse(in);

            assertThat(data.slotDuration()).isEqualTo(1L); // 1000ms / 1000 = 1s
            assertThat(data.k()).isEqualTo(10L);
            assertThat(data.epochLength()).isEqualTo(100L); // 10 * 10
        }
    }

    @Test
    void parse_emptyNonAvvmBalances_returnsEmptyMap() throws IOException {
        String json = """
                {
                  "startTime": 0,
                  "nonAvvmBalances": {},
                  "protocolConsts": {
                    "k": 2160,
                    "protocolMagic": 764824073
                  }
                }
                """;
        try (InputStream in = new ByteArrayInputStream(json.getBytes())) {
            ByronGenesisData data = ByronGenesisParser.parse(in);

            assertThat(data.nonAvvmBalances()).isEmpty();
            assertThat(data.protocolMagic()).isEqualTo(764824073L);
        }
    }

    @Test
    void parse_missingSlotDuration_defaultsToZero() throws IOException {
        String json = """
                {
                  "startTime": 0,
                  "nonAvvmBalances": {},
                  "protocolConsts": {
                    "k": 2160,
                    "protocolMagic": 1
                  }
                }
                """;
        try (InputStream in = new ByteArrayInputStream(json.getBytes())) {
            ByronGenesisData data = ByronGenesisParser.parse(in);

            assertThat(data.slotDuration()).isEqualTo(0L);
            assertThat(data.k()).isEqualTo(2160L);
        }
    }
}
