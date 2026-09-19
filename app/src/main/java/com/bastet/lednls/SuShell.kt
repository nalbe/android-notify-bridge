package com.bastet.lednls

/** Single persistent root shell for NLS needs. */
object SuShell {

    val main = RootShell()

    fun exec(cmd: String, timeoutMs: Long = 4000): String = main.exec(cmd, timeoutMs)
}