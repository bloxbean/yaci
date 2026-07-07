# Linear Leios Support Plan for Yaci and Yaci Store

Last reviewed: 2026-06-26

## Scope

This document captures the expected work to support Cardano Ouroboros Linear Leios in:

- Yaci core/helper/events in this repository.
- Yaci Store in `/Users/satya/work/bloxbean/yaci-store`.
- The current Musashi Dojo public prototype testnet.

It separates prototype-testnet compatibility from durable CIP-0164 support because the current prototype is not yet wire-format compatible with the published CIP.

## Source Baseline

Public sources reviewed:

- CIP-0164, "Ouroboros Linear Leios - Greater transaction throughput": https://cips.cardano.org/cip/CIP-0164
- User-specified CIP branch: https://github.com/cardano-scaling/CIPs/blob/leios/CIP-0164/README.md
- Leios public site and Musashi Dojo entry point: https://leios.cardano-scaling.org/ and https://www.musashi.network/
- Musashi Dojo getting-started guide: https://leios.cardano-scaling.org/docs/testnet/getting-started/
- Musashi Dojo stake-pool guide: https://leios.cardano-scaling.org/docs/testnet/register-stake-pool/
- Leios roadmap: https://leios.cardano-scaling.org/docs/roadmap/
- Ouroboros Leios repository: https://github.com/input-output-hk/ouroboros-leios
- Prototype 2026w25 release notes: https://github.com/input-output-hk/ouroboros-leios/releases/tag/prototype-2026w25
- Cardano Ledger `leios-prototype` branch: https://github.com/IntersectMBO/cardano-ledger/tree/leios-prototype

Local sources reviewed:

- Yaci repository at `/Users/satya/work/bloxbean/yaci`.
- Yaci Store repository at `/Users/satya/work/bloxbean/yaci-store`.
- Ouroboros Leios checkout at `/Users/satya/work/cardano-comm-projects/ouroboros-leios`, branch `main`, commit `972d5a9a2dddb52b82dda7308f025729c0c55cc2`.
- Local Leios testnet config under `/Users/satya/work/cardano-comm-projects/ouroboros-leios/testnet/config`.

Important source caveat:

- The `prototype-2026w25` release notes explicitly say that the current prototype network mini-protocols and CDDL wire formats have evolved and are not yet consistent with CIP-0164. For current testnet support, use the `leios-prototype` branches of `cardano-blueprint` and `cardano-ledger` as the operative format sources. For long-term support, keep CIP-0164 as the product/protocol target.

## Current State

### CIP-0164 Target

Linear Leios keeps the Praos chain as a sequence of ranking blocks (RBs) and adds endorser blocks (EBs) for additional transaction capacity. EBs reference transaction hashes and sizes rather than carrying full transaction bodies. Committee votes are aggregated into certificates. A later RB can include a certificate for a previously announced EB, at which point the EB's transaction closure affects ledger state.

The long-term protocol surface includes:

- New or extended ledger era, currently discussed around Dijkstra and later hard-fork scoping.
- Extended RB header fields for announced EB hash/size and certified EB indicator.
- RB body support for either normal transactions or an EB certificate.
- EB structure with ordered transaction references.
- Vote and certificate structures using BLS12-381 aggregate signatures.
- New or updated N2N mini-protocols for EB/vote notification and EB/transaction fetching.
- Client interface updates, likely including a backward-compatible merged block representation for applications that expect blocks to contain transactions directly.

### Current Public Testnet

Musashi Dojo is a public Leios prototype testnet. As of the reviewed docs:

- It is intended to run from late June 2026 through the mainnet hard fork with successive releases from prototypes to release candidates.
- The current guide describes `prototype-2026w25`, node version `cardano-node 11.0.1.164`.
- Network magic is `164`.
- Bootstrap relay is `leios-node.play.dev.cardano.org:3001`.
- The chain tip is in the Dijkstra era in the guide examples.
- The testnet is explicitly unstable: chain resets, config repinning, and instruction changes are expected.
- The early phase is focused on node population, sync behavior, IO-driven load, DApp/tool testing, and feedback.
- The local testnet config includes `ExperimentalProtocolsEnabled: true`, a `DijkstraGenesisFile`, and `LeiosDbConfig` using SQLite `leios.db`.

Current testnet traces to watch include `LeiosBlockForged`, `LeiosBlockCertified`, `LeiosBlockAcquired`, `LeiosBlockTxsAcquired`, `CertRBStaged`, and `CertRBReleased`.

## What Changes for Yaci

Yaci is close to the consensus wire surface, so it is directly impacted.

### Era and Versioning

Current Yaci `Era` stops at `Conway`. Add a new Dijkstra-era representation and avoid assuming that the enum integer value alone is sufficient until the final hard-fork combinator era tag is confirmed.

Work:

- Add `Era.Dijkstra` and update `EraUtil`, metadata, docs, and tests.
- Add a feature flag for prototype Leios parsing, for example `yaci.leios.prototype.enabled`.
- Track exact source hashes for prototype decoding: Leios release tag, cardano-ledger branch commit, cardano-blueprint branch commit.

### Header Decoding

Current `BlockHeaderSerializer` assumes post-Babbage headers have fixed positions ending at operational certificate and protocol version. Leios adds optional fields and current prototype formats may not match the CIP shape.

Work:

- Refactor header decoding to be era-aware instead of using only "last field is protocol version" inference.
- Add a Leios/Dijkstra header model with:
  - `announcedEbHash`
  - `announcedEbSize`
  - `certifiedEb`
  - raw header body CBOR, for forward compatibility
- Preserve current Conway behavior unchanged.
- Add golden CBOR tests for Conway and Dijkstra/Leios headers.

### Block Body Decoding

Current `Block` expects normal transaction bodies, witness sets, auxiliary data, and invalid transaction indexes. Leios RBs may carry a certificate instead of direct transactions, or a client-facing merged representation may inline EB transactions.

Work:

- Introduce a block-kind model:
  - normal Praos block
  - transaction RB (`TxRB`)
  - certificate RB (`CertRB`)
  - merged Leios block if the node/client interface serves inlined EB transactions
- Add `LeiosCertificate` model with slot, EB hash, signer bitfield, aggregate signature, and raw CBOR.
- Add `EndorserBlock` model with ordered transaction references and raw CBOR.
- Add a "ledger-effective transactions" view that existing helper users can consume without understanding EB internals.
- Keep raw block CBOR support enabled in prototype mode to survive parser gaps.

### ChainSync and BlockFetch

For current Yaci, ChainSync roll-forward carries headers and BlockFetch returns block bytes. Full Leios support may require extra EB closure fetching, not just RB fetching.

Short-term:

- Connect Yaci to a local Leios-enabled node that has already handled LeiosFetch/staging internally.
- First determine what the local node serves through N2N BlockFetch and N2C LocalChainSync for `prototype-2026w25`: native RB, merged block, or another prototype-specific shape.
- Make Yaci tolerate CertRBs without crashing even before Store processes EB transactions.

Long-term:

- Implement native Leios N2N support only after mini-protocol IDs, state machines, and CDDLs stabilize in cardano-blueprint.
- Add LeiosNotify and LeiosFetch agents if Yaci is expected to act as a data node rather than only as a client of a full Leios node.
- Implement EB closure fetch, transaction reference resolution, freshest-first request policy, and bounded caches.
- Decide whether Yaci should ever process raw votes. Votes are high volume and probably should be optional/off by default for indexer use cases.

### Local Clients and Queries

Transaction submission and mempool monitoring are expected to remain mostly unchanged. Local state queries may need extensions for Leios protocol parameters, committee state, BLS key registrations, and Dijkstra-era genesis/protocol params.

Work:

- Verify current local-state queries against the Musashi node.
- Add Dijkstra query wrappers only where cardano-node exposes stable query constructors.
- Avoid blocking Store's current-testnet support on committee/BLS query support unless needed for explorer features.

## What Changes for Yaci Store

Yaci Store is impacted both by data shape and throughput.

### Existing Pipeline Impact

Store currently consumes `BlockEvent`, `BlockHeaderEvent`, and `TransactionEvent` emitted from Yaci models. Existing processors assume a block has a single list of direct transactions. This can continue for low-risk compatibility if Yaci provides a merged ledger-effective transaction list, but Store should also persist Leios-native metadata.

### Proposed Store Model

Add a dedicated Leios store module rather than overloading existing block/transaction tables.

Suggested modules:

- `stores:leios`
- `stores-api:leios-api`
- `starters:leios-spring-boot-starter`

Suggested tables:

- `leios_endorser_block`
  - `eb_hash`
  - `announced_by_block_hash`
  - `announced_by_block_number`
  - `announced_slot`
  - `announced_eb_size`
  - `tx_count`
  - `tx_bytes`
  - `certified`
  - `certified_by_block_hash`
  - `certified_by_block_number`
  - `raw_cbor` or raw-cbor pointer if enabled
- `leios_eb_tx`
  - `eb_hash`
  - `tx_hash`
  - `tx_index`
  - `tx_size`
  - `ledger_block_hash`
  - `ledger_block_number`
  - `ledger_slot`
- `leios_certificate`
  - `rb_hash`
  - `rb_number`
  - `slot_no`
  - `eb_hash`
  - `signers`
  - `aggregated_signature`
  - `raw_cbor`
- `leios_vote` only if explicitly enabled
  - high-volume table, disabled by default
  - likely useful for diagnostics, not normal indexing

Extend `block` table only for small, query-critical fields:

- `block_kind`
- `announced_eb_hash`
- `announced_eb_size`
- `certified_eb`
- `certified_eb_hash`

Extend transaction metadata carefully:

- Existing `transaction.tx_hash` should remain unique.
- Do not force EB provenance into the primary transaction row if the same transaction can be referenced more than once or observed before final ledger inclusion.
- Use relation tables to represent RB/EB provenance.
- Define whether existing transaction APIs return:
  - direct RB transactions only,
  - ledger-effective transactions,
  - or both with a source flag.

Recommended default:

- Existing APIs should return ledger-effective transactions so downstream users keep working.
- New Leios APIs should expose native RB/EB/certificate relationships.

### Rollback and Pruning

Current Store rollback deletes by slot. Leios adds objects announced at one slot and certified/applied at a later slot.

Work:

- Roll back Leios-native tables by ledger slot for certified/application state.
- Keep or delete uncertified EB observations based on source slot and retention policy.
- Ensure `leios_eb_tx` rows tied to a rolled-back CertRB are removed even if the EB was announced before the rollback point.
- Add pruning controls for raw CBOR, EB closures, and diagnostic votes.

### Performance Impact

Leios is intended to increase throughput by an order of magnitude or more. Store writes that are acceptable today may become bottlenecks.

Work:

- Batch Leios-native table writes.
- Add indexes only for expected queries; avoid indexing raw high-volume diagnostic data.
- Test large block/EB transaction batches against PostgreSQL and MySQL.
- Validate parallel processing behavior around epoch transitions and rollback.
- Revisit transaction, UTXO, asset, script, governance, and analytics processors under sustained higher throughput.

## Current Testnet Support Plan

Goal: make Yaci and Yaci Store useful on Musashi Dojo quickly without pretending the prototype is stable.

### Step 1: Run Through a Local Leios Node

Use the Leios-provided relay setup:

- `nix run github:input-output-hk/ouroboros-leios#leios-testnet-relay`, or
- the prebuilt `prototype-2026w25` binaries, or
- the Docker image published for the release.

Yaci should connect to the local node rather than the public bootstrap relay initially. The local node handles prototype-specific Leios behavior, including EB fetch/staging.

### Step 2: Add Prototype Network Configuration

Add sample config for:

- host `127.0.0.1`
- port `3010`
- protocol magic `164`
- Dijkstra-era enabled
- raw block CBOR enabled
- Leios prototype mode enabled

### Step 3: Smoke-Test ChainSync and BlockFetch

Deliverables:

- Confirm handshake version negotiated by Yaci with `cardano-node 11.0.1.164`.
- Confirm roll-forward headers decode past Dijkstra blocks.
- Confirm block fetch can retrieve blocks around known EB/cert activity.
- Capture raw CBOR fixtures for:
  - TxRB
  - CertRB
  - block with announced EB
  - block with no EB activity

Exit criteria:

- Yaci can follow the testnet tip without parser crashes.
- Yaci Store can persist standard block and transaction rows for any merged/normal blocks served by the node.
- Leios-specific prototype data is at least stored as raw CBOR and surfaced in logs/metrics.

### Step 4: Prototype Leios Decoding

Use `prototype-2026w25` plus matching `cardano-ledger`/`cardano-blueprint` prototype branches, not CIP-0164, as the decoding baseline.

Deliverables:

- Dijkstra header parser.
- Prototype RB/certificate parser.
- Test fixtures committed under `core/src/test/resources`.
- A compatibility note saying this parser is intentionally prototype-specific.

### Step 5: Minimal Store Visibility

Deliverables:

- Store `block_kind`, EB announcement hash/size, and certified flag.
- Add `leios_certificate` rows if the prototype block body exposes certificate bytes.
- Add an optional log-ingestion or metrics adapter for Leios trace events from `node.log` as a temporary diagnostic path.

Non-goals for current prototype:

- Do not implement full BLS verification in Yaci.
- Do not implement Yaci-native LeiosFetch unless required by the local node interface.
- Do not make schema guarantees based only on the prototype.
- Do not promise stable Blockfrost-compatible Leios APIs yet.

## Full CIP-0164 Support Plan

### Phase 0: Spec Tracking and Fixture Collection

Duration: 1-2 weeks, then continuous.

Tasks:

- Track CIP-0164, cardano-blueprint `leios-prototype` or successor branch, cardano-ledger Leios branch, and Musashi releases.
- Create a `docs/leios/leios-spec-tracking.md` matrix with source commits and format deltas.
- Collect golden CBOR fixtures from official node/ledger tests.
- Decide final Yaci public model names before APIs leak.

Exit criteria:

- There is a single pinned source set for prototype support and another for intended mainline support.
- Parser tests are driven by fixtures, not only hand-written assumptions.

### Phase 1: Yaci Prototype Compatibility

Duration: 2-4 weeks.

Tasks:

- Add Dijkstra era handling.
- Make header/block parsers prototype-aware and tolerant.
- Run Yaci against a local Musashi node.
- Add configuration docs for current testnet.

Exit criteria:

- Yaci can sync current Musashi testnet blocks through a local Leios node.
- Failures produce actionable parse errors with block hash/slot/raw CBOR captured.

### Phase 2: Yaci Core Native Leios Models

Duration: 4-6 weeks.

Tasks:

- Add stable models for RB, EB, certificate, vote, and transaction references.
- Add native CDDL-driven serializers/deserializers.
- Add merged ledger-effective transaction view.
- Add conformance tests against official fixtures.
- Keep prototype and mainline decoders isolated where formats differ.

Exit criteria:

- Core can decode Leios block structures without Store.
- Existing Conway/Babbage tests remain unchanged.
- Public helper APIs can expose either native Leios structures or ledger-effective transactions.

### Phase 3: Yaci Store Leios Indexing

Duration: 4-6 weeks.

Tasks:

- Add Leios store module and migrations.
- Add event types such as `LeiosEndorserBlockEvent`, `LeiosCertificateEvent`, and `LeiosLedgerTransactionsEvent` if needed.
- Persist EB/certificate/provenance data.
- Update rollback processors.
- Add basic Leios Store APIs.

Exit criteria:

- Store can answer:
  - Which EB did this RB announce?
  - Which EB did this RB certify?
  - Which transactions entered the ledger through a certified EB?
  - Which block/slot gives the ledger-effective inclusion point for a transaction?

### Phase 4: Native Leios Mini-Protocols

Duration: 6-10 weeks after protocol specs stabilize.

Tasks:

- Implement LeiosNotify and LeiosFetch agents.
- Add protocol IDs and handshake/version support.
- Add EB closure fetch and bounded transaction cache.
- Add fetch decision logic and backpressure.
- Add integration tests against Leios node/devnet.

Exit criteria:

- Yaci can fetch enough Leios-native data without relying on a full node to provide merged blocks.
- Native protocol support can be enabled/disabled independently from normal ChainSync/BlockFetch.

### Phase 5: Throughput, Reliability, and Storage Hardening

Duration: 4-8 weeks, then continuous.

Tasks:

- Run sustained high-throughput Store benchmarks.
- Test rollback across EB announcement/certification boundaries.
- Measure database write amplification.
- Tune batch sizes and parallel processors.
- Add metrics for EB fetch lag, certified EB count, effective tx throughput, parser failures, and DB lag.

Exit criteria:

- Store keeps up with target testnet load on reference hardware.
- Rollback and restart are validated with Leios data present.

### Phase 6: Release Candidate and Mainnet Readiness

Duration: aligned with Leios release-candidate schedule.

Tasks:

- Track Dijkstra/mainnet hard-fork versions.
- Remove prototype-only behavior from default configs.
- Publish migration and upgrade notes.
- Validate against Preview/Preprod when Leios moves there.
- Coordinate API semantics with downstream consumers.

Exit criteria:

- Yaci and Yaci Store support the release-candidate node formats.
- Prototype support is clearly marked legacy or removed.
- Existing non-Leios networks are unaffected.

## Key Risks

### Specification and Format Drift

Risk: CIP-0164, cardano-blueprint, cardano-ledger, and Musashi prototype formats are not identical today.

Mitigation:

- Pin every prototype implementation to a release tag and source commit.
- Keep prototype decoder paths behind feature flags.
- Treat CIP-0164 as the durable target, but only ship production defaults against release-candidate formats.

### Unknown Client-Facing Block Shape

Risk: Yaci may see native RBs, merged blocks, or prototype-specific blocks depending on N2N/N2C path.

Mitigation:

- First inspect actual bytes from a local Musashi node.
- Preserve raw CBOR.
- Build separate native and ledger-effective views.

### High Throughput Exposes Store Bottlenecks

Risk: Store modules, DB indexes, and analytics exporters may not keep up with Leios load.

Mitigation:

- Benchmark early with synthetic high-throughput fixtures.
- Batch Leios writes.
- Keep diagnostic vote indexing disabled by default.
- Add lag metrics per processor.

### Rollback Semantics Are More Complex

Risk: EB announcement and certification happen at different blocks/slots, so delete-by-slot alone can leave inconsistent Leios provenance.

Mitigation:

- Model announced and ledger-effective lifecycle separately.
- Add rollback tests around CertRBs.
- Make Leios table cleanup explicit.

### Native Mini-Protocol Complexity

Risk: LeiosFetch/LeiosNotify require state machines, cache policy, and request prioritization that are not needed for normal BlockFetch.

Mitigation:

- Defer native mini-protocols until specs stabilize.
- Use a local Leios node for near-term testnet support.
- Implement native agents only with conformance fixtures and integration tests.

### BLS and Committee Semantics

Risk: BLS key registration, committee selection, key rotation, and certificate validation are still moving parts.

Mitigation:

- Store certificate bytes and metadata first.
- Avoid independent certificate validity claims until official cryptographic test vectors and ledger APIs stabilize.
- Keep vote indexing optional.

### API Compatibility

Risk: Existing users expect one block to contain a direct transaction list.

Mitigation:

- Preserve existing transaction APIs as ledger-effective views.
- Add Leios-native APIs separately.
- Document that block body hash verification differs for merged views.

### Testnet Instability

Risk: Musashi can reset or repin configs every couple of weeks.

Mitigation:

- Automate config refresh.
- Store network/release metadata in test reports.
- Do not make Musashi prototype behavior part of stable Yaci API.

## Recommended Immediate Backlog

### Yaci

1. Add `Era.Dijkstra` and update era mapping/tests.
2. Add a Leios prototype feature flag.
3. Capture Musashi `prototype-2026w25` raw header/block CBOR fixtures.
4. Refactor `BlockHeaderSerializer` into era-specific decoders.
5. Add Leios/Dijkstra header fields while preserving raw header bytes.
6. Add minimal CertRB-safe block decoding.
7. Document Musashi connection settings.

### Yaci Store

1. Add a design ADR for Leios indexing semantics.
2. Add `block_kind` and EB announcement/certification fields to the block domain model in a migration branch.
3. Define `stores:leios` schema and rollback rules.
4. Add prototype metrics/log ingestion for Leios trace events if native fields are not yet available.
5. Run Store against a local Musashi relay and record throughput/parser gaps.

### Cross-Repo

1. Build a fixture pack from:
   - Musashi live blocks
   - cardano-ledger `leios-prototype` tests
   - cardano-blueprint CDDL examples when available
2. Maintain a source compatibility matrix.
3. Decide API semantics for transaction inclusion:
   - direct RB inclusion
   - EB announcement
   - EB certification
   - ledger-effective inclusion

## Open Questions

1. What exact CBOR shape does `prototype-2026w25` serve to Yaci over N2N BlockFetch?
2. Does local ChainSync expose merged blocks for Dijkstra/Leios, or native RBs only?
3. What final era tag/value will Dijkstra use in the hard-fork combinator?
4. Which cardano-blueprint branch/commit is the canonical source for current Leios mini-protocol IDs?
5. Should Yaci implement native Leios mini-protocols for production, or is running behind a Leios-enabled node enough for Yaci Store's primary use cases?
6. How should Blockfrost-compatible APIs represent transactions included through certified EBs?
7. Should Yaci Store persist votes at all, or leave vote/certificate validation to node tooling?

## Bottom Line

For the current Musashi prototype, prioritize "follow the chain without crashing, preserve raw data, expose basic Leios metadata" through a local Leios node. For durable Linear Leios support, treat it as a consensus-era upgrade requiring new Yaci core models, parser architecture, optional native mini-protocols, and a dedicated Yaci Store Leios module. Keep prototype and production paths separate until the release-candidate formats converge.
