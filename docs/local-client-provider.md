# Local Client Provier

``LocalClientProvider`` helper class simplifies the interaction with a local Cardano node. 
It provides functionalities like state query, mempool tx monitoring and tx submission through protocol specific
clients on a single connection.

- [LocalStateQueryClient](local-state-queries.md)
- [LocalTxSubmissionClient](local-tx-submission.md)
- [LocalTxMonitoring](local-tx-monitoring.md)

## Create LocalClientProvider

Create and start a LocalClientProvider.

```java
LocalClientProvider localClientProvider = new LocalClientProvider(nodeSocketFile, protocolMagic)
localQueryProvider.start();
```

## Get protocol specific clients

### Get LocalStateQueryClient

```java
LocalStateQueryClient localStateQueryClient = localClientProvider.getLocalStateQueryClient()
```

### Get LocalTxSubmissionClient

```java
LocalTxSubmissionClient localTxSubmissionClient = localClientProvider.getTxSubmissionClient()
```

### Get LocalTxMonitorClient

```java
LocalTxMonitorClient localTxMonitorClient = localClientProvider.getTxMonitorClient()
```

## Listeners
The following listeners can be added to LocalClientProvider to listen to events or get results.

- **LocalStateQueryListener** : To receive state query result
```java
localClientProvider.addLocalStateQueryListener(listener)
```
- **LocaTxMonitorListener** : To get tx monitoring events
```java
localClientProvider.addTxMonitorListener(listener)
```
- **LocalTxSubmissionListener** : To get tx submission result
```java
localClientProvider.addTxSubmissionListener(listener)
```

- **LocalClientProviderListener** : To get connection ready event

```java
localClientProvider.setLocalClientProviderListener(listener)
```

## To shutdown

```java
localClientProvider.shutdown()
```
