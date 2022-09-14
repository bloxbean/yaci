## First App using Reactive Api

### A simple program to follow the chain by connecting to a public Cardano node

Java Apps can use BlockStreamer to stream blocks from a Cardano node.
BlockStreamer.fromLatest(NetworkType).stream() returns an instance of Flux. An application can subscribe to the Flux to 
receive incoming Block data and also can apply various Flux specific operators supported by [Project Reactor](https://projectreactor.io)

```java
BlockStreamer streamer = BlockStreamer.fromLatest(NetworkType.MAINNET);
Flux< Block> blockFlux =  streamer.stream();

blockFlux.subscribe(block -> {
    System.out.println("Received Block >> " + block.getHeader().getHeaderBody().getBlockNumber());
    System.out.println("Total # of Txns >> " + block.getTransactionBodies().size());
});
```

```java
streamer.shutdown();
```

## NetworkTypes

NetworkType enum has 4 network types.

1. MAINNET
2. LEGACY_TESTNET
3. PREPOD
4. PREV_TESTNET

Application just needs to specify the network type to connect to the public relay of the network.
To connect to a custom node or a private network, app needs to provide informations like host, port, a well known point 
and protocol magic.

## Other apis in BlockStreamer

1. To start streaming blocks from a specific point

```java
BlockStreamer fromPoint(NetworkType networkType, Point point)
```

```java
BlockStreamer fromPoint(String host, int port, Point point, VersionTable versionTable)
```

2. To stream blocks from Point-1 to Point-2

```java
BlockStreamer forRange(NetworkType networkType, Point fromPoint, Point toPoint)
```

```java
BlockStreamer forRange(String host, int port, Point fromPoint, Point toPoint, VersionTable versionTable)
```
