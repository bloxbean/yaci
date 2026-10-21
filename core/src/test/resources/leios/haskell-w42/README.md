# Haskell reference vectors for Musashi rc-2026w42 (Dijkstra / Leios)

Golden CBOR files from the Haskell node that Musashi `rc-2026w42` runs, used to check Yaci's parsing. They are
copied unchanged, except the header, which is the inner bytes of the consensus file.

| File | Source (repo @ commit, path) | Content |
| :--- | :--- | :--- |
| `dijkstra-golden-tx.cbor` | cardano-ledger @ `9df9f98c`, `eras/dijkstra/impl/golden/tx.cbor` | `exampleDijkstraTx`, mempool form `[body, witnesses, aux]`. As in w40a, plus witness set key 8 (Plutus V4 scripts) in the transaction and in its sub-transaction. Tx id `31bd401e…5c0f`; sub-transaction id `80c3560e…ba4c`. |
| `dijkstra-golden-gentx.cbor` | ouroboros-consensus @ `840e789b`, `ouroboros-consensus-cardano/golden/cardano/CardanoNodeToNodeVersion2/GenTx_Dijkstra` | A minimal transaction in the hard-fork wrapper `[7, 24(bytes .cbor tx)]`; its id `3da9a3b3…f11b` is the `GenTxId_Dijkstra` golden. |
| `dijkstra-golden-header.cbor` | inner bytes of ouroboros-consensus @ `840e789b` `.../CardanoNodeToNodeVersion2/Header_Dijkstra` | Dijkstra header: block 3, slot 9, `header_version_info = [2, 0]`, no Leios certificate, `eb_references_announcement = [hash, 123]`. |

The Leios vote golden (`leios/LeiosVote`) is byte-identical to the w40a one in `../haskell/`.

Pins: the Musashi `rc-2026w42` release binary is cardano-node `2d6a2a66`. Its `cabal.project` pins
ouroboros-consensus `840e789b`, cardano-ledger `9df9f98c` and ouroboros-network `96ee6e0e`.
Re-check these files when the pins in `adr/leios-todo.md` move.
