package com.bloxbean.cardano.yaci.core.common;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;

public class Constants {
    public final static Point WELL_KNOWN_MAINNET_POINT = new Point(16588737, "4e9bbbb67e3ae262133d94c3da5bffce7b1127fc436e7433b87668dba34c354a");
    public final static Point WELL_KNOWN_TESTNET_POINT = new Point(13694363, "b596f9739b647ab5af901c8fc6f75791e262b0aeba81994a1d622543459734f2");
    public final static Point WELL_KNOWN_PREPOD_POINT = new Point(4325, "f3d7cd6f93cb4c59b61b28ac974f4a4dccfc44a4c83c1998aad17bb6b7b03446");
    public final static Point WELL_KNOWN_PREVIEW_POINT = new Point(86440, "9adae180ce67acd8a7e172f3b81ef79f75234623066a2387885218d769cf246a");

    public final static long MAINNET_PROTOCOL_MAGIC = NetworkType.MAINNET.getProtocolMagic();
    public final static long LEGACY_TESTNET_PROTOCOL_MAGIC = NetworkType.LEGACY_TESTNET.getProtocolMagic();
    public final static long PREPOD_PROTOCOL_MAGIC = NetworkType.PREPOD.getProtocolMagic();
    public final static long PREVIEW_PROTOCOL_MAGIC = NetworkType.PREV_TESTNET.getProtocolMagic();

    public final static String TESTNET_IOHK_RELAY_ADDR = "relays-new.cardano-testnet.iohkdev.io";
    public final static int TESTNET_IOHK_RELAY_PORT = 3001;

    public final static String MAINNET_IOHK_RELAY_ADDR = "relays-new.cardano-mainnet.iohk.io";
    public final static int MAINNET_IOHK_RELAY_PORT = 3001;

    public final static String PREPOD_IOHK_RELAY_ADDR = "preprod-node.world.dev.cardano.org";
    public final static int PREPOD_IOHK_RELAY_PORT = 30000;

    public final static String PREVIEW_IOHK_RELAY_ADDR = "preview-node.world.dev.cardano.org";
    public final static int PREVIEW_IOHK_RELAY_PORT = 30002;

}
