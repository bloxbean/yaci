# Native script parse failures

Native scripts in witnesses and auxiliary data are parsed independently. If a script cannot be
parsed or converted to JSON, Yaci returns a `NativeScript` with its `type`, a null `content`, and a
non-null `parseError`. Other scripts and the rest of the block remain available. Callers should
check `parseError` before consuming `content`. A warning is logged without formatting the script.
The existing two-argument constructor and normal script JSON remain supported.

An iterative check limits native-script CBOR array nesting to 256 levels before invoking recursive
parsing or JSON conversion. This is a local representation safety limit, not a ledger validity
rule. Composite scripts typically add two array levels per script level. A narrowly scoped
`StackOverflowError` fallback also isolates failures in third-party script processing. Other VM
errors are not suppressed. Block decoding, raw witness extraction, and non-canonical network re-encoding traverse
nested CBOR arrays iteratively so deeply nested scripts can reach this isolation boundary.
Other malformed CBOR and failures outside native-script processing still propagate normally.
