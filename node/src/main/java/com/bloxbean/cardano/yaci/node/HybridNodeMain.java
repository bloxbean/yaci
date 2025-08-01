package com.bloxbean.cardano.yaci.node;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Main class for running a Hybrid Yaci Node
 *
 * This demonstrates a complete node that can:
 * 1. Sync with real Cardano nodes (preprod/mainnet)
 * 2. Serve other Yaci clients
 * 3. Act as a bridge/relay node
 */
@Slf4j
public class HybridNodeMain {

    public static void main(String[] args) {
        try {
            // Parse command line arguments
            HybridNodeConfig config = parseArgs(args);
            config.validate();

            log.info("Starting Hybrid Yaci Node with config: {}", config);

            // Create and start the hybrid node
            HybridYaciNode node = new HybridYaciNode(config);

            // Add shutdown hook for graceful shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutdown hook triggered, stopping node...");
                node.stop();
            }));

            // Start the node
            node.start();

            // Print status information
            printNodeStatus(node);

            // Keep the main thread alive
            CountDownLatch latch = new CountDownLatch(1);

            // Optional: Add a monitoring thread
            if (config.isEnableMonitoring()) {
                startMonitoringThread(node, latch);
            }

            // Wait for shutdown
            latch.await();

        } catch (Exception e) {
            log.error("Failed to start Hybrid Yaci Node", e);
            System.exit(1);
        }
    }

    private static HybridNodeConfig parseArgs(String[] args) {
        if (args.length == 0) {
            log.info("No arguments provided, using preprod default configuration");
            return HybridNodeConfig.preprodDefault();
        }

        String mode = args[0].toLowerCase();

        switch (mode) {
            case "preprod":
                return HybridNodeConfig.preprodDefault();

            case "mainnet":
                return HybridNodeConfig.mainnetDefault();

            case "server-only":
                int serverPort = args.length > 1 ? Integer.parseInt(args[1]) : 13337;
                return HybridNodeConfig.serverOnly(serverPort);

            case "client-only":
                if (args.length < 4) {
                    throw new IllegalArgumentException("client-only mode requires: <host> <port> <magic>");
                }
                String host = args[1];
                int port = Integer.parseInt(args[2]);
                long magic = Long.parseLong(args[3]);
                return HybridNodeConfig.clientOnly(host, port, magic);

            case "test":
                if (args.length < 5) {
                    throw new IllegalArgumentException("test mode requires: <host> <port> <magic> <server-port> <use-rocksdb>");
                }
                String testHost = args[1];
                int testPort = Integer.parseInt(args[2]);
                long testMagic = Long.parseLong(args[3]);
                int testServerPort = Integer.parseInt(args[4]);
                boolean useRocksDB = Boolean.parseBoolean(args[5]);
                return HybridNodeConfig.testConfig(testHost, testPort, testMagic, testServerPort, useRocksDB);

            case "perf-test":
                if (args.length < 6) {
                    throw new IllegalArgumentException("perf-test mode requires: <host> <port> <magic> <server-port> <use-rocksdb> <enable-pipeline>");
                }
                String perfHost = args[1];
                int perfPort = Integer.parseInt(args[2]);
                long perfMagic = Long.parseLong(args[3]);
                int perfServerPort = Integer.parseInt(args[4]);
                boolean perfUseRocksDB = Boolean.parseBoolean(args[5]);
                boolean enablePipeline = Boolean.parseBoolean(args[6]);
                return HybridNodeConfig.performanceTestConfig(perfHost, perfPort, perfMagic, perfServerPort, perfUseRocksDB, enablePipeline);

            default:
                throw new IllegalArgumentException("Unknown mode: " + mode +
                    ". Valid modes: preprod, mainnet, server-only, client-only, test, perf-test");
        }
    }

    private static void printNodeStatus(HybridYaciNode node) {
        log.info("=== Hybrid Yaci Node Status ===");
        log.info("Configuration: {}", node.getConfig());
        log.info("Running: {}", node.isRunning());
        log.info("Client syncing: {}", node.isSyncing());
        log.info("Server running: {}", node.isServerRunning());
        log.info("Local tip: {}", node.getLocalTip());
        log.info("=== End Status ===");

        // Print connection information
        if (node.getConfig().isEnableServer()) {
            log.info("Server listening on port: {}", node.getConfig().getServerPort());
            log.info("Other Yaci clients can connect to: localhost:{}", node.getConfig().getServerPort());
        }

        if (node.getConfig().isEnableClient()) {
            log.info("Client syncing from: {}:{}",
                node.getConfig().getRemoteHost(), node.getConfig().getRemotePort());
        }
    }

    private static void startMonitoringThread(HybridYaciNode node, CountDownLatch latch) {
        Thread monitoringThread = new Thread(() -> {
            log.info("Starting monitoring thread...");

            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // Print periodic status
                    log.info("=== Periodic Status ===");
                    log.info("Blocks processed: {}", node.getBlocksProcessed());
                    log.info("Last processed slot: {}", node.getLastProcessedSlot());
                    log.info("Local tip: {}", node.getLocalTip());
                    log.info("Running: {}, Syncing: {}, Server: {}",
                        node.isRunning(), node.isSyncing(), node.isServerRunning());

                    // Check if we should stop
                    if (!node.isRunning()) {
                        log.info("Node stopped, ending monitoring");
                        latch.countDown();
                        break;
                    }

                    // Wait before next status check
                    TimeUnit.MINUTES.sleep(5);
                }
            } catch (InterruptedException e) {
                log.info("Monitoring thread interrupted");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Error in monitoring thread", e);
            }
        });

        monitoringThread.setDaemon(true);
        monitoringThread.setName("HybridNodeMonitoring");
        monitoringThread.start();
    }

    /**
     * Print usage information
     */
    private static void printUsage() {
        System.out.println("Usage: java -jar yaci-node.jar [mode] [options]");
        System.out.println();
        System.out.println("Modes:");
        System.out.println("  preprod              - Connect to preprod network (default)");
        System.out.println("  mainnet              - Connect to mainnet network");
        System.out.println("  server-only [port]   - Run server only (no client sync)");
        System.out.println("  client-only <host> <port> <magic> - Run client only (no server)");
        System.out.println("  test <host> <port> <magic> <server-port> <use-rocksdb> - Custom test configuration");
        System.out.println("  perf-test <host> <port> <magic> <server-port> <use-rocksdb> <enable-pipeline> - Performance testing mode");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar yaci-node.jar preprod");
        System.out.println("  java -jar yaci-node.jar mainnet");
        System.out.println("  java -jar yaci-node.jar server-only 13337");
        System.out.println("  java -jar yaci-node.jar client-only preprod-node.world.dev.cardano.org 30000 1");
        System.out.println("  java -jar yaci-node.jar test localhost 3001 42 13337 false");
        System.out.println("  java -jar yaci-node.jar perf-test preprod-node.play.dev.cardano.org 3001 1 13337 false true   # Pipelined");
        System.out.println("  java -jar yaci-node.jar perf-test preprod-node.play.dev.cardano.org 3001 1 13337 false false  # Sequential");
    }
}
