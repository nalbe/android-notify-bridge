package com.bastet.notifybridge

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
import java.util.Collections

/**
 * NotificationListenerService - standalone headless notification bridge.
 *
 * Lives in its own APK (com.bastet.notifybridge). The whole forwarding policy
 * lives in an optional device-side config (/data/local/tmp/notifybridge.json):
 * which observed event goes where is a rule list, each rule rendering a
 * text line ("$pkg $id $key $incoming $on ...") into a configured sink
 * (abstract Unix socket, or logcat). No config file = built-in defaults
 * that reproduce the classic consumer contract:
 *   ENQ <pkg> <id> / CAN <pkg> <id>
 *   RING_ON <0|1> / RING_OFF
 *   VOIP_ON <pkg> / VOIP_OFF <pkg>
 *   SCREEN <0|1>, PULSE <0|1>
 *
 * Observed bus is a superset of the old transport: notify / ring / voip /
 * screen / pulse events with normalized fields; SIM- and messenger-call
 * classification stays built-in (the package lists are config), because a
 * raw notification carries no "this is a call" marker.
 *
 * This app is a pure transport: it never supervises, restarts or otherwise
 * reaches into any consumer (daemon). The config is re-read only when
 * asked to: on service (re)start or on the RELOAD_CONFIG broadcast.
 *
 * Threading: the system may call onListenerConnected() repeatedly (GSI
 * rebinds, process freezes). Each socket sink owns one reconnect loop
 * thread created once; onListenerConnected is then a no-op. Only the loop
 * thread closes its own socket, so a stale reader can never close a fresh
 * connection.
 */
class NotificationBridgeService : NotificationListenerService() {

    /** Current resolved config; swapped atomically on reload. */
    @Volatile
    private var config: BridgeConfig = BridgeConfig.DEFAULT

    /** Live sink registry; rebuilt on reload. */
    @Volatile
    private var sinks: SinkRegistry? = null

    private var pulseObserver: ContentObserver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private val loopLock = Object()

    private var lastPulseSent = -1

    override fun onListenerConnected() {
        applyConfig()
        startSinks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_POST_TEST) postTestNotification()
        return START_STICKY
    }

    private fun postTestNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel("bridgetest") == null) {
            nm.createNotificationChannel(
                NotificationChannel("bridgetest", "Bridge test", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        nm.notify(
            4242,
            Notification.Builder(this, "bridgetest")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Bridge self-test")
                .setContentText("ENQ should hit the socket consumer and arm the bridge output")
                .build()
        )
        android.util.Log.i("notifybridge", "self-test notification posted")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerPulseObserver()
        registerScreenReceiver()
    }

    // ------------------------------------------------------------ config

    /** Load (or reload) config and rebuild sinks/rules. Triggered on service
     *  (re)start and on every RELOAD_CONFIG broadcast - no polling. */
    private fun applyConfig() {
        val cfg = BridgeConfig.load()
        synchronized(loopLock) {
            val old = sinks
            if (old != null) old.stopAll()
            sinks = SinkRegistry(cfg, onSocketConnect).also { it.startAll() }
            config = cfg
        }
        android.util.Log.i("notifybridge",
            "config: ${cfg.rules.size} rules, ${cfg.sinks.size} sinks")
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
            android.util.Log.i("notifybridge", "screen receiver on")
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
            android.util.Log.i("notifybridge", "pulse observer on Settings.System")
        }
    }

    /** Forward a toggle change as a pulse event; dropped while no sink is
     *  connected - the consumer re-reads the toggle itself on its next
     *  arm, and the connect replay covers a restart. */
    private fun forwardPulse(on: Boolean) {
        val v = if (on) 1 else 0
        if (lastPulseSent == v) return        // dedupe Settings exit ack storms
        lastPulseSent = v
        emit(BridgeEvent("pulse", if (on) "on" else "off", on = on))
        if (!on && sinks?.anySocketConnected() == false)
            android.util.Log.w("notifybridge", "pulse=0 but no socket; disarm missed")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        synchronized(loopLock) {
            pulseObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
            pulseObserver = null
            screenReceiver?.let { runCatching { unregisterReceiver(it) } }
            screenReceiver = null
        }
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
            EventRouter.mirror(e, cfg)
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

    // ------------------------------------------------------------ loop

    private fun startSinks() {
        sinks?.startAll()
    }

    // ------------------------------------------------------------ events

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        android.util.Log.i("notifybridge", "posted ${sbn.packageName} id=${sbn.id} key=${sbn.key}")
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
        android.util.Log.i("notifybridge", "removed ${sbn.packageName} id=${sbn.id}")
        if (ringNotifs.remove(sbn.key)) {
            ringIncoming.remove(sbn.key)
            // Drop the rainbow only once the dialer has no live call left:
            // a ringing->ongoing swap / decline->redial reuses ids and must
            // not flicker the output off between the two.
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
     * the consumer's missed-call verification can claim them.
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
        private const val ACTION_POST_TEST = "com.bastet.notifybridge.POST_TEST"

        /** Live service instance while the process is up; null when dead.
         *  ReloadReceiver pokes this directly - no startService (blocked
         *  from a background receiver on Android 8+, and a dead process
         *  applies its config on the first bind anyway). */
        @Volatile
        private var instance: NotificationBridgeService? = null

        fun pokeReload() {
            // No mtime optimisation needed: reloads are command-only now,
            // so every broadcast unconditionally re-reads + rebuilds.
            val s = instance ?: return
            s.applyConfig()
        }
    }
}