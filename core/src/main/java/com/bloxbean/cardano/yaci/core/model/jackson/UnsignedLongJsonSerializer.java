package com.bloxbean.cardano.yaci.core.model.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigInteger;

/**
 * Writes a {@code long} holding an unsigned 64-bit value (a ledger Word64, CCL's convention) as the JSON number it
 * stands for, so 2^64 - 1 is written {@code 18446744073709551615}, not {@code -1}. Use with
 * {@code @JsonSerialize(using = ...)} on a field, or {@code contentUsing} on a map or collection, together with
 * {@link UnsignedLongJsonDeserializer}.
 */
public class UnsignedLongJsonSerializer extends JsonSerializer<Long> {
    @Override
    public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value >= 0)
            gen.writeNumber(value);
        else
            gen.writeNumber(new BigInteger(Long.toUnsignedString(value)));
    }
}
