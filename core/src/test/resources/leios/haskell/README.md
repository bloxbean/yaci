# Haskell reference vectors (Dijkstra / Leios)

Golden CBOR files from the Haskell node, used to check Yaci's parsing. They are copied unchanged.

| File | Source (repo @ commit, path) | Content |
| :--- | :--- | :--- |
| `dijkstra-golden-tx.cbor` | cardano-ledger @ `d93c6546`, `eras/dijkstra/impl/golden/tx.cbor` | `exampleDijkstraTx`: a mempool transaction `[body, witnesses, aux]` with a sub-transaction (key 23, body keys 24-26), credential guards (key 14), direct deposits (25), account balance intervals (26), pool registrations with and without `bls_key`, redeemer tag 6 (guarding), Plutus V4 in a script ref and in aux data key 5. Tx id `9cf72928…86f9`; sub-transaction id `49059b9f…260e`. |
| `dijkstra-golden-gentx.cbor` | ouroboros-consensus @ `cbd10033`, `ouroboros-consensus-cardano/golden/cardano/CardanoNodeToNodeVersion2/GenTx_Dijkstra` | The same transaction in the hard-fork wrapper `[7, 24(bytes .cbor tx)]`. |
| `dijkstra-golden-header.cbor` | inner bytes of ouroboros-consensus @ `cbd10033` `.../CardanoNodeToNodeVersion2/Header_Dijkstra` | Dijkstra header: block 3, slot 9, `block_body_contains_leios_cert = true`, `eb_announcement = [hash, 123]`. |
| `leios-golden-vote.cbor` | ouroboros-consensus @ `cbd10033`, `.../golden/cardano/leios/LeiosVote` | Leios vote `[announcing_rb_hash, 1000, signature]`. |

Pins: Musashi `prototype-2026w40a` runs ouroboros-consensus `cbd10033`. Its `cabal.project` pins cardano-ledger
`d93c6546`, whose `dijkstra.cddl` is byte-identical to cardano-blueprint `131cc95a` `src/ledger/eras/dijkstra.cddl`.
Re-check these files when the pins in `adr/leios-todo.md` move.
