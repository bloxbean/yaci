package com.bloxbean.cardano.yaci.core.model;

/**
 * Native-script discriminators: the unsigned integer at the start of a script's CBOR array.
 * These are wire-format values, not CBOR major types or Plutus language versions.
 */
public final class NativeScriptType {
    /** A signature from the specified verification-key hash is required. */
    public static final int REQUIRE_SIGNATURE = 0;
    /** Every child script must be satisfied (JSON type {@code all}). */
    public static final int REQUIRE_ALL_OF = 1;
    /** At least one child script must be satisfied (JSON type {@code any}). */
    public static final int REQUIRE_ANY_OF = 2;
    /** At least the specified number of children must be satisfied (JSON type {@code atLeast}). */
    public static final int REQUIRE_M_OF = 3;
    /** The validity interval must start at or after the specified slot (JSON type {@code after}). */
    public static final int REQUIRE_TIME_AFTER = 4;
    /** The validity interval must end at or before the specified slot (JSON type {@code before}). */
    public static final int REQUIRE_TIME_BEFORE = 5;

    private NativeScriptType() {}
}
