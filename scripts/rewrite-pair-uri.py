# Rewrites the addresses in a Tandem pairing link, so an Android emulator (which reaches the Mac only as 10.0.2.2) can use a link
# that tandemd pair-show printed: python3 scripts/rewrite-pair-uri.py "<link>" 10.0.2.2:47831
import sys, base64
def b64d(s): return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))
def dec(b, i=0):
    ib = b[i]; mt, ai = ib >> 5, ib & 31; i += 1
    if ai < 24: n = ai
    elif ai == 24: n = b[i]; i += 1
    elif ai == 25: n = int.from_bytes(b[i:i+2], "big"); i += 2
    elif ai == 26: n = int.from_bytes(b[i:i+4], "big"); i += 4
    else: raise ValueError
    if mt == 0: return n, i
    if mt == 2: return b[i:i+n], i + n
    if mt == 3: return b[i:i+n].decode(), i + n
    if mt == 4:
        out = []
        for _ in range(n):
            v, i = dec(b, i); out.append(v)
        return out, i
    if mt == 5:
        out = []
        for _ in range(n):
            k, i = dec(b, i); v, i = dec(b, i); out.append((k, v))
        return out, i
    if mt == 7: return bool(ai == 21), i
    raise ValueError(mt)
def head(mt, n):
    if n < 24: return bytes([mt << 5 | n])
    if n < 256: return bytes([mt << 5 | 24, n])
    return bytes([mt << 5 | 25]) + n.to_bytes(2, "big")
def enc(v):
    if isinstance(v, bool): return bytes([0xf5 if v else 0xf4])
    if isinstance(v, int): return head(0, v)
    if isinstance(v, bytes): return head(2, len(v)) + v
    if isinstance(v, str): s = v.encode(); return head(3, len(s)) + s
    if isinstance(v, list): return head(4, len(v)) + b"".join(enc(x) for x in v)
    if isinstance(v, tuple): return enc(v[0]) + enc(v[1])
    raise TypeError(type(v))
uri = sys.argv[1]; new = sys.argv[2]
prefix = "tandem://pair/1?d="
raw = b64d(uri[len(prefix):])
m, _ = dec(raw)
m = [(k, [new] if k == "addrs" else v) for k, v in m]
out = head(5, len(m)) + b"".join(enc(k) + enc(v) for k, v in m)
print(prefix + base64.urlsafe_b64encode(out).decode().rstrip("="))
