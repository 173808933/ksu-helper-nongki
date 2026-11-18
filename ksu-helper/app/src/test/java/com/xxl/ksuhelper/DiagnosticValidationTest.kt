package com.xxl.ksuhelper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 仅测试文本验收规则，不初始化 Activity、不调用 shell、不执行任何引擎。 */
class DiagnosticValidationTest {
    private val a93 = "5.4.254-qgki-g9ef4a332d496"
    private val reno7 = "5.4.254-qgki-gaa96eae94d32"

    @Test fun exactReleaseMatches() {
        assertTrue(MainActivity.matchesKernelRelease("$a93 SMP preempt mod_unload", a93))
    }

    @Test fun whitespaceIsNormalized() {
        assertTrue(MainActivity.matchesKernelRelease("  $a93\tSMP\n", a93))
    }

    @Test fun differentBuildIsRejected() {
        assertFalse(MainActivity.matchesKernelRelease("$reno7 SMP", a93))
    }

    @Test fun versionPrefixIsRejected() {
        assertFalse(MainActivity.matchesKernelRelease("5.4.254 SMP", a93))
        assertFalse(MainActivity.matchesKernelRelease("$a93 SMP", "5.4.254"))
    }

    @Test fun unavailableIdentifierIsRejected() {
        assertFalse(MainActivity.matchesKernelRelease(null, a93))
        assertFalse(MainActivity.matchesKernelRelease("", a93))
        assertFalse(MainActivity.matchesKernelRelease("   ", a93))
        assertFalse(MainActivity.matchesKernelRelease(a93, ""))
    }

    @Test fun successfulRootIdentityIsAccepted() {
        assertTrue(MainActivity.isRootIdentity(0, "uid=0(root) gid=0(root)"))
        assertTrue(MainActivity.isRootIdentity(0, "uid=0 gid=0"))
    }

    @Test fun nonRootAndPartialIdentityAreRejected() {
        assertFalse(MainActivity.isRootIdentity(0, "uid=2000(shell) gid=2000(shell)"))
        assertFalse(MainActivity.isRootIdentity(0, "uid=00(root)"))
        assertFalse(MainActivity.isRootIdentity(0, "euid=0(root)"))
        assertFalse(MainActivity.isRootIdentity(0, "uid=0unexpected"))
    }

    @Test fun failedOrTimedOutCommandIsRejected() {
        assertFalse(MainActivity.isRootIdentity(1, "uid=0(root)"))
        assertFalse(MainActivity.isRootIdentity(-101, "uid=0(root)\n[超时]"))
        assertFalse(MainActivity.isRootIdentity(0, ""))
    }
}
