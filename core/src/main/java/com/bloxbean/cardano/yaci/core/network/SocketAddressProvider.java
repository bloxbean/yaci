package com.bloxbean.cardano.yaci.core.network;

import java.net.SocketAddress;
import java.util.List;

@FunctionalInterface
interface SocketAddressProvider {
    List<SocketAddress> get();
}
