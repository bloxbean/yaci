package com.bloxbean.cardano.yaci.core.model.jackson;

import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

public class StakeCredentialSerializer extends JsonSerializer<StakeCredential> {
    @Override
    public void serialize(StakeCredential stakeCredential, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        jsonGenerator.writeFieldName(mapper.writeValueAsString(stakeCredential));
    }
}
