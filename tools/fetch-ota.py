#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 OTA 直链按需提取分区，全包不落地。

OPPO/一加的 CDN 只放行 curl/8.5.0 的 UA，浏览器 UA 会 403。
5 GB 的包用 HTTP Range 只取需要的那几段。

用法：
    python fetch-ota.py <url> --list                 # 列条目 / payload 分区
    python fetch-ota.py <url> --part boot --out boot.img
    python fetch-ota.py <url> --part vendor_boot --out vb.img
"""

import argparse
import io
import struct
import sys
import urllib.request
import zipfile

UA = "curl/8.5.0"


def http_range(url, start, end):
    req = urllib.request.Request(url, headers={
        "Range": f"bytes={start}-{end}",
        "User-Agent": UA,
    })
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read()


def http_size(url):
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return int(r.headers.get("Content-Length", 0))


class RemoteZip:
    """只靠 Range 读 zip 目录和单个条目。"""

    def __init__(self, url):
        self.url = url
        self.size = http_size(url)
        self.entries = {}
        self._read_directory()

    def _read_directory(self):
        # 尾部先取 128KB，够覆盖 EOCD + ZIP64 结构
        tail_len = min(131072, self.size)
        tail = http_range(self.url, self.size - tail_len, self.size - 1)
        base = self.size - tail_len

        # ZIP64 EOCD locator: PK\x06\x07 (20 字节)
        loc = tail.rfind(b"PK\x06\x07")
        cd_off = cd_size = cd_count = None
        if loc >= 0:
            z64_eocd_off = struct.unpack("<Q", tail[loc + 8:loc + 16])[0]
            z64 = http_range(self.url, z64_eocd_off, z64_eocd_off + 55)
            if z64[:4] == b"PK\x06\x06":
                cd_count = struct.unpack("<Q", z64[32:40])[0]
                cd_size = struct.unpack("<Q", z64[40:48])[0]
                cd_off = struct.unpack("<Q", z64[48:56])[0]

        if cd_off is None:
            # 普通 EOCD: PK\x05\x06
            e = tail.rfind(b"PK\x05\x06")
            if e < 0:
                raise RuntimeError("找不到 zip 目录（可能不是 zip 或需要登录）")
            cd_count = struct.unpack("<H", tail[e + 10:e + 12])[0]
            cd_size = struct.unpack("<I", tail[e + 12:e + 16])[0]
            cd_off = struct.unpack("<I", tail[e + 16:e + 20])[0]

        cd = http_range(self.url, cd_off, cd_off + cd_size - 1)

        p = 0
        while p + 46 <= len(cd) and cd[p:p + 4] == b"PK\x01\x02":
            method = struct.unpack("<H", cd[p + 10:p + 12])[0]
            csize = struct.unpack("<I", cd[p + 20:p + 24])[0]
            usize = struct.unpack("<I", cd[p + 24:p + 28])[0]
            nlen = struct.unpack("<H", cd[p + 28:p + 30])[0]
            elen = struct.unpack("<H", cd[p + 30:p + 32])[0]
            clen = struct.unpack("<H", cd[p + 32:p + 34])[0]
            lho = struct.unpack("<I", cd[p + 42:p + 46])[0]
            name = cd[p + 46:p + 46 + nlen].decode("utf-8", "replace")

            # ZIP64 扩展字段里可能覆盖 size/offset
            if 0xFFFFFFFF in (csize, usize, lho):
                q = p + 46 + nlen
                end = q + elen
                while q + 4 <= end:
                    hid = struct.unpack("<H", cd[q:q + 2])[0]
                    hsz = struct.unpack("<H", cd[q + 2:q + 4])[0]
                    if hid == 0x0001:
                        body = cd[q + 4:q + 4 + hsz]
                        k = 0
                        if usize == 0xFFFFFFFF:
                            usize = struct.unpack("<Q", body[k:k + 8])[0]; k += 8
                        if csize == 0xFFFFFFFF:
                            csize = struct.unpack("<Q", body[k:k + 8])[0]; k += 8
                        if lho == 0xFFFFFFFF:
                            lho = struct.unpack("<Q", body[k:k + 8])[0]; k += 8
                    q += 4 + hsz

            self.entries[name] = dict(method=method, csize=csize, usize=usize, lho=lho)
            p += 46 + nlen + elen + clen

    def read(self, name):
        e = self.entries[name]
        hdr = http_range(self.url, e["lho"], e["lho"] + 29)
        nlen = struct.unpack("<H", hdr[26:28])[0]
        elen = struct.unpack("<H", hdr[28:30])[0]
        start = e["lho"] + 30 + nlen + elen
        data = http_range(self.url, start, start + e["csize"] - 1)
        if e["method"] == 0:
            return data
        import zlib
        return zlib.decompress(data, -15)


def list_payload(data):
    """粗读 payload.bin 的 manifest，列出分区名与大小。"""
    try:
        if data[:4] != b"CrAU":
            return
        ver = struct.unpack(">Q", data[4:12])[0]
        msize = struct.unpack(">Q", data[12:20])[0]
        man = data[20:20 + msize]
        # manifest 是 protobuf，直接抓可打印的分区名
        import re
        names = re.findall(rb"[a-z_]{2,20}", man)
        seen, out = set(), []
        for n in names:
            s = n.decode()
            if s not in seen and len(s) > 2:
                seen.add(s)
                out.append(s)
        print(f"  payload 版本 {ver}，manifest {msize} 字节")
        print("  候选分区名:", ", ".join(out[:40]))
    except Exception as ex:
        print("  payload 解析失败:", ex)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--part")
    ap.add_argument("--out")
    a = ap.parse_args()

    print(f"打开 {a.url[:80]}...")
    z = RemoteZip(a.url)
    print(f"总大小 {z.size / 1073741824:.2f} GB，{len(z.entries)} 个条目\n")

    interesting = [n for n in z.entries
                   if n.endswith((".img", ".bin")) or "payload" in n or "boot" in n]
    for n in sorted(interesting):
        e = z.entries[n]
        print(f"  {n:<48} {e['usize']:>12,} B  {'STORED' if e['method'] == 0 else 'DEFLATE'}")

    if a.list:
        if "payload.bin" in z.entries:
            print("\n读取 payload manifest...")
            head = z.read("payload.bin")
            list_payload(head[:4 << 20])
        return

    if not a.part:
        return
    target = None
    for cand in (f"{a.part}.img", f"images/{a.part}.img", a.part):
        if cand in z.entries:
            target = cand
            break
    if not target:
        print(f"\n找不到 {a.part}.img；若只有 payload.bin，需要解 payload")
        return

    out = a.out or f"{a.part}.img"
    print(f"\n提取 {target} -> {out}")
    data = z.read(target)
    with open(out, "wb") as f:
        f.write(data)
    print(f"完成 {len(data):,} 字节")


if __name__ == "__main__":
    sys.exit(main())
