# First App using Fetcher Api

### A simple program to follow the chain by connecting to a remote Cardano node

Using N2NChainSyncFetcher, you can connect to a remote/local Cardano node through Cardano server port. Each fetcher api
provides different callback apis to receive the result.

**Consumer Function to receive Block**

In the below example, we are passing a Consumer function to the **start** method of **N2NChainSyncFetcher**. The Consuemr function is
be applied whenever there is a new block available.

```java
 N2NChainSyncFetcher chainSyncFetcher = new N2NChainSyncFetcher(Constants.MAINNET_IOHK_RELAY_ADDR, Constants.MAINNET_IOHK_RELAY_PORT,
                Constants.WELL_KNOWN_MAINNET_POINT, Constants.MAINNET_PROTOCOL_MAGIC);

chainSyncFetcher.start(new Consumer<Block>() {
            @Override
            public void accept(Block block) {
                System.out.println("Received Block >> " + block.getHeader().getHeaderBody().getBlockNumber());
                System.out.println("Total # of Txns >> " + block.getTransactionBodies().size());
            }
});
```

**Listeners to recieve blocks and other events**

You can also attach a **_BlockFetchListener_** to the fetcher to receive blocks and to listen to other events (e.g; noBlockFound) published by the fetcher.

To receive other low level events like **"rollForward"**, **"rollBackward"** (to handle specific scenarios during rollback), you need to
add a **_ChainSyncAgentListener_**.

```java
chainSyncFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
    @Override
    public void blockFound(Block block) {
       System.out.println("Received Block >> " + block.getHeader().getHeaderBody().getBlockNumber());
       System.out.println("Total # of Txns >> " + block.getTransactionBodies().size());
    }
});

chainSyncFetcher.start();
```

Add ChainSyncAgentLister to listen to other low level events specific to ChainSyncAgent

```java
chainSyncFetcher.addChainSyncListener(new ChainSyncAgentListener() {
     @Override
     public void rollforward(Tip tip, BlockHeader blockHeader) {
         System.out.println("RollForward >> " + tip);
     }
     
     @Override
     public void rollbackward(Tip tip, Point toPoint) {
          System.out.println("Rollbakcward >> " + toPoint);
     }
});
```

**Note:** All listeners should be added before **start** method call
