package com.bloxbean.cardano.yaci.core.protocol.leios;

import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.Arrays;
import java.util.Objects;

public final class LeiosRawCbor {
    private final byte[] cbor;

    public LeiosRawCbor(byte[] cbor) {
        Objects.requireNonNull(cbor, "cbor");
        if (CborSerializationUtil.deserialize(cbor).length != 1) {
            throw new IllegalArgumentException("raw CBOR must contain exactly one top-level value");
        }
        this.cbor = Arrays.copyOf(cbor, cbor.length);
    }

    public static LeiosRawCbor of(byte[] cbor) {
        return new LeiosRawCbor(cbor);
    }

    public byte[] getCbor() {
        return Arrays.copyOf(cbor, cbor.length);
    }

    public String toHex() {
        return HexUtil.encodeHexString(cbor);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LeiosRawCbor that)) {
            return false;
        }
        return Arrays.equals(cbor, that.cbor);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(cbor);
    }

    @Override
    public String toString() {
        return "LeiosRawCbor{bytes=" + cbor.length + '}';
    }
}
