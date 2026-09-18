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
