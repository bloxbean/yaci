# Mux CBOR Byte Scanning

This note explains how Yaci splits inbound mux payload bytes into mini-protocol messages.
It is intentionally focused on the narrow framing problem handled by:

- `MiniProtoStreamingByteToMessageDecoder`
- `ProtocolChannel`
- `CborByteScanner`

It does not describe block, transaction, datum, redeemer, or Leios payload decoding. Those
are handled later by protocol-specific serializers.

## Why This Exists

Cardano mini-protocol messages are CBOR encoded and carried inside mux segments. The mux
layer gives Yaci bytes for a protocol id, but it does not guarantee that one read equals
one complete mini-protocol message.

A TCP read or Netty decode pass can contain:

```text
partial CBOR message
```

or:

```text
one complete CBOR message + part of the next message
```

or:

```text
multiple complete CBOR messages
```

The mux decoder therefore needs to answer one question:

```text
Do we have a complete CBOR item boundary, and which original bytes belong to it?
```

The important phrase is "original bytes". The mux layer must not decode a CBOR value into
a Java object and then encode it again just to find a boundary. Re-encoding can change the
bytes. For example:

```text
18 00
```

is a valid non-canonical encoding of integer `0`. A CBOR library may re-encode it as:

```text
00
```

That is a different byte sequence. For opaque payloads and hash-sensitive Cardano data,
that is not acceptable.

Another important example is an empty indefinite-length map:

```text
BF FF
```

`BF` starts an indefinite-length map. `FF` is the break byte that closes it. If a library
drops or rewrites the break byte while re-encoding, the mini-protocol stream can become
desynchronized.

The current decoder avoids that class of bug by scanning CBOR structure and slicing the
original byte array.

## Mux Segment Shape

The decoder first reads the mux segment header:

```text
+-------------+-------------+-------------+----------------+
| timestamp   | protocol id | payload len | payload bytes  |
| 4 bytes     | 2 bytes     | 2 bytes     | payload len    |
+-------------+-------------+-------------+----------------+
```

The `protocol id` selects the mini-protocol. Yaci keeps a separate `ProtocolChannel`
buffer per registered protocol because each mini-protocol stream has independent CBOR
boundaries.

Simplified flow:

```text
read mux segment
  -> find protocol channel
  -> append payload bytes to that protocol buffer
  -> scan complete top-level CBOR items
  -> emit complete mini-protocol message bytes
  -> keep incomplete residue buffered
```

Segments for unregistered protocols are rejected. This avoids keeping unbounded buffers
for protocol ids that no agent owns.

## CBOR Item Basics

Every CBOR data item starts with an initial byte:

```text
high 3 bits: major type
low  5 bits: additional information
```

For example:

```text
82 01 02
```

`0x82` in binary is:

```text
1000_0010
```

The high 3 bits are `100`, which is major type `4`: array.

The low 5 bits are `00010`, which is additional information `2`: two elements.

So:

```text
82 01 02
```

means:

```text
array of 2 elements
  element 1: 01
  element 2: 02
```

and the complete item occupies 3 bytes.

The major types used by the scanner are:

```text
0 unsigned integer
1 negative integer
2 byte string
3 text string
4 array
5 map
6 tag
7 simple value / float / break
```

The additional information field either contains the value/length directly or says how
many following bytes contain it:

```text
0..23   value or length is stored directly in the low 5 bits
24      next 1 byte stores the value or length
25      next 2 bytes store the value or length
26      next 4 bytes store the value or length
27      next 8 bytes store the value or length
31      indefinite length, valid for byte/text strings, arrays, and maps
```

## How Arrays Are Scanned

For arrays, the length is the number of child CBOR items. Knowing the child count is not
enough to know the byte size of the array. Each child can have a different encoded size.

Example:

```text
83 01 42 AA BB 82 02 03
```

Structural view:

```text
83              array of 3 elements

01              element 1: unsigned integer 1

42 AA BB        element 2: byte string of length 2
                  AA BB

82 02 03        element 3: array of 2 elements
                  02
                  03
```

The scanner handles this recursively:

```text
scan item at offset 0
  initial byte 83 -> array, 3 elements
  scan child 1 -> integer, 1 byte
  scan child 2 -> byte string, header plus 2 bytes
  scan child 3 -> array, recursively scan its 2 children
  return offset after child 3
```

In pseudocode:

```java
scanItem(offset):
    read initial byte
    majorType = high 3 bits
    additionalInfo = low 5 bits

    if integer/simple:
        skip the encoded argument bytes

    if byte string/text string:
        read length
        skip length bytes

    if array:
        read element count
        repeat element count times:
            offset = scanItem(offset)

    if map:
        read pair count
        repeat pair count times:
            offset = scanItem(offset) // key
            offset = scanItem(offset) // value

    if tag:
        read tag number
        offset = scanItem(offset) // tagged item

    return offset
```

## How Maps Are Scanned

For maps, the length is the number of key/value pairs. The scanner therefore scans two
CBOR child items for every pair.

Example:

```text
A2 01 41 AA 02 82 03 04
```

Structural view:

```text
A2              map of 2 pairs

01              key 1: unsigned integer 1
41 AA           value 1: byte string of length 1

02              key 2: unsigned integer 2
82 03 04        value 2: array of 2 elements
```

The scanner does not care what the keys or values mean. It only needs to know where each
child item ends.

## Indefinite-Length Items

CBOR arrays, maps, byte strings, and text strings can use indefinite-length encoding. In
that case, there is no fixed element count or byte length in the first item header. The
scanner keeps scanning chunks or child items until it sees the break byte:

```text
FF
```

Example indefinite array:

```text
9F 01 02 03 FF
```

Structural view:

```text
9F      start indefinite-length array
01      child item
02      child item
03      child item
FF      break, end of array
```

Example empty indefinite map:

```text
BF FF
```

Structural view:

```text
BF      start indefinite-length map
FF      break, end of map
```

The break byte is part of the original CBOR encoding. It must be preserved in the emitted
payload slice.

## Mini-Protocol Message Boundary Rule

Most Cardano mini-protocol messages are encoded as top-level CBOR arrays. For example:

```text
82 04 58 ...
```

can be read structurally as:

```text
array of 2 items
  message tag 4
  byte string payload
```

Yaci uses this convention when several complete top-level CBOR items are present in the
same protocol buffer:

```text
an untagged top-level array starts a mini-protocol message
following non-array top-level items are grouped with that message
the next untagged top-level array starts the next message
```

"Untagged" means tags are skipped before checking the major type. So a tag-wrapped array
is still treated as a message start:

```text
C0 81 01
```

Structural view:

```text
C0      CBOR tag 0
81 01   tagged value: array of 1 element
```

The scanner reports this as a top-level item whose outer major type is tag, but whose
untagged major type is array. The mux decoder can therefore split:

```text
C0 81 01 C0 81 02
```

into:

```text
C0 81 01
C0 81 02
```

The grouping rule exists for compatibility with payloads that appear as multiple
top-level CBOR items in a protocol buffer. Example:

```text
81 01 02 41 AA 81 03
```

Top-level CBOR items:

```text
81 01       array [1]
02          integer 2
41 AA       byte string h'AA'
81 03       array [3]
```

Yaci emits:

```text
message 1: 81 01 02 41 AA
message 2: 81 03
```

The non-array items after the first array are grouped with the first message until the
next array starts.

If the decoder cannot yet prove the trailing grouped item is complete, it waits for more
mux payload bytes instead of emitting early.

Example split across mux segments:

```text
segment 1 payload: 81 01 42
segment 2 payload: AA BB
```

After segment 1:

```text
81 01       complete array [1]
42          byte string header: length 2, but bytes are missing
```

The decoder waits.

After segment 2:

```text
81 01 42 AA BB
```

The decoder emits the original five bytes as one protocol payload.

## What Happens To Incomplete CBOR

Incomplete CBOR is not an error by itself. It usually means the value was split across
mux segments.

Example:

```text
82 01
```

The first byte says "array of 2 elements", but only one child item is present. The scanner
returns "incomplete", and the protocol buffer keeps the bytes.

When the next mux segment arrives:

```text
02
```

the buffer becomes:

```text
82 01 02
```

and the message can be emitted.

## What Happens To Malformed CBOR

Malformed CBOR is treated differently from incomplete CBOR. For example, a top-level break
byte is invalid:

```text
FF
```

`FF` is only valid inside an indefinite-length item. If it appears as a top-level item, the
decoder treats the peer stream as invalid, clears the protocol buffer, marks the decoder
as poisoned, and closes the channel.

This is intentionally strict. Once a mux stream is structurally invalid, guessing future
message boundaries is unsafe.

## Buffer Size Policy

The default mux decoder does not impose a hard maximum on incomplete CBOR accumulation.
This is deliberate. LocalStateQuery can return very large results, including ledger-state
sized responses, and those responses may be much larger than a small fixed cap.

This does not mean every declared CBOR size is accepted. If a CBOR item declares a length
that cannot be represented safely by Java byte-array indexing, the scanner treats that as
invalid protocol input. The removed limit was the small fixed accumulation cap, not the
basic Java safety checks.

The decoder does support an optional `maxIncompleteBufferSize` constructor for constrained
or test-specific use cases. The default production path leaves it unlimited and relies on:

- registered protocol ids only
- CBOR structural validation
- channel close on malformed CBOR
- normal process and deployment memory limits

Do not add a small global cap here without validating LocalStateQuery and other large
response paths.

## Narrow Scope

The mux scanner only provides CBOR item boundaries and original byte slices. It does not:

- validate Cardano CDDL
- check mini-protocol state-machine rules
- parse blocks, transactions, datums, redeemers, or Leios endorser blocks
- canonicalize CBOR
- sort map keys
- convert CBOR into Java model objects

Those concerns belong to the protocol-specific serializers and model parsers after the
mux layer has emitted a complete payload.

## Debugging Checklist

When debugging a mux payload in hex:

1. Read the mux header and identify the protocol id.
2. Append the payload to that protocol's existing buffer.
3. Look at the first CBOR byte.
4. Split the first byte into major type and additional information.
5. For arrays, recursively scan the declared number of child items.
6. For maps, recursively scan key and value for each pair.
7. For byte/text strings, skip exactly the declared payload length.
8. For indefinite items, scan until the `FF` break byte.
9. Emit only when the full CBOR item or grouped mini-protocol payload is complete.
10. Slice and pass through the original bytes; do not decode and re-encode at mux level.
