package com.bloxbean.cardano.yaci.core.protocol.handshake.serializers;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
class HandshakeSerializersTest {

    @Test
    @Disabled("initiatorOnlyDiffusionMode is true in the pre-geenerated tables")
    public void proposedVersionDeserialise() {
        var bytes = HexUtil.decodeHexString("8200a507821a2d964a09f408821a2d964a09f409821a2d964a09f40a821a2d964a09f40b841a2d964a09f400f4");
        var proposedVersion = HandshakeSerializers.ProposedVersionSerializer.INSTANCE.deserialize(bytes);
        Map<Long, VersionData> expectedVersions = new HashMap<>();
        var table = N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic());
        expectedVersions.put(7L, table.getVersionDataMap().get(7L));
        expectedVersions.put(8L, table.getVersionDataMap().get(8L));
        expectedVersions.put(9L, table.getVersionDataMap().get(9L));
        expectedVersions.put(10L, table.getVersionDataMap().get(10L));
        expectedVersions.put(11L, table.getVersionDataMap().get(11L));
        var versionTable = new VersionTable(expectedVersions);
        assertEquals(versionTable, proposedVersion.getVersionTable());
    }

}