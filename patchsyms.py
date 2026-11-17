#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""patchsyms.py —— 把 .ko 里指向"未导出内核符号"的重定位，改指到一个导出符号 + 固定偏移

原理：KASLR 对整个内核镜像做统一平移，所以任意两个内核符号的**差值恒定**。
取 `printk`（已导出、CRC 已知）当锚点，把
    R_X(sym=X, addend=a)   →   R_X(sym=printk, addend=a + (addr(X) - addr(printk)))
模块加载时内核按 `val = sym->st_value + r_addend` 算地址（MOVW/ABS64/CALL26 都走这条），
拿到的就是 X 的真实地址。CALL26 越界时内核还会自动生成 PLT veneer（ARM64_MODULE_PLTS）。

处理完把 X 在符号表里标成 SHN_ABS，避免 simplify_symbols 再去解析它。

用法：python patchsyms.py <ko> [anchor]
"""
import json
import struct
import sys

SHT_RELA = 4
SHT_SYMTAB = 2
SHN_UNDEF = 0
SHN_ABS = 0xfff1
R_AARCH64_ABS64 = 257
R_AARCH64_PREL32 = 261
R_AARCH64_CALL26 = 283
R_AARCH64_JUMP26 = 282
MOVW_RANGE = (264, 265, 266, 267)   # G0_NC? 实际类型号见下
MOVW_TYPES = {0x108, 0x109, 0x10a, 0x10b, 0x10c, 0x10d}

SYMS_TXT = r'C:/Users/XXL/WorkBuddy/2026-10-15-03-17-27/_deliverables/findx3-4.19/symbols.txt'

# 内核里根本不存在的符号（5.x 才有）：没有自己的地址，直接映射到语义最接近的导出符号。
# key = 模块里引用的名字，value = 内核里真实存在、可用的替代符号
OVERRIDE = {
    '__cfi_slowpath':     'printk',    # CFI 慢路径，4.19 无 CFI，正常情况下永不调用
    '__dynamic_pr_debug': 'printk',    # 动态调试，退化成普通打印
    'strscpy_pad':        'strscpy',   # 只差"补零"，签名一致
}


def load_syms():
    d = {}
    for ln in open(SYMS_TXT, encoding='utf-8', errors='replace'):
        p = ln.split()
        if len(p) >= 3:
            try:
                d[p[2]] = int(p[0], 16)
            except ValueError:
                pass
    return d


def main():
    path = sys.argv[1]
    anchor = sys.argv[2] if len(sys.argv) > 2 else 'printk'
    ksym = load_syms()
    d = bytearray(open(path, 'rb').read())

    e_shoff, = struct.unpack_from('<Q', d, 0x28)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from('<HHH', d, 0x3a)
    secs = []
    for i in range(e_shnum):
        o = e_shoff + i * e_shentsize
        name, typ, flags, addr, off, size, link, info, align, entsz = struct.unpack_from(
            '<IIQQQQIIQQ', d, o)
        secs.append(dict(i=i, name=name, type=typ, off=off, size=size,
                         link=link, info=info, entsz=entsz, hdr=o))

    shstr = secs[e_shstrndx]
    def sname(s):
        b = d[shstr['off'] + s['name']:]
        return b.split(b'\0', 1)[0].decode()

    symtab = next(s for s in secs if s['type'] == SHT_SYMTAB)
    strtab = secs[symtab['link']]
    nent = symtab['size'] // 24

    def sym_name(idx):
        st_name, = struct.unpack_from('<I', d, symtab['off'] + idx * 24)
        b = d[strtab['off'] + st_name:]
        return b.split(b'\0', 1)[0].decode()

    idx_of = {}
    und = []
    for i in range(1, nent):
        o = symtab['off'] + i * 24
        st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from('<IBBHQQ', d, o)
        if st_shndx == SHN_UNDEF:
            n = sym_name(i)
            idx_of.setdefault(n, i)
            und.append(n)

    if anchor not in idx_of:
        print('锚点 %s 不在未定义符号里，无法打补丁' % anchor)
        return 1
    aidx = idx_of[anchor]
    if anchor not in ksym:
        print('符号表里没有锚点地址')
        return 1
    abase = ksym[anchor]

    patched, skipped = [], []
    targets = [n for n in und if n not in ('__this_module',)]
    # 只处理"内核符号表里有、但模块导不出"的那些；导出符号走 __versions，不动
    cm = json.load(open(r'C:/Users/XXL/WorkBuddy/2026-10-15-03-17-27/_deliverables/findx3-4.19/crc_map.json'))

    todo = {}
    for n in targets:
        if n in cm:
            continue          # 已导出，正常解析
        real = OVERRIDE.get(n, n)
        if real not in ksym:
            skipped.append(n)
            continue
        todo[idx_of[n]] = ksym[real] - abase

    nrel = 0
    for s in secs:
        if s['type'] != SHT_RELA:
            continue
        cnt = s['size'] // 24
        for k in range(cnt):
            o = s['off'] + k * 24
            r_off, r_info, r_add = struct.unpack_from('<QQq', d, o)
            rsym = r_info >> 32
            rtype = r_info & 0xffffffff
            if rsym not in todo:
                continue
            delta = todo[rsym]
            struct.pack_into('<Q', d, o + 8, ((aidx & 0xffffffff) << 32) | rtype)
            struct.pack_into('<q', d, o + 16, r_add + delta)
            nrel += 1

    for i, delta in todo.items():
        o = symtab['off'] + i * 24
        struct.pack_into('<H', d, o + 6, SHN_ABS)   # st_shndx
        struct.pack_into('<Q', d, o + 8, 0)         # st_value

    open(path, 'wb').write(d)
    print('重定向符号 %d 个，重定位条目 %d 条，锚点 %s' % (len(todo), nrel, anchor))
    for i, delta in sorted(todo.items(), key=lambda x: -x[1]):
        print('  %-34s delta=%+#x' % (sym_name(i), delta))
    if skipped:
        print('符号表里找不到（未处理）:', skipped)
    return 0


if __name__ == '__main__':
    sys.exit(main())
