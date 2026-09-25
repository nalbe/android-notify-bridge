package com.bastet.notifybridge

/**
 * Per-source logcat tags. Every line the bridge writes goes to exactly one
 * of these, so a chatty notify stream can be silenced while watching the
 * broadcast source (`adb logcat -s nb-bcast:D nb-core:D`), and a config
 * reload is never buried under notification spam.
 *
 *   nb-core      config apply, sinks/sockets, reload, fragment errors
 *   nb-notify    NLS posted/removed, call/missed classification
 *   nb-bcast     registered system broadcasts (screen, charge, ...)
 *   nb-settings  watched Settings keys (pulse and friends)
 *
 * Each BridgeEvent carries the source it came from ([BridgeEvent.source]);
 * an event without one logs under nb-core - visible, never silently lost.
 */
object BLog {
    const val CORE = "nb-core"
    const val NOTIFY = "nb-notify"
    const val BCAST = "nb-bcast"
    const val SETTINGS = "nb-settings"

    const val SRC_NOTIFY = "notify"
    const val SRC_BROADCAST = "broadcast"
    const val SRC_SETTINGS = "settings"

    fun tagOf(e: BridgeEvent): String = when (e.source) {
        SRC_NOTIFY -> NOTIFY
        SRC_BROADCAST -> BCAST
        SRC_SETTINGS -> SETTINGS
        else -> CORE
    }
}
