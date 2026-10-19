# Musashi w40a fixtures

Captured on 2026-10-19 from the public Musashi relay `leios-node.play.dev.cardano.org:3001`
(network magic 164), which runs ouroboros-leios `prototype-2026w40a`.
Tool: `./gradlew :helper:captureW27Fixtures -PfixtureDir=<dir> -PfixtureSeconds=300`.

| File | What it is |
|---|---|
| `block-162998.hex` | certifying block: no own txs, Leios certificate, announces the next EB |
| `block-162999.hex` | certifying block: no own txs, Leios certificate, no announcement |
| `block-163000.hex` | normal block: 354 txs, announces an EB |
| `block-163003.hex` | normal block: 274 txs, no announcement |
| `block-163013.hex` | normal block: 362 txs, announces an EB, header protocol version 12.67 |
| `endorser-block-3639947.hex` | an endorser block (`MsgLeiosBlock` payload: map tx hash -> size) |
| `vote-*.hex` | votes: `[announcing_rb_hash, voter_id, signature]` |

Block files are the BlockFetch payload: `[8, [header, block_body]]`.
