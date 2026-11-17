"""把 KSU 3.x 的 SELinux 策略注入改造成 4.19 的「序列化 + 重载」形式。

4.19 的 selinux_ss 里 policydb 是内联的，没有可替换的 policy 指针，
复制出来改完后必须用 security_load_policy 重新装回去。
"""
import os


def _cut(src, start, end, replacement):
    i = src.index(start)
    j = src.index(end, i) + len(end)
    return src[:i] + replacement + src[j:]


def _replace_func(src, head_marker, new_text):
    """按大括号配对替换整个函数（head_marker 必须包含函数签名和左花括号）。"""
    i = src.index(head_marker)
    k = src.index("{", i)
    depth = 0
    while k < len(src):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                k += 1
                break
        k += 1
    return src[:i] + new_text + src[k:]


def patch_sepolicy_419(port):
    # ================= rules.c =================
    p = os.path.join(port, "selinux/rules.c")
    src = open(p, encoding="utf-8").read()

    src = src.replace("struct selinux_policy *backup_sepolicy;\n", "")

    head_old = "    struct selinux_policy *pol, *old_pol;\n    struct policydb *db;"
    head_new = "    struct policydb *db;"

    head_body = (
        "    ksu419_lock();\n"
        "    db = ksu419_policy_dup();\n"
        "    if (!db) {\n"
        '        pr_err("ksu419: dup sepolicy failed\\n");\n'
        "        ksu419_unlock();\n"
        "        %s\n"
        "    }"
    )

    tail_body = (
        "    if (success_cmd_count > 0) {\n"
        "        int commit_ret = ksu419_policy_commit(db);\n"
        "\n"
        "        if (commit_ret) {\n"
        '            pr_err("ksu419: commit sepolicy failed: %d\\n", commit_ret);\n'
        "            success_cmd_count = commit_ret;\n"
        "        }\n"
        "    }\n"
        "    ret = success_cmd_count;\n"
        "    goto out_419_cleanup;\n"
        "\n"
        "out_drop_new_policy:\n"
        "out_419_cleanup:\n"
        "    ksu419_policy_free(db);\n"
        "    ksu419_unlock();"
    )

    tail_apply = (
        "    {\n"
        "        int commit_ret = ksu419_policy_commit(db);\n"
        "\n"
        "        if (commit_ret)\n"
        '            pr_err("ksu419: apply rules failed: %d\\n", commit_ret);\n'
        "    }\n"
        "    ksu419_policy_free(db);\n"
        "    ksu419_unlock();"
    )

    src = src.replace(head_old, head_new, 1)
    src = _cut(
        src,
        "    mutex_lock(&selinux_state.policy_mutex);",
        "    db = &pol->policydb;",
        head_body % "return;",
    )
    src = _cut(
        src,
        "    rcu_assign_pointer(selinux_state.policy, pol);",
        "out_unlock:\n    mutex_unlock(&selinux_state.policy_mutex);",
        tail_apply,
    )

    src = src.replace(head_old, head_new, 1)
    src = _cut(
        src,
        "    mutex_lock(&selinux_state.policy_mutex);",
        "    db = &pol->policydb;",
        head_body % "ret = -ENOMEM;\n        goto out_free;",
    )
    src = _cut(
        src,
        "    rcu_assign_pointer(selinux_state.policy, pol);",
        "out_unlock:\n    mutex_unlock(&selinux_state.policy_mutex);",
        tail_body,
    )

    open(p, "w", encoding="utf-8", newline="\n").write(src)
    print("  rules.c: 换成 reload 模式")

    # ================= sepolicy.c =================
    p = os.path.join(port, "selinux/sepolicy.c")
    src = open(p, encoding="utf-8").read()

    src = src.replace(
        "#define KSU_SUPPORT_ADD_TYPE",
        "/* 4.19: flex_array based policydb, add_type disabled */",
    )

    # add_type 定义（注意：必须带左花括号，才能跳过前面的函数声明）
    src = _replace_func(
        src,
        "static bool add_type(struct policydb *db, const char *type_name, bool attr)\n{",
        "static bool add_type(struct policydb *db, const char *type_name, bool attr)\n"
        "{\n"
        "    /* 4.19: type_val_to_struct / sym_val_to_name 是 flex_array，\n"
        "     * 与 5.x 的连续数组不兼容。Android 策略里 su 域本来就存在。 */\n"
        '    pr_info("ksu419: add_type(%s) skipped on 4.19\\n", type_name);\n'
        "    return false;\n"
        "}\n",
    )

    # filename_trans：4.19 是单链表结构，与 5.x 的 key/datum 完全不同
    src = _replace_func(
        src,
        "static bool add_filename_trans(struct policydb *db, const char *s, const char *t, const char *c, const char *d,\n"
        "                               const char *o)\n{",
        "static bool add_filename_trans(struct policydb *db, const char *s, const char *t,\n"
        "                               const char *c, const char *d, const char *o)\n"
        "{\n"
        "    /* 4.19: 暂不支持带文件名的 type_transition */\n"
        '    pr_info("ksu419: add_filename_trans skipped on 4.19\\n");\n'
        "    return false;\n"
        "}\n",
    )
    # 4.19 的 avtab.htable 是 flex_array*，不能直接用 [] 索引
    src = src.replace("for (n = db->te_avtab.htable[i]; n; prev = n, n = n->next) {",
                      "for (n = flex_array_get_ptr(db->te_avtab.htable, i); n; prev = n, n = n->next) {")
    src = src.replace("db->te_avtab.htable[i] = n->next;",
                      "flex_array_put_ptr(db->te_avtab.htable, i, n->next, GFP_ATOMIC);")

    src = src.replace("removed.htable[0] = n;",
                      "flex_array_put_ptr(removed.htable, 0, n, GFP_ATOMIC);")

    print("  sepolicy.c: add_type / filename_trans 桩化 + avtab flex_array 适配")

    # 删掉两个依赖 struct selinux_policy 的函数
    for name in (
        "struct selinux_policy *ksu_dup_sepolicy(",
        "void ksu_destroy_sepolicy(",
    ):
        i = src.index(name)
        j = src.index("\n}\n", i) + 3
        src = src[:i] + "/* 4.19: replaced by sepolicy_419.c (ksu419_*) */\n" + src[j:]

    open(p, "w", encoding="utf-8", newline="\n").write(src)

    # ================= sepolicy.h =================
    p = os.path.join(port, "selinux/sepolicy.h")
    src = open(p, encoding="utf-8").read()
    for line in list(src.splitlines()):
        if "ksu_dup_sepolicy" in line or "ksu_destroy_sepolicy" in line:
            src = src.replace(line + "\n", "")
    if "ksu419_lock" not in src:
        add = (
            "/* 4.19 适配层 */\n"
            "void ksu419_lock(void);\n"
            "void ksu419_unlock(void);\n"
            "struct policydb *ksu419_policy_dup(void);\n"
            "int ksu419_policy_commit(struct policydb *db);\n"
            "void ksu419_policy_free(struct policydb *db);\n"
            "\n#endif"
        )
        src = src.replace("#endif", add, 1)
    open(p, "w", encoding="utf-8", newline="\n").write(src)

    print("  sepolicy 4.19 适配完成")
