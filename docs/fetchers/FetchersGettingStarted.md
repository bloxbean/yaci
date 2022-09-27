# Table of Contents
1. [Follow the Chain](#follow-the-chain)
    * [Follow a remote Cardano Node using N2NChainSyncFetcher](#follow-a-remote-cardano-node-using-n2nchainsyncfetcher)
    * [Follow a local Cardano Node using N2CChainSyncFetcher](#follow-a-local-cardano-node)
2. [Fetch Blocks using BlockFetcher](#fetch-blocks-from-point-1-to-point-2-using-blockfetcher)
    * [Fetch and receive blocks in a consumer function](#fetch-and-receive-blocks-in-a-consumer-function)
    * [Fetch and receive blocks in Listener](#fetch-and-receive-blocks-in-listener)

## 1. Follow the Chain

### Follow a remote Cardano node using N2NChainSyncFetcher

Using N2NChainSyncFetcher, you can connect to a remote/local Cardano node through Cardano server port. Each fetcher api
provides different callback apis to receive the result.

**Consumer Function to receive Block**

In the below example, we are passing a Consumer function to the **start** method of **N2NChainSyncFetcher**. The Consuemr function is
be applied whenever there is a new block available.

```java
N2NChainSyncFetcher chainSyncFetcher = new N2NChainSyncFetcher(Constants.MAINNET_IOHK_RELAY_ADDR, Constants.MAINNET_IOHK_RELAY_PORT,
        Constants.WELL_KNOWN_MAINNET_POINT, Constants.MAINNET_PROTOCOL_MAGIC);

chainSyncFetcher.start(block -> {
        System.out.println("Received Block >> " + block.getHeader().getHeaderBody().getBlockNumber());
         System.out.println("Total # of Txns >> " + block.getTransactionBodies().size());
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

### Follow a local Cardano Node
//TODO

## 2. Fetch blocks from point-1 to point-2 using BlockFetcher

To fetch block bodies from point-1 to point-2, you need to use ```BlockFetcher```.

**Note:** Currently full parsing is supported only for Shelly and post-Shelley era blocks. 
So use this fetcher only for Shelley and post-Shelley era (Mary, Alonzo, Babbage ...) blocks.

### Fetch and receive blocks in a consumer function

The following example fetches blocks from last block of Byron era to a babbage era block. The blocks are received in the
```Consumer``` function passed to the ```start()``` method. 

**Note:** Consumer function only supports Shelley or post-Shelley era blocks

```java
        //Last Byron Block
        Point from = new Point(4492799, "f8084c61b6a238acec985b59310b6ecec49c0ab8352249afd7268da5cff2a457");
        //Some point in Babbage era
        Point to = new Point(72719195, "a75cd55e48be3d341a7f8404094ee3cb9ce1f9fc923d23f675846d65546ef1dd");

        BlockFetcher blockFetcher = new BlockFetcher("192.168.0.228", 6000, NetworkType.MAINNET.getProtocolMagic());
        blockFetcher.start(block -> {
            System.out.println("Block >>> {} -- {} {}" + block.getHeader().getHeaderBody().getBlockNumber() + "  " + block.getHeader().getHeaderBody().getSlot() + "  " + block.getEra());
        });

        blockFetcher.fetch(from, to);

```

### Fetch and receive blocks in Listener

You can also receive blocks using ```BlockfetchAgentListener```

```java
        //Last Byron Block
        Point from = new Point(4492799, "f8084c61b6a238acec985b59310b6ecec49c0ab8352249afd7268da5cff2a457");
        //Some point in Babbage era
        Point to = new Point(72719195, "a75cd55e48be3d341a7f8404094ee3cb9ce1f9fc923d23f675846d65546ef1dd");

        BlockFetcher blockFetcher = new BlockFetcher("192.168.0.228", 6000, NetworkType.MAINNET.getProtocolMagic());
        blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            @Override
            public void byronBlockFound(ByronBlock block) {
                System.out.println("Byron block > " + block.getHeader().getConsensusData().getSlotId().getSlot());
            }

            @Override
            public void blockFound(Block block) {
                System.out.println("Block >>> {} -- {} {}" + block.getHeader().getHeaderBody().getBlockNumber() + "  " + block.getHeader().getHeaderBody().getSlot() + "  " + block.getEra());
            }
        });

        blockFetcher.start();
        blockFetcher.fetch(from, to);
```

### Shutdown

```shutdown()``` can be called at the end to release the connection.

```java
blockFetcher.shutdown();
```

