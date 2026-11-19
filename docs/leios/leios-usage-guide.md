# Leios usage guide for application developers

Design: [ADR 0016](../../adr/0016-leios-on-next-ccl-parsing-and-era-activation.md). Fixtures: Musashi `rc-2026w42`
(`core/src/test/resources/leios/w42`).

> **Status:** experimental. Dijkstra is not final, and every Leios field and setting may change in any release.
> Musashi is respun on breaking node releases; the current pin is in [leios-spec-tracking.md](leios-spec-tracking.md).

## The model in 30 seconds

1. **Ranking blocks (RBs)** are the normal chain. A Dijkstra header may **announce** an endorser block (EB):
   `HeaderBody.ebAnnouncement` (hash and size).
2. **Endorser blocks** carry most of the transactions and travel separately. If voters approve one, the **next**
   block **certifies** it: its header has `leiosCertInBody == true`, and its body carries the Leios certificate. The
   EB's transactions enter the ledger at that block, before the block's own (in practice it has none).
3. **Yaci puts them together.** `BlockSync` and `BlockRangeSync` deliver a certifying block to `onBlock` complete:
   the EB's transactions first, in EB order, then the block's own. You use the same callback, `Block` and
   `Transaction` objects as on any other network.

Leios activates by era: only a Dijkstra block with `leiosCertInBody == true` triggers it. On a network that has not
reached Dijkstra (mainnet, preprod, preview today), the jar behaves exactly as `next`: no side connection, no extra
callbacks, the same objects on the same thread.

---

## Follow a Leios network with `BlockSync` or `BlockRangeSync`

No Leios code is needed:

```java
BlockSync blockSync = new BlockSync("leios-node.play.dev.cardano.org", 3001, 164L, wellKnownPoint);

blockSync.startSync(point, new BlockChainDataListener() {
    @Override
    public void onBlock(Era era, Block block, List<Transaction> transactions) {
        // era == Era.Dijkstra. A certifying block already holds its EB's transactions:
        // on Musashi w42, block 10,027 has no transactions of its own and arrives with the 237 of EB f951f865….
    }
});
```

`BlockRangeSync` works the same way for history.

What happens underneath, for a certifying block only:

- Inside the `onBlock` call, Yaci fetches the EB and its transactions over LeiosFetch on a **side connection** to
  the same peer. The main connection (ChainSync, BlockFetch, KeepAlive) is unchanged.
- The certified EB is the one the block's **parent** announced. Yaci remembers recent announcements; if the parent
  was not seen (for example a range that starts on a certifying block), it looks the parent up on a short-lived
  side connection.
- The EB is checked against the parent's announcement (hash and size), and each transaction against its EB entry
  (hash and size), before anything is mapped.
- This takes at most **45 s** of Yaci time per certifying block, retried within that budget. Your own callbacks
  are not bounded by it. Blocks behind it wait, because delivery is synchronous: callbacks keep their order and
  their thread.

Every other block is delivered exactly as before. An exception from your `onBlock` takes the ordinary path to
`onParsingError`, for a completed certifying block too.

### Where a transaction came from

```java
LeiosEndorserBlockRef eb = block.getLeiosEndorserBlock();  // null unless Yaci added EB transactions to this block
if (eb != null) {
    eb.getEbHash();   // the certified EB
    eb.getEbSlot();   // the slot of the block that announced it (the parent)
    eb.getEbSize();   // the announced size of the EB's references
    eb.getTxCount();  // the block's first txCount transactions came from the EB
}
```

`leiosEndorserBlock` is left out of JSON when null, so the JSON of every other block is unchanged. The header,
both certificates and `Block.getCbor()` are kept as received, so `blockBodySize` and `blockBodyHash` describe the
block as it is on chain, not the merged body.

### Leios fields on every Dijkstra block

```java
HeaderBody hb = block.getHeader().getHeaderBody();
EbAnnouncement ann = hb.getEbAnnouncement();   // nullable: the EB this block announces (getHash(), getSize())
Boolean certifies = hb.getLeiosCertInBody();   // TRUE when this block certifies its parent's EB; null before Dijkstra

LeiosCertificate cert = block.getLeiosCertificate(); // nullable
if (cert != null) {
    cert.getSigners();    // hex bit field over the committee
    cert.getSignature();  // hex aggregate signature
}
String peras = block.getPerasCertificate();          // nullable, hex
```

---

## When the EB cannot be had

Each failed attempt is reported through `BlockChainDataListener.onEndorserBlockUnavailable(EndorserBlockUnavailableEvent)`.
The event carries the certifying block (slot, hash, number), the EB hash, the reason, the attempt number and an
`outcome`:

| `outcome` | Meaning |
| :--- | :--- |
| `RETRYING` | Budget is left; Yaci tries again after a 1 s pause. |
| `DELIVERED_WITHOUT_EB_TXS` | Budget exhausted with `skipUnavailableEndorserBlocks = true`: the block is delivered as received, without the EB's transactions. |
| `SYNC_HALTED` | Budget exhausted in strict mode (the default): the block is **not** delivered, nothing more comes from this start, the main connection is closed, and `isRunning()` returns false. |

An exception thrown from `onEndorserBlockUnavailable` is logged and changes nothing.

**After `SYNC_HALTED`, restart from your own cursor**, as after any failed sync: `startSync` / `startSyncFromTip` for
`BlockSync`, or `restart(listener)` then `fetch(from, to)` for `BlockRangeSync`. The certifying block comes again,
with a fresh budget. Do not restart from inside a callback on the event loop; hand it to another thread.

---

## Settings: `LeiosConfig`

Pass it to the `BlockSync` or `BlockRangeSync` constructor; the constructors without it use
`LeiosConfig.defaultConfig()`.

```java
LeiosConfig leiosConfig = LeiosConfig.builder()
        .skipUnavailableEndorserBlocks(true)
        .build();
BlockRangeSync rangeSync = new BlockRangeSync(host, port, 164L, leiosConfig);
```

| Setting | Default | Meaning |
| :--- | :--- | :--- |
| `inlineEndorserBlockTxs` | `true` | `false`: certifying blocks are delivered as received (no EB transactions, `leiosEndorserBlock == null`), and no side connection is opened. |
| `skipUnavailableEndorserBlocks` | `false` | `false` (strict): halt the sync when the 45 s budget runs out. `true`: deliver the block without its EB transactions and report `DELIVERED_WITHOUT_EB_TXS`. |

`BlockRangeSync.fetch` works as on `next`, including from inside `batchDone`. With inlining on it also throws
`IllegalStateException` after a halt (call `restart(listener)` then `fetch`) and while a certifying block of the
current range waits for its EB. Neither happens before Dijkstra.

Each reply on the side connection (the EB, its transactions, a parent-lookup reply) must arrive within 15 s; an
attempt that misses it is retried on a fresh connection within the 45 s budget. A link that cannot carry one EB's
transactions in 15 s (about 1 MB/s for a 15 MB reply) therefore never gets them: the sync halts in strict mode.

---

## Other entry points deliver blocks as received

`N2NChainSyncFetcher` (including `start(Consumer<Block>)`), `BlockFetcher`, `BlockStreamer` and `PeerClient` do not
complete certifying blocks: such a block arrives without the EB's transactions. Use `BlockSync` or `BlockRangeSync`
if you need every ledger transaction.

**Node-to-client (`N2CChainSyncFetcher`):** the node itself inlines the EB's transactions into the certifying block
it serves, so Yaci needs nothing. If the node could not read the EB, the block arrives without transactions and
Yaci logs a warning. `Block.getLeiosEndorserBlock()` is never set over node-to-client.

---

## Dijkstra ledger fields

CCL reads them, and Yaci maps them. They are null (or empty) for earlier eras, so Conway objects and their JSON are
unchanged.

| CDDL | Yaci |
| :--- | :--- |
| tx body 14 `guards` | `TransactionBody.guards` (`List<Credential>`) |
| tx body 23 `sub_transactions` | `TransactionBody.subTransactions`: each `SubTransaction` has `body`, `witnesses`, `auxData` |
| tx body 24 `required_top_level_guards` | `TransactionBody.requiredTopLevelGuards` (`TopLevelGuard`: `credential`, `datum`) |
| tx body 25 `direct_deposits` | `TransactionBody.directDeposits` (reward account hex → coin) |
| tx body 26, 27 | `accountBalanceIntervals`, `startingAccountBalanceIntervals` (`AccountBalanceInterval`) |
| native script 6 | `NativeScriptType.REQUIRE_GUARD`; JSON `{"type": "guard", "credential": {...}}` |
| redeemer tag 6 | `RedeemerTag.Guarding` |
| pool BLS key | `PoolParams.blsKey` (`BlsKey`: `publicKey`, `proofOfPossession`) |
| protocol parameter update keys 34–56 | `ProtocolParamUpdate`: reference script limits, `maxPledgeLeverage`, `minPoolMargin`, `leios*` and `peras*` parameters, `refInputsCostPerMultiAssetPolicy`, `refInputsCostPerDatumByte` |
| Plutus V4 | `PlutusScriptType.PlutusScriptV4`; `Witnesses.plutusV4Scripts` (key 8, empty if absent); `AuxData.plutusV4Scripts` (key 5, null if absent) |
| era | `Era.Dijkstra` (block tag 8); `localstate.api.Era.Dijkstra` and `TxBodyType.DIJKSTRA` (index 7) for local state queries and submission |

**UTxO indexers:** a sub-transaction's inputs and outputs are only in `TransactionBody.subTransactions`; the
`onBlock` transaction list does not flatten them. Walk the sub-transactions of each transaction.

---

## Current limitations

- Certificates are mapped, **not** BLS-verified. Yaci trusts the node's chain selection.
- Votes are not parsed. The LeiosNotify and LeiosFetch agents stay in core for applications that speak Leios
  themselves, with payloads kept as the bytes received; there is no Leios event API in the helpers.
- A strict halt stops the sync until the application restarts it. A Yaci Store release used on a Leios network must
  handle `SYNC_HALTED` (ADR 0016, Consequences).
- A parent more than one 45 s budget's search away (a slot gap of the order of a day) is not found by search; a
  restart from an earlier point delivers the parent first.
- Local state queries in the Dijkstra era are not verified against a Musashi node.
- How long nodes keep old certified EBs is not specified.
