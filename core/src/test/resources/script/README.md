# Preprod script hash fixtures

The six `preprod-<transaction hash>.cbor` files contain hex-encoded complete transactions
fetched from `https://preprod.koios.rest/api/v1/tx_cbor?_tx_hashes={...}` on 2026-09-20.
Tests use only saved fixtures, never the network. `preprod-transactions.json` records their
transaction hashes, block heights, slots, script versions/hashes, and mint policy IDs.

| Transaction | Coverage |
| --- | --- |
| `f90dce5765108da976abdbb9fc618f9a6ffd9fa4d93b2f288eed1808545424c9` | PR #185 native script, thousands of nested arrays; block 5183974 |
| `f8b75f2e474a794fdfb213aedfe88163a0da58c8e42d0142e899ad470fa691f5` | Three native minting scripts; all hashes equal policies in transaction body |
| `3231ff0846d3ee82095faa0dd65a520a74ded2b4f63239575c59a756e804c447` | Additional native script |
| `55d0b8aa2a7dfdb5c6d59b6af608e50415d7ecf1185a12f2be9965cd4e3f2f6c` | Plutus V1 |
| `a14ea1d0bd6f877de03e3aa24f1c0fffa3c3e9ec9ac250b32db64eff0b344507` | Plutus V2 |
| `7e8c2eeec76e9efd146cf466c7ae1c3e9acfce53766a5e84d28b5eaf76d4a34e` | Plutus V3 |

Expected hashes were calculated independently with Python `hashlib.blake2b(digest_size=28)`.
Native scripts use byte `00` followed by their exact original CBOR slice. Plutus scripts use
the language byte (01/02/03) followed by the decoded byte-string payload, using Python `cbor2`.
An independent iterative CBOR boundary scanner located original transaction-body and script
slices; no Yaci serializer or JSON-to-script conversion generated these expectations.
Transaction hashes were checked with Blake2b-256 of the exact transaction-body slice.
Mint policies were extracted independently from body field 9.

`koios-script-info.json` retains the hash, creation transaction and script type returned by
Koios `script_info` for all eight script hashes. The request selected only these columns,
avoiding the deeply nested JSON script itself. All eight independently calculated hashes
were found in Koios. A creation transaction may precede the fixture transaction that uses it.

`preprod-hashes.json` additionally records every witness script in nine existing preprod
block fixtures under `../block/` (22 scripts). These expectations use the same independent
procedure. Tests check transaction identity, ordering and all hashes with full-CBOR return
flags disabled. Synthetic tests supplement these fixtures with non-minimal and indefinite
encodings, tagged sets, unknown scripts, duplicate witness fields, all Plutus versions,
JSON failures, metadata/datum recovery, and auxiliary scripts in both supported formats.
The real deeply nested script also runs on a 256 KiB Java thread stack. Block recovery tests
insert saved script bytes into an existing block; those modified blocks are synthetic and
are not claimed to be valid on-chain blocks.

## Actual reference-script outputs (preview)

Five `preview-reference-<transaction hash>.cbor` fixtures are complete, original transactions
from the public **preview** network. Candidates were discovered with read-only queries of the
local Yaci Store `address_utxo` table. They were confirmed on preview, not preprod, using
Koios on 2026-09-20. Database credentials and connection settings are not part of the fixtures.

| Transaction | Reference outputs |
| --- | --- |
| `1cdba0f42a48f6a7488e095248459ef89426c1521836d966f731d1357eb5543b` | #0 native `before`; #1 Plutus V2 |
| `8e286dd9ba7112be7bb3dea2789f24c5ddd33bc171e2f19bba422918ba163695` | #0 native `after`; #1 Plutus V2 |
| `359b712b1db9f5169c2b755e0cd315f3e9bcea1e04f3400cf93fa47cb54a8b68` | #0 Plutus V1 |
| `f4b56e9fab1564f57564a9d6e489af8e67fdb9993586891a6eb7f8c0ddd497f2` | #0 Plutus V2 |
| `3927881a2d76c3d1b160ccac69ba986c653621ea1a84a9e0d329f0f774f92332` | #0 Plutus V2 |

Public sources (replace the braces with comma-separated transaction hashes):

- `https://preview.koios.rest/api/v1/tx_cbor?_tx_hashes={...}` supplies complete transaction CBOR.
- `https://preview.koios.rest/api/v1/tx_info?_tx_hashes={...}&_scripts=true` supplies the output
  indexes and `reference_script.hash`/`type` reported by the network indexer.

`koios-reference-outputs.json` retains the relevant output fields from the public response.
`reference-transactions.json` records network, transaction/block identity, slot, and every
output's expected script hash and original `scriptRef` bytes, including outputs without a
reference script. The CBOR is neither reconstructed nor synthesized.

Each transaction body's hash was independently verified with Python Blake2b-256. Reference
bytes were extracted from output field 3 (tag 24), and the embedded `[language, body]` was
located with the independent boundary scanner. Python Blake2b-224 of the language prefix plus
the exact native body bytes or decoded Plutus byte-string payload matched Koios for all seven
outputs. The five initially selected database rows also matched both the reference bytes and
hash; the other two outputs were discovered in the same fetched transactions.

The offline regression parses the saved transaction bodies through Yaci and checks output
counts, indexes, script hashes, unchanged reference bytes, and null fields on ordinary outputs.
Actual reference-output coverage here is native, Plutus V1 and V2. Plutus V3 reference scripts
and deeply nested native references remain synthetic tests; the earlier preprod Plutus V3
fixture is a real witness script, not a reference output.
