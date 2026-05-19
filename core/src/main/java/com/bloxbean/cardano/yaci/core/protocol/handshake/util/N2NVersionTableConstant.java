package com.bloxbean.cardano.yaci.core.protocol.handshake.util;

import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.N2NVersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;

import java.util.HashMap;
import java.util.Map;

public class N2NVersionTableConstant {
    public final static long PROTOCOL_V4 = 4;
    public final static long PROTOCOL_V5 = 5;
    public final static long PROTOCOL_V6 = 6;
    public final static long PROTOCOL_V7 = 7;
    public final static long PROTOCOL_V8 = 8;
    public final static long PROTOCOL_V9 = 9;
    public final static long PROTOCOL_V10 = 10;
    public final static long PROTOCOL_V11 = 11;
    public final static long PROTOCOL_V12 = 12;
    public final static long PROTOCOL_V13 = 13;
    public final static long PROTOCOL_V14 = 14;
    public final static long PROTOCOL_V15 = 15; //srv support
    public final static long PROTOCOL_V100 = 100;

    /**
     * Check if the negotiated version supports app-layer protocols (Protocol 100+).
     *
     * @param versionNumber the negotiated version from handshake
     * @return true if the version supports app-layer protocols
     */
    public static boolean isAppLayerVersion(long versionNumber) {
        return versionNumber >= PROTOCOL_V100;
    }

    public static boolean hasAppLayerVersion(VersionTable versionTable) {
        return versionTable != null && versionTable.getVersionDataMap().keySet().stream()
                .anyMatch(N2NVersionTableConstant::isAppLayerVersion);
    }

    public static VersionTable withAppLayer(VersionTable versionTable) {
        if (versionTable == null)
            throw new IllegalArgumentException("Version table is required");

        Map<Long, VersionData> versionTableMap = new HashMap<>(versionTable.getVersionDataMap());
        if (versionTableMap.keySet().stream().anyMatch(N2NVersionTableConstant::isAppLayerVersion))
            return new VersionTable(versionTableMap);

        VersionData appVersionData = versionTableMap.get(PROTOCOL_V15);
        if (appVersionData == null) {
            appVersionData = versionTableMap.entrySet().stream()
                    .filter(entry -> entry.getKey() < PROTOCOL_V100)
                    .max(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No node-to-node version data available for app-layer V100"));
        }

        versionTableMap.put(PROTOCOL_V100, appVersionData);
        return new VersionTable(versionTableMap);
    }

    public static VersionTable v4AndAbove(long networkMagic) {
        N2NVersionData versionData = new N2NVersionData(networkMagic, true);

        Map<Long, VersionData> versionTableMap = new HashMap<>();
        versionTableMap.put(PROTOCOL_V4, versionData);
        versionTableMap.put(PROTOCOL_V5, versionData);
        versionTableMap.put(PROTOCOL_V6, versionData);
        versionTableMap.put(PROTOCOL_V7, versionData);
        versionTableMap.put(PROTOCOL_V8, versionData);
        versionTableMap.put(PROTOCOL_V9, versionData);
        versionTableMap.put(PROTOCOL_V10, versionData);
        versionTableMap.put(PROTOCOL_V11, versionData);
        versionTableMap.put(PROTOCOL_V12, versionData);
        versionTableMap.put(PROTOCOL_V13, versionData);
        versionTableMap.put(PROTOCOL_V14, versionData);
        versionTableMap.put(PROTOCOL_V15, versionData);

        return new VersionTable(versionTableMap);
    }

    public static VersionTable v11AndAbove(long networkMagic) {
        return v11AndAbove(networkMagic, true, 0, false);
    }

    public static VersionTable v11AndAbove(long networkMagic, boolean initiatorOnlyDiffusionMode,
                                           int peerSharing, boolean query) {
        N2NVersionData versionData = new N2NVersionData(networkMagic, initiatorOnlyDiffusionMode, peerSharing, query);

        Map<Long, VersionData> versionTableMap = new HashMap<>();
        versionTableMap.put(PROTOCOL_V11, versionData);
        versionTableMap.put(PROTOCOL_V12, versionData);
        versionTableMap.put(PROTOCOL_V13, versionData);
        versionTableMap.put(PROTOCOL_V14, versionData);
        versionTableMap.put(PROTOCOL_V15, versionData);

        return new VersionTable(versionTableMap);
    }

    /**
     * Version table for Yaci nodes that support app-layer protocols.
     * Includes V100 for app-layer capability signaling. When connecting to Cardano nodes,
     * V100 is ignored and the highest mutually supported Cardano node-to-node version is negotiated.
     */
    public static VersionTable v11AndAboveWithAppLayer(long networkMagic) {
        return v11AndAboveWithAppLayer(networkMagic, true, 0, false);
    }

    public static VersionTable v11AndAboveWithAppLayer(long networkMagic, boolean initiatorOnlyDiffusionMode,
                                                       int peerSharing, boolean query) {
        N2NVersionData versionData = new N2NVersionData(networkMagic, initiatorOnlyDiffusionMode, peerSharing, query);

        Map<Long, VersionData> versionTableMap = new HashMap<>();
        versionTableMap.put(PROTOCOL_V11, versionData);
        versionTableMap.put(PROTOCOL_V12, versionData);
        versionTableMap.put(PROTOCOL_V13, versionData);
        versionTableMap.put(PROTOCOL_V14, versionData);
        versionTableMap.put(PROTOCOL_V15, versionData);
        versionTableMap.put(PROTOCOL_V100, versionData);

        return new VersionTable(versionTableMap);
    }

}
