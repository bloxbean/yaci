package com.bloxbean.cardano.yaci.core.common;

import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;

public enum NetworkType {
    MAINNET(764824073), LEGACY_TESTNET(1097911063), PREPOD(1), PREV_TESTNET(2);

    long protocolMagic;
    NetworkType(long protocolMagic) {
        this.protocolMagic = protocolMagic;
    }

    public long getProtocolMagic() {
        return protocolMagic;
    }

    public VersionTable getN2NVersionTable() {
        return N2NVersionTableConstant.v4AndAbove(getProtocolMagic());
    }
}
