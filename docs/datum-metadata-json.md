# Datum and metadata failure isolation

Datum and metadata JSON is optional display data. A deeply nested value must not prevent
block synchronization merely because recursive CBOR processing or JSON conversion exhausts
the stack. The byte-based block, witness, and auxiliary-data serializers now recover these
values independently:

- `Datum.json` is null and `Datum.parseError` describes the failure. `Datum.cbor` contains
  the original bytes, and `Datum.hash` is calculated from those bytes. This includes redeemer data.
- `AuxData.metadataJson` is null and `AuxData.metadataParseError` describes the failure.
  `AuxData.metadataCbor` contains the original metadata value. Other auxiliary scripts remain available.

## How recovery works

Both block-fetch and local chain-sync read only the era from the block envelope before dispatching
to the block parser. They do not decode transaction data just to choose an era. On parsing errors,
block-fetch diagnostics likewise extract only the header, so logging cannot revisit failed data.

Ordinary parsing continues to use the existing decoder and serializers. If CBOR processing
exhausts the stack, parsing restarts from the original buffer. An iterative CBOR boundary
scanner locates witness datums, redeemer data, and metadata without constructing a recursive
object tree or hashing nested map keys. Temporary empty containers let the existing serializers
parse the block's other fields. Each isolated value is then decoded independently; an overflow
leaves its source CBOR, datum hash where applicable, and an error reason, with null JSON.
The same recovery path retains original bytes when decoding succeeds but optional JSON fails.

Temporary containers are never returned as block, transaction, witness, or auxiliary CBOR.
Transaction-body bytes and hashes are preserved, as is the existing auxiliary-data hash check.
The scanner handles definite and indefinite containers, tags, strings, and complex map keys.
It rejects truncated values, invalid boundaries, and misplaced BREAK markers.

This does not introduce a general-purpose CBOR decoder or encoder. The shared codec from
PR #185 remains unchanged. Recovery is limited to optional datum/redeemer-data and metadata
values; failures in required block fields still propagate. Other JVM errors are not suppressed.
Malformed Plutus data still raises its existing error when decoding reaches that validation.
An opaque value retained after overflow has verified CBOR boundaries, but has not completed
semantic Plutus/metadata validation. Callers of `deserializeDI`/`Datum.from(DataItem)` do not
have original bytes and cannot use the byte-level recovery path; use byte-based entry points
when handling untrusted nesting.

## Compatibility

Ordinary datum JSON keeps the existing pretty format, and ordinary metadata keeps its existing
canonical CBOR representation. JSON conversion no longer falls back to recursive `toString()`.
Healthy Conway map-form redeemers retain their existing re-encoded four-element CBOR representation,
even if another value triggers recovery. Their data CBOR and datum hashes still use source bytes.
Empty witness/auxiliary byte buffers continue to return null. Block recovery, like the normal parser,
accepts a sequence of complete CBOR values and returns the first block; malformed trailing framing
is rejected. Single datum/witness/auxiliary slices must still contain exactly one value.
Error fields are omitted from serialized JSON when null, and existing constructors remain available.
No global Jackson constraint or new depth cutoff is introduced. Failed values use their original
encoding, without canonicalization; metadata CBOR is only the metadata value, not the full
auxiliary-data structure used for its transaction hash.

Inline datums already expose their embedded CBOR directly.

## Raw witness correction

The ordinary block path also uses the same boundary scanner to retain exact witness datum and
redeemer-data bytes (#187). It handles tagged/untagged and definite/indefinite containers, all
length-header widths, and empty/singleton containers without re-encoding their contents.
Array-form redeemers retain their whole source encoding. Conway map-form redeemers keep the
existing synthesized four-field `cbor`; their nested data bytes and hashes are corrected.

Raw extraction remains optional enrichment after the initial parse. A failure is logged with
block/witness context and leaves the initially parsed value in place. Datum and redeemer passes
are independent, and failures do not stop later witnesses or blocks. Count mismatches skip the
affected collection to avoid attaching bytes to the wrong parsed value. For example, duplicate
Conway map keys can collapse during decoding and produce a raw/parsed redeemer count mismatch.
Fallback values are not guaranteed to contain exact source CBOR; this is separate from optional
JSON conversion failures reported through `Datum.parseError`.

See the [offline extraction fixtures and error classifications](../core/src/test/resources/block/raw-witness-expectations.md).

## Era coverage

The recovery code follows the formats used by this branch from Shelley through Conway:

| Era | Relevant data encoding |
| --- | --- |
| Shelley | Auxiliary data is a metadata map. |
| Allegra / Mary | Also supports `[metadata, native scripts]` auxiliary data. |
| Alonzo | Adds tag-259 auxiliary-data maps, witness datums, and array-form redeemers. |
| Babbage | Uses these formats; inline datums are already retained as embedded CBOR bytes. |
| Conway | Also supports map-form redeemers and tagged datum sets; older auxiliary formats remain valid. |

The scanner looks at the encoding rather than choosing a format solely from the era number.
Block field positions used for recovery remain the same from Shelley through Conway; additional
fields such as Alonzo's invalid-transaction list are left to the existing parser.
Byron blocks use separate serializers, which this change does not modify.

Tests cover all three auxiliary-data formats, both redeemer forms, and tagged/indefinite datum sets.
Both sync entry points are tested on 512 KB stacks with deep maps, lists, constructor tags, and
complex keys. Synthetic block fixtures exercise the header/body layouts from Shelley through Conway
and both CBOR-return settings; dispatch tests also cover Byron main and epoch-boundary blocks.
This is format coverage, not a claim that a full chain sync was run through every era for this change.
The formats are described in the ledger's
[Conway CDDL](https://github.com/IntersectMBO/cardano-ledger/blob/master/eras/conway/impl/cddl/data/conway.cddl).
