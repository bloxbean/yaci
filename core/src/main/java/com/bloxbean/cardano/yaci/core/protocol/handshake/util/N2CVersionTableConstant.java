package com.bloxbean.cardano.yaci.core.protocol.handshake.util;

import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.N2CVersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.OldN2CVersionData;
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
    public final static long PROTOCOL_V13 = 32781;
    public final static long PROTOCOL_V14 = 32782;
    public final static long PROTOCOL_V15 = 32783;
    public final static long PROTOCOL_V16 = 32784;
    public final static long PROTOCOL_V17 = 32785;
    public final static long PROTOCOL_V18 = 32786;
    public final static long PROTOCOL_V19 = 32787;
    public final static long PROTOCOL_V20 = 32788;

    public static VersionTable v1AndAbove(long networkMagic) {
        OldN2CVersionData oldVersionData = new OldN2CVersionData(networkMagic);
        N2CVersionData versionData = new N2CVersionData(networkMagic, false);

        Map<Long, VersionData> versionTableMap = new HashMap<>();
        versionTableMap.put(PROTOCOL_V1, oldVersionData);
        versionTableMap.put(PROTOCOL_V2, oldVersionData);
        versionTableMap.put(PROTOCOL_V3, oldVersionData);
        versionTableMap.put(PROTOCOL_V4, oldVersionData);
        versionTableMap.put(PROTOCOL_V5, oldVersionData);
        versionTableMap.put(PROTOCOL_V6, oldVersionData);
        versionTableMap.put(PROTOCOL_V7, oldVersionData);
        versionTableMap.put(PROTOCOL_V8, oldVersionData);
        versionTableMap.put(PROTOCOL_V9, oldVersionData);
        versionTableMap.put(PROTOCOL_V10, oldVersionData);
        versionTableMap.put(PROTOCOL_V11, oldVersionData);
        versionTableMap.put(PROTOCOL_V12, oldVersionData);
        versionTableMap.put(PROTOCOL_V13, oldVersionData);
        versionTableMap.put(PROTOCOL_V14, oldVersionData);
        versionTableMap.put(PROTOCOL_V15, versionData);
        versionTableMap.put(PROTOCOL_V16, versionData);
        versionTableMap.put(PROTOCOL_V17, versionData);
        versionTableMap.put(PROTOCOL_V18, versionData);
        versionTableMap.put(PROTOCOL_V19, versionData);
        versionTableMap.put(PROTOCOL_V20, versionData);

        return new VersionTable(versionTableMap);
    }

    /**
     * Version table for latest N2C protocol versions (V15 and above with query support)
     */
    public static VersionTable v15AndAbove(long networkMagic, boolean query) {
        N2CVersionData versionData = new N2CVersionData(networkMagic, query);

        Map<Long, VersionData> versionTableMap = new HashMap<>();
        versionTableMap.put(PROTOCOL_V15, versionData);
        versionTableMap.put(PROTOCOL_V16, versionData);
        versionTableMap.put(PROTOCOL_V17, versionData);
        versionTableMap.put(PROTOCOL_V18, versionData);
        versionTableMap.put(PROTOCOL_V19, versionData);
        versionTableMap.put(PROTOCOL_V20, versionData);

        return new VersionTable(versionTableMap);
    }
}
