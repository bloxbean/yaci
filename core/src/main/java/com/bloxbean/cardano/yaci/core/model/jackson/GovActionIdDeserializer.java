package com.bloxbean.cardano.yaci.core.model.jackson;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

public class GovActionIdDeserializer extends KeyDeserializer {
    private ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Object deserializeKey(String s, DeserializationContext deserializationContext)
            throws IOException {
        return objectMapper.readValue(s, GovActionId.class);
    }
}
