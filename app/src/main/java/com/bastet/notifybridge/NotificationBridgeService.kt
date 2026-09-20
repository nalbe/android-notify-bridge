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
 * Lives in its own APK (com.bastet.notifybridge). The whole forwarding
 * policy lives in optional device-side config fragments
 * (/data/local/tmp/notifybridge.json plus json files inside
 * /data/local/tmp/notifybridge.d, see BridgeConfig): each observed event
 * goes where the merged rule list says, each rule rendering a text line
 * ("$pkg $id $key $incoming $on ...") into a configured sink (abstract
 * Unix socket, or logcat). No config at all = the bridge is fully
 * passive: no sinks, no rules, no observers, nothing sent.
 *
 * Observed bus is a superset of the old transport: notify / ring / voip /
 * screen / pulse events with normalized fields; SIM- and messenger-call
 * classification stays built-in (the package lists are config), because a
 * raw notification carries no "this is a call" marker. Settings toggles
 * (the LED switch and anything else you want as a 0/1 bus event) are
 * config too - see BridgeConfig.watchedSettings, nothing is hidden in
 * code.
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

    /** Current resolved config; null = passive (no file or invalid).
     *  Swapped atomically on reload. */
    @Volatile
    private var config: BridgeConfig? = null

    /** Live sink registry; rebuilt on reload. Null while passive. */
    @Volatile
    private var sinks: SinkRegistry? = null

    private var settingObservers: MutableMap<String, ContentObserver>? = null
    private var screenReceiver: BroadcastReceiver? = null
    private val loopLock = Object()

    private val lastSettingSent = HashMap<String, Boolean>()

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
    }

    // ------------------------------------------------------------ config

    /** Load (or reload) config and rebuild sinks/rules. Triggered on service
     *  (re)start and on every RELOAD_CONFIG broadcast - no polling. A
     *  missing or broken config means fully passive: sinks stopped, all
     *  observers and receivers unregistered, no classification state kept. */
    private fun applyConfig() {
        val cfg = BridgeConfig.load()
        var active: BridgeConfig? = null
        synchronized(loopLock) {
            if (cfg == null || !cfg.isActive) {
                sinks?.stopAll()
                sinks = null
                config = null
                teardownSettingObservers()
                teardownScreenReceiver()
                val why = if (cfg == null) "absent/invalid" else "empty, no rules"
                android.util.Log.i("notifybridge", "config $why: passive")
                return
            }
            val old = sinks
            if (old != null) old.stopAll()
            sinks = SinkRegistry(cfg, onSocketConnect).also { it.startAll() }
            config = cfg
            active = cfg
            ensureScreenReceiver()
            syncSettingObservers(cfg)
        }
        val a = active
        android.util.Log.i("notifybridge",
            "config active: ${a?.rules?.size} rules, ${a?.sinks?.size} sinks, " +
                "${a?.watchedSettings?.size} watched settings")
    }

    // ------------------------------------------------------------ sources

    /** ACTION_SCREEN_OFF/ON -> screen event. Register/unregister strictly
     *  follows config lifecyle: passive bridge has no receiver. */
    private fun ensureScreenReceiver() {
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

    private fun teardownScreenReceiver() {
        synchronized(loopLock) {
            screenReceiver?.let { runCatching { unregisterReceiver(it) } }
            screenReceiver = null
        }
    }

    /** Trusted live screen state from the framework (not sysfs). */
    private fun isInteractive(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    /** Unregister every settings observer; called on teardown (passive
     *  config) and at the start of every resync. */
    private fun teardownSettingObservers() {
        synchronized(loopLock) {
            settingObservers?.values?.forEach {
                runCatching { contentResolver.unregisterContentObserver(it) }
            }
            settingObservers = null
        }
    }

    /** Watch every Settings key listed in the config (tables system /
     *  global / secure, one ContentObserver per table). Any writer to a
     *  watched key trips its table observer and we forward the polarity.
     *  Rebuilt on every config apply - a reload alone picks up
     *  watchedSettings changes, no service restart. */
    private fun syncSettingObservers(cfg: BridgeConfig) {
        synchronized(loopLock) {
            teardownSettingObservers()
            lastSettingSent.keys.retainAll(
                cfg.watchedSettings.map { "${it.table}/${it.name}" }
            )
            if (cfg.watchedSettings.isEmpty()) return
            val byTable = cfg.watchedSettings.groupBy { it.table }
            val obs = HashMap<String, ContentObserver>()
            for ((table, settings) in byTable) {
                val uri = when (table) {
                    "system" -> Settings.System.CONTENT_URI
                    "global" -> Settings.Global.CONTENT_URI
                    "secure" -> Settings.Secure.CONTENT_URI
                    else -> null
                } ?: continue
                val active = settings.map { it.name }.toSet()
                val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        val name = uri?.lastPathSegment ?: return
                        if (name !in active) return
                        forwardSetting(table, name, cfg)
                    }
                }
                contentResolver.registerContentObserver(uri, true, o)
                obs[table] = o
            }
            settingObservers = obs
            android.util.Log.i("notifybridge",
                "watched settings: ${cfg.watchedSettings.size} on ${byTable.keys.sorted()}")
        }
    }

    /** Forward a toggle change as its mapped event (see WatchedSetting);
     *  dropped while no sink is connected - the consumer re-reads the
     *  toggle itself on its next arm, and the connect replay covers a
     *  restart. */
    private fun forwardSetting(table: String, name: String, cfg: BridgeConfig) {
        val ws = cfg.watchedSettings.firstOrNull { it.table == table && it.name == name }
            ?: return
        val raw = when (table) {
            "system" -> Settings.System.getString(contentResolver, name)
            "global" -> Settings.Global.getString(contentResolver, name)
            "secure" -> Settings.Secure.getString(contentResolver, name)
            else -> null
        }
        val on = when {
            raw == null -> ws.defaultOn
            else -> runCatching { raw.toInt() != 0 }.getOrDefault(ws.defaultOn)
        }
        val k = "$table/$name"
        if (lastSettingSent[k] == on) return   // dedupe Settings exit ack storms
        lastSettingSent[k] = on
        emit(BridgeEvent(ws.event, if (on) "on" else "off", on = on, setting = name))
        if (!on && sinks?.anySocketConnected() == false)
            android.util.Log.w("notifybridge",
                "${ws.event}=0 but no socket; disarm missed")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        synchronized(loopLock) {
            teardownSettingObservers()
            teardownScreenReceiver()
        }
        sinks?.stopAll()
        super.onDestroy()
    }

    // ------------------------------------------------------------ routing

    private fun emit(e: BridgeEvent) {
        val cfg = config ?: return
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
        if (cfg != null) replayInto(sink, cfg)
    }

    // ------------------------------------------------------------ loop

    private fun startSinks() {
        sinks?.startAll()
    }

    // ------------------------------------------------------------ events

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val cfg = config ?: return      // passive: nothing classified, nothing kept
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
        val cfg = config ?: return      // passive: no bus, no bookkeeping
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

    private val dialerPkg: String get() = config?.dialerPkg ?: ""
    private val voipPkgs: Set<String> get() = config?.voipPkgs ?: emptySet()

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