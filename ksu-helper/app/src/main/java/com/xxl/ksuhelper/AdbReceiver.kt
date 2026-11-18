package com.xxl.ksuhelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ADB 命令行接口。
 *
 * 用有序广播（ordered broadcast）把结果同步回给 adb —— `am broadcast` 会打印
 * `Broadcast completed: result=0, data="..."`，天然就是一条命令一个返回值，
 * 不需要额外写文件再 cat。
 *
 * 时长限制：广播有约 10s 的超时，所以只实现**短命令**。
 * 提权（40s+）和探测（60s+）请用 am start 走 Activity，见 docs/ADB.md。
 *
 * 用法：
 *   adb shell am broadcast -a com.xxl.ksuhelper.CMD --es cmd status
 *   adb shell am broadcast -a com.xxl.ksuhelper.CMD --es cmd shell --es arg "id"
 */
class AdbReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd")?.trim()?.lowercase().orEmpty()
        if (cmd.isEmpty()) {
            setResultData("err: 缺少 --es cmd。可用：status|appid|shell|kos|clean|doc")
            return
        }
        val arg = intent.getStringExtra("arg").orEmpty()

        // 广播回调有 ~10s 预算，放子线程做 IO，用 goAsync 保活
        val pending = goAsync()
        Thread({
            val out = try {
                dispatch(context.applicationContext, cmd, arg)
            } catch (t: Throwable) {
                "err: ${t.javaClass.simpleName}: ${t.message}"
            }
            try {
                pending.setResultData(out)
            } catch (_: Throwable) {
            }
            pending.finish()
        }, "adb-cmd").start()
    }

    private fun dispatch(ctx: Context, cmd: String, arg: String): String = when (cmd) {
        "status" -> status(ctx)
        "appid" -> "pkg=${ctx.packageName}\nuid=${ctx.applicationInfo.uid}"
        "shell" -> shell(arg)
        "kos" -> kos()
        "clean" -> clean(ctx)
        "doc" -> DOC
        else -> "err: 未知命令 $cmd。可用：status|appid|shell|kos|clean|doc"
    }

    // ---------- 具体命令 ----------

    private fun status(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("pkg=").append(ctx.packageName).append(" uid=").append(ctx.applicationInfo.uid).append('\n')
        sb.append("device=").append(Build.DEVICE).append(" model=").append(Build.MODEL).append('\n')
        sb.append("android=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
        sb.append("kernel=").append(System.getProperty("os.version") ?: "?").append('\n')
        sb.append("boot_id=").append(readBootId()).append('\n')
        sb.append("shizuku=").append(if (Shizuku.pingBinder()) "binder-ok" else "binder-dead")
        sb.append(" perm=").append(
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ) "granted" else "denied"
        ).append('\n')

        val probe = sh(
            "echo kptr=\$(cat /proc/sys/kernel/kptr_restrict 2>/dev/null); " +
                    "echo module=\$( [ -d /sys/module/kernelsu ] && echo yes || echo no ); " +
                    "echo rksh=\$( [ -S /data/local/tmp/rksh.sock ] && echo yes || echo no ); " +
                    "echo glhold=\$(cat /data/local/tmp/.glhold 2>/dev/null || echo none); " +
                    "echo p7=\$( [ -x /data/local/tmp/p7 ] && echo yes || echo no ); " +
                    "echo gl54=\$( [ -x /data/local/tmp/ghostlock54 ] && echo yes || echo no )",
            8
        )
        sb.append(probe.trim()).append('\n')
        sb.append("ko:\n").append(kos())
        return sb.toString()
    }

    /** 以 root（rksh 存在时）或 shell 身份执行一条命令。 */
    private fun shell(cmd: String): String {
        if (cmd.isBlank()) return "err: 缺少 --es arg \"<命令>\""
        val out = sh("[ -S /data/local/tmp/rksh.sock ] && echo RKSH || echo SH", 5).trim()
        return if (out.contains("RKSH")) sh("/data/local/tmp/rksh -c " + cmd.sq(), 9)
        else sh(cmd, 9)
    }

    /** 列出 /sdcard/Download 下的 ko 及其 vermagic，方便确认该装哪个。 */
    private fun kos(): String {
        val d = File("/sdcard/Download")
        val list = d.listFiles { f -> f.isFile && f.name.endsWith(".ko") } ?: return "(无 /sdcard/Download 访问权限)"
        if (list.isEmpty()) return "(Download 下没有 .ko)"
        return list.sortedBy { it.name }.joinToString("\n") { f ->
            "  ${f.name}  ${f.length()} B  vermagic=${vermagicOf(f) ?: "(读不到明文)"}"
        }
    }

    /** 清掉测试残留：.glhold（否则本次开机不让再跑）和 ko 缓存。 */
    private fun clean(ctx: Context): String {
        val sb = StringBuilder()
        val gl = sh("rm -f /data/local/tmp/.glhold && echo removed || echo fail", 8).trim()
        sb.append("glhold: ").append(gl).append('\n')
        val koDir = File(ctx.getExternalFilesDir(null), "ko")
        var n = 0
        koDir.listFiles()?.forEach { if (it.delete()) n++ }
        sb.append("ko-cache: 删除 $n 个文件（${koDir.absolutePath}）\n")
        sb.append("提示：.glhold 清掉后本次开机可重跑漏洞，但 init_cred 的预算不会恢复，慎用")
        return sb.toString()
    }

    // ---------- 底层 ----------

    private fun sh(cmd: String, timeoutSec: Long): String {
        if (!Shizuku.pingBinder()) return "(Shizuku binder 不在线)"
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return "(Shizuku 未授权，先在 App 里点授权)"
        }
        return try {
            val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val sb = StringBuilder()
            val t1 = Thread {
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { synchronized(sb) { sb.append(it).append('\n') } }
                }
            }
            val t2 = Thread {
                runCatching {
                    p.errorStream.bufferedReader().forEachLine { synchronized(sb) { sb.append(it).append('\n') } }
                }
            }
            t1.start(); t2.start()
            val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!done) p.destroy()
            t1.join(800); t2.join(800)
            val text = synchronized(sb) { sb.toString().trimEnd() }
            if (done) text else "$text\n[超时 ${timeoutSec}s]"
        } catch (t: Throwable) {
            "(执行失败: ${t.javaClass.simpleName}: ${t.message})"
        }
    }

    private fun readBootId(): String = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    } catch (_: Throwable) {
        "?"
    }

    /** 从 .ko 里抠 vermagic（和 App 内逻辑一致，只认明文）。 */
    private fun vermagicOf(f: File): String? = try {
        val key = "vermagic=".toByteArray(Charsets.US_ASCII)
        val b = f.readBytes()
        var head = -1
        var i = 0
        while (i <= b.size - key.size) {
            if (b[i] == key[0]) {
                var j = 1
                while (j < key.size && b[i + j] == key[j]) j++
                if (j == key.size) { head = i + key.size; break }
            }
            i++
        }
        if (head < 0) null else {
            var e = head
            while (e < b.size && b[e] != 0.toByte()) e++
            String(b, head, e - head, Charsets.UTF_8).trim().ifEmpty { null }
        }
    } catch (_: Throwable) {
        null
    }

    /** 单引号包裹，内部单引号转成 '\'' —— 防止命令被截断。 */
    private fun String.sq(): String = "'" + replace("'", "'\\''") + "'"

    companion object {
        const val ACTION = "com.xxl.ksuhelper.CMD"

        private val DOC = """
            KSU Helper ADB 接口
            ────────────────────────────────────────────
            adb shell am broadcast -a $ACTION --es cmd <命令> [--es arg <参数>]

              status        设备/内核/root/模块/ko 一览（多行）
              appid         包名与 uid（写 allowlist 用）
              shell         执行命令，rksh 存在时自动走 root
                              adb shell am broadcast -a $ACTION --es cmd shell --es arg "id"
              kos           列出 /sdcard/Download/*.ko 及其 vermagic
              clean         删除 .glhold 与 ko 缓存（测试前清场）
              doc           显示本说明

            长任务（超广播 10s 限制）用 Activity：
              adb shell am start -n com.xxl.ksuhelper/.MainActivity --es run root
              adb shell am start -n com.xxl.ksuhelper/.MainActivity --es run probe

            返回值形如：Broadcast completed: result=0, data="..."
            多行内容里的换行会以 \n 转义显示。
        """.trimIndent()
    }
}
