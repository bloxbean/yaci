"""Minimal N2N probe: handshake offering one version, then LeiosNotify RequestNext on mini-protocol 18."""
import socket, struct, sys, time

HOST, PORT = "leios-node.play.dev.cardano.org", 3001
V15 = bytes.fromhex("8418a4f500f4")            # [164, true, 0, false]
V16 = bytes.fromhex("8518a4f500f4f4")          # [164, true, 0, false, perasSupport=false]

def frame(proto, payload):
    return struct.pack(">IHH", int(time.time() * 1e6) & 0xffffffff, proto, len(payload)) + payload

def read_frames(s, seconds, max_frames=99):
    s.settimeout(seconds); buf = b""; out = []
    try:
        while True:
            chunk = s.recv(65536)
            if not chunk: out.append(("closed", None)); break
            buf += chunk
            while len(buf) >= 8:
                _, pm, ln = struct.unpack(">IHH", buf[:8])
                if len(buf) < 8 + ln: break
                out.append((pm & 0x7fff, buf[8:8 + ln])); buf = buf[8 + ln:]
                if len(out) >= max_frames: return out
    except socket.timeout: out.append(("timeout", None))
    except ConnectionResetError: out.append(("reset", None))
    return out

def probe(version, data, request=bytes.fromhex("8103")):
    s = socket.create_connection((HOST, PORT), timeout=10)
    propose = bytes([0x82, 0x00, 0xa1, version]) + data            # [0, {version: data}]
    s.sendall(frame(0, propose))
    hs = read_frames(s, 5, max_frames=1)
    print(f"v{version} handshake reply:", [(p, d.hex() if d else d) for p, d in hs[:1]])
    s.sendall(frame(18, request))
    res = read_frames(s, 40)
    summary = [(p, d[:24].hex() if d else d) for p, d in res[:6]]
    print(f"v{version} after {request.hex()} on 18:", summary)
    s.close()

probe(15, V15, bytes.fromhex("8100"))   # w40a RequestNext: expect [1, announcement]
probe(16, V16, bytes.fromhex("8103"))   # w42 RequestNext: reset while Musashi runs w40a
