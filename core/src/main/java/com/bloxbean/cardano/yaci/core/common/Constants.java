package com.bloxbean.cardano.yaci.core.common;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;

public class Constants {
    public final static Point WELL_KNOWN_MAINNET_POINT = new Point(16588737, "4e9bbbb67e3ae262133d94c3da5bffce7b1127fc436e7433b87668dba34c354a");
    public final static Point WELL_KNOWN_PREPROD_POINT = new Point(87480, "528c3e6a00c82dd5331b116103b6e427acf447891ce3ade6c4c7a61d2f0a2b1c");
    public final static Point WELL_KNOWN_PREVIEW_POINT = new Point(8000, "70da683c00985e23903da00656fae96644e1f31dce914aab4ed50e35e4c4842d");
    public final static Point WELL_KNOWN_SANCHONET_POINT = new Point(20, "6a7d97aae2a65ca790fd14802808b7fce00a3362bd7b21c4ed4ccb4296783b98");

    public final static long MAINNET_PROTOCOL_MAGIC = NetworkType.MAINNET.getProtocolMagic();
    public final static long PREPROD_PROTOCOL_MAGIC = NetworkType.PREPROD.getProtocolMagic();
    public final static long PREVIEW_PROTOCOL_MAGIC = NetworkType.PREVIEW.getProtocolMagic();
    public final static long SANCHONET_PROTOCOL_MAGIC = NetworkType.SANCHONET.getProtocolMagic();

    /**
     * @deprecated Use MAINNET_PUBLIC_RELAY_ADDR
     */
    @Deprecated(since = "0.3.4")
    public final static String MAINNET_IOHK_RELAY_ADDR = "backbone.cardano.iog.io";

    /**
     * @deprecated Use MAINNET_PUBLIC_RELAY_PORT
     */
    @Deprecated(since = "0.3.4")
    public final static int MAINNET_IOHK_RELAY_PORT = 3001;

    /**
     * @deprecated Use PREPROD_PUBLIC_RELAY_ADDR
     */
    @Deprecated(since = "0.3.4")
    public final static String PREPROD_IOHK_RELAY_ADDR = "preprod-node.play.dev.cardano.org";

    /**
     * @deprecated Use PREPROD_PUBLIC_RELAY_PORT
     */
    @Deprecated(since = "0.3.4")
    public final static int PREPROD_IOHK_RELAY_PORT = 3001;

    /**
     * @deprecated Use PREVIEW_PUBLIC_RELAY_ADDR
     */
    @Deprecated(since = "0.3.4")
    public final static String PREVIEW_IOHK_RELAY_ADDR = "preview-node.world.dev.cardano.org";

    /**
     * @deprecated Use PREVIEW_PUBLIC_RELAY_PORT
     */
    @Deprecated(since = "0.3.4")
    public final static int PREVIEW_IOHK_RELAY_PORT = 3001;

    public final static String MAINNET_PUBLIC_RELAY_ADDR = "backbone.cardano.iog.io";
    public final static int MAINNET_PUBLIC_RELAY_PORT = 3001;

    public final static String PREPROD_PUBLIC_RELAY_ADDR = "preprod-node.play.dev.cardano.org";
    public final static int PREPROD_PUBLIC_RELAY_PORT = 3001;

    public final static String PREVIEW_PUBLIC_RELAY_ADDR = "preview-node.play.dev.cardano.org";
    public final static int PREVIEW_PUBLIC_RELAY_PORT = 3001;

    public final static String SANCHONET_PUBLIC_RELAY_ADDR = "sanchonet-node.play.dev.cardano.org";
    public final static int SANCHONET_PUBLIC_RELAY_PORT = 3001;

}
