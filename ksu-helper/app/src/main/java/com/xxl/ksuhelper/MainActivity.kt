package com.xxl.ksuhelper

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        private const val REQ_SHIZUKU = 1001
        private const val TAG = "KsuHelper"

        /** 纯文本校验，可在本地单元测试，不执行 shell 或加载模块。
         *  必须**完整** release 相等：前缀相同不代表同一构建（5.4.254 vs 5.4.254-qgki-g9ef4…），
         *  读不到标识也不能当匹配 —— 拿错 ko 去 insmod 的代价是内核崩。 */
        internal fun matchesKernelRelease(vermagic: String?, kernelRelease: String): Boolean {
            val release = vermagic?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            return !release.isNullOrBlank() && kernelRelease.isNotBlank() && release == kernelRelease
        }

        /** uid=0 必须整词匹配：`euid=0`、`uid=00`、`uid=0unexpected` 都不算，
         *  且命令本身必须成功（超时/失败时输出里可能有残留的 uid=0）。 */
        internal fun isRootIdentity(exitCode: Int, output: String): Boolean =
            exitCode == 0 && Regex("(?:^|\\s)uid=0(?:\\(|\\s|$)").containsMatchIn(output)
    }

    /** 设备档案：机型匹配后提供该机型的 GL_* 地址 */
    data class Profile(
        val id: String,
        val name: String,
        val models: List<String>,
        val kernel: String,
        val soc: String,
        val env: Map<String, String>,
        val note: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val t0 = System.currentTimeMillis()
    // SimpleDateFormat 不是线程安全的，而 log() 会被主线程/IO/Shizuku 回调并发调用。
    // 用 java.time 的 DateTimeFormatter（不可变、线程安全）。
    private val df = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    // Shizuku
    private var binderAlive by mutableStateOf(false)
    private var permissionGranted by mutableStateOf(false)
    private var serverUid by mutableStateOf(-1)

    // 运行状态
    private var running by mutableStateOf(false)
    private var busyLabel by mutableStateOf("")
    private var rootReady by mutableStateOf(false)
    private var logText by mutableStateOf("")
    private var koPath by mutableStateOf("/sdcard/Download/kernelsu.ko")
    private var ksudPath by mutableStateOf("/sdcard/Download/ksud")
    private var rootCmd by mutableStateOf("id")

    // 档案
    private var profiles by mutableStateOf<List<Profile>>(emptyList())
    private var activeProfile by mutableStateOf<Profile?>(null)
    private var profileAuto by mutableStateOf(true)
    private var useExplicitEnv by mutableStateOf(false)
    private var autoKsu by mutableStateOf(true)

    /** p7 没有任何「只探测不触发」的安全中间档。
     *  QEMU 实测（2026-11-17）：GL_ATTR=self + GL_NOPUNCH=1 + GL_NTRIG=0 时
     *  p7 确实跳过了 trigger（triggering 0 x sched_setattr），但 arm 阶段
     *  FUTEX_LOCK_PI 已在当前栈帧上建了 waiter，进程 _exit 后栈释放 →
     *  PI 链留悬空指针 → 3 秒后内核在 rt_mutex_adjust_prio_chain 里 oops。
     *  所以旧版「只探测」开关是假保险，已移除。 */
    private val kernelVer: String = System.getProperty("os.version") ?: ""

    private val logBuf = StringBuilder()
    private var lastExportAt = 0L
    private var lastRunLogPath: String? = null
    private var ranRootThisSession = false

    /** p7 输出的主副本。/data/local/tmp 在 /data 分区（ext4/f2fs），
     *  比 /sdcard（FUSE → /data/media）少一层转发；配合每 2s sync 才能在 panic 后留下东西。 */
    private val RUN_LOG_REMOTE = "/data/local/tmp/p7run.log"

    /** /proc/sys/kernel/random/boot_id 世界可读，App 不需要任何权限 */
    private fun bootId(): String = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    } catch (e: Throwable) {
        "-"
    }
    private var pendingAuto: String? = null

    private val logFile: File by lazy {
        File(getExternalFilesDir(null), "khelper.log").also { it.parentFile?.mkdirs() }
    }

    private val binderReceivedListener = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() = refreshShizuku()
    }
    private val binderDeadListener = object : Shizuku.OnBinderDeadListener {
        override fun onBinderDead() = refreshShizuku()
    }
    private val permissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode == REQ_SHIZUKU) {
                permissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
                log(if (permissionGranted) "[shizuku] 权限已授予" else "[shizuku] 权限被拒绝")
                refreshShizuku()
                if (permissionGranted) runPending()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                MainScreen()
            }
        }

        try {
            if (logFile.exists() && logFile.length() > 2_000_000) {
                logFile.copyTo(File(logFile.parentFile, "khelper.prev.log"), overwrite = true)
                logFile.writeText("")
            }
        } catch (_: Throwable) {
        }

        section("启动")
        log("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        log("机型 ${Build.MANUFACTURER} ${Build.MODEL} / device=${Build.DEVICE} product=${Build.PRODUCT}")
        log("系统 Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) / ${Build.DISPLAY}")
        log("内核 $kernelVer")
        log("[session] boot_id=${bootId()}  （重启后 boot_id 会变，用它区分会话）")
        loadProfiles()
        refreshShizuku()
        log("Shizuku binder=${if (binderAlive) "在线" else "离线"} 权限=${if (permissionGranted) "已授予" else "未授予"}")

        intent?.getStringExtra("ko")?.let { koPath = it }
        intent?.getStringExtra("ksud")?.let { ksudPath = it }
        intent?.getStringExtra("cmd")?.let { rootCmd = it }
        intent?.getStringExtra("profile")?.let { id ->
            profiles.firstOrNull { it.id == id }?.let {
                activeProfile = it
                profileAuto = false
                if (it.env.isNotEmpty()) useExplicitEnv = true
                log("[档案] 由 intent 指定 ${it.name}，注入=${useExplicitEnv}")
            }
        }
        intent?.getStringExtra("run")?.let { auto ->
            pendingAuto = auto
            log("[auto] 请求执行: $auto")
            scope.launch {
                delay(1200)
                refreshShizuku()
                runPending()
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    // ---------------- 日志 ----------------

    /** 落盘走单线程，避免主线程 fsync（StrictMode 会报 disk write on main）。 */
    private val logWriter = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ksu-log-writer").apply { isDaemon = true }
    }
    @Volatile private var lastLogFlushMs = 0L

    private fun log(s: String) {
        val elapsed = System.currentTimeMillis() - t0
        val line = "[${df.format(java.time.LocalTime.now())} +${elapsed}ms] $s"
        Log.i(TAG, line)
        logWriter.execute {
            try {
                logFile.appendText(line + "\n")
            } catch (_: Throwable) {
            }
        }
        synchronized(logBuf) {
            logBuf.append(line).append('\n')
            if (logBuf.length > 300_000) logBuf.delete(0, logBuf.length - 200_000)
            // 节流：之前每来一行就 toString() 一次最多 300KB，几百行日志等于几十 MB 拷贝
            val now = System.currentTimeMillis()
            if (now - lastLogFlushMs >= 120) {
                lastLogFlushMs = now
                logText = logBuf.toString()
            }
        }
    }

    /** 强制把缓冲刷到 UI。流程结束时调，避免最后几行因节流没显示。 */
    private fun flushLogText() {
        synchronized(logBuf) {
            logText = logBuf.toString()
            lastLogFlushMs = System.currentTimeMillis()
        }
    }

    private fun section(title: String) {
        log("")
        log("========== $title ==========")
    }

    // ---------------- 档案 ----------------

    /** 档案优先读外部，便于不改 APK 就能加机型 / 改地址。
     *  外部路径：<App目录>/profiles.json（用文件管理器或 adb 替换即可）。 */
    private fun readProfilesJson(): String {
        val ext = File(getExternalFilesDir(null), "profiles.json")
        if (ext.exists() && ext.length() > 0L) {
            log("  档案来源：外部 ${ext.absolutePath}")
            return ext.readText()
        }
        return assets.open("profiles.json").bufferedReader().use { it.readText() }
    }

    private fun loadProfiles() {
        val list = mutableListOf<Profile>()
        try {
            val text = readProfilesJson()
            val arr = JSONObject(text).getJSONArray("profiles")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val env = mutableMapOf<String, String>()
                o.optJSONObject("env")?.let { e ->
                    e.keys().forEach { k -> env[k] = e.getString(k) }
                }
                val models = mutableListOf<String>()
                o.optJSONArray("models")?.let { a ->
                    for (j in 0 until a.length()) models.add(a.getString(j))
                }
                list.add(
                    Profile(
                        o.optString("id"), o.optString("name"), models,
                        o.optString("kernel"), o.optString("soc"), env, o.optString("note")
                    )
                )
            }
        } catch (e: Throwable) {
            log("!! 读取 profiles.json 失败: ${e.javaClass.simpleName}: ${e.message}")
        }
        profiles = list
        if (list.isEmpty()) return

        val model = "${Build.MODEL} ${Build.DEVICE} ${Build.PRODUCT}".uppercase()
        val byModel = list.firstOrNull { p ->
            p.models.any { it.isNotEmpty() && model.contains(it.uppercase()) }
        }
        if (byModel != null) {
            activeProfile = byModel
            profileAuto = true
        } else {
            val byKernel = list.filter {
                it.env.isNotEmpty() && it.kernel.isNotEmpty() && kernelVer.startsWith(it.kernel)
            }
            activeProfile = if (byKernel.size == 1) byKernel[0] else list.firstOrNull { it.id == "auto" }
            if (byKernel.size > 1) {
                log("! 内核 $kernelVer 命中 ${byKernel.size} 个档案（${byKernel.joinToString { it.name }}），无法自动判定")
                log("  已回退到通用模式；请在「设备档案」里手动选择")
            }
        }
        val p = activeProfile
        section("设备档案")
        log("选中 ${p?.name ?: "(无)"}  soc=${p?.soc ?: "-"}  kernel=${p?.kernel ?: "-"}")
        if (p != null && p.note.isNotEmpty()) log("备注 ${p.note}")
        if (p != null && p.env.isEmpty() && p.id != "auto") {
            log("! 该档案没有可用地址（KALLSYMS_ALL=n 或非 4.19），只能靠 GL_AUTO 自动推导")
        }
        if (p != null && p.env.isNotEmpty()) {
            useExplicitEnv = true
            log("地址 " + p.env.entries.joinToString(" ") { "${it.key}=${it.value}" })
            log("已默认开启「注入档案地址」（否则 p7 会用它内置的 Find X3 默认地址，本机大概率错位）")
            val missing = missingKeys(p)
            if (missing.isNotEmpty()) {
                log("! 档案不完整，缺 ${missing.joinToString()} —— 这些项会沿用 p7 内置默认值，可能不正确")
            }
        }
    }

    private val fullKeys = listOf(
        "GL_CROFF", "GL_REALCRED", "GL_INITCRED", "GL_INITTASK",
        "GL_SIGENFORCE", "GL_PARANOID", "GL_SELINUX", "GL_SCRATCH"
    )

    private fun missingKeys(p: Profile?): List<String> {
        if (p == null || p.env.isEmpty()) return fullKeys
        return fullKeys.filter { it !in p.env }
    }

    private fun completenessText(p: Profile?): String {
        if (p == null) return "无档案"
        if (p.id == "auto") return "自动推导"
        val miss = missingKeys(p)
        return if (miss.isEmpty()) "地址完整(8/8)" else "缺 ${miss.size} 项地址"
    }

    private fun selectProfile(p: Profile) {
        activeProfile = p
        profileAuto = false
        log("[档案] 手动切换 ${p.name}（env ${p.env.size} 项）")
        if (p.env.isNotEmpty()) log("       " + p.env.entries.joinToString(" ") { "${it.key}=${it.value}" })
    }

    // ---------------- 基础工具 ----------------

    private fun refreshShizuku() {
        binderAlive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        permissionGranted = try {
            binderAlive && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        serverUid = try {
            if (binderAlive) Shizuku.getUid() else -1
        } catch (_: Throwable) {
            -1
        }
    }

    private fun requestShizuku() {
        if (!binderAlive) {
            log("[shizuku] 服务未运行：请先安装并启动 Shizuku")
            return
        }
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                log("[shizuku] 已有权限")
                refreshShizuku()
                runPending()
            } else {
                Shizuku.requestPermission(REQ_SHIZUKU)
                log("[shizuku] 已发起授权请求，请在弹窗点「允许」")
            }
        } catch (e: Throwable) {
            log("[shizuku] 请求失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 不能用 Process.waitFor(timeout, unit)：JDK 默认实现靠 exitValue() 轮询，
     * 只捕获 IllegalThreadStateException；ShizukuRemoteProcess 抛 IllegalArgumentException。
     */
    /** 关闭进程的三个流。EOF 只表示读到尾，文件描述符还得显式关。 */
    private fun closeProc(p: Process) {
        try { p.inputStream.close() } catch (_: Throwable) {}
        try { p.errorStream.close() } catch (_: Throwable) {}
        try { p.outputStream.close() } catch (_: Throwable) {}
    }

    private fun waitProc(proc: Process, timeoutSec: Long): Boolean {
        return try {
            if (proc is rikka.shizuku.ShizukuRemoteProcess) {
                proc.waitForTimeout(timeoutSec, TimeUnit.SECONDS)
            } else {
                proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            }
        } catch (e: Throwable) {
            val end = System.currentTimeMillis() + timeoutSec * 1000
            while (System.currentTimeMillis() < end) {
                try {
                    proc.exitValue(); return true
                } catch (_: Throwable) {
                }
                Thread.sleep(100)
            }
            false
        }
    }

    data class Res(val code: Int, val out: String)

    private fun sh(cmd: String, timeoutSec: Long = 60): Res {
        if (!binderAlive) return Res(-100, "Shizuku 服务未运行")
        if (!permissionGranted) return Res(-100, "Shizuku 未授权")
        val started = System.currentTimeMillis()
        val toClose = mutableListOf<Process>()
        val r = try {
            val proc = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val sb = StringBuilder()
            val tOut = Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { l ->
                        synchronized(sb) { sb.append(l).append('\n') }
                    }
                } catch (_: Throwable) {
                }
            }
            val tErr = Thread {
                try {
                    proc.errorStream.bufferedReader().forEachLine { l ->
                        synchronized(sb) { sb.append(l).append('\n') }
                    }
                } catch (_: Throwable) {
                }
            }
            toClose += proc
            tOut.start(); tErr.start()
            val done = waitProc(proc, timeoutSec)
            if (!done) {
                proc.destroy()
                tOut.join(500); tErr.join(500)
                Res(-101, synchronized(sb) { sb.toString() } + "[超时 ${timeoutSec}s]")
            } else {
                tOut.join(1500); tErr.join(1500)
                val code = try {
                    proc.exitValue()
                } catch (_: Throwable) {
                    -1
                }
                Res(code, synchronized(sb) { sb.toString() })
            }
        } catch (e: Throwable) {
            Res(-102, "启动进程失败: ${e.javaClass.simpleName}: ${e.message}")
        }
        Log.d(TAG, "sh[${r.code}] ${System.currentTimeMillis() - started}ms: ${cmd.take(100)}")
        // 三个流背后是 ParcelFileDescriptor，EOF 不等于关闭。
        // 一次提权里 sh() 会调上百次，不关会撞 fd 上限。
        toClose.forEach { p -> closeProc(p) }
        return r
    }

    private fun shWithStdin(cmd: String, data: ByteArray, timeoutSec: Long = 180): Res {
        if (!binderAlive || !permissionGranted) return Res(-100, "Shizuku 未就绪")
        val toClose = mutableListOf<Process>()
        val res = try {
            val proc = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            toClose += proc
            val sb = StringBuilder()
            val tOut = Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { l ->
                        synchronized(sb) { sb.append(l).append('\n') }
                    }
                } catch (_: Throwable) {
                }
            }
            val tErr = Thread {
                try {
                    proc.errorStream.bufferedReader().forEachLine { l ->
                        synchronized(sb) { sb.append(l).append('\n') }
                    }
                } catch (_: Throwable) {
                }
            }
            tOut.start(); tErr.start()
            Thread {
                try {
                    proc.outputStream.use { it.write(data); it.flush() }
                } catch (_: Throwable) {
                }
            }.start()
            val done = waitProc(proc, timeoutSec)
            if (!done) {
                proc.destroy()
                Res(-101, synchronized(sb) { sb.toString() } + "[超时 ${timeoutSec}s]")
            } else {
                tOut.join(1500); tErr.join(1500)
                val code = try {
                    proc.exitValue()
                } catch (_: Throwable) {
                    -1
                }
                Res(code, synchronized(sb) { sb.toString() })
            }
        } catch (e: Throwable) {
            Res(-102, "stdin 传输失败: ${e.javaClass.simpleName}: ${e.message}")
        }
        // 三个流背后是 ParcelFileDescriptor，EOF 不等于关闭。
        // 一次提权里 sh() 会调上百次，不关会撞 fd 上限。
        toClose.forEach { p -> closeProc(p) }
        return res
    }

    /** 本地 APK 完整性检查，不访问设备 shell，也不执行任何引擎。 */
    private fun missingPackagedAssets(): List<String> {
        val bundled = assets.list("")?.toSet().orEmpty()
        val rk9Asset = if (kernelVer.startsWith("5.4")) "rk9-54.ko" else "rk9.ko"
        // 当前机型对应的内置 ko 也必须存在，否则 pickBundledKo() 返回的名字
        // 在 APK 里查不到，装载会在 assets.open() 处才炸
        val bundledKoAsset = pickBundledKo()?.asset
        val required = listOf(
            "p7", rk9Asset, "rkshd", "rksh", "ghostlock54",
            "probe.sh", "probe-perf", "ksucheck", "profiles.json"
        ) + listOfNotNull(bundledKoAsset)
        return required.filter { it !in bundled }
    }

    private fun auditPackagedAssets() {
        if (running) return
        running = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    section("APK 完整性检查（仅本地读取）")
                    val missing = missingPackagedAssets()
                    val bundled = assets.list("")?.sorted().orEmpty()
                    log("  包内资源：${bundled.joinToString()}")
                    log("  内核：$kernelVer；该结果只检查文件存在，不证明内核兼容")
                    if (missing.isEmpty()) {
                        log("  文件存在性检查通过；尚未验证授权、模块或 root")
                    } else {
                        log("  检查未通过：缺少 ${missing.joinToString()}")
                        log("  组件释放将被阻断，未执行推送、提权或内核操作")
                    }
                }
            } catch (e: Throwable) {
                log("!! APK 检查失败: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                flushLogText()
                busyLabel = ""
                running = false
            }
        }
    }

    private fun extractAssets(): File {
        // fail-closed：缺件就不放行，别等推到设备上才报一堆莫名其妙的错
        val missing = missingPackagedAssets()
        check(missing.isEmpty()) { "APK 缺少必需资源：${missing.joinToString()}；已在释放组件前停止" }
        val dir = File(getExternalFilesDir(null), "bin")
        if (!dir.exists() && !dir.mkdirs()) error("无法创建目录 ${dir.absolutePath}")
        // rk9 是内核模块，4.19 和 5.4 的 KAPI 不同，按内核版本选
        val rk9Asset = if (kernelVer.startsWith("5.4")) "rk9-54.ko" else "rk9.ko"
        val pairs = listOf(
            "p7" to "p7", rk9Asset to "rk9.ko", "rkshd" to "rkshd", "rksh" to "rksh",
            // 5.4 专用引擎（源码级移植：fake lock 窗口 + rb_erase 越界写），
            // 与 p7 是两条独立路线，按内核版本二选一
            "ghostlock54" to "ghostlock54",
            // 只读探测件：不写内核、不提权、不烧 init_cred 预算
            "probe.sh" to "probe.sh",
            "probe-perf" to "probe-perf",
            "ksucheck" to "ksucheck"
        )
        for ((src, dst) in pairs) {
            val f = File(dir, dst)
            assets.open(src).use { input ->
                f.outputStream().use { out -> input.copyTo(out) }
            }
            f.setReadable(true, false)
        }
        return dir
    }

    /** 内置 ko 描述 */
    private data class BundledKo(val asset: String, val label: String, val vermagic: String)

    /**
     * 按内核版本 / 机型组挑内置 ko。
     * 现有 4 个：qcom-4.19.157、mtk-4.19.191、sm7325-5.4.254（UAPI5/UAPI6）。
     * 返回 null 表示该内核本轮没有可用的内置模块（走 /sdcard/Download 兜底）。
     */
    private fun pickBundledKo(): BundledKo? {
        val kv = kernelVer
        val pid = activeProfile?.id ?: ""
        // UAPI6 只在手动选中带 _uapi6 的档案时使用；默认 UAPI5（KSU 3.3.1 管理器）
        val uapi6 = pid.endsWith("_uapi6")
        return when {
            kv.startsWith("5.4") -> if (uapi6)
                BundledKo("kernelsu-sm7325-uapi6.ko", "sm7325-5.4.254 UAPI6", "5.4.254-qgki-gaa96eae94d32")
            else
                BundledKo("kernelsu-sm7325-uapi5.ko", "sm7325-5.4.254 UAPI5(secpolicy)", "5.4.254-qgki-gaa96eae94d32")

            pid in listOf("oppo_findx3_pedm00", "oppo_pad_opd2101", "oneplus_9r_le2100") ->
                BundledKo("kernelsu-qcom157-uapi5.ko", "qcom-4.19.157 UAPI5", "4.19.157-perf+")

            pid in listOf(
                "oppo_a2pro_pjg110", "oppo_reno6pro_peem00",
                "oppo_reno5pro_pdsm00", "oppo_a2m_pju110", "oppo_a97_pftm10"
            ) -> BundledKo("kernelsu-mtk419-uapi5.ko", "mtk-4.19.191 UAPI5", "4.19.191+")

            // 档案没命中时按内核大版本兜底（同组内核 vermagic 前缀一致）
            kv.startsWith("4.19.157") ->
                BundledKo("kernelsu-qcom157-uapi5.ko", "qcom-4.19.157 UAPI5(兜底)", "4.19.157-perf+")

            kv.startsWith("4.19.191") ->
                BundledKo("kernelsu-mtk419-uapi5.ko", "mtk-4.19.191 UAPI5(兜底)", "4.19.191+")

            else -> null
        }
    }

    /** 取模块文件：优先 App 目录 ko/（用户自己放的），其次内置 assets。
     *
     *  注：内置的 4 个 kernelsu-*.ko 共 20MB，已从 APK 移出以控制体积；
     *  5.4 走 ghostlock54 时它自带精简版 ko，根本不需要这里的文件。
     */
    private fun bundledKo(b: BundledKo): File? {
        val f = File(getExternalFilesDir(null), "ko/${b.asset}")
        if (f.exists() && f.length() > 0L) return f
        return try {
            f.parentFile?.mkdirs()
            assets.open(b.asset).use { i ->
                f.outputStream().use { o -> i.copyTo(o) }
            }
            f
        } catch (e: Throwable) {
            log("  ! 未找到模块 ${b.asset}")
            log("    新版把内置 ko 移出了 APK（省 20MB），请放一份到：")
            log("      ${f.absolutePath}")
            if (File(koPath).exists()) {
                log("    检测到手动指定的 $koPath，本轮改用它")
                return File(koPath)
            }
            log("    也可以在上方「模块路径」里指定现成 ko")
            null
        }
    }

    /** 读不到标识不得当作匹配；只比较完整 release，非 GKI 仍需另核对 CRC/CFI。 */
    private fun matchesKernelRelease(vermagic: String?): Boolean =
        Companion.matchesKernelRelease(vermagic, kernelVer)

    /** 从 ko 里抠出 vermagic 字符串，用于运行前比对内核（不匹配就别 insmod 了） */
    private fun koVermagic(f: File): String? = try {
        val b = f.readBytes()
        val key = "vermagic=".toByteArray(Charsets.US_ASCII)
        var i = 0
        var head = -1
        while (i <= b.size - key.size) {
            if (b[i] == key[0]) {
                var j = 1
                while (j < key.size && b[i + j] == key[j]) j++
                if (j == key.size) {
                    head = i + key.size
                    break
                }
            }
            i++
        }
        if (head < 0) null else {
            var e = head
            while (e < b.size && b[e] != 0.toByte()) e++
            String(b, head, e - head, Charsets.UTF_8).trim().ifEmpty { null }
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * 推送一个文件。
     * 目标可能正在被执行（p7 成功后进程仍持有可执行映像），此时直接 cp 会
     * ETXTBSY("Text file busy")。所以一律先写 $remote.new，再 mv -f 覆盖：
     * rename(2) 只换目录项，不动正在使用的 inode，一定成功。
     */
    private fun pushFile(local: File, remote: String): Boolean {
        val tmp = "$remote.new"
        val base = "cp '${local.absolutePath}' '$tmp' 2>&1 && chmod 644 '$tmp'"
        var r = sh("$base && mv -f '$tmp' '$remote' 2>&1", 90)
        if (r.code == 0) return true
        log("  cp($tmp) 失败(${r.code}) ${r.out.trim()} → 改用 stdin")
        val b64 = Base64.encodeToString(local.readBytes(), Base64.NO_WRAP)
        r = shWithStdin("base64 -d > '$tmp' && chmod 644 '$tmp' && mv -f '$tmp' '$remote'", b64.toByteArray())
        if (r.code != 0) log("  stdin 失败: ${r.out.trim()}")
        return r.code == 0
    }

    /** 本次开机是否已经跑过漏洞。
     *
     *  init_cred.usage 初值只有 4，而 exploit 成功后那个进程会被系统 SIGKILL
     *  （kmsg: init: Untracked pid ... received signal 9），每被杀一次就
     *  atomic_dec(init_cred.usage)。扣到 0 时 __put_cred 会把静态 init_cred
     *  当 slab 对象释放，内核在随机位置 panic —— 表现为「提权后过一会儿重启」。
     *
     *  所以同一次开机只许跑一次。注意 /data/local/tmp 挂在 /data 上，
     *  重启并不会清空它 —— 所以标记里必须写 boot_id，靠它判断是否同一轮开机。
     */
    private fun alreadyExploitedThisBoot(): Boolean {
        val want = bootId().trim()
        if (want.isBlank() || want == "未知") return false
        val got = sh("cat /data/local/tmp/.glhold 2>/dev/null || echo NONE", 10).out.trim()
        return got.contains(want)
    }

    private fun markExploited() {
        val id = bootId().trim()
        sh("printf '%s\\n' '$id' > /data/local/tmp/.glhold 2>/dev/null; echo ok", 10)
        log("  已写入 .glhold（boot_id=$id，本次开机不再跑漏洞）")
    }

    private fun checkRoot(quiet: Boolean = false): Boolean {
        val r = sh("[ -S /data/local/tmp/rksh.sock ] && /data/local/tmp/rksh -c 'id' 2>&1", 20)
        val ok = isRootIdentity(r.code, r.out)
        rootReady = ok
        if (ok && !quiet) log("  root: ${r.out.trim()}")
        return ok
    }

    private fun pushAll(dir: File): Boolean {
        val files = listOf(
            "p7" to "/data/local/tmp/p7",
            "rk9.ko" to "/data/local/tmp/rk9.ko",
            "rkshd" to "/data/local/tmp/rkshd",
            "rksh" to "/data/local/tmp/rksh"
        )
        for ((n, dst) in files) {
            val f = File(dir, n)
            val t = System.currentTimeMillis()
            if (!pushFile(f, dst)) {
                log("  !! $n 推送失败")
                return false
            }
            log("  $n (${f.length() / 1024} KB) -> $dst  OK  ${System.currentTimeMillis() - t}ms")
        }
        val chmod = sh(
            "chmod 755 /data/local/tmp/p7 /data/local/tmp/rkshd /data/local/tmp/rksh; " +
                    "chmod 644 /data/local/tmp/rk9.ko; ls -l /data/local/tmp/p7 /data/local/tmp/rk9.ko " +
                    "/data/local/tmp/rkshd /data/local/tmp/rksh 2>&1", 60
        )
        log(chmod.out.trimEnd())
        return chmod.code == 0
    }

    // ---------------- 诊断 ----------------

    private fun collectDiagnostics(tag: String) {
        section("诊断快照[$tag]")
        log(
            sh(
                "echo -n 'uname   : '; uname -a; " +
                        "echo -n 'boot_id : '; cat /proc/sys/kernel/random/boot_id; " +
                        "echo -n 'uptime  : '; cat /proc/uptime; " +
                        "echo -n 'loadavg : '; cat /proc/loadavg; " +
                        "echo -n 'selinux : '; getenforce; " +
                        "echo -n 'id      : '; id; " +
                        "echo -n 'CapEff  : '; grep CapEff /proc/self/status; " +
                        "echo -n 'kptr    : '; cat /proc/sys/kernel/kptr_restrict 2>/dev/null || echo '读不到'; " +
                        "echo -n 'dmesg_r : '; cat /proc/sys/kernel/dmesg_restrict 2>/dev/null || echo '读不到'", 40
            ).out.trimEnd()
        )
        log(
            sh(
                "echo '--- /proc/modules(kernelsu/rk) ---'; cat /proc/modules 2>/dev/null | grep -E '^(kernelsu|rk9)' || echo none; " +
                        "echo '--- /proc/rk9 ---'; ls -l /proc/rk9 2>&1; " +
                        "echo '--- socket ---'; ( [ -S /data/local/tmp/rksh.sock ] && echo 'socket 存在' ) || echo 'socket 不存在或无权查看' ; " +
                        "echo '--- 组件 ---'; ls -l /data/local/tmp/p7 /data/local/tmp/rk9.ko /data/local/tmp/rkshd /data/local/tmp/rksh 2>&1", 40
            ).out.trimEnd()
        )
        if (rootReady) {
            log("--- root 侧 ---")
            log(sh("/data/local/tmp/rksh -c 'id; getenforce; dmesg 2>/dev/null | tail -20' 2>&1", 60).out.trimEnd())
        }
        log("--- /sdcard/plog.txt ---")
        log(sh("tail -40 /sdcard/plog.txt 2>/dev/null || echo '(无)'", 30).out.trimEnd())
        val diagLog = lastRunLogPath ?: "/sdcard/root.log"
        log("--- p7 输出（$diagLog，尾部 40 行）---")
        log(sh("tail -40 '$diagLog' 2>/dev/null || tail -40 /sdcard/root.log 2>/dev/null || echo '(无)'", 30).out.trimEnd())
    }

    /** 把导出的日志通过系统分享面板送出去。
     *  Android 11+ 的文件管理器进不了 Android/data，只给路径用户也拿不到，
     *  所以直接弹分享，用户选「保存到文件」或发到微信/QQ 就能取走。 */
    private fun shareLog(f: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", f)
            val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(i, "分享日志 ${f.name}"))
        } catch (e: Throwable) {
            log("  ! 分享日志失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 推送 + 探测：一个动作做完，全程不跑漏洞、不烧 init_cred 预算。
     *
     *  1. 按内核版本把引擎（5.4 是 ghostlock54，4.19 是 p7）推到 /data/local/tmp
     *  2. 自检 uid / selinux / CapEff / chrt（chrt 被挡则 gl54 跑不起来）
     *  3. probe.sh 采集 sysctl / kallsyms / config.gz
     *  4. probe-perf 用 perf 采样验证 x20 与 x29-40
     *
     *  注意：ELf 不能从 /storage/emulated 直接执行（noexec），必须推 /data/local/tmp。
     *  整个函数体在 IO 线程，sh() 是同步阻塞的，在主线程跑 probe.sh 会 ANR。 */
    private fun runProbe() {
        if (running) return
        scope.launch {
            running = true
            try {
                log("========== 推送 + 探测（不跑漏洞，不烧预算）==========")
                withContext(Dispatchers.IO) {
                    val dir = extractAssets()
                    log("  组件目录: ${dir.absolutePath}")
                    val engine = if (kernelVer.startsWith("5.4")) "ghostlock54" else "p7"

                    busyLabel = "推送组件"
                    log("== 1/4 推送引擎（$engine）==")
                    if (pushFile(File(dir, engine), "/data/local/tmp/$engine")) {
                        sh("chmod 755 /data/local/tmp/$engine 2>/dev/null", 15)
                        log("  $engine -> /data/local/tmp/$engine  OK")
                        log("  可手动跑: adb shell /data/local/tmp/$engine")
                    } else {
                        log("  !! 推送失败（探测继续，只是没法手动跑）")
                    }

                    busyLabel = "权限自检"
                    log("== 2/4 权限自检 ==")
                    log(
                        sh(
                            "echo -n 'uid     : '; id; " +
                                    "echo -n 'selinux : '; getenforce; " +
                                    "echo -n 'cap     : '; grep CapEff /proc/self/status; " +
                                    "echo -n 'chrt    : '; chrt -f 1 /system/bin/true 2>&1; echo rc=\$?", 30
                        ).out.trimEnd()
                    )

                    busyLabel = "探测"
                    sh("chmod 755 $dir/probe.sh 2>/dev/null", 15)
                    log("== 3/4 probe.sh：sysctl / kallsyms / selinux / config.gz ==")
                    log(sh("sh $dir/probe.sh 2>&1", 120).out.trimEnd())

                    log("== 4/4 probe-perf：验证 x20 与 x29-40 ==")
                    if (pushFile(File(dir, "probe-perf"), "/data/local/tmp/probe-perf")) {
                        sh("chmod 755 /data/local/tmp/probe-perf 2>/dev/null", 15)
                        log(sh("/data/local/tmp/probe-perf 2>&1", 120).out.trimEnd())
                    } else {
                        log("  !! probe-perf 推送失败，跳过（无法验证 x20）")
                    }
                }
                log("========== 完成 ==========")
            } catch (e: Throwable) {
                log("!! 失败: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                flushLogText()
                busyLabel = ""
                running = false
            }
        }
    }

    private fun exportLog() {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastExportAt < 2500) {
            log("[导出] 距上次不足 2.5s，已跳过（避免重复写文件）")
            return
        }
        lastExportAt = nowMs
        scope.launch {
            withContext(Dispatchers.IO) {
                // 运行中不要采诊断：一次会拉起十几个 sh，给 p7 的 perf 采样加压
                if (permissionGranted && !running) collectDiagnostics("导出前")
                try {
                    val dir = File(getExternalFilesDir(null), "logs")
                    dir.mkdirs()
                    val name = "ksuhelper-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.log"
                    val f = File(dir, name)
                    val header = buildString {
                        appendLine("KSU Helper 日志导出")
                        appendLine("时间    ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                        appendLine("App     ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        appendLine("机型    ${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine("设备    device=${Build.DEVICE} product=${Build.PRODUCT}")
                        appendLine("系统    Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) / ${Build.DISPLAY}")
                        appendLine("内核    $kernelVer")
                        appendLine("档案    ${activeProfile?.name ?: "-"} [${if (profileAuto) "自动" else "手动"}] 注入=${useExplicitEnv}")
                        appendLine("Shizuku binder=${binderAlive} perm=${permissionGranted} uid=$serverUid")
                        appendLine("root    $rootReady")
                        if (!binderAlive) appendLine("注意    Shizuku 未运行，本次导出不含任何执行记录")
                        if (!ranRootThisSession)
                            appendLine("注意    本次启动没有执行过「一键提权」，p7 相关字段是上一次运行留下的")
                        appendLine("boot_id ${bootId()}")
                        appendLine("".padEnd(64, '='))
                    }
                    // 磁盘日志：含以往会话（重启前的也在），App 目录可直接读，不依赖 Shizuku
                    var hist = ""
                    try {
                        if (logFile.exists()) hist = logFile.readText().takeLast(400_000)
                    } catch (_: Throwable) {
                    }
                    var runTail = ""
                    try {
                        val newest = getExternalFilesDir(null)
                            ?.listFiles { x -> x.name.startsWith("run-") && x.name.endsWith(".log") }
                            ?.maxByOrNull { it.lastModified() }
                        if (newest != null) {
                            lastRunLogPath = newest.absolutePath
                            runTail = newest.readText().takeLast(80_000)
                        }
                    } catch (_: Throwable) {
                    }
                    val body = buildString {
                        appendLine("=== 会话（内存，仅本次启动） ===")
                        appendLine(logText.trimEnd())
                        appendLine()
                        appendLine("=== 磁盘历史（含以往重启前的会话） ===")
                        appendLine(hist.trimEnd().ifBlank { "(空)" })
                        appendLine()
                        appendLine("=== 最近一次 p7 输出（${lastRunLogPath ?: "-"}，尾部） ===")
                        appendLine(runTail.trimEnd().ifBlank { "(无)" })
                    }
                    f.writeText(header + body)
                    log("日志已导出 ${f.absolutePath} (${f.length()} B，含历史 ${hist.length} B / p7 输出 ${runTail.length} B)")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity,
                            "已导出到\n${f.absolutePath}\n\n长按文件可复制路径，或点「分享」发给文件管理器",
                            Toast.LENGTH_LONG).show()
                        shareLog(f)
                    }
                } catch (e: Throwable) {
                    log("!! 导出失败: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    // ---------------- 主流程 ----------------

    private fun startRoot(force: Boolean = false) {
        if (running) return
        // 4.19 和 5.4 都验证过地址；其它版本一律拦下
        val kvOk = kernelVer.startsWith("4.19") || kernelVer.startsWith("5.4")
        if (!force && !kvOk) {
            log("!! 拒绝执行：当前内核 $kernelVer 既不是 4.19 也不是 5.4")
            log("   p7 的 GL_* 是按这两代内核推的，其它版本上跑可能 panic 重启")
            log("   如确需强跑：am start ... --es run root --es force true")
            return
        }
        running = true
        scope.launch {
            val started = System.currentTimeMillis()
            try {
                val ok = withContext(Dispatchers.IO) { doRoot() }
                log("总耗时 ${System.currentTimeMillis() - started}ms")
                // 主流程 ok 只代表「引擎没报错」，不代表 root 真的可用。
                // 5.4 没有 rksh（rk9/rkshd 未适配 A93），checkRoot 必然 false ——
                // 这种情况下如实报「未核验」，别把模块加载当成提权成功。
                val rootObserved = ok && withContext(Dispatchers.IO) { checkRoot(quiet = true) }
                log(when {
                    rootObserved -> "== root 通道已核验；KSU 授权和管理器仍需分别验收 =="
                    ok -> "== 主流程结束，但 root 未核验；模块存在不等于授权成功 =="
                    else -> "== 流程失败或未完成 =="
                })
                if (ok && !rootObserved && kernelVer.startsWith("5.4")) {
                    log("   5.4 路径没有 root shell（rksh 依赖 rkshd+rk9，尚未适配），")
                    log("   root 是否真的下来了请看上面 ksucheck 的 GRANT_ROOT 一行")
                }
                withContext(Dispatchers.IO) {
                    collectDiagnostics(if (rootObserved) "root 通道已核验" else "root 未核验")
                }
            } catch (e: Throwable) {
                log("!! 流程异常: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                // 没有 finally 的话，中途抛异常会让 running 永久 true，所有按钮变灰且无法恢复
                flushLogText()
                busyLabel = ""
                running = false
            }
        }
    }

    private suspend fun doRoot(): Boolean = withContext(Dispatchers.IO) {
        // 闸门：init_cred.usage 只有 4，同一次开机重复跑会把内核扣崩。
        if (alreadyExploitedThisBoot()) {
            log("  本次开机已跑过漏洞（boot_id 匹配 .glhold），拒绝重复执行")
            log("  如需重跑，重启设备（重启会重置 init_cred 计数）")
            // 5.4 没有 rksh，checkRoot 恒 false；用模块是否已加载当判据
            val modOn = sh("[ -d /sys/module/kernelsu ] && echo Y || echo N", 10).out.contains("Y")
            return@withContext modOn || checkRoot()
        }
        // 模块已在位说明内核侧没必要再动，直接走装载/校验
        if (sh("[ -d /sys/module/kernelsu ] && echo Y || echo N", 10).out.contains("Y")) {
            log("  kernelsu 模块已在位（/sys/module/kernelsu 存在），跳过漏洞直接校验")
            if (autoKsu) autoKsuSetup()
            return@withContext true
        }
        try {
            section("一键提权")
            ranRootThisSession = true
            log("档案 ${activeProfile?.name ?: "-"}  注入=${useExplicitEnv}  自动装KSU=${autoKsu}")
            recoverLastRunLog()

            // 本次开机已经提过权：没必要再推送/再跑漏洞（而且 p7 进程还在，
            // 覆盖可执行文件会 ETXTBSY）。直接进 KSU 环节。
            if (checkRoot()) {
                log("  已有 root（本次开机已提权），跳过推送与漏洞")
                if (autoKsu) autoKsuSetup() else log("  ! 「提权后自动装 KSU」是关的，跳过")
                return@withContext true
            }

            // ---- 按内核版本选引擎 ----
            // 5.4：ghostlock54（源码级移植，几何硬编码，不用 GL_* 参数猜）
            // 4.19：p7（原生二进制，靠 GL_* 档案地址）
            val krel = sh("uname -r", 10).out.trim()
            log("  内核 $krel")
            if (krel.contains("5.4")) {
                log("  → ghostlock54 引擎")
                return@withContext runGhostlock54()
            }
            log("  → p7 引擎")

            busyLabel = "释放组件"
            log("== 1/5 释放组件 ==")
            val t1 = System.currentTimeMillis()
            val dir = extractAssets()
            log("  ${dir.absolutePath}  ${System.currentTimeMillis() - t1}ms")

            busyLabel = "推送组件"
            log("== 2/5 推送 ==")
            if (!pushAll(dir)) {
                log("  !! 推送失败")
                if (checkRoot()) {
                    log("  但已有 root 可用，继续装 KSU（不再跑漏洞）")
                    if (autoKsu) autoKsuSetup()
                    return@withContext true
                }
                log("  中止")
                return@withContext false
            }

            if (checkRoot()) {
                log("  已有 root，跳过提权")
                if (autoKsu) autoKsuSetup()
                return@withContext true
            }

            if (sh("[ -e /proc/rk9 ] && echo yes", 15).out.contains("yes")) {
                busyLabel = "拉起 root 服务"
                log("== /proc/rk9 已存在，只拉起 rkshd ==")
                sh("printf '%s\\n' '( /data/local/tmp/rkshd )' > /data/local/tmp/rk_cmd; printf x > /proc/rk9", 30)
                delay(2500)
                if (checkRoot()) {
                    if (autoKsu) autoKsuSetup()
                    return@withContext true
                }
            }

            busyLabel = "执行漏洞"
            // 不再做「KASLR 预检」：
            //   · GL_LEAK=1 在 QEMU 上实测仍会 arm（env 已验证进子进程），不够安全；
            //   · 而且预检成功也不代表正式执行那一次能泄漏成功（perf 采样是概率的）。
            // 改为在正式执行过程中实时盯 plog —— p7 的泄漏结果 `[L] text=` 写在
            // arm（`[M] requeued`）之前，所以看到 text=0 立即 kill 就能抢在 punch 前截断。
            log("== 3/5 执行（约 25~40s）==")
            sh("rm -f /sdcard/plog.txt /data/local/tmp/rk_init.txt /data/local/tmp/rksh.sock /data/local/tmp/p7.pid", 20)
            val env = buildEnv("/data/local/tmp/rk9.ko")
            val runLog = File(
                getExternalFilesDir(null),
                "run-" + SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date()) + ".log"
            )
            lastRunLogPath = runLog.absolutePath
            log("  env $env")
            log("  p7 输出 -> $RUN_LOG_REMOTE（/data 分区；每 2s sync 一次 + 复制到 App 目录）")
            log("  注意：panic 时 f2fs 会回滚，不 sync 的话最后几十秒的日志会整个消失")
            val t3 = System.currentTimeMillis()
            val run = sh(
                "rm -f $RUN_LOG_REMOTE; cd /data/local/tmp && " +
                        "( $env nohup ./p7 > '$RUN_LOG_REMOTE' 2>&1 & echo \$! > /data/local/tmp/p7.pid ) ; " +
                        "sleep 1; sync; echo -n 'pid='; cat /data/local/tmp/p7.pid 2>/dev/null", 25
            )
            log("  提交 exit=${run.code} ${run.out.trim()} (${System.currentTimeMillis() - t3}ms)")

            // ---- 泄漏守卫（替代原来的 KASLR 预检）----
            // p7 在 plog 里的顺序是：[P]/[prctl] 采样 → [L] text=0x... → [M] requeued（arm）
            // 所以只要在 arm 之前看到 text=0x0，kill 掉 p7 就能抢在 punch 之前截断，
            // 既不额外 arm 一次，也能避免「提权失败但内核被埋雷」。
            busyLabel = "检查泄漏"
            var guardKilled = false
            for (g in 1..12) {
                delay(400)
                val tail = sh("tail -c 1500 /sdcard/plog.txt 2>/dev/null", 8).out
                if (tail.isBlank()) continue
                if (tail.contains("[M] requeued") || tail.contains("[W] post-trigger")) break // 已经 arm
                if (tail.contains("[L] text=0x0")) {
                    log("!! 泄漏守卫：plog 出现 [L] text=0x0（KASLR 未泄漏），立即终止 p7")
                    sh("kill -9 \$(cat /data/local/tmp/p7.pid 2>/dev/null) 2>/dev/null", 10)
                    guardKilled = true
                    break
                }
            }
            if (guardKilled) {
                log("  已在 punch 之前截断，本次不会给内核留下悬空 waiter。")
                log("  成因：p7 的泄漏没拿到 text（perf 采样失败），cred 地址缺 slide 必写不中。")
                return@withContext false
            }

            busyLabel = "等待 root 服务"
            log("== 4/5 等待（最多 72s，p7 退出即判失败）==")
            var alive = true
            var p7Gone = false
            for (i in 1..36) {
                delay(2000)
                busyLabel = "等待 ${i * 2}s"
                // 每 2s 把 p7 输出刷盘并复制到 App 目录：
                // 内核 panic / 硬重启时 f2fs 会回滚到上次 checkpoint，不 sync 的内容
                // 会整块消失（23:43 那次就是这样，run-*.log 只剩 0 字节）。
                sh("sync; cp -f $RUN_LOG_REMOTE '${runLog.absolutePath}' 2>/dev/null", 15)
                if (checkRoot(quiet = true)) {
                    log("  第 ${i * 2}s 就绪")
                    markExploited()
                    log("  ${sh("/data/local/tmp/rksh -c 'id' 2>&1", 20).out.trim()}")
                    if (autoKsu) autoKsuSetup() else log("  ! 「提权后自动装 KSU」是关的，跳过")
                    return@withContext true
                }
                val st = sh(
                    "p=\$(cat /data/local/tmp/p7.pid 2>/dev/null); " +
                            "[ -n \"\$p\" ] && [ -d /proc/\$p ] && echo alive || echo gone", 10
                ).out
                if (st.contains("gone")) {
                    p7Gone = true
                    log("  p7 已退出（第 ${i * 2}s）——比超时早，直接判失败")
                    break
                }
                if (sh("true", 8).code != 0) {
                    alive = false
                    log("  !! shell 失联")
                    break
                }
            }
            busyLabel = "收集现场"
            log("== 5/5 失败 ==")
            if (!alive) log("  建议长按电源 12s 重启后重试")
            reportP7()
            if (!p7Gone && alive) log("  p7 仍在运行但没等到 socket（超时）")
            false
        } catch (e: Throwable) {
            log("!! 异常: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** 提权成功后：加载 kernelsu.ko + 从 KSU 管理器 APK 里提取 ksud */
    /** 5.4 专用引擎：ghostlock54（源码级移植版 GhostLock，CVE-2026-43499）
     *
     *  与 p7 的本质区别：几何不靠 GL_* 参数拼，而是硬编码在源码里；
     *  越界写走 fake lock 窗口（selinux_state 那一页内的 0xa80~0xf00）+ rb_erase，
     *  不用 punch 伪 waiter，所以没有 rt_mutex_waiter 0x50/0x58 那类坑。
     *
     *  默认模式 = 提权 + 加载内嵌的 kernelsu.ko（运行时释放到 /data/local/tmp/kernelsu.ko），
     *  所以只需要推这一个文件。
     */
    private suspend fun runGhostlock54(): Boolean = withContext(Dispatchers.IO) {
        busyLabel = "释放组件"
        log("== 1/4 释放 ghostlock54 ==")
        val dir = extractAssets()
        val bin = File(dir, "ghostlock54")
        log("  ${bin.absolutePath} (${bin.length()} 字节)")
        // 阈值必须低于真实体积。A93 版编出来是 368,576 B（STRIP=1，含精简 ko），
        // 之前写成 500_000 会让 5.4 路径在这里直接 return false —— 漏洞根本不执行。
        if (!bin.exists() || bin.length() < 300_000) {
            log("  !! ghostlock54 提取失败"); return@withContext false
        }

        busyLabel = "推送"
        log("== 2/4 推送到 /data/local/tmp ==")
        val push = sh("cp -f '${bin.absolutePath}' /data/local/tmp/ghostlock54 && " +
                "chmod 755 /data/local/tmp/ghostlock54 && ls -l /data/local/tmp/ghostlock54", 60)
        log("  ${push.out.trim()}")
        if (!push.out.contains("ghostlock54")) { log("  !! 推送失败"); return@withContext false }

        busyLabel = "执行漏洞"
        log("== 3/4 执行（提权 + 加载 KernelSU，约 30~60s）==")
        sh("rm -f /data/local/tmp/gl54.log", 10)
        // 默认模式：父进程会 daemonize 并原地挂起保活
        // （exploit 成功后内核 PI 引用指向存活线程的栈，进程退掉会 panic）
        val run = sh("cd /data/local/tmp && nohup ./ghostlock54 > gl54.log 2>&1 & " +
                "sleep 3; echo submitted", 40)
        log("  ${run.out.trim()}")

        busyLabel = "等待结果"
        log("== 4/4 等待 ==")
        // 成功判定：这条路径没有 rksh.sock（checkRoot 恒 false），只能看模块是否加载。
        // 用 [ -d /sys/module/kernelsu ] 而不是 grep /proc/modules —— 后者统计行数，
        // 一旦别的模块的 "used by" 列出现 kernelsu，计数会是 2，永远判不成功。
        val startNs = System.nanoTime()
        val deadlineNs = startNs + 75_000_000_000L
        var modOk = false
        var round = 0
        while (System.nanoTime() < deadlineNs) {
            delay(1500)
            round++
            // 超时按真实毫秒算：每轮还有两次 sh 往返（约 40~150ms），
            // 按次数算名义 75s 实际会拖到 90s 左右
            val elapsed = (System.nanoTime() - startNs) / 1_000_000_000L
            busyLabel = "等待 ${elapsed}s"
            if (!modOk && sh("[ -d /sys/module/kernelsu ] && echo yes", 8).out.contains("yes")) {
                modOk = true
                log("  [${elapsed}s] kernelsu 模块已加载")
            }
            if (modOk && round > 3) {
                // 不能用 `id` 当证据：那是在 shell(uid 2000) 里跑的，恒输出 uid=2000。
                // 真正的判据是模块加载 + 后续 ksucheck 的 GRANT_ROOT。
                log("  [${elapsed}s] kernelsu 模块已加载（提权结果由 ksucheck 验证）")
                markExploited()
                // 模块已由 ghostlock54 内部的脚本 insmod（带 kln）。
                // 但不能走 autoKsuSetup：那里所有 root 操作都依赖 /data/local/tmp/rksh，
                // 而 rksh 需要 rkshd + rk9.ko，rk9-54.ko 是 Reno7(gaa96) 编的，
                // 在 A93(g9ef4) 上 insmod 会因 vermagic 不符被拒 —— 等于没有 root shell。
                // 所以这里只做一件 5.4 上真正能做的事：直接问内核模块活没活。
                log("  --- 验证内核侧（ksucheck 不需要 root shell）---")
                verifyKsu()
                if (autoKsu) {
                    log("  ! 5.4 路径暂无 root shell：ksud 部署与 allowlist 写入需要 rk9 适配 A93，")
                    log("    当前只能确认模块加载与 supercall 是否可用（见上面 ksucheck 的输出）")
                }
                return@withContext true
            }
            if (round % 4 == 0) {
                val t = sh("tail -2 /data/local/tmp/gl54.log 2>/dev/null", 8).out.trim()
                if (t.isNotBlank()) log("  [${elapsed}s] ${t.lines().last()}")
            }
        }

        val total = (System.nanoTime() - startNs) / 1_000_000_000L
        log("!! 超时（实际 ${total}s）")
        log("--- gl54.log ---")
        log(sh("tail -30 /data/local/tmp/gl54.log 2>/dev/null || echo '(无输出)'", 15).out.trimEnd())
        return@withContext false
    }

    /** 预写 KSU allowlist，绕过 Manager 的版本/签名绑定。
     *
     *  模块在 init 阶段读 /data/adb/ksu/.allowlist，命中即视为已授权。
     *  所以只要在 insmod 之前把文件写好，装哪个版本的 Manager（甚至不装）都无所谓。
     *
     *  格式（对照 kernel/policy/allowlist.c 与 uapi/app_profile.h）：
     *    u32 magic = 0x7f4b5355
     *    u32 version = 4
     *    N × struct app_profile (784 字节)
     *      version@0 / key@4(256) / curr_uid@260(s32) / allow_su@264(u8)
     *      union.rp_config.use_default@268
     */
    /** 只抓 KSU 相关的 dmesg。
     *  直接 `dmesg | tail -25` 会被 ColorOS 的 ipv6_rto / charger 日志整段淹没，
     *  模块 init 时那几行 allowlist / manager 判定在几千行之前就刷没了。 */
    private fun dmesgKsu(): String {
        val r = sh("/data/local/tmp/rksh -c 'dmesg | grep -iE " +
                "\"allowlist|load_allow|kernelsu|Searching manager|is_manager|manager uid|reboot kprobe\" " +
                "| tail -25' 2>&1", 50)
        val t = r.out.trim()
        return if (t.isBlank()) "(dmesg 里没有 kernelsu 相关记录)" else t
    }

    private fun writeAllowlistHint() {
        try {
            val uid = applicationInfo.uid
            val buf = java.io.ByteArrayOutputStream()
            fun putU32(v: Int) {
                buf.write(v and 0xff); buf.write((v shr 8) and 0xff)
                buf.write((v shr 16) and 0xff); buf.write((v shr 24) and 0xff)
            }
            putU32(0x7f4b5355)   // FILE_MAGIC
            putU32(4)            // FILE_FORMAT_VERSION
            // 要授权两条：
            //   1) KSU Manager 自己（key 用 me.weishu.kernelsu）—— 这是"KSU 生态能用"的前提，
            //      Manager 拿到 su 才能读白名单、管模块、给其它 app 授权；
            //   2) 本 App —— 省得每次再去 Manager 里点一次。
            // uid 2000(shell) 不要写：track_throne 的 prune 会因为它和 package 对不上而删掉
            // （is_uid_exist 要求 uid%100000 与 package 同时匹配 packages.list）。
            val mgrUid = sh("pm list packages -U me.weishu.kernelsu 2>/dev/null | sed 's/.*uid://' | head -1", 15)
                .out.trim().toIntOrNull()
            val entries = ArrayList<Pair<Int, String>>()
            if (mgrUid != null && mgrUid > 0) entries.add(mgrUid to "me.weishu.kernelsu")
            entries.add(uid to packageName)   // 不能用硬编码：.p2 变体的 applicationId 不同
            for ((u, pkg) in entries) {
                val p = ByteArray(784)
                p[0] = 4                                   // profile.version
                val key = pkg.toByteArray()
                System.arraycopy(key, 0, p, 4, minOf(key.size, 255))
                p[260] = (u and 0xff).toByte()             // curr_uid (小端 s32)
                p[261] = ((u shr 8) and 0xff).toByte()
                p[262] = ((u shr 16) and 0xff).toByte()
                p[263] = ((u shr 24) and 0xff).toByte()
                p[264] = 1                                 // allow_su = true
                // union 起始要 8 字节对齐：allow_su@264 + 7 padding → union@272
                p[272] = 1                                 // rp_config.use_default = true
                // ★ 必填：profile_valid() 会检查 selinux_domain 非空，
                //   为空直接 return false，整条记录被丢弃（allowlist.c 156-170）。
                "u:r:ksu:s0\u0000".toByteArray().copyInto(p, 704)
                buf.write(p)
            }
            log("  allowlist 条目: " + entries.joinToString { "${it.second}=${it.first}" })
            val data = buf.toByteArray()

            // 统一走 pushFile：它内部是 stdin + base64，不依赖 shell 能否读 App 私有目录。
            // 之前用 getExternalFilesDir(null) ?: cacheDir 做兜底，但 cacheDir 是 0700，
            // 兜底分支必然失败 —— 等于没有兜底。
            val tmp = File(cacheDir, "allowlist.bin").apply { writeBytes(data) }
            if (!pushFile(tmp, "/data/local/tmp/allowlist.bin")) {
                log("  ! allowlist 推送失败")
                return
            }
            sh("chmod 644 /data/local/tmp/allowlist.bin 2>/dev/null", 15)
            val r = sh("/data/local/tmp/rksh -c 'mkdir -p /data/adb/ksu && " +
                    "cp -f /data/local/tmp/allowlist.bin /data/adb/ksu/.allowlist && " +
                    "chmod 600 /data/adb/ksu/.allowlist && stat -c %s /data/adb/ksu/.allowlist' 2>&1", 40)
            val size = r.out.trim().lines().lastOrNull()?.trim()?.toIntOrNull()
            if (size == data.size) {
                log("  allowlist 预写成功（${data.size} 字节，uid=$uid，含 selinux_domain）")
            } else {
                log("  ! allowlist 预写失败：${r.out.trim().take(120)}")
            }
        } catch (e: Throwable) {
            log("  ! allowlist 预写异常：${e.message}")
        }
    }

    private suspend fun autoKsuSetup() {
        section("自动加载 KSU")
        val p = activeProfile
        log("  内核 $kernelVer  档案 ${p?.name ?: "(无)"}  SELinux ${sh("getenforce 2>/dev/null", 10).out.trim()}")

        // 1) ko：/sdcard/Download 下用户手放的优先（便于换 UAPI/换组），否则用内置
        // Download 里可能同时放着多个机型的 ko，盲取 `head -1` 会 insmod 错的那个。
        // 逐个比对 vermagic，只接受与本机内核匹配的；都不匹配就退回内置。
        var ko = ""
        val cands = sh("ls -1 /sdcard/Download/*.ko 2>/dev/null", 20).out
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (cands.isNotEmpty()) {
            var picked: String? = null
            for (c in cands) {
                val head = koVermagic(File(c))?.split(" ")?.firstOrNull()
                val okVm = matchesKernelRelease(head)
                log("  Download 候选 $c  vermagic=${head ?: "(读不到)"} -> ${if (okVm) "匹配" else "跳过"}")
                if (okVm && picked == null) picked = c
            }
            if (picked != null) ko = picked
            else log("  ! Download 里的 ko 都不匹配本机 $kernelVer，改用内置")
        }
        if (ko.isNotEmpty()) {
            log("  ko 来源: $ko（Download，vermagic 已匹配）")
        } else {
            val b = pickBundledKo()
            if (b == null) {
                log("  ! 当前内核 $kernelVer 没有匹配的内置 ko")
                log("    请把对应 kernelsu.ko 放到 /sdcard/Download/ 后重试")
                return
            }
            val f = bundledKo(b)
            if (f == null) return
            // 运行前先比对 vermagic：不匹配直接放弃，避免 insmod 报一堆无用错误
            val vm = koVermagic(f)
            log("  内置 ${b.asset}  ${f.length() / 1024} KB  组: ${b.label}")
            log("  ko vermagic: ${vm ?: "(读不到)"}")
            log("  本机 uname  : $kernelVer")
            val vmOk = matchesKernelRelease(vm)
            if (!vmOk) {
                log("  !! vermagic 不匹配（ko=${vm?.split(" ")?.first()}，本机=$kernelVer）")
                log("     多半是内核小版本不同（如 .155 vs .157）。先别 insmod，找同版本内核的 boot 重编 ko。")
                log("     若确认本机内核与档案一致，可把 ko 放 /sdcard/Download/ 覆盖内置再试。")
                return
            }
            log("  vermagic 匹配")
            if (pushFile(f, "/data/local/tmp/kernelsu.ko")) {
                ko = "/data/local/tmp/kernelsu.ko"
                log("  已推送 -> /data/local/tmp/kernelsu.ko")
            }
        }
        if (ko.isEmpty()) {
            log("  ! 本机型未内置 ko，请把对应 kernelsu.ko 放到 /sdcard/Download/")
            return
        }

        // 1.5) 预写 allowlist
        // 模块在 init 阶段会读 /data/adb/ksu/.allowlist，把里面的 uid 直接标记为已授权。
        // 这样可以绕过 Manager：不管用户装的是哪个版本/哪个变体，甚至根本没装，
        // 加载完模块后 su 就能用（前提是 allowlist 在 insmod 之前就写好）。
        writeAllowlistHint()

        // 2) insmod
        // 注意：模块只在 init 阶段读一次 allowlist。如果它已经加载着（上一次写的
        // allowlist 可能还是空的），这次写的新内容不会被读到——必须卸载重载。
        val already = sh("cat /proc/modules 2>/dev/null | grep -c '^kernelsu' || true", 20).out.trim()
        if (already != "0" && already.isNotEmpty()) {
            log("  kernelsu 已加载，但 allowlist 刚更新过 → 卸载重载才生效")
            val rm = sh("/data/local/tmp/rksh -c 'rmmod kernelsu; echo RC=\$?' 2>&1", 40)
            log("  rmmod: ${rm.out.trim().replace("\n", " | ")}")
            delay(800)
            if (rm.out.contains("RC=0") || rm.out.contains("not currently loaded")) {
                log("  已卸载，准备重新加载")
            } else {
                log("  ! rmmod 失败（可能仍被引用），本轮 allowlist 不会生效")
                return
            }
        }
        run {
            val t0 = System.currentTimeMillis()
            // ★ 4.19 不导出 kallsyms_lookup_name，模块必须通过 kln= 参数拿到它。
            //   拿不到时模块内部符号解析走 /proc/kallsyms，被 kptr_restrict 挡住，
            //   结果 GET_INFO 返回的 flags 全零 —— 管理器据此判「内核不支持」。
            //   level=3 让模块把详细日志打到 kmsg。
            val kln = sh("grep ' kallsyms_lookup_name$' /proc/kallsyms 2>/dev/null | head -1 | cut -d' ' -f1", 15).out.trim()
            val klnArg = if (kln.length > 8 && kln != "0000000000000000") " kln=0x$kln" else ""
            if (klnArg.isEmpty()) {
                // 模块不内建 kallsyms 表，符号全靠这个地址去查。
                // 没传 kln 时 insmod 仍会成功、/proc/modules 也有 kernelsu，
                // 但所有 kallsyms_lookup_name 返回 0 —— su / allowlist 全部静默失效，
                // 表面看「模块加载了」，实际是个空壳。宁可不装，也不要假装成功。
                log("  !! 取不到 kallsyms_lookup_name 地址（kptr_restrict=2 或权限不足）")
                log("     ko 靠 kln 传这个地址来解析全部内核符号，没有它就只是个空壳模块。")
                log("     已中止 insmod —— 先解决 kallsyms 读取，别继续往下走。")
                return
            }
            log("  kln=0x$kln")
            val ins = sh("/data/local/tmp/rksh -c 'insmod $ko$klnArg level=3; echo RC=\$?' 2>&1", 180)
            log("  insmod ${System.currentTimeMillis() - t0}ms  ${ins.out.trim().replace("\n", " | ")}")
            val mods = sh("cat /proc/modules 2>/dev/null | grep '^kernelsu' || echo none", 20).out.trim()
            log("  /proc/modules: $mods")
            if (mods.startsWith("none")) {
                log("  ! 模块没加载成功，ksud 部署没有意义，已跳过")
                log("  --- dmesg 尾部（insmod 报错的真正原因在这里）---")
                log(dmesgKsu())
                log("  常见原因：vermagic/组别不符、CFI type hash 不符（clang 版本不同）、")
                log("            unknown symbol、MODVERSIONS CRC 不符（Module.symvers 与本机内核不同）")
                return
            }
            val d = dmesgKsu()
            if (d.isNotBlank()) log("  --- insmod 后 dmesg 尾部 ---\n$d")
        }
        log("  模块已加载")
        log("  /sys/module/kernelsu/version: " + sh("cat /sys/module/kernelsu/version 2>/dev/null || echo '(无此节点)'", 20).out.trim())

        // 3) ksud：从已安装的 KSU 管理器 APK 里取 libksud.so
        val apk = sh("pm path me.weishu.kernelsu 2>/dev/null | head -1", 20).out.trim()
            .removePrefix("package:").trim()
        if (apk.isEmpty()) {
            log("  ! 没检测到 KernelSU 管理器（me.weishu.kernelsu），装好管理器后再点「部署 ksud」")
            return
        }
        val mgr = sh("dumpsys package me.weishu.kernelsu 2>/dev/null | grep -E 'versionName|versionCode' | head -2", 20)
            .out.trim().replace("\n", "  ")
        log("  管理器: $mgr")
        log("  提示: 我们构建的是 versionCode=32681 + Android Debug 证书；官方是 32680 + 官方签名")
        val abi = if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) "arm64-v8a" else Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        log("  ksud 来源: $apk -> lib/$abi/libksud.so")
        val dep = sh(
            "/data/local/tmp/rksh -c 'mkdir -p /data/adb && unzip -p $apk lib/$abi/libksud.so > /data/adb/ksud && " +
                    "chmod 755 /data/adb/ksud && ls -l /data/adb/ksud' 2>&1", 180
        )
        log("  ${dep.out.trim()}")
        log("  ksud sha256: " + sh("/data/local/tmp/rksh -c 'sha256sum /data/adb/ksud 2>/dev/null | cut -c1-16' 2>&1", 40).out.trim())
        val ver = sh("/data/local/tmp/rksh -c '/data/adb/ksud --version' 2>&1 | head -3", 60).out.trim()
        log("  ksud --version: ${ver.ifBlank { "(无输出)" }}")
        log("  → 现在打开 KSU 管理器应能看到已安装；若仍显示未安装，重启后再提权一次")
        verifyKsu()
    }

    /** 直接问内核：supercall 通不通、当前 uid 在不在 allowlist。
     *
     *  ksucheck 不需要 root —— 它用自己进程的 uid 走 KSU 的 reboot-magic 拿 fd，
     *  所以 4.19（有 rksh）和 5.4（没有 root shell）都能用同一个验证。
     */
    private fun verifyKsu() {
        val chk = File(File(getExternalFilesDir(null), "bin"), "ksucheck")
        if (!chk.exists()) { log("  ! ksucheck 未解出，跳过内核侧验证"); return }
        // ELF 从 /storage/emulated 执行会 Permission denied（noexec）
        if (!pushFile(chk, "/data/local/tmp/ksucheck")) {
            log("  ! ksucheck 推送失败，跳过验证"); return
        }
        sh("chmod 755 /data/local/tmp/ksucheck 2>/dev/null", 15)
        log("  --- ksucheck：向内核确认 supercall 与 allowlist ---")
        log(sh("/data/local/tmp/ksucheck 2>&1", 60).out.trimEnd())
    }

    /** 上次如果是 panic / 硬重启，p7 输出还留在 /data/local/tmp —— 捞进 App 目录留档 */
    private fun recoverLastRunLog() {
        try {
            val sz = sh("stat -c %s $RUN_LOG_REMOTE 2>/dev/null || echo 0", 10).out.trim()
                .lineSequence().lastOrNull()?.toLongOrNull() ?: 0L
            if (sz <= 0L) return
            val dst = File(getExternalFilesDir(null), "run-recovered.log")
            val r = sh(
                "cp -f $RUN_LOG_REMOTE '${dst.absolutePath}' && chmod 644 '${dst.absolutePath}'", 20
            )
            if (r.code == 0) {
                log("[恢复] 上次残留的 p7 输出 $sz B 已存为 ${dst.name}（崩溃现场）")
                log("       尾部: " + sh("tail -3 $RUN_LOG_REMOTE 2>/dev/null", 15)
                    .out.trim().replace("\n", " | "))
            }
        } catch (_: Throwable) {
        }
    }

    /** 失败时把 p7 自己的地址、关键日志捞出来并和档案比对 */
    private fun reportP7() {
        // 先把 /data 上的真身拷过来（覆盖 App 目录里每 2s 同步的那份）
        lastRunLogPath?.let { sh("cp -f $RUN_LOG_REMOTE '$it' 2>/dev/null", 15) }
        val runLog = lastRunLogPath ?: RUN_LOG_REMOTE
        val off = sh("grep '^\\[off\\]' '$runLog' 2>/dev/null | head -6", 20).out.trimEnd()
        log("--- p7 实际使用的地址 ---")
        log(off.ifBlank { "(无 [off] 行)" })
        val p = activeProfile
        if (p != null && p.env.isNotEmpty() && off.isNotBlank()) {
            // 必须带前导空白：否则 "cred=" 会命中 "initcred=" 里的子串，
            // 导致把 initcred 的值当成 CROFF，误报「env 没生效」
            fun pick(key: String) = Regex("(?:^|\\s)$key=(0x[0-9a-fA-F]+)")
                .find(off)?.groupValues?.get(1)
            val pairs = listOf(
                "selinux" to "GL_SELINUX", "scratch" to "GL_SCRATCH", "cred" to "GL_CROFF",
                "realcred" to "GL_REALCRED", "inittask" to "GL_INITTASK",
                "initcred" to "GL_INITCRED", "sigenforce" to "GL_SIGENFORCE", "paranoid" to "GL_PARANOID"
            )
            var bad = 0
            for ((usedKey, envKey) in pairs) {
                val used = pick(usedKey) ?: continue
                val want = p.env[envKey] ?: continue
                if (!want.equals(used, true)) {
                    bad++
                    log("!! $envKey 不一致：p7 用 $used，档案是 $want")
                }
            }
            if (bad == 0) log("  地址与档案一致") else
                log("  共 $bad 项不一致 → 说明 env 没生效（检查「注入档案地址」是否打开）")
        }
        // KASLR 是否泄漏成功，这才是 OPPO/一加 Android 14+ 上最常见的死因：
        // shell 没有任何 capability（CapEff=0）→ p7 的 prctl 采样 in-func=0 →
        // 拿不到内核 IP → text=0x0 → 所有 target 地址都缺 slide → cred 写入必然无效
        val leakLine = sh("grep -m1 'text=' /sdcard/plog.txt 2>/dev/null", 10).out.trim()
        if (leakLine.isNotBlank()) {
            log("--- KASLR 泄漏 ---")
            log(leakLine)
            if (leakLine.contains("text=0x0") || leakLine.contains("text=0 ")) {
                log("!! KASLR 未泄漏（text=0）：p7 算出的 target 地址缺少 slide，")
                log("   即使 cred 写入指令执行了也是写到错误位置 → uid 不会变。")
                log("   成因：本机 shell 的 CapEff=0，p7 靠 prctl/perf 采样内核 IP 的方式拿不到样本。")
                log("   对策：Shizuku 用 root 模式启动（需要先有 root），或关掉 KASLR。")
            } else {
                log("   text 非 0，KASLR 已泄漏")
            }
        }
        log("--- plog 尾部 ---")
        log(sh("tail -30 /sdcard/plog.txt 2>/dev/null || echo '(无)'", 20).out.trimEnd())
        log("--- p7 输出尾部 ---")
        log(sh("tail -12 '$runLog' 2>/dev/null || tail -12 /sdcard/root.log 2>/dev/null || echo '(无)'", 20).out.trimEnd())
    }

    private fun buildEnv(ko: String): String {
        val hasExplicit = useExplicitEnv && !activeProfile?.env.isNullOrEmpty()
        val sb = StringBuilder("GL_ATTR=cred GL_PERM=1 GL_ZEROSIG=1 GL_RETRY=2 GL_RCRED=1")
        // GL_PRIV=1：提权后给自己补 capability（含 CAP_SYSLOG）。
        // OPPO 把 kptr_restrict 设成 2 时，即使 uid=0 也读不到 /proc/kallsyms，
        // rk9 就拿不到 kln，socket 建不起来。补 caps 是绕开它的唯一办法。
        sb.append(" GL_PRIV=1")
        // GL_AUTO 只影响「用 perf_event_open 自助泄漏内核 text 基址」这一步，
        // 不是"全自动"，也和 kallsyms 无关。
        // 之前误以为它会带进 kallsyms 失败，于是在注入地址时把它关掉 —— 结果 text 恒为 0，
        // 所有 target 地址都缺 KASLR slide：cred 写到错位置（uid 不变），
        // 但 punch 已经把伪造 waiter 摆进内核栈了，于是"提权失败 + 之后按电源键就重启"。
        sb.append(" GL_AUTO=1")
        if (hasExplicit) {
            // profiles.json 可以被用户从 Android/data 里替换，值直接拼进命令行就是 shell 注入。
            // 只放行 GL_ 开头的键和「地址/数字」形态的值，不合规的丢掉并记日志。
            val keyRe = Regex("^GL_[A-Z0-9_]+$")
            val valRe = Regex("^[0-9a-zA-Z_]+$")
            activeProfile?.env?.forEach { (k, v) ->
                if (keyRe.matches(k) && valRe.matches(v)) {
                    sb.append(' ').append(k).append('=').append(v)
                } else {
                    log("  ! 档案里过滤掉可疑条目：$k=$v（只允许 GL_* 与字母数字值）")
                }
            }
        }
        sb.append(" GL_KO=").append(ko)
        return sb.toString()
    }

    private fun runPending() {
        val auto = pendingAuto ?: return
        if (!binderAlive) {
            log("[auto] Shizuku 未运行，跳过 $auto")
            pendingAuto = null
            return
        }
        if (!permissionGranted) {
            log("[auto] 无权限，发起授权请求")
            requestShizuku()
            return
        }
        pendingAuto = null
        log("[auto] 开始执行: $auto")
        when (auto) {
            "probe" -> probe()
            "selfcheck" -> pushOnly()
            "status" -> statusAll()
            // --es 传的是 String，--ez 才是 Boolean，两个都认
            "root" -> startRoot(
                (intent?.getStringExtra("force")?.toBoolean() == true) ||
                        (intent?.getBooleanExtra("force", false) == true)
            )
            "loadko" -> loadKsu(koPath)
            "ksud" -> deployKsud(ksudPath)
            "cmd" -> runRootCmd(rootCmd)
            "sh" -> runShellCmd(rootCmd)
            "export" -> exportLog()
            "envtest" -> {
                log("[envtest] 档案=${activeProfile?.name ?: "-"} 注入=$useExplicitEnv")
                log("[envtest] " + buildEnv("/data/local/tmp/rk9.ko"))
            }
            "diag" -> scope.launch { withContext(Dispatchers.IO) { collectDiagnostics("手动") } }
            else -> log("[auto] 未知动作: $auto")
        }
    }

    private fun probe() {
        if (running) return
        running = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    section("probe")
                    for (c in listOf("id", "uname -r", "getenforce", "grep CapEff /proc/self/status")) {
                        val t = System.currentTimeMillis()
                        val r = sh(c, 20)
                        log("  [$c] exit=${r.code} ${System.currentTimeMillis() - t}ms")
                        log("    ${r.out.trim()}")
                    }
                }
            } catch (e: Throwable) {
                log("!! 状态读取失败: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                flushLogText()
                busyLabel = ""
                running = false
            }
        }
    }

    private fun pushOnly() {
        if (running) return
        running = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    section("自检（只推送）")
                    val dir = extractAssets()
                    log("  ${dir.absolutePath}")
                    if (!pushAll(dir)) return@withContext false
                    val probe = sh(
                        "echo -n 'uid: '; id; echo -n 'selinux: '; getenforce; " +
                                "echo -n 'cap: '; grep CapEff /proc/self/status; " +
                                "echo -n 'chrt: '; chrt -f 1 /system/bin/true 2>&1; echo rc=$?", 30
                    )
                    log(probe.out.trimEnd())
                    true
                } catch (e: Throwable) {
                    log("!! 异常: ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
            }
            log(if (ok) "== 自检通过 ==" else "== 自检失败 ==")
            flushLogText()
            busyLabel = ""
            running = false
        }
    }

    private fun runRootCmd(cmd: String) {
        if (cmd.isBlank()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                log("$ rksh -c $cmd")
                val t = System.currentTimeMillis()
                // 不能把命令塞进单引号：cmd 里含 ' 会被截断成多个参数。
                // 走 stdin 让 rksh 从管道读，shell 不参与解析。
                val r = shWithStdin("/data/local/tmp/rksh -c \"\$(cat)\" 2>&1", cmd.toByteArray())
                log(r.out.trimEnd())
                log("[exit ${r.code} ${System.currentTimeMillis() - t}ms]")
            }
        }
    }

    private fun runShellCmd(cmd: String) {
        if (cmd.isBlank()) return
        running = true
        scope.launch {
            withContext(Dispatchers.IO) {
                log("$ sh -c $cmd")
                val t = System.currentTimeMillis()
                val r = sh(cmd, 60)
                log(r.out.trimEnd())
                log("[exit ${r.code} ${System.currentTimeMillis() - t}ms]")
            }
            busyLabel = ""
            running = false
        }
    }

    private fun loadKsu(ko: String) {
        if (ko.isBlank()) return
        scope.launch {
            running = true
            busyLabel = "加载 KSU 模块"
            withContext(Dispatchers.IO) {
                section("加载 KSU 模块")
                val exist = sh("ls -l '$ko' 2>&1", 20)
                log(exist.out.trim())
                if (exist.code != 0) {
                    log("!! 找不到 $ko")
                    return@withContext
                }
                val cp = sh("cp '$ko' /data/local/tmp/kernelsu.ko && chmod 644 /data/local/tmp/kernelsu.ko && ls -l /data/local/tmp/kernelsu.ko", 120)
                log(cp.out.trim())
                if (cp.code != 0) {
                    log("!! 拷贝失败")
                    return@withContext
                }
                val t = System.currentTimeMillis()
                val kln2 = sh("grep ' kallsyms_lookup_name$' /proc/kallsyms 2>/dev/null | head -1 | cut -d' ' -f1", 15).out.trim()
                val klnArg2 = if (kln2.length > 8 && kln2 != "0000000000000000") " kln=0x$kln2" else ""
                if (klnArg2.isEmpty()) {
                    log("!! 取不到 kallsyms_lookup_name，中止 insmod（没有 kln 的模块是空壳）")
                    return@withContext
                }
                val ins = sh("/data/local/tmp/rksh -c 'insmod /data/local/tmp/kernelsu.ko$klnArg2 level=3' 2>&1", 90)
                log("insmod exit=${ins.code} ${System.currentTimeMillis() - t}ms")
                log(ins.out.trim().ifBlank { "(无输出)" })
                delay(800)
                log(
                    sh(
                        "echo '--- /proc/modules ---'; cat /proc/modules | grep '^kernelsu' || echo '(未加载)'; " +
                                "echo '--- /sys/module/kernelsu ---'; ls /sys/module/kernelsu 2>&1 | head -5; " +
                                "echo '--- dmesg ---'; dmesg 2>/dev/null | grep -iE 'kernelsu|ksu' | tail -10", 40
                    ).out
                )
            }
            busyLabel = ""
            running = false
        }
    }

    private fun unloadKsu() {
        scope.launch {
            withContext(Dispatchers.IO) {
                section("卸载 kernelsu")
                log(sh("/data/local/tmp/rksh -c 'rmmod kernelsu' 2>&1; cat /proc/modules | grep '^kernelsu' || echo '(已卸载)'", 60).out)
            }
        }
    }

    private fun deployKsud(path: String) {
        if (path.isBlank()) return
        scope.launch {
            running = true
            busyLabel = "部署 ksud"
            withContext(Dispatchers.IO) {
                section("部署 ksud")
                val cp = sh("cp '$path' /data/local/tmp/ksud && chmod 755 /data/local/tmp/ksud && ls -l /data/local/tmp/ksud", 120)
                log(cp.out.trim())
                if (cp.code != 0) {
                    log("!! 拷贝失败：确认 $path 存在")
                    return@withContext
                }
                log(sh("/data/local/tmp/rksh -c 'mkdir -p /data/adb /data/adb/ksu && cp /data/local/tmp/ksud /data/adb/ksud && chmod 755 /data/adb/ksud && ls -l /data/adb/ksud' 2>&1", 90).out)
            }
            busyLabel = ""
            running = false
        }
    }

    private fun statusAll() {
        scope.launch {
            withContext(Dispatchers.IO) {
                section("状态检查")
                log(
                    sh(
                        "echo -n 'uname: '; uname -r; echo -n 'selinux: '; getenforce; " +
                                "echo -n 'boot_id: '; cat /proc/sys/kernel/random/boot_id; " +
                                "echo '--- 组件 ---'; ls -l /data/local/tmp/p7 /data/local/tmp/rk9.ko " +
                                "/data/local/tmp/rkshd /data/local/tmp/rksh 2>&1; " +
                                "echo '--- socket ---'; ( [ -S /data/local/tmp/rksh.sock ] && echo 'socket 存在' ) || echo 'socket 不存在或无权查看' ; " +
                                "echo '--- 模块 ---'; ls -d /proc/rk9 2>&1; cat /proc/modules | grep -E '^(kernelsu|rk9)' || true", 40
                    ).out
                )
                checkRoot()
            }
        }
    }

    // ---------------- UI ----------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        var showProfiles by remember { mutableStateOf(false) }
        Scaffold { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = 14.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(8.dp))
                Text("KSU 助手 · 临时 root", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Shizuku 取得 shell 身份执行提权链，重启失效",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusLine("Shizuku 服务", if (binderAlive) "运行中" else "未运行", binderAlive)
                        StatusLine("Shizuku 权限", if (permissionGranted) "已授权" else "未授权", permissionGranted)
                        if (serverUid >= 0) {
                            StatusLine("服务 UID", "$serverUid${if (serverUid == 2000) " (shell)" else ""}", serverUid == 2000)
                        }
                        StatusLine("root 状态", if (rootReady) "已就绪" else "未就绪", rootReady)
                        Text(
                            "设备 ${Build.MODEL} / Android ${Build.VERSION.RELEASE} / $kernelVer",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!binderAlive) {
                    Spacer(Modifier.height(8.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Shizuku 未运行 —— 无法执行任何操作", color = Color(0xFFB71C1C), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                "先在电脑上执行：adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh\n" +
                                        "（或 Shizuku App 内用无线调试启动），再回到本页点「授权」。",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("设备档案", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "${activeProfile?.name ?: "(无)"}  ${if (profileAuto) "[自动]" else "[手动]"}",
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "cred=${activeProfile?.env?.get("GL_CROFF") ?: "-"}  soc=${activeProfile?.soc ?: "-"}  " +
                                            completenessText(activeProfile),
                                    fontSize = 11.sp,
                                    color = if (missingKeys(activeProfile).isEmpty() || activeProfile?.id == "auto")
                                        MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFB71C1C)
                                )
                            }
                            TextButton(onClick = { showProfiles = true }) { Text("切换") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = useExplicitEnv, onCheckedChange = { useExplicitEnv = it })
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("注入档案地址（GL_*）", fontSize = 13.sp)
                                Text(
                                    if (useExplicitEnv) "会把 GL_SELINUX 等写入 env" else "默认 GL_AUTO=1，由 p7 自行推导",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = autoKsu, onCheckedChange = { autoKsu = it })
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("提权后自动装 KSU", fontSize = 13.sp)
                                Text(
                                    "insmod kernelsu.ko + 从 KSU 管理器取 ksud 部署到 /data/adb",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("⚠ 5.4 上没有「只探测」的中间档", fontSize = 13.sp, color = Color(0xFFB71C1C))
                                Text(
                                    "QEMU 实测：GL_ATTR=self + GL_NOPUNCH=1 + GL_NTRIG=0 仍会在 arm 后 " +
                                            "3 秒内被内核 PI 链踩崩。p7 必须一路走完才有意义，中途停下等于留悬空 waiter。",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { requestShizuku() }, enabled = !running) { Text("授权") }
                    OutlinedButton(onClick = { statusAll() }, enabled = !running && permissionGranted) { Text("状态") }
                    OutlinedButton(onClick = { exportLog() }, enabled = !running) { Text("导出日志") }
                }

                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { auditPackagedAssets() },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("APK 完整性检查（不执行引擎）") }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { startRoot() },
                    enabled = !running && permissionGranted,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (running) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(busyLabel.ifBlank { "运行中" })
                    } else {
                        Text("一键提权", fontSize = 16.sp)
                    }
                }

                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { runProbe() },
                    enabled = !running && permissionGranted,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("推送 + 探测（自检，不跑漏洞）") }

                Spacer(Modifier.height(14.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Root 命令", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = rootCmd, onValueChange = { rootCmd = it },
                            label = { Text("命令") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { runRootCmd(rootCmd) }, enabled = !running && rootReady) { Text("以 root 执行") }
                            OutlinedButton(onClick = { runShellCmd(rootCmd) }, enabled = !running && permissionGranted) {
                                Text("以 shell 执行")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("KernelSU", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = koPath, onValueChange = { koPath = it },
                            label = { Text("kernelsu.ko 路径") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { loadKsu(koPath) }, enabled = !running && rootReady) { Text("加载模块") }
                            OutlinedButton(onClick = { unloadKsu() }, enabled = !running && rootReady) { Text("卸载") }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = ksudPath, onValueChange = { ksudPath = it },
                            label = { Text("ksud 路径") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { deployKsud(ksudPath) }, enabled = !running && rootReady) {
                            Text("部署 ksud 到 /data/adb")
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("日志", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { synchronized(logBuf) { logBuf.clear() }; logText = "" }) { Text("清空") }
                    TextButton(onClick = { exportLog() }, enabled = !running) { Text("导出") }
                }
                Card(Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            logText.ifBlank { "(暂无)" },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 220.dp, max = 520.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .padding(8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(40.dp))
            }

            if (showProfiles) {
                AlertDialog(
                    onDismissRequest = { showProfiles = false },
                    title = { Text("选择设备档案") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            profiles.forEach { p ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    RadioButton(
                                        selected = p.id == activeProfile?.id,
                                        onClick = { selectProfile(p); showProfiles = false }
                                    )
                                    Column {
                                        Text(p.name, fontSize = 13.sp)
                                        Text(
                                            "${p.soc}  cred=${p.env["GL_CROFF"] ?: "-"}  ${completenessText(p)}",
                                            fontSize = 11.sp,
                                            color = if (missingKeys(p).isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                                            else Color(0xFFB71C1C)
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showProfiles = false }) { Text("关闭") } }
                )
            }
        }
    }

    @Composable
    private fun StatusLine(label: String, value: String, ok: Boolean) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ok) "●" else "○", color = if (ok) Color(0xFF2E7D32) else Color(0xFFB71C1C), fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 13.sp, modifier = Modifier.width(96.dp))
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
