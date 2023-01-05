package com.bloxbean.cardano.yaci.helper.listener;

public interface LocalClientProviderListener {
    /**
     * This method is called after successful handshake during connection or re-connection.
     */
    void onConnectionReady();
}
