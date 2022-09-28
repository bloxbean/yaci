## Local State Queries

Local State Query mini-protocol allows to query the consensus / ledger state. This mini protocol is part of the
Node-to-Client protocol, hence it is only used by local (and thus trusted) clients. Possible queries depend on the era
(Byron, Shelly, etc) 

Yaci provides implementation of Local State mini-protocol. Using ```LocalStateQueryClient``` you can execute supported
queries. 

The following queries are currently supported by Yaci-core. More queries will be supported in the future. 
But you can also easily add support for new queries by providing a Request and Reply implementation for the specific query.
To implement a new query, you can refer to an existing query's request and reply classes. 
(Example: ```ChainPointQuery``` and ```ChainPointQueryResult``` - query for chain block pointed at)

| Query                        | Description                              |
|------------------------------|------------------------------------------|
| `SystemStartQuery`           | System start time                        |
| `BlockHeightQuery`           | Current block height                     |
| `ChainPointQuery`            | Current block point at (slot, blockhash) |
| `CurrentProtocolParamsQuery` | Current protocol parameters              |
| `EpochNoQuery`               | Current epoch no query                   |
| `UtxoByAddressQuery`         | Get current utxos for an address         |


### Connect to Local Node using LocalState protocol and start the agent

Create a ```LocalStateQueryClient``` instance and use it to query the local Cardano node.

```java
 LocalStateQueryClient queryClient = new LocalStateQueryClient(nodeSocketFile, Constants.MAINNET_PROTOCOL_MAGIC);
 queryClient.start();
```

**To shutdown the query client**

```java
queryClient.shutdown();
```

**To reacquire at chain tip**

```java
Mono<Point> reAcquireMono = queryClient.reAcquire();
reAcquireMono.block();
```

**To acquire at a point**

```java
 Mono<Point> acquireMono = queryClient.acquire(point);
 acquireMono.block();
```

### Queries

Invoke ```LocalQueryClient.executeQuery(Query)``` to get an instance of ``Mono``. The result can then be received through
a blocking call ``Mono.block(timeout)`` or through a non-block call using ``Mono.subscribe()``.

#### 1. Get Start time

Use LocalStateQueryClient to get the start time. 

```java
 Mono<SystemStartResult> queryResultMono = queryClient.executeQuery(new SystemStartQuery());
 SystemStartResult systemStartResult = queryResultMono.block(Duration.ofSeconds(10)); //with timeout 10sec
```

For non-blocking call, use ```Mono.subscribe()```.

```java
 queryResultMono.subscribe(result -> {
     System.out.println("Start time >> " + result.getLocalDateTime());
 });
```
#### 2. Get Block Height

```java
 Mono<BlockHeightQueryResult> blockHeightQueryMono = queryClient.executeQuery(new BlockHeightQuery());
 BlockHeightQueryResult blockHeightQueryResult = blockHeightQueryMono.block(Duration.ofSeconds(10));
```

#### 3. Get current chain point

```java
 Mono<ChainPointQueryResult> chainPointQueryMono = queryClient.executeQuery(new ChainPointQuery());
 ChainPointQueryResult chainPointQueryResult = chainPointQueryMono.block(Duration.ofSeconds(10));
```

#### 4. Get current protocol parameters

```java
Mono<CurrentProtocolParamQueryResult> mono = queryClient.executeQuery(new CurrentProtocolParamsQuery(Era.Alonzo));
CurrentProtocolParamQueryResult protocolParams = mono.block(Duration.ofSeconds(10));
```

#### 5. Get Current Epoch No

```java
Mono<EpochNoQueryResult> queryResultMono = queryClient.executeQuery(new EpochNoQuery(Era.Alonzo));
EpochNoQueryResult epochNoQueryResult = queryResultMono.block(Duration.ofSeconds(10));
```

#### 6. Get Utxos by an address

```java
Mono<UtxoByAddressQueryResult> queryResultMono = queryClient.executeQuery(new UtxoByAddressQuery(Era.Alonzo, new Address("addr1vpf...")));
UtxoByAddressQueryResult utxoByAddressQueryResult = queryResultMono.block(Duration.ofSeconds(20));
```
