# Raw witness extraction regressions (#187)

The `preprod4974660.txt`, `preprod4974662.txt`, and `preprod4974666.txt` fixtures contain
hex-encoded, original network block CBOR saved during the investigation of #185–#187.
They are offline fixtures; tests make no network requests.

| Block | Slot | Hash | Transactions | Datums / redeemers |
| --- | --- | --- | --- | --- |
| 4,974,660 | 129241110 | `119c20053671956014be7f23c4ba8d47669582ef75f5100f601bb93688554e24` | 17 | 30 / 36 |
| 4,974,662 | 129241142 | `c0e3fe4ef16a67b47dc48c67580b70a996881288c2e8425b4ec9f39a649e0d0c` | 11 | 30 / 9 |
| 4,974,666 | 129241244 | `f48f946756c7daac15d19e7297ba69f4ab8d620945eb09a5e31f5e389708b464` | 2 | 38 / 1 |

`raw-witness-expectations.json` records every datum and redeemer-data value, including
values in witnesses after the originally failing witness. Offsets are zero-based byte
offsets into the decoded fixture, not positions in its hex text. Lengths include the
value's own CBOR tags and nested container headers/terminators.

The offsets were independently extracted with Python `cbor2` stream decoding
(`read_size=1` to disable read-ahead), after reading each container's header. Python
`hashlib.blake2b(source[offset:offset + length], digest_size=32)` produced the hashes.
No Yaci extraction helper or serialization of the decoded value produced these expectations.
The fixture traversal is:

```text
[era, [header, transactionBodies, witnesses, auxiliaryData, invalidTransactions]]
witness = { ..., 4: [datum, ...], 5: redeemers, ... }
array redeemers = [[tag, index, data, [memory, steps]], ...]
map redeemers = {[tag, index]: [data, [memory, steps]], ...}
```

The fixture also records transaction hashes, per-witness datum/redeemer counts, redeemer
purpose/index, and execution units captured from PR #188 at commit
`8c0b2f8e1f1793dd26002b74074b0319b692eee1`. Tests assert those fields directly, so failures
show the changed value without depending on Jackson property ordering or unrelated model fields.
The whole-output comparison against #188 remains one-time validation evidence in the PR.

## Error classification and behavior

- Blocks 4,974,660 and 4,974,662 have a tagged datum-array prefix `d90102981e`:
  tag 258 followed by a 30-element array. The old tag-skip counter decremented to -1,
  leaving `1e` to be interpreted as an item, which is reserved additional information.
- Block 4,974,666 has the same offset defect with a 38-element array. Its length byte
  `26` was a valid CBOR item by itself, causing a count mismatch instead of that exception.
- Extended/indefinite map headers and extended/indefinite redeemer field arrays had
  separate fixed-offset assumptions. Boundary tests cover each helper, all argument
  widths, container tags, empty/singleton cases, and invalid/truncated framing.
- Duplicate Conway redeemer-map keys are a separate case. They were observed in the
  locally saved preview block 2,587,542: two raw `[Mint, 0]` keys collapse to one decoded
  map key. On a mismatch, the raw entries now follow the same decoded-key equality and
  first-key order, with the last value supplying original bytes and hashes. An unresolved
  mismatch still logs the failure, retains parsed values, and continues with later witnesses.
  Synthetic regressions cover different encodings of equal keys, interleaved duplicates,
  ordering, exact winning bytes/hashes, and unresolved mismatches.
- Recovery must apply the same duplicate-key rules. Tests combine duplicate maps with a deeply
  nested datum in the same or a later witness, then compare the normal and recovery redeemer
  lists through both sync paths on 512 KB stacks. They also cover deep overwritten/winning
  redeemer data and ensure array-form redeemers are never deduplicated.

`preview2587542.txt` contains the original duplicate-key block. Independent `cbor2` stream
inspection places the second `[Mint, 0]` value's data at byte offsets `[4098, 4101)`: `d87a80`.
Its Blake2b-256 hash is `8392f0c940435c06888f9bdb8c74a95dc69f156367d6a089cf008ae05caae01e`.
Both values happen to contain identical data in this block, so its output remains unchanged;
the regression also verifies that every parsed redeemer reaches raw-data correction.
The synthetic tests deliberately use different values and non-minimal encodings to prove that
last-value selection and original-byte hashing work.

This change corrects the optional raw-byte enrichment pass. It does not make malformed
required block fields acceptable. Extraction exceptions preserve the initially parsed
values; callers must not assume that fallback values contain exact source CBOR. Conway's
whole-redeemer `cbor` keeps its existing synthesized four-field-array representation.
