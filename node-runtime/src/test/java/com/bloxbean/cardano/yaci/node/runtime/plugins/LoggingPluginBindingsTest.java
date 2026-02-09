package com.bloxbean.cardano.yaci.node.runtime.plugins;

import com.bloxbean.cardano.yaci.events.api.support.DomainEventBindings;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingPluginBindingsTest {

    @Test
    void generatedBindingsShouldBeDiscoverable() {
        ServiceLoader<DomainEventBindings> loader = ServiceLoader.load(DomainEventBindings.class);
        boolean found = false;
        for (DomainEventBindings b : loader) {
            if (b.targetType().isAssignableFrom(LoggingPlugin.class)) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .as("Generated DomainEventBindings for LoggingPlugin should be discoverable via ServiceLoader")
                .isTrue();
    }
}

