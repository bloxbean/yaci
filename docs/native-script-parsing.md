# Native script JSON and parse failures

Native scripts in witnesses and auxiliary data are converted to JSON independently. Nested
`all`, `any`, and `atLeast` scripts are traversed iteratively, without a recursive client-library
script tree or a fixed CBOR nesting cutoff. Ordinary scripts retain their existing JSON format
and numeric behavior. An iterative estimate selects pretty printing only when the estimated
output fits a 64 KiB character budget; larger scripts use fully compact JSON. Estimation stops
as soon as the budget is exceeded, before generating any JSON. This is a formatting threshold,
not a script size or depth limit: every script node is still written and validated.

If JSON conversion fails, Yaci returns a `NativeScript` with its `type`, null `content`, and a
non-null `parseError` naming the failure. Other scripts and the rest of the block remain
available. The two- and three-argument constructors remain supported. Callers must inspect
`parseError` before consuming `content`. Failures are returned per script without logging one
warning per script.

Structural corruption (for example an empty script or a non-integer type) still raises an error;
it is not converted into a type `-1` placeholder. Unknown script types retain the existing omission
behavior, consistently in witnesses and both auxiliary-data formats.

CBOR decoding and non-canonical network re-encoding traverse nested arrays iteratively. This does
not make arbitrary nested maps or tags stack-safe. Canonical encoding still uses the recursive
library encoder. Neither canonical nor non-canonical re-encoding guarantees the original bytes;
on-chain hashes must use the appropriate original byte representation.

Deep datum/metadata conversion and the existing redeemer/datum byte-extraction issues are separate
follow-ups: [#186](https://github.com/bloxbean/yaci/issues/186) and
[#187](https://github.com/bloxbean/yaci/issues/187).

## Script hashes and policy IDs

`NativeScript.getHash()` and `PlutusScript.getHash()` expose a lowercase, 56-character
Blake2b-224 script hash. For a minting script this is the policy ID. Consumers can use
this field directly instead of parsing `content` through a client library.

- Native: `Blake2b-224(0x00 || original script CBOR)`.
- Plutus V1/V2/V3: `Blake2b-224(version byte 0x01/0x02/0x03 || script payload)`.
  The surrounding CBOR byte-string header is excluded; the payload is not decoded again.

This follows the ledger's [script hashing rule](https://github.com/IntersectMBO/cardano-ledger/blob/master/libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs).
Native hashes use exact source slices, including indefinite arrays and non-minimal integer
encodings. They never re-encode a script or parse its JSON. This preserves support for
thousands of nested script levels, and the hash remains available if JSON conversion fails.

Hashes are populated for witness and auxiliary scripts returned by byte-based witness,
auxiliary-data, and block deserialization, including recovery paths. They do not depend on
`returnFullTxCbor` or `returnBlockCbor`. Block parsing reuses the existing raw witness
extraction and shares a field scan with datum/redeemer correction. Auxiliary hashes and
optional auxiliary CBOR also share an extraction; recovery reuses its available raw values.
If optional block byte extraction fails, the block is still returned and affected native
hashes remain null rather than being computed from re-encoded data. For native scripts, `deserializeDI` and
`deserializeNativeScript(Array)` cannot recover original bytes and therefore leave `hash`
null. Existing constructors and builders remain supported; manually constructed objects
only have a hash when explicitly supplied. Plutus `deserializeDI` can compute a hash because
the decoded byte-string payload retains all bytes needed for hashing.

Plutus hash calculation is optional enrichment: an unmapped `PlutusScriptType` or a hashing
exception logs an error with the exception stack trace and returns the script with its type
and content intact, but a null hash. This does not invoke block parsing error callbacks.
Native hashing has the same isolation for standalone witnesses, auxiliary data, normal blocks,
and recovery. Source extraction or count/type alignment failures log a stack trace and preserve
the parsed scripts without attaching new hashes. Alignment is checked for the entire collection
before assigning hashes. An individual hash-calculation failure leaves that script's hash null
and does not prevent later scripts from being hashed.
Required script-content decoding errors still follow the existing parsing-error behavior.
This isolation is not automatic support for future Plutus versions or eras: recognizing new
wire fields and assigning the correct language prefix still requires an update.

Existing `content` formats are preserved: native JSON, CBOR-wrapped Plutus payloads in
witnesses, and unwrapped Plutus payloads in auxiliary data. Output `scriptRef` remains its existing raw representation. `TransactionOutput.scriptHash`
and helper `Utxo.scriptHash` expose the reference-script hash, including collateral-return
outputs. The local-state UTxO query maps this hash to CCL's `referenceScriptHash` field.

Reference hashing reads the embedded `[language, body]` wrapper locally and reuses the same
hash function. Native bodies are hashed from their original CBOR slice without constructing
a script tree or JSON; Plutus bodies use the byte-string payload. No additional block scan
is needed. Missing references, malformed CBOR, unsupported languages, or incorrect wrapper/body
types yield null `scriptHash`, preserving `scriptRef` and the containing output.
This checks encoding and body kind only: a non-null hash does not establish valid native-script
semantics or a valid/executable Plutus program. Existing constructors remain supported.

Offline preprod fixtures and their provenance are documented in
[`core/src/test/resources/script/README.md`](../core/src/test/resources/script/README.md).
