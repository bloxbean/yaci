# Linear Leios (Musashi) — Documentation Index

Living documentation for Yaci's Linear Leios (CIP-0164) support on the
**Musashi** prototype testnet. Everything in this folder is expected to be
edited as the prototype evolves — point-in-time design decisions live in
[`adr/`](../../adr/) instead.

## Documents

| Document | What it is | When to read it |
| :--- | :--- | :--- |
| [leios-musashi-source-tracking-guide.md](leios-musashi-source-tracking-guide.md) | **Start here.** How the wire formats are defined upstream, the weekly release cadence, which repos to watch, and the step-by-step re-pin procedure when the network changes | You're maintaining Leios support, or a `prototype-2026wXX` release just dropped |
| [leios-spec-tracking.md](leios-spec-tracking.md) | The **pin matrix** — exactly which upstream commits/releases the current Yaci implementation targets, and its status | You need to know what Yaci implements *right now* |
| [linear-leios-support-plan.md](linear-leios-support-plan.md) | The long-term plan for Yaci and Yaci Store: prototype compatibility through full CIP-0164 support, phases, risks, store schema sketches | You're planning the next phase of Leios work |

## Related design records (immutable, in `adr/`)

- [ADR 0007](../../adr/0007-linear-leios-musashi-network-mini-protocols.md) —
  Leios N2N mini-protocols (`leios-notify` = 18, `leios-fetch` = 19),
  transport-only, opaque payloads
- [ADR 0008](../../adr/0008-leios-musashi-code-review-findings-fable.md) —
  code-review findings for the mini-protocol implementation
- [ADR 0010](../../adr/0010-leios-musashi-serialization-and-listener-integration.md) —
  serialization layer + `BlockChainDataListener` integration design
- [ADR 0011](../../adr/0011-dijkstra-block-restructure-musashi-w27.md) —
  the prototype-2026w27 Dijkstra block restructure: why the parser gap
  happened and the re-pin remediation

## The 30-second orientation

Musashi (network magic **164**, Dijkstra era) is an unstable weekly-release
prototype testnet — it does **not** speak final CIP-0164. Its wire truth is
the `leios-prototype` branch of
[cardano-scaling/cardano-blueprint](https://github.com/cardano-scaling/cardano-blueprint),
deployed via
[`prototype-2026wXX` releases](https://github.com/input-output-hk/ouroboros-leios/releases)
that can **respin the network** (chain reset) on breaking changes. Yaci
therefore supports Musashi *per pinned release tag*, never open-ended — see
the guide for the full model and procedure.
