### Agents

Each agent in Yaci Core implements a specific mini protocol. An agent is a low level component which understands how to
interact with a Cardano node. Regular Java applications don't need to use agent apis directly as there are high level
apis available for specific use cases.

List of currently available agents:

| mini protocol            | Agent Class                                                             |
|--------------------------|-------------------------------------------------------------------------|
| `n2n Handshake`          | com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent        |
| `n2n Block-Fetch`        | com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent      |     
| `n2n Chain-Sync`         | com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainsyncAgent    | 
| `n2n TxSubmission`       | com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionAgent  | 
| `n2n Keep-Alive`         | Not started                                                             | 
| `n2c Handshake`          | com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent        | 
| `n2c Chain-Sync`         | com.bloxbean.cardano.yaci.core.protocol.chainsync.n2c.ChainsyncAgent    | 
| `n2c Local TxSubmission` | com.bloxbean.cardano.yaci.core.protocol.localtx.LocalTxSubmissionAgent  | 
| `n2c Local State Query`  | com.bloxbean.cardano.yaci.core.protocol.localstate.LocalStateQueryAgent |
| `n2c Local Tx Monitor`   | Not Started                                                             |

