package com.bloxbean.cardano.yaci.node.api.consensus;

/**
 * Supported consensus modes for app-layer block finalization.
 */
public enum ConsensusMode {
    SINGLE_SIGNER(0),
    MULTI_SIG(1),
    ROUND_ROBIN(2),
    BFT(3);

    private final int value;

    ConsensusMode(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static ConsensusMode fromValue(int value) {
        for (ConsensusMode mode : values()) {
            if (mode.value == value) return mode;
        }
        throw new IllegalArgumentException("Unknown consensus mode: " + value);
    }

    public static ConsensusMode fromString(String name) {
        return switch (name.toLowerCase().replace("-", "_").replace(" ", "_")) {
            case "single_signer", "singlesigner" -> SINGLE_SIGNER;
            case "multi_sig", "multisig" -> MULTI_SIG;
            case "round_robin", "roundrobin" -> ROUND_ROBIN;
            case "bft" -> BFT;
            default -> throw new IllegalArgumentException("Unknown consensus mode: " + name);
        };
    }
}
