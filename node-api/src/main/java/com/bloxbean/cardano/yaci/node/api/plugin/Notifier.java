package com.bloxbean.cardano.yaci.node.api.plugin;

public interface Notifier {
    void notify(Notification notification) throws Exception;
}

