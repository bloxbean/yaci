package com.bloxbean.cardano.yaci.core.model.jackson;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

public class GovActionIdSerializer extends JsonSerializer<GovActionId> {
    @Override
    public void serialize(GovActionId govActionId, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        jsonGenerator.writeFieldName(mapper.writeValueAsString(govActionId));
    }
}
