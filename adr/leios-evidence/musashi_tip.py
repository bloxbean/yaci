"""Print the current Musashi tip as 'slot hash blockNo' (ChainSync FindIntersect with no points)."""
import socket, struct, time

HOST, PORT = "leios-node.play.dev.cardano.org", 3001

def frame(proto, payload):
    return struct.pack(">IHH", int(time.time() * 1e6) & 0xffffffff, proto, len(payload)) + payload

def read_frame(s):
    buf = b""
    while len(buf) < 8: buf += s.recv(8 - len(buf))
    _, _, ln = struct.unpack(">IHH", buf)
    body = b""
    while len(body) < ln: body += s.recv(ln - len(body))
    return body

def uint(b, i):
    ai = b[i] & 31
    if ai < 24: return ai, i + 1
    n = {24: 1, 25: 2, 26: 4, 27: 8}[ai]
    return int.from_bytes(b[i + 1:i + 1 + n], "big"), i + 1 + n

s = socket.create_connection((HOST, PORT), timeout=15)
s.sendall(frame(0, bytes.fromhex("8200a10f8418a4f500f4")))   # handshake, N2N v15, magic 164
read_frame(s)
s.sendall(frame(2, bytes.fromhex("820480")))                  # ChainSync MsgFindIntersect []
r = read_frame(s)                                             # [6, tip] = MsgIntersectNotFound
# tip = [[slot, hash], blockNo]; r = 82 06 82 82 <slot> 5820 <hash> <blockNo>
i = 4
slot, i = uint(r, i)
assert r[i] == 0x58 and r[i + 1] == 0x20
h = r[i + 2:i + 34].hex(); i += 34
block_no, _ = uint(r, i)
print(slot, h, block_no)
