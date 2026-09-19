package com.bastet.lednls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * NotificationListenerService - standalone headless notification bridge.
 *
 * Lives in its own APK (com.bastet.lednls) installed alongside the daemon by
 * the KernelSU module, so the notification LED works without the GUI. The
 * GUI is an optional configurator on top.
 *
 * Transport: abstract Unix domain socket "chgd_noty" (same process code
 * space as the daemon). Text protocol, one command per line:
 *   ENQ <pkg> <id>      onNotificationPosted
 *   CAN <pkg> <id>      onNotificationRemoved
 *   VOIP_ON [pkg]       messenger call notification posted (rainbow)
 *   VOIP_OFF [pkg]      messenger call notification removed
 *   RING_ON <0|1>       phone call notification posted (1 incoming,
 *                       0 outgoing/ongoing)
 *   RING_OFF            last phone call notification removed
 *   PULSE <0|1>         Settings.System notification_light_pulse changed
 *                       (0: daemon disarms the LED right away, like SystemUI;
 *                       1: daemon drops its 3s settings cache)
 *   SCREEN <0|1>        ACTION_SCREEN_OFF/ON. The kernel fires no uevent
 *                       when the backlight changes, so this edge is what
 *                       lets a parked notification flash the moment the
 *                       screen falls - the daemon needs no 1s poll.
 *                       Replayed on connect so state survives a rebind.
 *   PING                liveness probe (daemon replies PONG)
 *   WD <ms>             daemon -> app: [led] watchdog_ms. Pushed on connect
 *                       and after every GUI save (the GUI SIGALRMs the
 *                       daemon; the daemon is root and the only legit
 *                       reader of led.conf). We apply it and drop the old
 *                       10s mtime poll for good.
 *
 * While this service is connected the daemon does not read logcat at
 * all: there is no logdr transport anymore. Notifications and call
 * lifecycle are sourced here exclusively.
 *
 * The user must grant notification access once (Settings -> Special app
 * access -> Notification access -> LED NLS).
 *
 * Supervision: this service also supervises the native daemon. At the
 * cadence received as "WD <ms>" over the socket ([led] watchdog_ms in
 * led.conf, 0 = off) it asks su whether chgd is alive and restarts it
 * when it is not. The GUI edits the key and SIGALRMs the daemon, which
 * re-reads its own config (root) and pushes the fresh value here - so
 * the cadence is applied event-driven. The initial value is read once
 * from the daemon's world-readable mirror /data/local/tmp/lednls.status
 * (no su, no led.conf access at all in this process). default 60000 ms,
 * 0 = disabled - every tick spawns an su shell, so it is opt-in.
 *
 * Live bridge state is mirrored to /data/local/tmp/lednls.status
 * ("connected=1"/"connected=0") for the GUI status screen.
 *
 * The system guarantees to rebind this listener, so a supervisor living
 * here outlives any shell keepalive. Boot-time autostart of chgd stays in
 * the KernelSU module's service.sh.
 *
 * Threading: the system may call onListenerConnected() repeatedly (GSI
 * rebinds, process freezes). A single daemon loop thread is created once
 * and survives all rebinds; onListenerConnected is then a no-op. Only the
 * loop thread closes/tracks its own socket, so a stale reader can never
 * close a fresh connection.
 */
class LedNotificationListenerService : NotificationListenerService() {

    /** Current tracked connection; swapped only by the loop thread. */
    @Volatile
    private var cur: LocalSocket? = null
    private var reconnector: Thread? = null
    private var watchdog: Thread? = null
    private var pulseObserver: ContentObserver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private val loopLock = Object()

    /** Watchdog cadence from led.conf, ms; 0 = supervisor disabled. */
    @Volatile
    private var companionMs: Long = 60000

    @Volatile
    private var connected = false

    /** Last notification_light_pulse value forwarded to the daemon, to
     *  dedupe SettingsProvider's change blasts (every put fires onChange,
     *  even rewrites of the same value). */
    @Volatile
    private var lastPulseSent = -1

    override fun onListenerConnected() {
        // Watchdog first so the daemon is likely alive when the loop retries.
        startWatchdog()
        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_POST_TEST) {
            postTestNotification()
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

    /** ACTION_SCREEN_OFF/ON -> "SCREEN <0|1>" over the socket. No polling
     *  anywhere: this is the only screen-state source the daemon needs for
     *  flashing a parked notification, and sysfs backlight has no uevent
     *  on this kernel. Registered once, torn down on destroy. */
    private fun registerScreenReceiver() {
        synchronized(loopLock) {
            if (screenReceiver != null) return
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    when (i?.action) {
                        Intent.ACTION_SCREEN_OFF -> forwardScreen(false)
                        Intent.ACTION_SCREEN_ON  -> forwardScreen(true)
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

    private fun forwardScreen(on: Boolean) {
        val v = if (on) 1 else 0
        if (cur != null) send("SCREEN $v\n")
    }

    /** Trusted live screen state from the framework (not sysfs). */
    private fun isInteractive(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    /** Watch the system "Notification light" toggle (Settings.System
     *  notification_light_pulse) the way stock SystemUI does: a
     *  ContentObserver on the System provider. Any writer trips it
     *  (Settings app, adb, the GUI) and we forward the fresh value over
     *  the socket so the daemon disarms/updates without ever polling.
     *  Registered once, survives all rebinds; torn down on destroy. */
    private fun registerPulseObserver() {
        synchronized(loopLock) {
            if (pulseObserver != null) return
            val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    if (uri == null ||
                        uri.lastPathSegment != "notification_light_pulse")
                        return
                    val on = runCatching {
                        Settings.System.getInt(
                            contentResolver, "notification_light_pulse", 1
                        ) != 0
                    }.getOrDefault(true)
                    forwardPulse(on)
                }
            }
            contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI, true, o
            )
            pulseObserver = o
            android.util.Log.i("led-nls", "pulse observer on Settings.System")
        }
    }

    /** Forward a toggle change: preferred way is the socket PULSE command
     *  (event-driven, no root). When the socket is down the daemon might
     *  be dead or reconnecting, so fall back to SIGUSR2 over su - same
     *  daemon-side effect, only for that rare window. */
    private fun forwardPulse(on: Boolean) {
        val v = if (on) 1 else 0
        if (lastPulseSent == v) return       // dedupe Settings exits ack storms
        lastPulseSent = v
        if (cur != null) {
            send("PULSE $v\n")
            return
        }
        if (on) return                        // turning back on needs no disarm
        android.util.Log.w("led-nls", "pulse=0 but socket down; SIGUSR2 fallback")
        Thread({
            try {
                SuShell.exec("kill -USR2 \$(pidof chgd) 2>/dev/null")
            } catch (_: Exception) {
                // no root / su pending: next notification arm re-reads anyway
            }
        }, "led-nls-pulse").apply { isDaemon = true }.start()
    }

    override fun onDestroy() {
        setConnected(false)
        synchronized(loopLock) {
            pulseObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
            pulseObserver = null
            screenReceiver?.let { runCatching { unregisterReceiver(it) } }
            screenReceiver = null
        }
        reconnector?.interrupt()
        watchdog?.interrupt()
        cur?.let { l ->
            try { l.close() } catch (_: Exception) {}
        }
        cur = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        android.util.Log.i("led-nls", "posted ${sbn.packageName} id=${sbn.id} key=${sbn.key}")
        if (isSimCallNotification(sbn)) {
            val incoming = simCallIsIncoming(sbn)
            ringNotifs.add(sbn.key)
            ringIncoming[sbn.key] = incoming
            // Arm the ring rainbow BEFORE the ENQ reaches the daemon: the
            // dialer ping that follows must see ring_is_active() and not
            // fall through into a missed-call verification window.
            send("RING_ON ${if (incoming) 1 else 0}\n")
        } else if (isCallNotification(sbn)) {
            callNotifs.add(sbn.key)
            // Arm the rainbow BEFORE the ENQ reaches the daemon, so the
            // chat-color path never touches the call notification.
            send("VOIP_ON ${sbn.packageName}\n")
        }
        send("ENQ ${sbn.packageName} ${sbn.id}\n")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        android.util.Log.i("led-nls", "removed ${sbn.packageName} id=${sbn.id}")
        if (ringNotifs.remove(sbn.key)) {
            ringIncoming.remove(sbn.key)
            // Drop the rainbow only once the dialer has no live call
            // notification left: a ringing->ongoing swap / decline ->
            // redial reuses ids and must not flicker the LED off between
            // the two.
            val still = ringNotifs.any { it.split('|').getOrNull(1) == sbn.packageName }
            if (!still) send("RING_OFF\n")
        }
        if (callNotifs.remove(sbn.key)) {
            // Only drop the rainbow once the package has no call notification
            // left: a ring->active swap reuses/changes the id and must not
            // flicker the LED off between the two.
            val still = callNotifs.any { it.split('|').getOrNull(1) == sbn.packageName }
            if (!still) send("VOIP_OFF ${sbn.packageName}\n")
        }
        send("CAN ${sbn.packageName} ${sbn.id}\n")
    }

    // ------------------------------------------------------------ call detect

    private val voipPkgs = setOf(
        "org.telegram.messenger",
        "com.whatsapp",
        "com.viber.voip",
        "org.thoughtcrime.securesms",
        "com.snapchat",
        "com.google.android.apps.tachyon"
    )

    /** Keys of notifications we classified as a live/incoming call, so the
     *  matching removal can be turned into a VOIP_OFF. */
    private val callNotifs = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Keys of notifications we classified as a live/incoming SIM call, so
     *  the matching removal can be turned into a RING_OFF. */
    private val ringNotifs = java.util.Collections.synchronizedSet(HashSet<String>())

    /** incoming (1) per live dialer call key; survives the removal path
     *  where the notification is gone and cannot be re-inspected. */
    private val ringIncoming = java.util.Collections.synchronizedMap(HashMap<String, Boolean>())

    /** The dialer package: its call notifications drive the ring rainbow. */
    private val dialerPkg = "com.google.android.dialer"

    /**
     * A live telephony (SIM) call notification from the dialer. What it is
     * NOT: a missed-call row (channel "missed_calls", no CALL category, no
     * answer/decline actions) - those must pass through as a plain ENQ so
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
     * category CALL, a "call"-ish channel id (Telegram "incoming_calls40",
     * WhatsApp "incoming_video_call"), or answer/decline/end-call actions.
     * A plain chat message matches none of these and takes the normal path.
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

    // ------------------------------------------------------------ loop

    /** One loop thread, ever. Rebind calls must not spawn more readers. */
    private fun startLoop() {
        synchronized(loopLock) {
            if (reconnector?.isAlive == true) return
            val t = Thread({ runLoop() }, "led-nls")
            t.isDaemon = true
            t.start()
            reconnector = t
        }
    }

    /** Start the supervisor thread with the current companionMs. */
    private fun startWatchdog() {
        companionMs = initialWatchdogMs()
        synchronized(loopLock) {
            applyWatchdogLocked()
        }
    }

    /** First cadence, read ONCE from the daemon's world-readable mirror
     *  at /data/local/tmp/lednls.status (which the daemon keeps fresh on
     *  connect/disconnect and after every GUI save). No su, no led.conf
     *  access; the daemon still pushes "WD <ms>" on connect, so this is
     *  only a starting value. */
    private fun initialWatchdogMs(): Long {
        return try {
            File(MIRROR_STATUS).readLines()
                .firstOrNull { it.startsWith("watchdog_ms=") }
                ?.substringAfter('=')?.trim()?.toLongOrNull()
                ?: DEFAULT_WATCHDOG_MS
        } catch (_: Exception) {
            DEFAULT_WATCHDOG_MS
        }
    }

    /**
     * Start the supervisor thread for the current companionMs. A live
     * supervisor with a positive cadence is left untouched (rebind storms
     * must not spawn duplicates); 0 disables and stops it.
     */
    private fun applyWatchdogLocked() {
        val cur = watchdog
        if (companionMs > 0) {
            if (cur?.isAlive == true) return  // already running
            val t = Thread({ watchLoop() }, "led-nls-watch")
            t.isDaemon = true
            t.start()
            watchdog = t
            android.util.Log.i("led-nls", "watchdog on, ${companionMs}ms")
        } else {
            cur?.interrupt()
            watchdog = null
            android.util.Log.i("led-nls", "watchdog off")
        }
    }

    /**
     * chgd owns the LED and the abstract socket; if it dies nothing blinks
     * and the reconnect loop just spins against a dead endpoint. The system
     * resurrects THIS process (notification listener rebind), so a supervisor
     * living here outlives any shell keepalive that nobody restarts.
     */
    private fun watchLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                ensureDaemon()
            } catch (_: Exception) {
                // no root yet / su denied: keep the supervisor alive,
                // retry next tick instead of crashing the process
            }
            try { Thread.sleep(companionMs) } catch (_: InterruptedException) { break }
        }
    }

    private fun ensureDaemon() {
        val pid = try {
            SuShell.exec("pidof chgd").trim()
        } catch (e: Exception) {
            ""                                   // no root (yet): retry next tick
        }
        if (pid.isNotEmpty()) return
        android.util.Log.w("led-nls", "chgd is dead, restarting via su")
        try {
            SuShell.exec(
                "setsid /data/adb/modules/led_hal_root/chgd " +
                    ">/data/local/tmp/chgd.err 2>&1 </dev/null &"
            )
        } catch (_: Exception) {
            // su not granted yet (KernelSU prompt pending): retry next tick
        }
    }

    private fun runLoop() {
        while (!Thread.currentThread().isInterrupted) {
            val ls = try {
                LocalSocket().also {
                    it.connect(
                        LocalSocketAddress("chgd_noty", LocalSocketAddress.Namespace.ABSTRACT)
                    )
                    // Block until EOF; never treat read-timeout as a drop.
                    it.soTimeout = 0
                    // Round-trip probe: triggers the daemon's accept log and
                    // confirms the write path before events can be dropped.
                    it.outputStream.write("PING\n".toByteArray(Charsets.UTF_8))
                    it.outputStream.flush()
                }
            } catch (_: Exception) {
                setConnected(false)
                Thread.sleep(2000L)      // daemon down: retry
                continue
            }
            cur = ls
            setConnected(true)
            android.util.Log.i("led-nls", "connected to chgd")
            replayState()
            drain(ls)
            android.util.Log.i("led-nls", "socket closed, reconnecting")
            // The daemon went away. Reclaim only if this is still our socket
            // (onDestroy may have swapped it, but it never swaps to a new one).
            if (cur === ls) cur = null
            setConnected(false)
            Thread.sleep(1000L)
        }
    }

    /** Block until EOF. The daemon is a text server too: our own PONG plus
     *  "WD <ms>" pushes (on connect, and after every GUI save) come back
     *  here. A read timeout just means "no traffic", keep waiting. */
    private fun drain(ls: LocalSocket) {
        try {
            val r = BufferedReader(InputStreamReader(ls.inputStream))
            while (true) {
                val line = try {
                    r.readLine()
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                if (line == null) break
                applyDaemonMessage(line)
            }
        } catch (_: Exception) {
        }
    }

    /** One daemon -> app message. Today: "WD <ms>" - the fresh [led]
     *  watchdog_ms (the GUI pokes the daemon with SIGALRM; the daemon, as
     *  root, is the only legitimate reader of the config it lives in). */
    private fun applyDaemonMessage(line: String) {
        if (!line.startsWith("WD ")) return
        val ms = line.substring(3).trim().toLongOrNull() ?: return
        android.util.Log.i("led-nls", "watchdog pushed from daemon: ${ms}ms")
        synchronized(loopLock) {
            if (ms != companionMs) {
                companionMs = ms
                applyWatchdogLocked()
            }
        }
    }

    // ------------------------------------------------------------ status

    private fun setConnected(v: Boolean) {
        if (connected == v) return
        connected = v
        android.util.Log.i("led-nls", "bridge state: $v")
    }

    // ------------------------------------------------------------ replay

    /**
     * After a reconnect (daemon restart / socket drop) the daemon has no
     * memory of the current state. Replay the SET of what is still live so
     * the caller re-arms: the rainbow modes (VOIP/RING) plus every active
     * notification key (so the daemon restores its id table and a pending
     * cancel can still disarm). Same ordering as on first post.
     */
    private fun replayState() {
        send("SCREEN ${if (isInteractive()) 1 else 0}\n")
        val ring = ArrayList(ringNotifs)
        val calls = ArrayList(callNotifs)
        val rest = getActiveNotifications(arrayOf()) ?: emptyArray()
        for (key in ring) {
            val incoming = ringIncoming[key] ?: false
            send("RING_ON ${if (incoming) 1 else 0}\n")
        }
        for (key in calls) {
            val parts = key.split('|')
            if (parts.size >= 2) send("VOIP_ON ${parts[1]}\n")
        }
        for (sbn in rest) {
            val key = sbn.key
            if (ring.contains(key) || calls.contains(key)) continue
            send("ENQ ${sbn.packageName} ${sbn.id}\n")
        }
    }

    // ------------------------------------------------------------ send

    private fun send(line: String) {
        val ls = cur ?: return          // drop events while reconnecting
        try {
            ls.outputStream.write(line.toByteArray(Charsets.UTF_8))
            ls.outputStream.flush()
        } catch (_: Exception) {
            // stale write: the loop thread notices EOF and cleans up
        }
    }

    companion object {
        private const val ACTION_POST_TEST = "com.bastet.lednls.POST_TEST"
        private const val MIRROR_STATUS = "/data/local/tmp/lednls.status"
        private const val DEFAULT_WATCHDOG_MS = 60000L
    }
}