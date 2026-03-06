package com.bloxbean.cardano.yaci.node.runtime.genesis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ShelleyGenesisParserTest {

    @Test
    void parse_extractsInitialFunds() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/genesis/test-shelley-genesis.json")) {
            ShelleyGenesisData data = ShelleyGenesisParser.parse(in);

            assertThat(data.initialFunds()).hasSize(2);
            assertThat(data.initialFunds())
                    .containsEntry("604fb41f142d1f7b8e51dd9232a110cf72aec955275439b72870b9c20d", 10000000000000L);
            assertThat(data.initialFunds())
                    .containsEntry("60a0f1aa7dca95017c11e7e373aebcf0c4568cf47ec12b94f8eb5bba8b", 3000000000000000L);
        }
    }

    @Test
    void parse_extractsNetworkMetadata() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/genesis/test-shelley-genesis.json")) {
            ShelleyGenesisData data = ShelleyGenesisParser.parse(in);

            assertThat(data.networkMagic()).isEqualTo(42);
            assertThat(data.epochLength()).isEqualTo(600);
            assertThat(data.slotLength()).isEqualTo(1.0);
            assertThat(data.systemStart()).isEqualTo("2024-01-01T00:00:00Z");
            assertThat(data.maxLovelaceSupply()).isEqualTo(45000000000000000L);
        }
    }

    @Test
    void parse_emptyInitialFunds_returnsEmptyMap() throws IOException {
        // Use preprod genesis which has empty initialFunds
        String json = """
                {
                  "activeSlotsCoeff": 0.05,
                  "epochLength": 432000,
                  "initialFunds": {},
                  "networkMagic": 1,
                  "slotLength": 1,
                  "systemStart": "2022-04-01T00:00:00Z",
                  "maxLovelaceSupply": 45000000000000000
                }
                """;
        try (InputStream in = new java.io.ByteArrayInputStream(json.getBytes())) {
            ShelleyGenesisData data = ShelleyGenesisParser.parse(in);

            assertThat(data.initialFunds()).isEmpty();
            assertThat(data.networkMagic()).isEqualTo(1);
        }
    }
}
