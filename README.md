<div align="center">
<img src="static/yaci.png" width="200">

<h4>A Cardano Mini Protocols implementation in Java</h4>

[![Clean, Build](https://github.com/bloxbean/yaci-core/actions/workflows/build.yml/badge.svg)](https://github.com/bloxbean/yaci-core/actions/workflows/build.yml)
</div>

## Dependencies

Maven

```xml
<dependency>
    <groupId>com.bloxbean.cardano</groupId>
    <artifactId>yaci-core</artifactId>
    <version>0.1.8</version>
</dependency>
```

Gradle

```xml
 implementation('com.bloxbean.cardano:yaci-core:0.1.8')
```

## [Documentation](docs/README.md)

## Status

| mini protocol            | initiator      |
|--------------------------|----------------|
| `n2n Handshake`          | Done           | 
| `n2n Block-Fetch`        | Done           |     
| `n2n Chain-Sync`         | Done           | 
| `n2n TxSubmission`       | In Progress    | 
| `n2n Keep-Alive`         | Done    | 
| `n2c Handshake`          | Done           | 
| `n2c Chain-Sync`         | Done           | 
| `n2c Local TxSubmission` | Done           | 
| `n2c Local State Query`  | Partially Done |
| `n2c Local Tx Monitor`   | Done   |


| Other tasks              | Status                                                   |
|--------------------------|----------------------------------------------------------|
| `Block Parsing`          | Tx Inputs, Tx Outputs, MultiAssets, Mint, Certificate    |
| `Eras`                   | Done (Shelley, Alonzo, Babbage),   Not Done(Byron Era)   |   
|                          |                                                          |


## Quick Guide 

### Using Reactive api

#### 1. Stream Blocks using reactive api (Project Reactor)

Connect to a public relay and stream blocks from last block.

```
Flux<Block> blocksFlux = BlockStreamer.fromLatest(NetworkType.MAINNET)
                .stream();

//Subscribe
blocksFlux.subscribe(block -> System.out.println(block.getHeader().getHeaderBody().getBlockNumber()));
```

#### 2. Stream Blocks from Point-1 to Point-2 

Connect to a Cardano node and stream blocks from Point1 to Point2.

```
  Point from = new Point(16588737, "4e9bbbb67e3ae262133d94c3da5bffce7b1127fc436e7433b87668dba34c354a");
  Point to = new Point(70223766, "21155bb822637508a91e9952e712040c0ea45107fb91898bfe8c9a95389b0d90");
  
  Flux<Block> streamer = BlockStreamer.forRange("<cardano_node_host>", <cardano_node_socket_port>,
                        N2NVersionTableConstant.v4AndAbove(Constants.MAINNET_PROTOCOL_MAGIC), from, to).stream();
      
  //Subscribe to the stream    
  streamer.subscribe(block -> {
            System.out.println(">> " + block.getEra());
            System.out.println(">> " + block.getHeader().getHeaderBody().getBlockNumber());
            System.out.println(">> " + block.getHeader().getHeaderBody().getBlockHash());
  });
```

### Using Fetchers (Callbacks)

If you want to receive all possible events, you can use out of box fetchers which provide data through callbacks/listeners.

#### 1. BlockFetcher
To receive blocks for a range

```java
        //Last Byron Block
        Point from = new Point(4492799, "f8084c61b6a238acec985b59310b6ecec49c0ab8352249afd7268da5cff2a457");
        Point to = new Point(70223766, "21155bb822637508a91e9952e712040c0ea45107fb91898bfe8c9a95389b0d90");

        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Constants.MAINNET_PROTOCOL_MAGIC); 
        BlockFetcher blockFetcher = new BlockFetcher(<cardano_host>, <cardano_node_socket_port>, versionTable);

        blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                System.out.println(block.getHeader().getHeaderBody().getBlockNumber());
                System.out.println(block.getAuxiliaryDataMap());
            }

            @Override
            public void batchDone() {
            }
        });

        blockFetcher.start(block -> {
            System.out.println("Block >>> " +  block.getHeader());
        });

        blockFetcher.fetch(from, to);

```

#### 2. ChainSyncFetcherFromLatest

Stream blocks from latest block

```
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Constants.MAINNET_PROTOCOL_MAGIC); 
        Point wellKnownPoint = new Point(16588737, "4e9bbbb67e3ae262133d94c3da5bffce7b1127fc436e7433b87668dba34c354a");
        
        ChainSyncFetcherFromLatest chainSyncFetcher = new ChainSyncFetcherFromLatest("<cardano_host>", <cardano_node_socket_port>,  wellKnownPoint, versionTable);

        chainSyncFetcher.addChainSyncListener(new ChainSyncAgentListener() {
            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                System.out.println("RollForward !!!");
            }
        });

        chainSyncFetcher.start(block -> {
            System.out.println("Block >>> " + block.getHeader().getHeaderBody().getBlockNumber());
            System.out.println("Metadata >>> " + block.getAuxiliaryDataMap());
        });
```

#### 3. TipFinder 

Find the tip

```java
        Point point = new Point(16588737, "4e9bbbb67e3ae262133d94c3da5bffce7b1127fc436e7433b87668dba34c354a");
        TipFinder tipFinder = new TipFinder("<cardano_host>", <cardano_socket_port>, point, Constants.MAINNET_PROTOCOL_MAGIC);
        tipFinder.start(tip -> {
            System.out.println("Tip found >>> " + tip);
        });

        tipFinder.shutdown();
```

## Build

```
$> git clone https://github.com/bloxbean/yaci-core
$> ./gradlew clean build
``` 

# Any questions, ideas or issues ?

- Create a Github [Issue](https://github.com/bloxbean/yaci/issues)
- [Discord Server](https://discord.gg/JtQ54MSw6p)

# Support from YourKit

YourKit has generously granted the BloxBean projects an Open Source licence to use their excellent Java Profiler.

![YourKit](https://www.yourkit.com/images/yklogo.png)

YourKit supports open source projects with innovative and intelligent tools
for monitoring and profiling Java and .NET applications.
YourKit is the creator of <a href="https://www.yourkit.com/java/profiler/">YourKit Java Profiler</a>,
<a href="https://www.yourkit.com/.net/profiler/">YourKit .NET Profiler</a>,
and <a href="https://www.yourkit.com/youmonitor/">YourKit YouMonitor</a>
