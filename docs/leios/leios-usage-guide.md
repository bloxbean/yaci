# Leios Usage Guide: Patterns for Application Developers

Last verified: 2026-10-19 (Musashi `prototype-2026w40a`). Design: `adr/0013-leios-complete-blocks.md`.

How to use Yaci's Linear Leios support in your application. Everything here targets the **Musashi** test
network (network magic `164`, Dijkstra era) and works with the same jar, unchanged, on mainnet and preprod:
Leios code paths never engage on non-Leios networks.

> **Status:** experimental, pinned to the deployed Musashi build `prototype-2026w40a`. Musashi is respun on
> breaking releases; the pin and how to re-check it are in `adr/leios-todo.md` and
> [leios-musashi-source-tracking-guide.md](leios-musashi-source-tracking-guide.md).

## The mental model in 30 seconds

1. **Ranking blocks (RBs)** are the normal chain. A ranking block may **announce** an endorser block (EB) in its
   header.
2. **Endorser blocks (EBs)** carry most of the transactions. They travel separately. If voters approve one, the
   **next** ranking block **certifies** it, and the EB's transactions enter the ledger at that block's position,
   before its own transactions (in practice a certifying block has none of its own).
3. **Yaci puts them together for you.** By default a certifying block reaches `onBlock` **complete**: the EB's
   transactions first, then the block's own. You use the same callback, `Block` and `Transaction` objects as on
   any other network, and you see every ledger transaction.

---

## Pattern 1: Follow Musashi like any other network (no Leios code)

```java
BlockSync blockSync = new BlockSync("leios-node.play.dev.cardano.org", 3001, 164L, wellKnownPoint);

blockSync.startSync(point, new BlockChainDataListener() {
    @Override
    public void onBlock(Era era, Block block, List<Transaction> transactions) {
        // era == Era.Dijkstra on Musashi. A certifying block already contains its EB's transactions:
        // block #156614 arrives with 5,255 transactions although it is 169 bytes on chain.
    }

    @Override
    public void onRollback(Point point) { /* unchanged: EB transactions roll back with their block */ }
});
```

The same works for `BlockRangeSync` (history), including old certifying blocks: Yaci fetches certified EBs
on request (verified on Musashi for EBs four weeks old).

What happens underneath:

- Yaci opens a second, small node-to-node connection to the same peer for EBs only. The main connection is
  unchanged.
- A certifying block waits until its EB transactions are fetched and checked (each one against the EB's
  reference hash). Blocks behind it wait too, so `onBlock`, `onRollback`, `batchDone` and the other callbacks
  keep their order. All callbacks are delivered on one thread.
- If an EB cannot be fetched, Yaci retries and reports it through `onEndorserBlockUnavailable`; it never
  delivers the block with transactions missing (see the settings below).
- Callbacks run on a Yaci worker thread (`yaci-leios-complete-blocks`), one at a time, in order. An exception
  from `onBlock` reaches `onParsingError`, as before. `stop()` drops events still queued and never interrupts
  your callback.

### Where a transaction came from (optional)

```java
LeiosEndorserBlockRef eb = block.getLeiosEndorserBlock();  // null unless the block certifies an EB
if (eb != null) {
    eb.getEbHash();   // the certified EB
    eb.getEbSlot();   // its slot (= the announcing block's slot)
    eb.getTxCount();  // the block's first txCount transactions came from the EB
}
```

### Leios fields available on every Dijkstra block

```java
HeaderBody hb = block.getHeader().getHeaderBody();
LeiosAnnouncement ann = hb.getLeiosAnnouncement();  // nullable: the EB this block announces
Boolean certified = hb.getLeiosCertified();         // TRUE when this block certifies its parent's EB

LeiosCertificate cert = block.getLeiosCertificate(); // nullable: parsed certificate
if (cert != null) {
    cert.getSigners();             // hex bitfield over the committee
    cert.getAggregatedSignature(); // hex, 48-byte BLS signature
    cert.getCbor();                // exact raw bytes (hex)
}
```

`blockBodySize` and `blockBodyHash` in the header always describe the block as it is on chain.

---

## Pattern 2: Handle endorser blocks yourself (inlining off)

If your application wants the block exactly as on the wire and to process EBs on its own, switch inlining off
(globally, like `returnFullTxCbor`):

```java
YaciConfig.INSTANCE.setInlineEndorserBlockTxs(false);
```

`onBlock` then receives certifying blocks as today (no EB transactions, `getLeiosEndorserBlock() == null`).
The certified EB is the one announced in the parent block's header. EB data arrives through the optional
callbacks below.

## Pattern 3: Observe endorser blocks and votes as they appear (opt-in callbacks)

These callbacks report what the node announces near the tip. They work with inlining on or off.

```java
LeiosConfig leiosConfig = LeiosConfig.builder()
        .fetchTxs(true)                 // fetch EB transaction bytes (default true)
        .maxTxsPerEndorserBlock(256)    // per-EB fetch cap (default 64)
        .deliverVotes(true)             // votes off by default (high volume)
        .build();                       // mode defaults to AUTO

BlockSync blockSync = new BlockSync(host, port, 164L, wellKnownPoint, leiosConfig);

blockSync.startSync(point, new BlockChainDataListener() {
    @Override
    public void onEndorserBlock(EndorserBlockEvent event) {
        LeiosPoint point = event.getPoint();          // (slot, ebHash)
        EndorserBlock eb = event.getEndorserBlock();
        eb.getTxRefs();                               // ordered reference hash -> size
        for (EndorserBlockTx tx : event.getTransactions()) {
            tx.getTxHash();                           // EB reference hash (of the full tx bytes), not the tx id
            tx.getTxCbor();                           // full tx CBOR (hex)
        }
        boolean complete = event.isTxsComplete();     // false if capped, failed or refs only
    }

    @Override
    public void onLeiosVotes(LeiosVotesEvent event) {
        for (LeiosVote vote : event.getVotes()) {
            vote.getAnnouncingRbHash();               // w40a vote: [announcing_rb_hash, voter_id, signature]
        }
    }
});
```

Notes:

- An EB seen here is **not** part of the ledger until a later block certifies it; uncertified EBs are
  discarded by the network. With inlining on you do not need these events for ledger data.
- Events depend on what the peer offers; they are not guaranteed for every EB.
- Vote parsing runs only when `deliverVotes(true)` **and** your listener overrides `onLeiosVotes`.

---

## Pattern 4: Raw protocol access (`LeiosNetworkClient`)

For tooling and diagnostics: opaque CBOR in, no policy.

```java
try (LeiosNetworkClient client = new LeiosNetworkClient(host, 3001)) { // magic 164 default
    client.addDataListener(new LeiosDataListener() {
        @Override public void onBlock(LeiosPoint requested, LeiosRawCbor endorserBlock) {
            byte[] raw = endorserBlock.getCbor();
        }
    });
    client.start();
    client.requestBlock(point);   // after onLeiosActivated
}
```

---

## Settings

| Setting | Default | Meaning |
| :--- | :--- | :--- |
| `YaciConfig.inlineEndorserBlockTxs` | `true` | Deliver certifying blocks with their EB transactions. `false`: blocks as on the wire (Pattern 2). |
| `LeiosConfig.skipUnavailableEndorserBlocks` | `false` | `false`: never deliver a certifying block without its EB transactions; wait and retry. `true`: after 3 failed attempts deliver it without them (reported through `onEndorserBlockUnavailable`). |
| `LeiosConfig.mode` | `AUTO` | `AUTO`: Leios only on Musashi (magic 164). `ENABLED`: always. `DISABLED`: never. |
| `LeiosConfig.fetchTxs` | `true` | Pattern 3 events: fetch EB transaction bytes. |
| `LeiosConfig.maxTxsPerEndorserBlock` | `64` | Pattern 3 events: per-EB fetch cap. |
| `LeiosConfig.txsOfferWaitMillis` | `2000` | Pattern 3 events: wait for a transactions offer before a refs-only event. |
| `LeiosConfig.deliverVotes` | `false` | Parse and deliver votes. |

## Behavior on mainnet / preprod

With `AUTO` (the default everywhere), your app runs on mainnet exactly as with a non-Leios Yaci: no second
connection, no wrapping of your listener, no Leios callbacks, and the Shelley–Conway parsing path is unchanged.
You do not need separate builds or feature flags.

## Current limitations

- Certificates are parsed and kept raw, **not** BLS-verified. Yaci trusts the node's chain selection.
- Dijkstra transaction bodies: key 14 is `guards`. Key-hash guards appear in `TransactionBody.requiredSigners`;
  script guards are not modelled yet. Sub-transactions (key 23), keys 24–27, native-script `require_guard` and
  the guarding redeemer tag are not modelled yet.
- The pool registration `bls_key` is accepted but not exposed.
- How long nodes keep old certified EBs is not specified yet; four weeks is verified on Musashi.
- If the first block after a start certifies an EB and its parent is more than 1,000 slots older (a long block
  gap), the parent search fails and the block waits; tip sync uses the intersection point and does not search.
- With inlining on, the Leios connection also receives LeiosNotify traffic, and Pattern 3 (if enabled on the main
  connection) fetches some EB transactions a second time. This costs bandwidth only.
- Musashi is respun on breaking node releases; this guide and the code are pinned to `prototype-2026w40a`.
  Check `adr/leios-todo.md` before assuming compatibility.
