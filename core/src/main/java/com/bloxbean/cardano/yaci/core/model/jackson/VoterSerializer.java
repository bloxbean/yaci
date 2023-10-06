package com.bloxbean.cardano.yaci.core.model.jackson;

import com.bloxbean.cardano.yaci.core.model.governance.Voter;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

public class VoterSerializer extends JsonSerializer<Voter> {
    @Override
    public void serialize(Voter voter, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        jsonGenerator.writeFieldName(mapper.writeValueAsString(voter));
    }
}
