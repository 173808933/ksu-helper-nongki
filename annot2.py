#!/usr/bin/env python3
"""Annotate aarch64 llvm-objdump listing with .rodata string refs (exact adrp/add pairing)."""
import re, sys

BIN = "/tmp/p7"
data = open(BIN, "rb").read()

_cache = {}
def cstr(addr):
    if addr in _cache:
        return _cache[addr]
    off = addr - 0x400000
    r = None
    if 0 <= off < len(data):
        end = data.find(b"\x00", off, off + 240)
        if end > off:
            s = data[off:end]
            t = s.decode("utf-8", "replace")
            if len(t) >= 3 and all(0x20 <= ord(c) <= 0x7e for c in t):
                r = t
    _cache[addr] = r
    return r

lines = open("/tmp/p7.s", encoding="utf-8", errors="replace").read().splitlines()
start = int(sys.argv[1], 16) if len(sys.argv) > 1 else 0
stop = int(sys.argv[2], 16) if len(sys.argv) > 2 else 1 << 64

# registers holding adrp page base
page = {}
out = []
recent = []   # (addr, text) window for xref detection

def dest_of(ins):
    """very rough: return dest register of common aarch64 forms"""
    m = re.match(r"(\w+)\s+(x\d+|w\d+|v\d+)", ins)
    if not m:
        return None
    op = m.group(1)
    if op in ("stp", "str", "stur", "strb", "strh", "sturb", "sturh", "cmp", "cmn",
              "tst", "b", "bl", "cbz", "cbnz", "tbz", "tbnz", "ret", "nop", "dmb",
              "st1", "prfm"):
        return None
    if op in ("ldp",):
        # ldp xA, xB, [..] -> both
        m2 = re.match(r"ldp\s+(x\d+|w\d+), (x\d+|w\d+)", ins)
        if m2:
            return [m2.group(1), m2.group(2)]
    if op in ("ldr", "ldur", "ldrb", "ldrh", "ldurb", "ldurh"):
        return m.group(2)
    return m.group(2)

for ln in lines:
    m = re.match(r"\s*([0-9a-f]+):\s+(.*)", ln)
    if not m:
        h = re.match(r"([0-9a-f]+) <(.+)>:", ln)
        if h:
            a0 = int(h.group(1), 16)
            if start <= a0 < stop:
                out.append(ln)
        continue
    addr = int(m.group(1), 16)
    if addr < start or addr >= stop:
        continue
    ins = m.group(2).strip()
    tag = ""

    a = re.match(r"adrp\s+(x\d+), 0x([0-9a-f]+)", ins)
    if a:
        page[a.group(1)] = int(a.group(2), 16)
        out.append(f"{addr:06x}: {ins}")
        continue

    a = re.match(r"add\s+(x\d+), (x\d+), #(0x[0-9a-f]+|\d+)", ins)
    if a and a.group(1) == a.group(2) and a.group(2) in page:
        tgt = page[a.group(2)] + int(a.group(3), 0)
        s = cstr(tgt)
        tag = f'   ; "{s}"' if s else f"   ; =0x{tgt:x}"
        out.append(f"{addr:06x}: {ins}{tag}")
        continue

    a = re.match(r"bl\s+0x([0-9a-f]+) <(.+)>", ins)
    if a and not a.group(2).startswith("$"):
        out.append(f"{addr:06x}: {ins}   ; -> {a.group(2)}")
        continue

    d = dest_of(ins)
    if d:
        for r in (d if isinstance(d, list) else [d]):
            page.pop(r, None)
    out.append(f"{addr:06x}: {ins}")

print("\n".join(out))
