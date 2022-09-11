# Fetcher

Fetchers are helpers which provide high level apis to simplify the access to Cardano node. A fetcher uses more than one agent to implement
a specific use case. All fetchers except "LocalTxSubmissionClient" mostly reads data from the node.  

List of available fetchers :

| Fetcher              | Description                                                                | Agents used                                     |
|----------------------|----------------------------------------------------------------------------|-------------------------------------------------|
 | BlockFetcher         | Fetch blocks from point 1 to point 2 (Node to Node)                        | HandshakeAgent, BlockfetchAgent                 |
| N2NChainSyncFetcher  | Fetch blocks from the current tip or from a wellknown point (Node to Node) | HandshakeAgent, ChainsyncAgent, BlockfetchAgent |
 | N2CChainSyncFetcher | Fetch blocks from the current tip or from a wellknown point (Node to Client)| HandshakeAgent, LocalChainSyncAgent             |
| TipFinder | Find tip of the remote Cardano node (Node to Node) | HandshakeAgent, ChainsyncAgent |
| LocalTipFinder | Find tip of the local Cardano node (Node to Client) | HandshakeAgent, LocalChainSyncAgent|
| LocalStateQueryClient | Query local ledger state (Node to Client) |  HandshakeAgent, LocalStateQueryAgent |
 | LocalTxSubmissionClient | Submit transactions to a local Cardano node (Node to Client) | HandshakeAgent, LocalTxSubmissionAgent |
