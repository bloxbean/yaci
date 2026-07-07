package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Controls helper-level Leios notify/fetch integration.
 *
 * <p>{@link Mode#AUTO} follows the currently-known Musashi network for tip-following sync. Range fetchers keep
 * Leios disabled in AUTO because notify/fetch events are near-tip and not scoped to the requested historical range;
 * use {@link Mode#ENABLED} to opt in explicitly for future Leios networks or diagnostics.</p>
 */
@Getter
@ToString
@Builder(toBuilder = true)
public class LeiosConfig {
    public static final int DEFAULT_MAX_TXS_PER_ENDORSER_BLOCK = 64;
    public static final long DEFAULT_TXS_OFFER_WAIT_MILLIS = 2_000;

    public enum Mode {
        /**
         * Attach Leios agents only where Yaci can infer Leios support safely from the configured network.
         */
        AUTO,
        /**
         * Always attach Leios agents; activation still requires a compatible node-to-node handshake.
         */
        ENABLED,
        /**
         * Never attach Leios agents.
         */
        DISABLED
    }

    @Builder.Default
    private Mode mode = Mode.AUTO;
    /**
     * When true, fetch EB transaction bytes after an Endorser Block and matching tx offer are seen.
     */
    @Builder.Default
    private boolean fetchTxs = true;
    /**
     * Upper bound for EB transactions fetched from one Endorser Block.
     */
    @Builder.Default
    private int maxTxsPerEndorserBlock = DEFAULT_MAX_TXS_PER_ENDORSER_BLOCK;
    /**
     * How long to wait for a matching tx offer before emitting a refs-only Endorser Block event.
     */
    @Builder.Default
    private long txsOfferWaitMillis = DEFAULT_TXS_OFFER_WAIT_MILLIS;
    /**
     * Enables Leios vote parsing and delivery when the listener overrides onLeiosVotes.
     */
    @Builder.Default
    private boolean deliverVotes = false;

    public static LeiosConfig defaultConfig() {
        return LeiosConfig.builder().build();
    }

    public static LeiosConfig disabled() {
        return LeiosConfig.builder().mode(Mode.DISABLED).build();
    }

    public boolean shouldAttach(long protocolMagic) {
        return shouldAttach(protocolMagic, true);
    }

    boolean shouldAttachForRange(long protocolMagic) {
        return shouldAttach(protocolMagic, false);
    }

    private boolean shouldAttach(long protocolMagic, boolean allowAuto) {
        return switch (mode) {
            case DISABLED -> false;
            case ENABLED -> true;
            case AUTO -> allowAuto && protocolMagic == Constants.MUSASHI_PROTOCOL_MAGIC;
        };
    }

    public boolean isCompatible(AcceptVersion acceptVersion, long protocolMagic) {
        if (mode == Mode.DISABLED || acceptVersion == null
                || N2NVersionTableConstant.isAppLayerVersion(acceptVersion.getVersionNumber())
                || acceptVersion.getVersionNumber() < N2NVersionTableConstant.PROTOCOL_V15) {
            return false;
        }

        VersionData versionData = acceptVersion.getVersionData();
        if (versionData == null) {
            return false;
        }

        if (mode == Mode.AUTO) {
            return protocolMagic == Constants.MUSASHI_PROTOCOL_MAGIC
                    && versionData.getNetworkMagic() == Constants.MUSASHI_PROTOCOL_MAGIC;
        }

        return protocolMagic < 0 || versionData.getNetworkMagic() == protocolMagic;
    }

    public static long protocolMagic(VersionTable versionTable) {
        if (versionTable == null || versionTable.getVersionDataMap() == null
                || versionTable.getVersionDataMap().isEmpty()) {
            return -1;
        }
        return versionTable.getVersionDataMap().values().iterator().next().getNetworkMagic();
    }

    public static VersionTable versionTableFor(long protocolMagic, LeiosConfig leiosConfig) {
        return versionTableFor(protocolMagic, leiosConfig, true);
    }

    static VersionTable versionTableForRange(long protocolMagic, LeiosConfig leiosConfig) {
        return versionTableFor(protocolMagic, leiosConfig, false);
    }

    private static VersionTable versionTableFor(long protocolMagic, LeiosConfig leiosConfig, boolean allowAuto) {
        LeiosConfig effectiveConfig = leiosConfig != null ? leiosConfig : LeiosConfig.defaultConfig();
        if (effectiveConfig.shouldAttach(protocolMagic, allowAuto)) {
            return N2NVersionTableConstant.v11AndAbove(protocolMagic);
        }
        return N2NVersionTableConstant.v4AndAbove(protocolMagic);
    }
}
