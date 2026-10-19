"""Ask the Musashi relay (w40a codecs) for an EB and its first 8 txs WITHOUT any prior offer.
Usage: leios_fetch_probe.py <slot> <eb_hash> [label]"""
import socket, struct, sys, time

HOST, PORT = "leios-node.play.dev.cardano.org", 3001

def frame(proto, payload):
    return struct.pack(">IHH", int(time.time() * 1e6) & 0xffffffff, proto, len(payload)) + payload

def cbor_uint(n):
    if n < 24: return bytes([n])
    for ai, size in ((24, 1), (25, 2), (26, 4), (27, 8)):
        if n < 1 << (8 * size): return bytes([ai]) + n.to_bytes(size, "big")

def read_message(s, proto, timeout):
    """Collect frames for one mini-protocol until the payload is a complete CBOR item (length known by header)."""
    s.settimeout(timeout); buf = b""; payload = b""
    try:
        while True:
            while len(buf) < 8:
                chunk = s.recv(65536)
                if not chunk: return "closed", payload
                buf += chunk
            _, pm, ln = struct.unpack(">IHH", buf[:8])
            while len(buf) < 8 + ln:
                chunk = s.recv(65536)
                if not chunk: return "closed", payload
                buf += chunk
            if pm & 0x7fff == proto: payload += buf[8:8 + ln]
            buf = buf[8 + ln:]
            if payload and complete(payload): return "ok", payload
    except socket.timeout: return "timeout", payload
    except ConnectionResetError: return "reset", payload

def complete(b):
    try: return skip(b, 0) == len(b)
    except IndexError: return False

def skip(b, i):
    mt, ai = b[i] >> 5, b[i] & 31; i += 1
    if ai < 24: arg = ai
    elif ai == 31: arg = None
    else:
        n = {24: 1, 25: 2, 26: 4, 27: 8}[ai]; arg = int.from_bytes(b[i:i + n], "big"); i += n
    if mt in (0, 1, 7): return i
    if mt in (2, 3):
        if arg is None:
            while b[i] != 0xff: i = skip(b, i)
            return i + 1
        if i + arg > len(b): raise IndexError
        return i + arg
    if mt == 6: return skip(b, i)
    count = arg if arg is None else (arg * 2 if mt == 5 else arg)
    if count is None:
        while b[i] != 0xff: i = skip(b, i)
        return i + 1
    for _ in range(count): i = skip(b, i)
    return i

def items(b, i):
    """Yield (start, end) of each child of the array/map whose header starts at i (definite or indefinite)."""
    mt, ai = b[i] >> 5, b[i] & 31; i += 1
    if ai == 31: count = None
    elif ai < 24: count = ai
    else:
        n = {24: 1, 25: 2, 26: 4, 27: 8}[ai]; count = int.from_bytes(b[i:i + n], "big"); i += n
    if count is not None and mt == 5: count *= 2
    k = 0
    while (count is None and b[i] != 0xff) or (count is not None and k < count):
        j = skip(b, i); yield i, j; i = j; k += 1

def bstr(b, i):
    ai = b[i] & 31
    if ai < 24: return b[i + 1:i + 1 + ai]
    n = {24: 1, 25: 2, 26: 4, 27: 8}[ai]; ln = int.from_bytes(b[i + 1:i + 1 + n], "big")
    return b[i + 1 + n:i + 1 + n + ln]

def verify(eb_msg, txs_msg):
    import hashlib
    eb_children = list(items(eb_msg, 0)); eb_map = eb_children[1][0]          # [1, eb]
    refs = [bstr(eb_msg, a).hex() for idx, (a, _) in enumerate(items(eb_msg, eb_map)) if idx % 2 == 0][:8]
    tx_children = list(items(txs_msg, 0)); tx_list = tx_children[3][0]        # [3, point, bitmaps, tx_list]
    got = [hashlib.blake2b(bstr(txs_msg, a), digest_size=32).hexdigest() for a, _ in items(txs_msg, tx_list)]
    return f"{sum(1 for r, g in zip(refs, got) if r == g)}/{len(got)} (EB has refs for {len(refs)} of the first 8)"

slot, eb_hash = int(sys.argv[1]), bytes.fromhex(sys.argv[2]); label = sys.argv[3] if len(sys.argv) > 3 else ""
point = b"\x82" + cbor_uint(slot) + b"\x58\x20" + eb_hash
s = socket.create_connection((HOST, PORT), timeout=15)
s.sendall(frame(0, bytes.fromhex("8200a10f8418a4f500f4")))           # handshake N2N v15, magic 164
read_message(s, 0, 10)
s.sendall(frame(19, b"\x82\x00" + point))                              # MsgLeiosBlockRequest [0, point]
status, eb = read_message(s, 19, 30)
print(f"{label} EB request: {status}, {len(eb)} bytes, starts {eb[:6].hex()}")
if status == "ok":
    bitmaps = b"\xbf\x00\x1b" + (0xFF << 56).to_bytes(8, "big") + b"\xff"  # txs 0..7 of window 0 (MSB first)
    s.sendall(frame(19, b"\x83\x02" + point + bitmaps))               # MsgLeiosBlockTxsRequest [2, point, bitmaps]
    status, txs = read_message(s, 19, 30)
    print(f"{label} BlockTxs request (no offer): {status}, {len(txs)} bytes, starts {txs[:6].hex()}")
    if status == "ok":
        print(f"{label} first 8 tx hashes match the EB references: {verify(eb, txs)}")
s.close()
