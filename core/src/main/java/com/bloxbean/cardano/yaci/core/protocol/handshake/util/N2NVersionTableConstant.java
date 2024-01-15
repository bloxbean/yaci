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

    public static VersionTable v4AndAbove(long networkMagic) {
        N2NVersionData versionData = new N2NVersionData(networkMagic, false);

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

        return new VersionTable(versionTableMap);
    }

    public static VersionTable v11AndAbove(long networkMagic) {
        return v11AndAbove(networkMagic, false, 0, false);
    }

    public static VersionTable v11AndAbove(long networkMagic, boolean initiatorAndResponderDiffusionMode, int peerSharing, boolean query) {
        N2NVersionData versionData = new N2NVersionData(networkMagic, initiatorAndResponderDiffusionMode, peerSharing, query);

        Map<Long, VersionData> versionTableMap = new HashMap<>();
        versionTableMap.put(PROTOCOL_V11, versionData);
        versionTableMap.put(PROTOCOL_V12, versionData);
        versionTableMap.put(PROTOCOL_V13, versionData);

        return new VersionTable(versionTableMap);
    }

}
