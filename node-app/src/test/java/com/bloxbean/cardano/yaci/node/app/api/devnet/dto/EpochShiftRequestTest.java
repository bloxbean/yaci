package com.bloxbean.cardano.yaci.node.app.api.devnet.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EpochShiftRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldDeserializeFromJson() throws Exception {
        String json = "{\"epochs\": 4}";
        EpochShiftRequest request = mapper.readValue(json, EpochShiftRequest.class);
        assertEquals(4, request.epochs());
    }

    @Test
    void shouldDefaultToZeroWhenEpochsMissing() throws Exception {
        String json = "{}";
        EpochShiftRequest request = mapper.readValue(json, EpochShiftRequest.class);
        assertEquals(0, request.epochs());
    }

    @Test
    void shouldSerializeToJson() throws Exception {
        EpochShiftRequest request = new EpochShiftRequest(4);
        String json = mapper.writeValueAsString(request);
        assertTrue(json.contains("\"epochs\":4"));
    }
}
