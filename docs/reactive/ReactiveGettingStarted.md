# First App using Reactive Api

//TODO

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
