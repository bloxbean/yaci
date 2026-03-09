package com.bloxbean.cardano.yaci.node.app;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

@Singleton
public class PluginClassLoaderProducer {

    private static final Logger log = LoggerFactory.getLogger(PluginClassLoaderProducer.class);

    @Produces
    @Singleton
    @Named("pluginClassLoader")
    public ClassLoader createPluginClassLoader() {
        // Read config programmatically to avoid circular dependency
        // (CDI proxy for ClassLoader + SmallRye Config ServiceLoader = infinite recursion)
        String pluginDirectory = ConfigProvider.getConfig()
                .getOptionalValue("yaci.plugins.directory", String.class)
                .orElse("plugins");

        // In GraalVM native image mode, dynamic class loading is not supported
        String vmName = System.getProperty("java.vm.name", "");
        if ("Substrate VM".equalsIgnoreCase(vmName)) {
            log.info("Running in native image mode - plugin directory loading disabled. "
                    + "Plugins must be on the classpath at build time.");
            return Thread.currentThread().getContextClassLoader();
        }

        if (pluginDirectory == null || pluginDirectory.isBlank()) {
            log.debug("No plugin directory configured");
            return Thread.currentThread().getContextClassLoader();
        }

        File dir = new File(pluginDirectory);
        if (!dir.exists() || !dir.isDirectory()) {
            log.debug("Plugin directory '{}' does not exist or is not a directory", pluginDirectory);
            return Thread.currentThread().getContextClassLoader();
        }

        File[] jarFiles = dir.listFiles((d, name) -> name.endsWith(".jar"));
        if (jarFiles == null || jarFiles.length == 0) {
            log.debug("No JAR files found in plugin directory '{}'", pluginDirectory);
            return Thread.currentThread().getContextClassLoader();
        }

        List<URL> urls = new ArrayList<>();
        for (File jar : jarFiles) {
            try {
                urls.add(jar.toURI().toURL());
                log.info("Discovered plugin JAR: {}", jar.getName());
            } catch (Exception e) {
                log.warn("Failed to load plugin JAR '{}': {}", jar.getName(), e.getMessage());
            }
        }

        if (urls.isEmpty()) {
            return Thread.currentThread().getContextClassLoader();
        }

        log.info("Creating plugin ClassLoader with {} JARs from '{}'", urls.size(), pluginDirectory);
        URLClassLoader pluginCl = new URLClassLoader(
                urls.toArray(new URL[0]),
                Thread.currentThread().getContextClassLoader()
        );
        Thread.currentThread().setContextClassLoader(pluginCl);
        return pluginCl;
    }
}
