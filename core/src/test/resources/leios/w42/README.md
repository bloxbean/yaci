# Musashi w42 fixtures

Captured on 2026-10-20 from the public Musashi relays (network magic 164) after the respin to ouroboros-leios
`rc-2026w42`. The bytes are exactly as served on the wire; nothing was re-encoded.

| File | What it is | How it was captured |
|---|---|---|
| `block-9091.hex` | normal block: 354 txs, announces an EB | BlockFetch |
| `block-10026.hex` | certifying block: no own txs, Leios certificate, announces EB `f951f865…` (8,534 bytes) | BlockFetch |
| `block-10027.hex` | certifying block: certifies EB `f951f865…` (237 txs per db-sync), announces the next EB | BlockFetch |
| `block-10428.hex` | normal block: 1 tx, no announcement | `./gradlew :helper:captureW27Fixtures` |
| `msg-leios-block-f951f865.hex` | LeiosFetch `MsgLeiosBlock` `[2, endorser_block]` for EB `f951f865…` | `adr/leios-evidence/leios_fetch_probe.py` |
| `msg-leios-block-txs-f951f865.hex` | LeiosFetch `MsgLeiosBlockTxs` `[4, [* #6.24(bytes .cbor block_transaction)]]`, all 237 txs | same |
| `dbsync-10027-tx-hashes.txt` | the 237 tx ids of block #10027 in block order, from db-sync (KleioScan) | KleioScan API |

Block files are the BlockFetch payload: `[8, [header, block_body]]` (the bytes inside `MsgBlock`'s `#6.24`).

The w42 endorser block point is `[slot of the announcing block, EB hash]`: `leios_fetch_probe.py 204753
f951f865a92d7a26817cce411b9d75b4e6e86315649602808f581e97f80675e5 eb10027 all <out_dir>`. Each EB entry is
`blake2b-256(tx bytes) => length(tx bytes)` over the bytes inside the `#6.24` wrapper (the block encoding, with
`is_valid`). In w40a the bytes were the mempool encoding `[body, witnesses, aux]`.

No live votes were captured: the network was idle. The vote encoding did not change from w40a
(`../w40a/vote-*.hex`, `../haskell-w42/README.md`).
