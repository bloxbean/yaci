package com.bloxbean.cardano.yaci.node.runtime.genesis;

import org.junit.jupiter.api.Test;

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
        try (InputStream in = new java.io.ByteArrayInputStream(json.getBytes())) {
            ByronGenesisData data = ByronGenesisParser.parse(in);

            assertThat(data.nonAvvmBalances()).isEmpty();
            assertThat(data.protocolMagic()).isEqualTo(764824073L);
        }
    }
}
