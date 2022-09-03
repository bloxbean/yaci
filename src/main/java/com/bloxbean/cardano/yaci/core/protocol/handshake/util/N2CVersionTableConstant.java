package com.bloxbean.cardano.yaci.core.protocol.handshake.util;

import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.N2CVersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;

import java.util.HashMap;
import java.util.Map;

public class N2CVersionTableConstant {
    public final static long PROTOCOL_V1 = 1;
    public final static long PROTOCOL_V2 = 32770;
    public final static long PROTOCOL_V3 = 32771;
    public final static long PROTOCOL_V4 = 32772;
    public final static long PROTOCOL_V5 = 32773;
    public final static long PROTOCOL_V6 = 32774;
    public final static long PROTOCOL_V7 = 32775;
    public final static long PROTOCOL_V8 = 32776;
    public final static long PROTOCOL_V9 = 32777;
    public final static long PROTOCOL_V10 = 32778;
    public final static long PROTOCOL_V11 = 32779;
    public final static long PROTOCOL_V12 = 32780;

    public static VersionTable v4AndAbove(long networkMagic) {
        N2CVersionData versionData = new N2CVersionData(networkMagic);

        Map<Long, VersionData> versionTableMap = new HashMap<>();
        versionTableMap.put(PROTOCOL_V4, versionData);
        versionTableMap.put(PROTOCOL_V5, versionData);
        versionTableMap.put(PROTOCOL_V6, versionData);
        versionTableMap.put(PROTOCOL_V7, versionData);
        versionTableMap.put(PROTOCOL_V8, versionData);
        versionTableMap.put(PROTOCOL_V9, versionData);
        versionTableMap.put(PROTOCOL_V10, versionData);

        return new VersionTable(versionTableMap);
    }

    public static VersionTable v6AndAbove(long networkMagic) {
        N2CVersionData versionData = new N2CVersionData(networkMagic);

        Map<Long, VersionData> versionTableMap = new HashMap<>();
        versionTableMap.put(PROTOCOL_V6, versionData);
        versionTableMap.put(PROTOCOL_V7, versionData);
        versionTableMap.put(PROTOCOL_V8, versionData);
        versionTableMap.put(PROTOCOL_V9, versionData);
        versionTableMap.put(PROTOCOL_V10, versionData);

        return new VersionTable(versionTableMap);
    }

    public static VersionTable v7AndAbove(long networkMagic) {
        N2CVersionData versionData = new N2CVersionData(networkMagic);

        Map<Long, VersionData> versionTableMap = new HashMap<>();
        versionTableMap.put(PROTOCOL_V7, versionData);
        versionTableMap.put(PROTOCOL_V8, versionData);
        versionTableMap.put(PROTOCOL_V9, versionData);
        versionTableMap.put(PROTOCOL_V10, versionData);

        return new VersionTable(versionTableMap);
    }

    public static VersionTable v1AndAbove(long networkMagic) {
        N2CVersionData versionData = new N2CVersionData(networkMagic);

        Map<Long, VersionData> versionTableMap = new HashMap<>();
        versionTableMap.put(PROTOCOL_V1, versionData);
        versionTableMap.put(PROTOCOL_V2, versionData);
        versionTableMap.put(PROTOCOL_V3, versionData);
        versionTableMap.put(PROTOCOL_V4, versionData);
        versionTableMap.put(PROTOCOL_V5, versionData);
        versionTableMap.put(PROTOCOL_V6, versionData);
        versionTableMap.put(PROTOCOL_V7, versionData);
        versionTableMap.put(PROTOCOL_V8, versionData);
        versionTableMap.put(PROTOCOL_V9, versionData);
        versionTableMap.put(PROTOCOL_V10, versionData);
        versionTableMap.put(PROTOCOL_V11, versionData);
        versionTableMap.put(PROTOCOL_V12, versionData);

        return new VersionTable(versionTableMap);
    }
}
