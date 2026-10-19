# Leios complete blocks: design

How Yaci turns Linear Leios blocks into complete blocks for applications, without changing Yaci's API.
Decision record: `adr/0013-leios-complete-blocks.md`.

The diagrams follow the C4 model, from the outside in. Images are generated from the PlantUML sources in
`diagrams/` (see `diagrams/README.md`); PNG copies sit next to each SVG.

1. System context: who talks to whom.
2. Containers: the Yaci modules involved.
3. Components: the parts of the new complete-block assembler.
4. Dynamic views: what happens, step by step, for tip sync, range sync, failures and rollbacks.

---

## 0. Leios in one picture

![Leios in one picture](diagrams/0-leios-overview.svg)

- RB n+1 certifies the EB announced by its **parent** RB n (found through `prevHash`).
- Certification happens at least 14 slots after the announcement on Musashi's parameters
  (CIP-0164: 3 × header diffusion + vote period + diffusion period = 3 × 1 + 4 + 7 slots).

What an application must see for RB n+1: one block with the 5,255 EB A transactions. That is the default.

---

## 1. System context (C4 level 1)

![System context (C4 level 1)](diagrams/1-c4-context.svg)

---

## 2. Containers (C4 level 2): Yaci modules involved

![Containers (C4 level 2)](diagrams/2-c4-container.svg)

On mainnet and preprod the assembler sees no certifying blocks and passes every block straight through.
The Leios agents are attached only when Leios is active (`LeiosConfig` `AUTO`: Musashi magic 164),
for both tip sync and range sync.

---

## 3. Components (C4 level 3): inside the complete-block assembler

![Components (C4 level 3)](diagrams/3-c4-component.svg)

| Part | Holds | Rule |
|---|---|---|
| Announcement memory | announcements of recent RBs, by RB hash | look up by the certifying block's `prevHash`; when sync starts from a point, fetch that block once to learn its announcement |
| EB resolver | the EB being fetched | ask only for **certified** EBs (exception: an early fetch at the tip of an EB this peer offered); check `blake2b-256(tx bytes as received) == EB reference hash` |
| Reorder buffer | the first waiting block and every block after it | release strictly in chain order; never skip a block |
| Merger | nothing | certifying block + EB transactions → complete block |

---

## 4. The complete block (`inlineEndorserBlockTxs = true`, default)

What changes in a certifying block (RB n+1 in the picture above) before it reaches the application:

![A certifying block before and after](diagrams/4-complete-block.svg)

With `inlineEndorserBlockTxs = false`, the block is delivered as it is on the wire (left side, plus
`leiosEndorserBlock` with txCount 0 as the pointer), and the EB with its transactions arrives through the
optional `onEndorserBlock(EndorserBlockEvent)` callback.

Every block that certifies nothing is delivered exactly as before.

---

## 5. Dynamic view: tip sync (BlockSync)

![Tip sync](diagrams/5-tip-sync.svg)

Without an offer, the EB is fetched when the certifying block arrives. Either way the application receives
RB n+1 only once it is complete.

---

## 6. Dynamic view: range sync (BlockRangeSync)

![Range sync](diagrams/6-range-sync.svg)

- Yaci never stops reading the connection: the EB answers arrive on the same connection as the blocks.
  Memory stays bounded because the block range is requested in parts.
- Yaci Store's batch of 100 blocks receives complete blocks in order, and `batchDone` arrives after the last
  block of the range, through the same ordered queue. Its parallel pipeline is unchanged.

---

## 7. Failures, retries and rollbacks

| EB resolver result | `skipUnavailableEndorserBlocks = false` (default) | `= true` (opt-in) |
|---|---|---|
| resolved, every transaction checks | merge and release | merge and release |
| not found, timeout or a transaction that does not match | report `onEndorserBlockUnavailable`, retry with a growing delay; delivery waits (a visible pause, no gap) | report `onEndorserBlockUnavailable`, release the block without the EB transactions |
| peer has no Leios protocols | fail at start with a clear message | same |

A transaction that does not match the EB is never delivered: it is treated like "not found".

Rollback: the node rolls back ranking blocks. EB transactions belong to the certifying block, so they roll back
with it. The assembler drops buffered blocks and announcements after the rollback point, then delivers
`onRollback` through the ordered queue. Nothing new is needed in the application.

---

## 8. What is pinned to which network version

| Piece | Deployed Musashi (`prototype-2026w40a`) | Next respin (`rc-2026w42`) |
|---|---|---|
| RB body | `[txs, leios_cert, peras_cert]`, tx = `[body, wits, aux, is_valid]` | same |
| LeiosFetch tags | BlockRequest 0, Block 1, TxsRequest 2, BlockTxs 3 | renumbered: 1, 2, 3, 4 |
| EB transactions | `[point, bitmaps, [* bytes]]`, mempool encoding | `[* #6.24(block_transaction)]` |
| Old certified EBs served | yes (tested 4 weeks back, first 8 txs) | untested |
| Request the node cannot answer | empty answer | connection is closed |

The application-facing behaviour is the same for both. Only the codecs change; they are tracked in
`adr/leios-todo.md`.
