## Local State Queries

Local State Query mini-protocol allows to query the consensus / ledger state. This mini protocol is part of the
Node-to-Client protocol, hence it is only used by local (and thus trusted) clients. Possible queries depend on the era
(Byron, Shelly, etc) 

Yaci provides implementation of Local State mini-protocol. Using ```LocalStateQueryClient``` you can execute supported
queries. 

The following queries are currently supported by Yaci-core. More queries will be supported in the future. 
But you can also easily add support for new queries by adding a Request and Reply implementation for the specific query.
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
