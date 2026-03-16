package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Converts protocol-param.json to CCL {@link ProtocolParams}.
 */
public class ProtocolParamsMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parse a protocol parameters JSON string into a CCL {@link ProtocolParams}.
     */
    public static ProtocolParams fromNodeProtocolParam(String json) throws IOException {
        return MAPPER.readValue(json, ProtocolParams.class);
    }
}
