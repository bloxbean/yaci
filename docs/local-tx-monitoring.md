# Local Tx Monitor

LocalTxMonitor class helps to query mempool of a local Cardano node using local tx monitor node-to-client mini protocol.
This class is not thread-safe. 

## Get LocalTxMonitorClient

Get ``LocalTxMonitorClient`` from a LocalClientProvider instance.

```java
LocalClientProvider localClientProvider = new LocalClientProvider(nodeSocketFile, protocolMagic);
localClientProvider.start();

LocalTxMonitorClient localTxMonitorClient = localQueryProvider.getTxMonitorClient();
```

## Acquire a mempool snapshot

```java
Mono<Long> mono = localTxMonitorClient.acquire();
```

## Acquire and get mempool size

```java
localTxMonitorClient.acquire().block(Duration.ofSeconds(10));
Mono<MempoolStatus> mono = localTxMonitorClient.getMempoolSizeAndCapacity();
```

## Acquire and get mempool size in one call

```java
Mono<MempoolStatus> mono = localTxMonitorClient.acquireAndGetMempoolSizeAndCapacity();
```

## Acquire and get current mempool transactions

```java
localTxMonitorClient.acquire().block();
Mono<List<byte[]>> mono = localTxMonitorClient.getCurrentMempoolTransactionsAsMono();
```

## Acquire and get current mempool transactions as Flux

```java
localTxMonitorClient.acquire().block();
Flux<byte[]> flux = localTxMonitorClient.getCurrentMempoolTransactions();
```
