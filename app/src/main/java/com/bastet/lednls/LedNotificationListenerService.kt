package com.bastet.lednls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.File
import java.util.Collections

/**
 * NotificationListenerService - standalone headless notification bridge.
 *
 * Lives in its own APK (com.bastet.lednls). The whole forwarding policy
 * lives in an optional device-side config (/data/local/tmp/lednls_bridge.json):
 * which observed event goes where is a rule list, each rule rendering a
 * text line ("$pkg $id $key $incoming $on ...") into a configured sink
 * (abstract Unix socket, or logcat). No config file = built-in defaults
 * that reproduce the classic chgd contract:
 *   ENQ <pkg> <id> / CAN <pkg> <id>
 *   RING_ON <0|1> / RING_OFF
 *   VOIP_ON <pkg> / VOIP_OFF <pkg>
 *   SCREEN <0|1>, PULSE <0|1>, PING -> PONG, WD <ms> (daemon -> app)
 *
 * Observed bus is a superset of the old transport: notify / ring / voip /
 * screen / pulse events with normalized fields; SIM- and messenger-call
 * classification stays built-in (the package lists are config), because a
 * raw notification carries no "this is a call" marker.
 *
 * Supervision: this service supervises the daemon it serves. At the
 * cadence from config "watchdogMs" (or the consumer's "WD <ms>" push, or
 * the built-in 60000 default) it asks su whether the daemon binary is
 * alive and restarts it when not. Live bridge state mirrors to
 * config "statusPath" style /data/local/tmp/lednls.status for the GUI.

 * Threading: the system may call onListenerConnected() repeatedly (GSI
 * rebinds, process freezes). Each socket sink owns one reconnect loop
 * thread created once; onListenerConnected is then a no-op. Only the loop
 * thread closes its own socket, so a stale reader can never close a fresh
 * connection.
 */
class LedNotificationListenerService : NotificationListenerService() {

    /** Current resolved config; swapped atomically on reload. */
    @Volatile
    private var config: BridgeConfig = BridgeConfig.DEFAULT

    /** Live sink registry; rebuilt on reload. */
    @Volatile
    private var sinks: SinkRegistry? = null

    private var watchdog: Thread? = null
    private var pulseObserver: ContentObserver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private val loopLock = Object()

    private var lastPulseSent = -1

    override fun onListenerConnected() {
        applyConfig()
        // Watchdog first so the daemon is likely alive when the loops fire.
        startWatchdog()
        startSinks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_POST_TEST -> postTestNotification()
            ACTION_RELOAD -> {
                lastCfgMtime = runCatching { File(BridgeConfig.CONFIG_PATH).lastModified() }.getOrDefault(0L)
                applyConfig()
            }
        }
        return START_STICKY
    }

    private fun postTestNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel("ledtest") == null) {
            nm.createNotificationChannel(
                NotificationChannel("ledtest", "LED test", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        nm.notify(
            4242,
            Notification.Builder(this, "ledtest")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("LED bridge test")
                .setContentText("ENQ should hit chgd and arm the LED")
                .build()
        )
        android.util.Log.i("led-nls", "self-test notification posted")
    }

    override fun onCreate() {
        super.onCreate()
        registerPulseObserver()
        registerScreenReceiver()
    }

    // ------------------------------------------------------------ config

    /** Load (or reload) config, rebuild sinks, restart the supervisor. */
    private fun applyConfig() {
        val cfg = BridgeConfig.load()
        synchronized(loopLock) {
            val old = sinks
            if (old != null) old.stopAll()
            sinks = SinkRegistry(cfg, onSocketLine, onSocketConnect).also { it.startAll() }
            config = cfg
            applyWatchdogLocked()
        }
        android.util.Log.i("led-nls",
            "config: ${cfg.rules.size} rules, ${cfg.sinks.size} sinks, " +
                "wd=${cfg.watchdogMs ?: "consumer"} daemon=${cfg.daemonName}")
    }

    private var lastCfgMtime = 0L

    /** Cheap mtime re-check; the RELOAD broadcast is the instant path. */
    private fun maybeReloadOnMtime() {
        val m = runCatching { File(BridgeConfig.CONFIG_PATH).lastModified() }.getOrDefault(0L)
        if (m != 0L && m != lastCfgMtime) {
            lastCfgMtime = m
            applyConfig()
        }
    }

    // ------------------------------------------------------------ sources

    /** ACTION_SCREEN_OFF/ON -> screen event. No polling anywhere: this is
     *  the only screen-state source the park logic needs on kernels with no
     *  backlight uevent. */
    private fun registerScreenReceiver() {
        synchronized(loopLock) {
            if (screenReceiver != null) return
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    when (i?.action) {
                        Intent.ACTION_SCREEN_OFF -> emit(BridgeEvent("screen", "off"))
                        Intent.ACTION_SCREEN_ON -> emit(BridgeEvent("screen", "on", on = true))
                    }
                }
            }
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            else
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(r, f)
            screenReceiver = r
            android.util.Log.i("led-nls", "screen receiver on")
        }
    }

    /** Trusted live screen state from the framework (not sysfs). */
    private fun isInteractive(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    /** System "Notification light" toggle, watched like stock SystemUI.
     *  Any writer trips the observer and we forward a pulse event. */
    private fun registerPulseObserver() {
        synchronized(loopLock) {
            if (pulseObserver != null) return
            val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    if (uri == null || uri.lastPathSegment != "notification_light_pulse") return
                    val on = runCatching {
                        Settings.System.getInt(contentResolver, "notification_light_pulse", 1) != 0
                    }.getOrDefault(true)
                    forwardPulse(on)
                }
            }
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, o)
            pulseObserver = o
            android.util.Log.i("led-nls", "pulse observer on Settings.System")
        }
    }

    /** Forward a toggle change: preferred way is the pulse event (event-
     *  driven, no root). When no socket is up, fall back to SIGUSR2 over
     *  su - same daemon-side effect, only for that rare window. */
    private fun forwardPulse(on: Boolean) {
        val v = if (on) 1 else 0
        if (lastPulseSent == v) return        // dedupe Settings exit ack storms
        lastPulseSent = v
        emit(BridgeEvent("pulse", if (on) "on" else "off", on = on))
        if (on) return                         // turning back on needs no disarm
        if (sinks?.anySocketConnected() == true) return
        android.util.Log.w("led-nls", "pulse=0 but no socket; SIGUSR2 fallback")
        Thread({
            try {
                SuShell.exec("kill -USR2 \$(pidof ${config.daemonName}) 2>/dev/null")
            } catch (_: Exception) {
                // no root / su pending: next notification arm re-reads anyway
            }
        }, "led-nls-pulse").apply { isDaemon = true }.start()
    }

    override fun onDestroy() {
        synchronized(loopLock) {
            pulseObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
            pulseObserver = null
            screenReceiver?.let { runCatching { unregisterReceiver(it) } }
            screenReceiver = null
        }
        watchdog?.interrupt()
        sinks?.stopAll()
        super.onDestroy()
    }

    // ------------------------------------------------------------ routing

    private fun emit(e: BridgeEvent) {
        val cfg = config
        val rg = sinks ?: return
        EventRouter.emit(e, cfg, rg)
    }

    /** Render every matching rule into [sink] only (replay on connect). */
    @Suppress("DEPRECATION")
    private fun replayInto(sink: Sink, cfg: BridgeConfig) {
        val actives = getActiveNotifications(arrayOf()) ?: emptyArray()
        val ring = java.util.HashSet(ringNotifs)
        val calls = java.util.HashSet(callNotifs)
        val ringKeys = ring
        val callKeys = calls

        fun fwd(e: BridgeEvent) {
            for (r in cfg.rules)
                if (r.matches(e) && r.to == sink.name)
                    sink.send(e.render(r.line))
        }
        // Screen state first, then the rainbow cutouts, then the rest.
        fwd(BridgeEvent("screen", if (isInteractive()) "on" else "off", on = isInteractive()))
        for (key in ringKeys) {
            val parts = key.split('|')
            fwd(BridgeEvent("ring", "on", pkg = parts.getOrElse(1) { "" }, incoming = ringIncoming[key] ?: false))
        }
        for (key in callKeys) {
            val parts = key.split('|')
            fwd(BridgeEvent("voip", "on", pkg = parts.getOrElse(1) { "" }))
        }
        for (sbn in actives) {
            val key = sbn.key
            if (ringKeys.contains(key) || callKeys.contains(key)) continue
            fwd(BridgeEvent("notify", "posted", pkg = sbn.packageName, id = sbn.id, key = key))
        }
    }

    private val onSocketConnect: (Sink) -> Unit = { sink ->
        val cfg = config
        replayInto(sink, cfg)
    }

    /** Consumer -> app lines: only "WD <ms>" matters (the classic cadence
     *  push). PONG is a probe echo we ignore. WD is the authoritative
     *  keepalive: it is written back into the config file (persistent) and
     *  applied immediately. */
    private val onSocketLine: (String) -> Unit = { line ->
        if (line.startsWith("WD ")) {
            val ms = line.substring(3).trim().toLongOrNull()
            if (ms != null && ms != config.watchdogMs) {
                android.util.Log.i("led-nls", "watchdog pushed from consumer: ${ms}ms")
                BridgeConfig.persistWatchdogMs(ms)
                synchronized(loopLock) {
                    config = config.copy(watchdogMs = ms)
                    // the file we just wrote must not trip the mtime reload
                    lastCfgMtime =
                        runCatching { File(BridgeConfig.CONFIG_PATH).lastModified() }.getOrDefault(0L)
                    applyWatchdogLocked()
                }
            }
        }
    }

    // ------------------------------------------------------------ loop

    private fun startSinks() {
        sinks?.startAll()
    }

    // ------------------------------------------------------------ watchdog

    private fun effectiveWd(): Long = config.watchdogMs ?: 60000L

    private fun startWatchdog() {
        applyWatchdogLocked()
    }

    /** Start/keep the supervisor thread for the effective cadence. 0
     *  disables and stops it. A live supervisor with positive cadence is
     *  left untouched (rebind storms must not spawn duplicates). */
    private fun applyWatchdogLocked() {
        val cur = watchdog
        val cadence = effectiveWd()
        if (cadence > 0) {
            if (cur?.isAlive == true) return
            val t = Thread({ watchLoop(cadence) }, "led-nls-watch")
            t.isDaemon = true
            t.start()
            watchdog = t
            android.util.Log.i("led-nls", "watchdog on, ${cadence}ms")
        } else {
            cur?.interrupt()
            watchdog = null
            android.util.Log.i("led-nls", "watchdog off")
        }
    }

    /** chgd owns the LED and the abstract socket; if it dies nothing blinks
     *  and the reconnect loops just spin against a dead endpoint. The system
     *  resurrects THIS process (notification listener rebind), so a supervisor
     *  living here outlives any shell keepalive nobody restarts. */
    private fun watchLoop(cadence: Long) {
        while (!Thread.currentThread().isInterrupted) {
            try {
                maybeReloadOnMtime()
                ensureDaemon()
            } catch (_: Exception) {
                // no root yet / su denied: keep the supervisor alive,
                // retry next tick instead of crashing the process
            }
            try { Thread.sleep(cadence) } catch (_: InterruptedException) { break }
        }
    }

    private fun ensureDaemon() {
        val cfg = config
        val pid = try {
            SuShell.exec("pidof ${cfg.daemonName}").trim()
        } catch (e: Exception) {
            ""                                    // no root (yet): retry next tick
        }
        if (pid.isNotEmpty()) return
        android.util.Log.w("led-nls", "${cfg.daemonName} is dead, restarting via su")
        try {
            SuShell.exec(
                "setsid ${cfg.daemonPath} >/data/local/tmp/chgd.err 2>&1 </dev/null &"
            )
        } catch (_: Exception) {
            // su not granted yet (KernelSU prompt pending): retry next tick
        }
    }

    // ------------------------------------------------------------ events

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        android.util.Log.i("led-nls", "posted ${sbn.packageName} id=${sbn.id} key=${sbn.key}")
        if (isSimCallNotification(sbn)) {
            val incoming = simCallIsIncoming(sbn)
            ringNotifs.add(sbn.key)
            ringIncoming[sbn.key] = incoming
            // RING before the notify so a following dialer ping sees the
            // ring active and skips missed-call verification.
            emit(BridgeEvent("ring", "on", pkg = sbn.packageName, id = sbn.id, incoming = incoming))
        } else if (isCallNotification(sbn)) {
            callNotifs.add(sbn.key)
            // VOIP before the notify so the chat-color path never touches it.
            emit(BridgeEvent("voip", "on", pkg = sbn.packageName, id = sbn.id))
        }
        emit(BridgeEvent("notify", "posted", pkg = sbn.packageName, id = sbn.id, key = sbn.key))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        android.util.Log.i("led-nls", "removed ${sbn.packageName} id=${sbn.id}")
        if (ringNotifs.remove(sbn.key)) {
            ringIncoming.remove(sbn.key)
            // Drop the rainbow only once the dialer has no live call left:
            // a ringing->ongoing swap / decline->redial reuses ids and must
            // not flicker the LED off between the two.
            val still = ringNotifs.any { it.split('|').getOrNull(1) == sbn.packageName }
            if (!still) emit(BridgeEvent("ring", "off", pkg = sbn.packageName))
        }
        if (callNotifs.remove(sbn.key)) {
            val still = callNotifs.any { it.split('|').getOrNull(1) == sbn.packageName }
            if (!still) emit(BridgeEvent("voip", "off", pkg = sbn.packageName))
        }
        emit(BridgeEvent("notify", "removed",
            pkg = sbn.packageName, id = sbn.id, key = sbn.key))
    }

    // ------------------------------------------------------------ call detect

    private val dialerPkg: String get() = config.dialerPkg
    private val voipPkgs: Set<String> get() = config.voipPkgs

    /** Keys of notifications classified as a live/incoming telephony call. */
    private val ringNotifs = Collections.synchronizedSet(HashSet<String>())
    private val callNotifs = Collections.synchronizedSet(HashSet<String>())

    /** incoming (1) per live dialer call key; survives removal where the
     *  notification is gone and cannot be re-inspected. */
    private val ringIncoming = Collections.synchronizedMap(HashMap<String, Boolean>())

    /**
     * A live telephony (SIM) call notification from the dialer. NOT a
     * missed-call row (channel "missed_calls", no CALL category, no
     * answer/decline actions) - those pass through as a plain notify so
     * the daemon's dialer.c missed-call verification can claim them.
     */
    private fun isSimCallNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != dialerPkg) return false
        val n = sbn.notification
        if ((n.channelId ?: "").lowercase().contains("missed")) return false
        if (n.category == Notification.CATEGORY_CALL) return true
        if ((n.channelId ?: "").lowercase().contains("call")) return true
        for (a in n.actions ?: emptyArray<Notification.Action>()) {
            val t = a.title?.toString()?.lowercase() ?: continue
            if (t.contains("answer") || t.contains("decline") ||
                t.contains("reject")  || t.contains("end call") ||
                t.contains("hang up"))
                return true
        }
        return false
    }

    /** 1 = incoming (answer/decline available), 0 = outgoing/ongoing. */
    private fun simCallIsIncoming(sbn: StatusBarNotification): Boolean {
        val ch = (sbn.notification.channelId ?: "").lowercase()
        if (ch.contains("incoming") || ch.contains("ring")) return true
        for (a in sbn.notification.actions ?: emptyArray<Notification.Action>()) {
            val t = a.title?.toString()?.lowercase() ?: continue
            if (t.contains("answer") || t.contains("decline") ||
                t.contains("reject"))
                return true
        }
        return false
    }

    /**
     * A messenger call notification, by the strongest available markers:
     * category CALL, a "call"-ish channel id, or answer/decline/end-call
     * actions. A plain chat message matches none of these and takes the
     * normal path.
     */
    private fun isCallNotification(sbn: StatusBarNotification): Boolean {
        if (!voipPkgs.contains(sbn.packageName)) return false
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_CALL) return true
        if ((n.channelId ?: "").lowercase().contains("call")) return true
        for (a in n.actions ?: emptyArray<Notification.Action>()) {
            val t = a.title?.toString()?.lowercase() ?: continue
            if (t.contains("answer") || t.contains("decline") ||
                t.contains("reject")  || t.contains("end call") ||
                t.contains("hang up"))
                return true
        }
        return false
    }

    companion object {
        private const val ACTION_POST_TEST = "com.bastet.lednls.POST_TEST"
        internal const val ACTION_RELOAD = "com.bastet.lednls.RELOAD_CONFIG"
    }
}