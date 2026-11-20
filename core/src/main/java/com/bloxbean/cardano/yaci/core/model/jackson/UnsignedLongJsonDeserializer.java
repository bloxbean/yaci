package com.bloxbean.cardano.yaci.core.model.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.math.BigInteger;

/**
 * Reads what {@link UnsignedLongJsonSerializer} writes: a JSON integer up to 2^64 - 1, kept as its 64 bits in a
 * {@code long}. A negative integer down to {@code Long.MIN_VALUE} is read as Jackson reads a {@code long}, so JSON
 * written with the signed value still reads the same; anything else that is not an integer is read as Jackson
 * reads a {@code Long}.
 */
public class UnsignedLongJsonDeserializer extends JsonDeserializer<Long> {
    private static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() != JsonToken.VALUE_NUMBER_INT)
            return ctxt.readValue(p, Long.class);
        BigInteger value = p.getBigIntegerValue();
        if (value.compareTo(MIN) < 0 || value.compareTo(MAX) > 0)
            return (Long) ctxt.handleWeirdNumberValue(Long.class, value, "not a 64-bit unsigned integer");
        return value.longValue();
    }
}
