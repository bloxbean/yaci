# Leios Usage Guide — Patterns for Application Developers

Last verified: 2026-07-07 (Musashi `prototype-2026w27`)

How to use Yaci's Linear Leios support in your application. Everything here
targets the **Musashi** prototype testnet (network magic `164`, Dijkstra era)
and works with the same jar, unchanged, on mainnet/preprod — Leios code paths
never engage on non-Leios networks.

> **Status:** experimental, pinned to `prototype-2026w27`. Musashi respins on
> breaking releases; APIs marked here are stable in shape but the wire formats
> underneath follow the prototype. See
> [leios-musashi-source-tracking-guide.md](leios-musashi-source-tracking-guide.md).

## The mental model in 30 seconds

Musashi has two transaction "lanes":

1. **Ranking blocks (RBs)** — the normal Praos chain. Delivered through the
   `BlockChainDataListener.onBlock(...)` callback you already use. An RB's
   transaction list contains **only its own** transactions.
2. **Endorser blocks (EBs)** — Leios' throughput lane. An RB header
   *announces* an EB (`leios_announcement`); a committee votes; the next RB
   *certifies* it (`leios_certified` + certificate in the body). Certified EB
   transactions enter the ledger **without ever appearing in any RB's
   transaction list**. Yaci fetches EB data automatically over the
   `leios-fetch` mini-protocol and delivers it via `onEndorserBlock(...)`.

Under load, most Musashi transactions flow through EBs — an app that only
reads `onBlock` sees a quiet chain correctly but misses EB traffic. Consume
both callbacks if you care about all transactions.

---

## Pattern 1 — Follow Musashi like any other network (zero Leios code)

If you only need ranking blocks (headers, RB-direct transactions, rollbacks),
nothing changes — the Dijkstra era is handled inside the existing API:

```java
BlockSync blockSync = new BlockSync("leios-node.play.dev.cardano.org", 3001,
        164L, wellKnownPoint);

blockSync.startSync(point, new BlockChainDataListener() {
    @Override
    public void onBlock(Era era, Block block, List<Transaction> transactions) {
        // era == Era.Dijkstra on Musashi; Conway/Babbage/... elsewhere.
        // Same Block model, same Transaction list as every other era.
    }

    @Override
    public void onRollback(Point point) { /* unchanged */ }
});
```

`BlockSync`, `BlockRangeSync`, `BlockFetcher`, and `N2NChainSyncFetcher` all
parse Dijkstra blocks (w27 restructured body) into the existing `Block`
model: `transactionBodies`, `transactionWitness`, `auxiliaryDataMap`,
`invalidTransactions` — with byte-exact transaction body slices, so tx hashes
are correct.

### Leios fields available on every Dijkstra block

Even in this pattern, the RB↔EB linkage is parsed for you:

```java
HeaderBody hb = block.getHeader().getHeaderBody();
LeiosAnnouncement ann = hb.getLeiosAnnouncement(); // nullable: announced EB
if (ann != null) {
    String announcedEbHash = ann.getEbHash();      // hex, 32 bytes
    long announcedEbSize   = ann.getEbSize();      // bytes
}
Boolean certified = hb.getLeiosCertified();        // w27 headers: TRUE/FALSE; older: null

LeiosCertificate cert = block.getLeiosCertificate(); // nullable: parsed cert
if (cert != null) {
    cert.getSigners();             // hex bitfield over the committee
    cert.getAggregatedSignature(); // hex, 48-byte BLS signature
    cert.getCbor();                // exact raw bytes (hex)
}
```

Chain rule: an RB with `leiosCertified == TRUE` certifies the EB announced by
its **immediately preceding** RB's header (not its own announcement).

---

## Pattern 2 — Observe Endorser Blocks and votes (opt-in callbacks)

Add a `LeiosConfig` and override the new default methods. Yaci's internal
coordinator handles the whole notify → offer → fetch → assemble protocol
dance; you just receive events:

```java
LeiosConfig leiosConfig = LeiosConfig.builder()
        .fetchTxs(true)                 // fetch EB transaction bytes (default true)
        .maxTxsPerEndorserBlock(256)    // per-EB fetch cap (default 64)
        .deliverVotes(true)             // votes off by default (high volume)
        .build();                       // mode defaults to AUTO

BlockSync blockSync = new BlockSync(host, port, 164L, wellKnownPoint, leiosConfig);

blockSync.startSync(point, new BlockChainDataListener() {
    @Override
    public void onBlock(Era era, Block block, List<Transaction> txs) { /* RB lane */ }

    @Override
    public void onEndorserBlock(EndorserBlockEvent event) {
        LeiosPoint point = event.getPoint();          // (slot, ebHash)
        EndorserBlock eb = event.getEndorserBlock();
        eb.getTxRefs();                               // ordered tx-hash -> size refs
        eb.txCount();                                 // number of referenced txs
        eb.getComputedHash();                         // blake2b-256 over EB bytes
        eb.getCbor();                                 // raw EB CBOR (hex)

        for (EndorserBlockTx tx : event.getTransactions()) { // fetched tx bytes
            tx.getTxHash();                           // matches an EB tx ref
            tx.getTxCbor();                           // exact full-tx CBOR (hex)
            tx.getEra();                              // tx-level era (ns8 mapping)
        }
        boolean complete = event.isTxsComplete();     // false if capped/failed/refs-only
        String announcement = event.getAnnouncementCbor(); // nullable, best-effort
    }

    @Override
    public void onLeiosVotes(LeiosVotesEvent event) {
        for (LeiosVote vote : event.getVotes()) {
            switch (vote.getFormat()) {
                case ANNOUNCING_RB_HASH ->            // current w27 shape
                        vote.getAnnouncingRbHash();
                case SLOT_EB_HASH -> {                // earlier prototype shape
                    vote.getSlot(); vote.getEbHash();
                }
                case UNKNOWN -> vote.getCbor();       // raw only, shape drifted
            }
        }
    }
});
```

Notes:

- **Same connection.** The Leios agents ride the existing `TCPNodeClient`;
  no second socket, no separate lifecycle.
- **`AUTO` mode** (default) attaches Leios agents only when the configured
  magic is Musashi's `164` and only for tip-following clients. On any other
  network the agent list is identical to a non-Leios build.
- **Failure isolation.** EB fetch problems degrade to missing/incomplete EB
  events (`txsComplete == false`); they never stall `onBlock` or chain sync.
- Vote parsing runs only when `deliverVotes(true)` **and** your listener
  overrides `onLeiosVotes` — otherwise zero overhead.

---

## Pattern 3 — Index all ledger transactions (RB lane + EB lane)

For an indexer (yaci-store style), treat inclusion source as a first-class
dimension and correlate the two lanes by EB hash:

```java
// RB lane: persist as usual, plus the Leios linkage columns
public void onBlock(Era era, Block block, List<Transaction> txs) {
    persistBlock(block);                       // block_kind, announced_eb_hash,
                                               // announced_eb_size, certified_eb, ...
    persistTransactions(txs, Source.RB_DIRECT);

    if (Boolean.TRUE.equals(block.getHeader().getHeaderBody().getLeiosCertified())) {
        // This RB certifies the EB announced by the PREVIOUS RB.
        // Mark that EB (by parent's announcement hash) as certified;
        // its transactions became ledger-effective at THIS block's position.
        markEndorserBlockCertified(parentAnnouncedEbHash(), block);
    }
}

// EB lane: persist observations keyed by ebHash
public void onEndorserBlock(EndorserBlockEvent event) {
    persistEndorserBlock(event.getPoint(), event.getEndorserBlock()); // leios_endorser_block
    persistEbTxs(event.getPoint(), event.getTransactions(),           // leios_eb_tx
                 event.isTxsComplete());
}
```

Rules that make this correct:

- **Ledger-effective ordering:** a certified EB's transactions take the chain
  position of the **certifying** RB (which carries no transactions of its
  own). RB-direct transactions keep their RB's position.
- **Do not apply EB transactions on observation.** An EB seen via
  `onEndorserBlock` is not ledger-effective until a later RB certifies it;
  uncertified/expired EBs contribute nothing.
- **Rollbacks:** EB events carry their own slots; roll back Leios tables by
  the certifying/announcing block slots exactly like other slot-keyed data.
- **Order of arrival is not guaranteed** between an EB event and the
  `onBlock` of its announcing RB — correlate by
  `headerBody.getLeiosAnnouncement().getEbHash() == event point ebHash`,
  tolerate either order.

> Yaci does not yet produce a merged "ledger-effective transaction stream" —
> that view is planned (support plan, Phase 3). Until then the correlation
> above is the application's job.

---

## Pattern 4 — Bounded range fetch (`BlockRangeSync` / `BlockFetcher`)

```java
BlockRangeSync rangeSync = new BlockRangeSync(host, port, 164L, LeiosConfig.disabled());
rangeSync.start(listener);
rangeSync.fetch(fromPoint, toPoint);   // Dijkstra blocks parse fine
```

- Historical Dijkstra ranking blocks (headers + RB-direct txs + announcements
  + certificates) work over any range.
- **EB bodies are not available historically** — the prototype has no EB
  range fetch. Under `AUTO`, range clients don't attach Leios agents at all.
  `LeiosConfig.Mode.ENABLED` opts in, but the notify stream then emits
  *near-tip* EB events unrelated to your requested range — usually not what
  a batch job wants.
- Practical consequence: complete EB coverage requires a continuously running
  tip-follower (Pattern 2/3). If your indexer was down, the missed EB bodies
  cannot currently be backfilled from the network.

### Planned: batch EB resolution for initial sync (placeholder API)

For bulk pipelines that process blocks in batches (yaci-store's parallel
initial sync being the reference case), the intended mechanism is **one bulk
EB-closure fetch per block batch**, made at batch-assembly time before
parallel processing. The API for this already exists as a **placeholder**:

```java
LeiosEndorserBlockFetcher fetcher = LeiosEndorserBlockFetcher.unsupported();
Map<String, EndorserBlockClosure> closures = fetcher.fetchClosures(points);
// throws UnsupportedOperationException today — see below
```

Contract (stable, code against it now): input = points of **certified** EBs;
returns a map keyed by EB hash (hex); an **absent key means unresolved** —
strict pipelines fail the batch, observational pipelines record the gap.

**Why it's unimplemented (TODO):** resolving arbitrary historical EBs needs
CIP-0164's `MsgLeiosMultiBlockRequest` (bulk "EBs + all referenced
transactions", designed for catch-up). The Musashi prototype has only
offer-gated single-point requests, its batch/range messages are commented
out of the pinned CDDL, and the protocol has **no error/not-found response**
— requesting an unoffered EB risks a stalled agent or connection reset. The
placeholder deliberately throws instead of silently returning empty results,
so a pipeline wired for strict completeness fails loudly rather than
committing batches with missing transactions. Implementation lands when a
target network ships the multi/range fetch messages — see ADR 0012.

---

## Pattern 5 — Raw protocol access (`LeiosNetworkClient`)

For tooling, diagnostics, or experimenting with the mini-protocols directly —
opaque CBOR in, no coordinator policy:

```java
try (LeiosNetworkClient client = new LeiosNetworkClient(host, 3001)) { // magic 164 default
    client.addDataListener(new LeiosDataListener() {
        @Override public void onBlockOffer(LeiosPoint point, long ebSize) {
            client.requestBlock(point);                  // manual fetch decisions
        }
        @Override public void onBlock(LeiosPoint requested, LeiosRawCbor endorserBlock) {
            byte[] raw = endorserBlock.getCbor();        // opaque bytes
        }
    });
    client.start();
    // ...
}
```

Most applications should prefer Patterns 2/3; this client exists below the
serialization layer and gives you the raw prototype wire values.

---

## `LeiosConfig` reference

| Setting | Default | Meaning |
| :--- | :--- | :--- |
| `mode` | `AUTO` | `AUTO`: attach Leios agents only for magic 164 on tip-following clients. `ENABLED`: always attach (activation still requires a Leios-capable handshake against the configured magic). `DISABLED`: never attach. |
| `fetchTxs` | `true` | Fetch EB transaction bytes after both the EB and a matching tx offer are seen. `false` → refs-only events. |
| `maxTxsPerEndorserBlock` | `64` | Per-EB fetch cap. Events over the cap arrive with `txsComplete == false`. |
| `txsOfferWaitMillis` | `2000` | How long to wait for a tx offer before emitting a refs-only event. |
| `deliverVotes` | `false` | Parse + deliver votes (only when `onLeiosVotes` is overridden). |

## Behavior on mainnet / preprod

With `AUTO` (the default everywhere), a build of your app containing all of
the above runs on mainnet with a byte-identical network profile to a
non-Leios Yaci: no Leios agents attach, no Leios callbacks fire, all parsing
paths for Shelley–Conway are unchanged. You do not need separate builds or
feature flags of your own.

## Current limitations (read before relying on this)

- `onBlock` is the only ledger-authoritative stream; `onEndorserBlock` /
  `onLeiosVotes` are observational until the merged ledger-effective view
  lands.
- EB data is near-tip only; no historical EB backfill (prototype limitation).
  `LeiosEndorserBlockFetcher` (batch closure resolution for initial sync) is
  a placeholder that throws `UnsupportedOperationException` until the final
  protocol's multi/range fetch exists.
- Dijkstra transaction bodies: key 14 is `guards` — keyhash-variant guards
  surface under `TransactionBody.requiredSigners`; credential-variant guards
  fail loudly via `onParsingError`. `sub_transactions` (key 23) and keys
  25/26 are not yet modeled.
- Certificates are parsed and raw-preserved, **not** BLS-verified.
- Vote shapes have already changed twice upstream; `LeiosVoteFormat.UNKNOWN`
  + raw CBOR is the escape hatch when they change again.
- Musashi respins on breaking node releases; this guide and the code are
  pinned to `prototype-2026w27`. Check the
  [pin matrix](leios-spec-tracking.md) before assuming compatibility.
