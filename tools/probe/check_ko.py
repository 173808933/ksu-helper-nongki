#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查 ko 能否真的加载到目标内核上。

一次性回答四个问题：
  1. vermagic 是否与目标内核一致
  2. __versions 段是否为空（空段比没有更糟，见下）
  3. 所有未定义符号在目标内核符号表里是否存在
  4. 这些符号是否**已导出**（内核只允许模块用 EXPORT_SYMBOL 的）

为什么空 __versions 会致命
--------------------------
内核 module.c 的 check_version()：

    for (i = 0; i < e_shnum; i++)
        if (!strcmp(secstrings + sh_name, "__versions")) break;

    if (i == e_shnum) return 1;          // 没有这个段 → 直接放行

    num = sh_size / sizeof(*versions);   // 段存在但为空 → num = 0
    for (...) if (!strcmp(versions[i].name, symname)) { ... }
    pr_err("no symbol version for %s\\n", symname);
    return 0;                             // ← 加载失败

所以「编 ko 时缺 Module.symvers」会生成一个 0 字节的 __versions 段，
反而比完全不带还糟：内核会报 no symbol version for module_layout。

修法：llvm-objcopy --remove-section=__versions（段有 debug 重定位时先 strip）

用法
----
  python check_ko.py <ko> <target-elf>

  target-elf 是用 vmlinux-to-elf 从 boot.img 恢复出来的带符号内核。
"""

import subprocess
import sys


def run(*args):
    return subprocess.run(args, capture_output=True, text=True).stdout


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 1
    ko, elf = sys.argv[1], sys.argv[2]

    print("=" * 60)
    print("ko      :", ko)
    print("target  :", elf)

    # 1) vermagic
    mi = run("llvm-readelf", "-p", ".modinfo", ko)
    vm = ""
    for line in mi.splitlines():
        if "vermagic=" in line:
            vm = line.split("vermagic=")[1].rstrip('"]').strip()
            break
    kv = vm.split(" ")[0] if vm else "(读不到)"
    print("\n[1] vermagic :", kv)
    if not vm:
        print("    ! 读不到 vermagic，模块可能不含 .modinfo")

    # 2) __versions
    sec = run("llvm-readelf", "-S", ko)
    vs_size, has_vs = None, False
    for line in sec.splitlines():
        if "__versions" in line and "PROGBITS" in line:
            parts = line.split()
            try:
                idx = parts.index("PROGBITS")
                vs_size = int(parts[idx + 3], 16)
            except (ValueError, IndexError):
                vs_size = -1
            has_vs = True
            break
    if not has_vs:
        print("[2] __versions: 不存在 → 内核 check_version 直接放行 ✓")
    elif vs_size == 0:
        print("[2] __versions: 存在但为 0 字节 → 加载会报 "
              "\"no symbol version for module_layout\"  ✗")
        print("    修: llvm-objcopy --remove-section=__versions <ko>")
    else:
        print(f"[2] __versions: {vs_size} 字节（有 CRC 表，需与目标内核一致）")

    # 3/4) 符号
    undef = [l.split()[-1] for l in run("llvm-nm", "-u", ko).splitlines()
             if len(l.split()) == 2]
    defined, exported = set(), set()
    for line in run("llvm-nm", elf).splitlines():
        p = line.split()
        if len(p) == 3:
            defined.add(p[2])
            if p[2].startswith("__ksymtab_"):
                exported.add(p[2][len("__ksymtab_"):])

    missing = [s for s in undef if s not in defined]
    notexp = [s for s in undef if s not in exported and s not in missing]
    ok = len(undef) - len(missing) - len(notexp)

    print(f"\n[3] 未定义符号 {len(undef)} 个")
    print(f"    内核里不存在 : {len(missing)}")
    for s in missing[:10]:
        print("       MISSING      ", s)
    print(f"    存在但未导出 : {len(notexp)}")
    for s in notexp[:10]:
        print("       NOT-EXPORTED ", s)
    print(f"    可直接使用   : {ok}")

    print("\n" + "=" * 60)
    verdict = []
    verdict.append("vermagic 需手动核对内核版本" if vm else "vermagic 缺失")
    if has_vs and vs_size == 0:
        verdict.append("空 __versions 会导致加载失败")
    if missing or notexp:
        verdict.append("有符号无法解析，insmod 会报 Unknown symbol")
    if len(verdict) == 1 and not missing and not notexp:
        print("结论：符号层面可以加载（vermagic 与空段两项需人工确认后再试）")
    else:
        print("结论：", "；".join(verdict))
    return 0


if __name__ == "__main__":
    sys.exit(main())
