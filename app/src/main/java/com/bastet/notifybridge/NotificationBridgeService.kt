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
 * /data/local/tmp/notifybridge.d, see BridgeConfig). Each event source is
 * its own config section - notifications, broadcasts, settings - that
 * both declares what it listens to and how to route what it yields, plus
 * an optional global routes table matched across every source. A route
 * renders a text line ("$pkg $id $key $incoming $on ...") into a
 * configured sink (abstract Unix socket, or logcat). No config at all =
 * the bridge is fully passive: no sinks, no routes, no observers,
 * nothing sent.
 *
 * Observed bus: notify / call / missed / screen / pulse / battery events
 * with normalized fields. Call classification runs on any package by
 * notification markers (category CALL, "call"-ish channel, answer /
 * decline / reject / end-call actions), because a raw notification
 * carries no "this is a call" marker; SIM-versus-VOIP rendering is a
 * route concern ([Route.pkg]), not a code concern. Settings toggles
 * (the LED switch and anything else you want as a 0/1 bus event) are
 * config too - see BridgeConfig.settings, nothing is hidden in code.
 * System broadcasts (screen, plug/unplug, battery - any action the
 * framework broadcasts) are config-driven as BridgeConfig.broadcasts:
 * each entry registers one intent action and renders its extras into
 * the bus event, no hardcoded action lives in this file. A broadcast
 * entry is off by removing it from the config; the notifications
 * source is off by dropping the notifications section or setting
 * enabled:false; the RELOAD_CONFIG reload is gated by
 * BridgeConfig.reload.
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
    private var broadcastReceiver: BroadcastReceiver? = null
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
        android.util.Log.i(BLog.CORE, "self-test notification posted")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    // ------------------------------------------------------------ config

    /** Load (or reload) config and rebuild sinks/routes. Triggered on service
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
                teardownBroadcastReceiver()
                val why = if (cfg == null) "absent/invalid" else "no active source"
                android.util.Log.i(BLog.CORE, "config $why: passive")
                return
            }
            val old = sinks
            if (old != null) old.stopAll()
            sinks = SinkRegistry(cfg, onSocketConnect).also { it.startAll() }
            config = cfg
            active = cfg
            ensureBroadcastReceivers(cfg)
            syncSettingObservers(cfg)
        }
        val a = active
        android.util.Log.i(BLog.CORE,
            "config active: ${a?.routes?.size} routes, ${a?.sinks?.size} sinks, " +
                "${a?.settings?.size} watched settings")
    }

    // ------------------------------------------------------------ sources

    /** System broadcasts -> bus events. One dynamic receiver is registered
     *  for every action listed in BridgeConfig.broadcasts; the filter is
     *  rebuilt on each config apply, so an action removed from the config
     *  stops matching immediately. A fixed polarity ("on"/"off" - also
     *  sets e.on) wins over eventAction, which wins over the raw intent
     *  action; registered extras are forwarded as $vars (see
     *  BridgeEvent.fields). An empty broadcasts list leaves no receiver
     *  registered at all. */
    private fun ensureBroadcastReceivers(cfg: BridgeConfig) {
        synchronized(loopLock) {
            teardownBroadcastReceiver()
            if (cfg.broadcasts.isEmpty()) {
                android.util.Log.i(BLog.BCAST, "broadcasts disabled by config")
                return
            }
            val r = object : BroadcastReceiver() {
                @Suppress("DEPRECATION")
                override fun onReceive(c: Context?, i: Intent?) {
                    val intent = i ?: return
                    val live = this@NotificationBridgeService.config ?: return
                    val bc = live.broadcasts.firstOrNull { it.action == intent.action } ?: return
                    val whenPol = bc.polarity
                    val on = whenPol ?: false
                    val ea = when {
                        whenPol != null -> if (whenPol) "on" else "off"
                        bc.eventAction != null -> bc.eventAction
                        else -> intent.action ?: ""
                    }
                    val extras = intent.extras
                    emit(BridgeEvent(
                        type = bc.event,
                        action = ea,
                        on = on,
                        source = BLog.SRC_BROADCAST,
                        fields = bc.fields.mapValues { (k, _) ->
                            val v = extras?.get(k) ?: return@mapValues ""
                            v.toString()
                        }
                    ))
                }
            }
            val f = IntentFilter().apply { cfg.broadcasts.forEach { addAction(it.action) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            else
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(r, f)
            broadcastReceiver = r
            android.util.Log.i(BLog.BCAST,
                "broadcast receiver: ${cfg.broadcasts.size} actions")
        }
    }

    private fun teardownBroadcastReceiver() {
        synchronized(loopLock) {
            broadcastReceiver?.let { runCatching { unregisterReceiver(it) } }
            broadcastReceiver = null
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
     *  Rebuilt on every config apply - a reload alone picks up settings
     *  changes, no service restart. Empty settings list = no observers. */
    private fun syncSettingObservers(cfg: BridgeConfig) {
        synchronized(loopLock) {
            teardownSettingObservers()
            if (cfg.settings.isEmpty()) {
                android.util.Log.i(BLog.SETTINGS, "watched settings disabled by config")
                return
            }
            lastSettingSent.keys.retainAll(
                cfg.settings.map { "${it.table}/${it.name}" }
            )
            val byTable = cfg.settings.groupBy { it.table }
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
            android.util.Log.i(BLog.SETTINGS,
                "watched settings: ${cfg.settings.size} on ${byTable.keys.sorted()}")
        }
    }

    /** Forward a toggle change as its mapped event (see WatchedSetting).
     *  Dropped while no sink is connected - the connect replay sends the
     *  current polarity to every freshly connected consumer, so nothing
     *  is lost on restart. */
    private fun forwardSetting(table: String, name: String, cfg: BridgeConfig) {
        val ws = cfg.settings.firstOrNull { it.table == table && it.name == name }
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
        emit(BridgeEvent(ws.event, if (on) "on" else "off", on = on, setting = name,
            source = BLog.SRC_SETTINGS))
        if (!on && sinks?.anySocketConnected() == false)
            android.util.Log.w(BLog.SETTINGS,
                "${ws.event}=0 but no socket; disarm missed")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        synchronized(loopLock) {
            teardownSettingObservers()
            teardownBroadcastReceiver()
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

    /** Render every matching route into [sink] only (replay on connect). */
    @Suppress("DEPRECATION")
    private fun replayInto(sink: Sink, cfg: BridgeConfig) {
        val rg = sinks ?: return
        val actives = getActiveNotifications(arrayOf()) ?: emptyArray()
        val callKeys = java.util.HashSet(callNotifs)

        fun fwd(e: BridgeEvent) {
            EventRouter.emitInto(e, cfg, rg, sink.name)
        }
        // Screen polarity first: each snapshot-flagged screen entry whose
        // polarity equals the live state re-emits it through its routes.
        val screenOn = isInteractive()
        for (bc in cfg.broadcasts) {
            if (!bc.snapshot) continue
            val pol = bc.polarity ?: screenOn
            if (pol != screenOn) continue
            fwd(BridgeEvent(bc.event, if (screenOn) "on" else "off", on = screenOn,
                source = BLog.SRC_BROADCAST))
        }
        // Watched settings replay next: like screen, a freshly connected
        // consumer must learn the CURRENT toggle state without reading the
        // settings itself (that is why the daemon never touches Settings.*).
        // Same polarity contract as forwardSetting(); the dedupe map is not
        // touched so a real change later still gets forwarded.
        for (ws in cfg.settings) {
            val raw = when (ws.table) {
                "system" -> Settings.System.getString(contentResolver, ws.name)
                "global" -> Settings.Global.getString(contentResolver, ws.name)
                "secure" -> Settings.Secure.getString(contentResolver, ws.name)
                else -> null
            }
            val on = when {
                raw == null -> ws.defaultOn
                else -> runCatching { raw.toInt() != 0 }.getOrDefault(ws.defaultOn)
            }
            fwd(BridgeEvent(ws.event, if (on) "on" else "off", on = on,
                setting = ws.name, source = BLog.SRC_SETTINGS))
        }
        for (key in callKeys) {
            val parts = key.split('|')
            fwd(BridgeEvent("call", "on", pkg = parts.getOrElse(1) { "" },
                incoming = callIncoming[key] ?: false, source = BLog.SRC_NOTIFY))
        }
        val missedKeys = java.util.HashSet(missedNotifs)
        for (key in missedKeys) {
            val parts = key.split('|')
            fwd(BridgeEvent("missed", "on", pkg = parts.getOrElse(1) { "" },
                key = key, source = BLog.SRC_NOTIFY))
        }
        for (sbn in actives) {
            val key = sbn.key
            if (callKeys.contains(key) || missedKeys.contains(key)) continue
            if (hasLiveCall(sbn.packageName)) continue
            fwd(BridgeEvent("notify", "posted", pkg = sbn.packageName, id = sbn.id, key = key,
                source = BLog.SRC_NOTIFY))
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
        if (cfg.notifications?.enabled != true) return   // notifications: source off
        android.util.Log.i(BLog.NOTIFY, "posted ${sbn.packageName} id=${sbn.id} key=${sbn.key}")
        if (isMissedCallNotification(sbn)) {
            missedNotifs.add(sbn.key)
            emit(BridgeEvent("missed", "on", pkg = sbn.packageName, id = sbn.id,
                key = sbn.key, source = BLog.SRC_NOTIFY))
        } else if (isCallNotification(sbn)) {
            val incoming = callIsIncoming(sbn)
            callNotifs.add(sbn.key)
            callIncoming[sbn.key] = incoming
            // CALL before any notify ping so a consumer painting from the
            // raw pool keeps deferring to the live call.
            emit(BridgeEvent("call", "on", pkg = sbn.packageName, id = sbn.id,
                incoming = incoming, source = BLog.SRC_NOTIFY))
        }
        // A package on a live call sends no raw notify ping: call.on
        // already carries the whole picture and a ping would only
        // confuse the consumer's pool. notify.* resumes the moment the
        // last call key disappears - no package lists needed.
        if (!hasLiveCall(sbn.packageName)) {
            emit(BridgeEvent("notify", "posted", pkg = sbn.packageName, id = sbn.id,
                key = sbn.key, source = BLog.SRC_NOTIFY))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val cfg = config ?: return      // passive: no bus, no bookkeeping
        if (cfg.notifications?.enabled != true) return   // notifications: source off
        android.util.Log.i(BLog.NOTIFY, "removed ${sbn.packageName} id=${sbn.id}")
        if (missedNotifs.remove(sbn.key)) {
            val still = missedNotifs.any { it.split('|').getOrNull(1) == sbn.packageName }
            if (!still) emit(BridgeEvent("missed", "off", pkg = sbn.packageName,
                source = BLog.SRC_NOTIFY))
        }
        if (callNotifs.remove(sbn.key)) {
            callIncoming.remove(sbn.key)
            // Drop the call pulse only once the package has no live call
            // left: a ringing->ongoing swap / decline->redial reuses ids
            // and must not flicker the output off between the two.
            val still = callNotifs.any { it.split('|').getOrNull(1) == sbn.packageName }
            if (!still) emit(BridgeEvent("call", "off", pkg = sbn.packageName,
                source = BLog.SRC_NOTIFY))
        }
        if (!hasLiveCall(sbn.packageName)) {
            emit(BridgeEvent("notify", "removed",
                pkg = sbn.packageName, id = sbn.id, key = sbn.key,
                source = BLog.SRC_NOTIFY))
        }
    }

    // ------------------------------------------------------------ call detect

    /** Keys of notifications classified as a live call (telephony or
     *  messenger VOIP, any package). Forwarded as call.on / call.off. */
    private val callNotifs = Collections.synchronizedSet(HashSet<String>())
    /** Keys of missed-call tombstones (channel contains "missed").
     *  Forwarded as missed.on / missed.off so the consumer needs no
     *  call_log read - this IS the classification the consumer used to
     *  re-derive by querying content://call_log. */
    private val missedNotifs = Collections.synchronizedSet(HashSet<String>())

    /** incoming (1) per live call key; survives removal where the
     *  notification is gone and cannot be re-inspected. */
    private val callIncoming = Collections.synchronizedMap(HashMap<String, Boolean>())

    /** True while [pkg] holds any live call key. Drives the notify.*
     *  suppression: during a call the raw ping stays off the bus. */
    private fun hasLiveCall(pkg: String): Boolean =
        callNotifs.any { it.split('|').getOrNull(1) == pkg }

    /**
     * A live call notification (SIM telephony or messenger VOIP) by the
     * strongest available markers: category CALL, a "call"-ish channel
     * id, or answer/decline/reject/end-call actions. Classification runs
     * on any package - SIM-versus-VOIP rendering is a route ([Route.pkg])
     * concern. A missed-call tombstone (channel contains "missed", which
     * ALSO contains "call") is excluded - [isMissedCallNotification] owns
     * those.
     */
    private fun isCallNotification(sbn: StatusBarNotification): Boolean {
        if (isMissedCallNotification(sbn)) return false
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

    /** A missed-call tombstone (channel contains "missed"). The channel
     *  id is the reliable marker (it already separated these from live
     *  calls for the old consumer verification). */
    private fun isMissedCallNotification(sbn: StatusBarNotification): Boolean {
        return (sbn.notification.channelId ?: "").lowercase().contains("missed")
    }

    /** 1 = incoming (answer/decline available), 0 = outgoing/ongoing. */
    private fun callIsIncoming(sbn: StatusBarNotification): Boolean {
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

    companion object {
        private const val ACTION_POST_TEST = "com.bastet.notifybridge.POST_TEST"

        /** Live service instance while the process is up; null when dead.
         *  ReloadReceiver pokes this directly - no startService (blocked
         *  from a background receiver on Android 8+, and a dead process
         *  applies its config on the first bind anyway). */
        @Volatile
        private var instance: NotificationBridgeService? = null

        fun pokeReload() {
            // A config that disabled listening survives the poke: gated on
            // reload, the running config stays frozen until the service
            // (re)starts or the flag is re-enabled in a file.
            val s = instance ?: return
            val live = s.config
            if (live != null && !live.reload) {
                android.util.Log.i(BLog.CORE,
                    "reload disabled by reload=false, keeping config")
                return
            }
            s.applyConfig()
        }
    }
}