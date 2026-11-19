# Linear Leios: documentation index

Living documentation for Yaci's experimental Linear Leios (CIP-0164) support. The decisions behind the current
design are in [ADR 0016](../../adr/0016-leios-on-next-ccl-parsing-and-era-activation.md); this folder explains how to
use and maintain it.

## Documents

| Document | What it is | When to read it |
| :--- | :--- | :--- |
| [leios-usage-guide.md](leios-usage-guide.md) | **Start here as an app developer.** Complete certifying blocks, the Leios fields on `Block`, `LeiosConfig`, halts and restarts | You build an application or indexer on Yaci |
| [leios-complete-blocks-design.md](leios-complete-blocks-design.md) | How `BlockSync` and `BlockRangeSync` complete a certifying block | You change or review that code |
| [leios-musashi-source-tracking-guide.md](leios-musashi-source-tracking-guide.md) | **Start here as a maintainer.** Where the wire formats are defined, the release cadence, the re-pin procedure | A Musashi release changed the wire format |
| [leios-spec-tracking.md](leios-spec-tracking.md) | The pin matrix: which upstream release Yaci targets now | You need to know what Yaci implements right now |
| [linear-leios-support-plan.md](linear-leios-support-plan.md) | The long-term plan for Yaci and Yaci Store, with what is done | You plan the next phase of Leios work |

## Design records (in `adr/`)

- [ADR 0016](../../adr/0016-leios-on-next-ccl-parsing-and-era-activation.md): **current.** CCL parses, Yaci maps;
  complete blocks; activation by era; `LeiosConfig`.
- [ADR 0007](../../adr/0007-linear-leios-musashi-network-mini-protocols.md): the Leios N2N mini-protocols
  (`leios-notify` = 18, `leios-fetch` = 19). Still stands.
- Superseded by ADR 0016 wherever they disagree:
  [0008](../../adr/0008-leios-musashi-code-review-findings-fable.md),
  [0010](../../adr/0010-leios-musashi-serialization-and-listener-integration.md),
  [0011](../../adr/0011-dijkstra-block-restructure-musashi-w27.md),
  [0012](../../adr/0012-leios-yaci-store-parallel-sync-analysis.md) (its Decision; the analysis stands) and
  [0013a](../../adr/0013a-leios-complete-blocks.md).

## The 30-second orientation

- **Parsing.** CCL `0.8.0-pre8` reads all ledger CBOR (Dijkstra blocks, headers, transactions, EB references, EB
  transactions); Yaci maps it to its models. Yaci has no Dijkstra or Leios ledger parser of its own.
- **Activation is by era, not by network.** A Dijkstra block whose header has `leiosCertInBody == true` certifies an
  endorser block (EB). Nothing else in Yaci looks at the network magic. Pre-Dijkstra networks (mainnet, preprod,
  preview) behave exactly as on `next`.
- **Complete blocks.** `BlockSync` and `BlockRangeSync` deliver a certifying block through `onBlock` with the EB's
  transactions first. Other fetchers deliver blocks as received.
- **Musashi** (network magic 164) is the only network in Dijkstra today. It is an unstable prototype testnet that is
  respun on breaking releases, so Yaci supports it per pinned release
  (see [leios-spec-tracking.md](leios-spec-tracking.md)).
