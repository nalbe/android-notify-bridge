package com.bastet.lednls

import java.util.concurrent.TimeUnit

/**
 * Root shell wrapper. Runs a command through the first working su binary
 * (KernelSU first, then the usual system paths). Every shell command here
 * is short-lived; stdout is captured, stderr is discarded.
 */
object Su {

    private val suCandidates = listOf(
        "/system/bin/su",       // KernelSU installs its su here (verified on Shark8)
        "su",                   // whatever is in the app PATH
        "/data/adb/ksu/bin/su", // KernelSU alternate location
        "/sbin/su",
        "/system/xbin/su"
    )

    data class Result(val out: String, val code: Int) {
        val ok: Boolean get() = code == 0
        /** Process was killed on timeout -> kernel is waiting for a decision. */
        val pending: Boolean get() = code == -1
    }

    fun run(cmd: String, timeoutSec: Long = 10): Result {
        return try {
            val out = SuShell.exec(cmd, timeoutSec * 1000)
            Result(out, 0)
        } catch (_: IllegalStateException) {
            legacyRun(cmd, timeoutSec)
        }
    }

    private fun legacyRun(cmd: String, timeoutSec: Long): Result {
        for (su in suCandidates) {
            val proc = try {
                Runtime.getRuntime().exec(arrayOf(su, "-c", cmd))
            } catch (_: Exception) {
                continue
            }
            proc.errorStream.close()

            val out = try {
                proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: Exception) {
                ""
            }

            val finished = try {
                proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            } catch (_: Exception) {
                false
            }
            if (!finished) {
                proc.destroyForcibly()
                return Result(out.trim(), -1)   // still waiting on a root dialog
            }
            val code = proc.exitValue()
            if (code == 127) continue           // su started but cmd missing: try next
            return Result(out.trim(), code)     // 0 = ok, anything else = denied/error
        }
        return Result("", 255)
    }
}