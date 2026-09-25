package com.bastet.notifybridge

import org.json.JSONObject
import java.io.File

/**
 * Runtime bridge configuration.
 *
 * Optional JSON at [CONFIG_PATH] ("/data/local/tmp/notifybridge.json",
 * adb-writable) turns the bridge into a routing engine: each observed
 * event is matched against [Route]s and forwarded to a [SinkCfg] as a
 * rendered text line.
 *
 * The config is organized as self-contained sections - one per event
 * source. A section both says what it listens to and how to route what
 * it yields:
 *
 *  - [notifications]  NotificationListenerService: posted/removed plus
 *    call classification (call.* / missed.*, markers on any package).
 *    Optional, disabled when absent. Routing lives in
 *    `notifications.out`.
 *  - [broadcasts]     System broadcasts (screen, plug/unplug, battery -
 *    any action the framework broadcasts), one entry per intent action.
 *    Each entry carries its own `out` routes.
 *  - [settings]       Settings toggles observed via ContentObservers on
 *    system/global/secure, one entry per key, each with its own `out`.
 *  - [routes]         Optional global routing table. Its `when` patterns
 *    are matched across every source (this is the "raw tube": a route
 *    with `when: "*"` mirrors everything into one sink). Section routes
 *    are scope-confined, global routes are not.
 *
 * Every route uses one `when` grammar: "<type>.<action>" where either
 * side may be "*" (e.g. "notify.posted", "call.*", "*.on", "*"). The
 * notification vocabulary is fixed (notify/call/missed x
 * posted/removed/on/off) and strictly validated, so a typo never
 * silently becomes a dead route.
 *
 * On a socket connect the bridge replays the live state (screen
 * polarity, active calls, active notifications) through the same
 * routes into the freshly connected sink. Broadcast entries flagged
 * `snapshot: true` feed the screen-state replay.
 *
 * Configuration is drop-in: every *.json in [CONFIG_DIR]
 * ("/data/local/tmp/notifybridge.d/") is merged with the main file
 * (main first, fragments alphabetical). Merge keeps one entry per key:
 * sinks by name, broadcasts by action, settings by table/name,
*  notifications.out by route key, global routes collapse exact
 *  duplicates (last wins); logAll OR, reload AND. A broken fragment is
 *  skipped and logged, it never kills the rest.
 *
 * WITHOUT any config file the bridge is fully passive: no sinks, no
 * observers, no classification - nothing to do, nothing sent. There are
 * no built-in default routes: the config file IS the whole policy, a bad
 * or absent file just means "stay quiet". A fresh example lives in the
 * project at config/notifybridge.example.json (see
 * config/deploy-config.ps1).
 *
 * Re-read happens on the RELOAD_CONFIG broadcast (or when the service
 * (re)starts) - no polling. A parse failure yields null and drops the
 * bridge to fully passive (all sinks/observers torn down) until the
 * config is fixed and re-applied. There is no stale-config fallback:
 * a broken file means "stay quiet", loudly logged.
 */
class BridgeConfig(
    /** Delivery endpoints. type = "socket" (abstract Unix domain) or
     *  "logcat" (debug mirror). name = sink key; for a socket it is also
     *  the abstract socket name. */
    val sinks: List<SinkCfg>,
    /** Discovery mode. When true every normalized event on the bus is
     *  mirrored to logcat (tag = event source, prefix "EVENT") before
     *  route matching - independent of sinks/routes. Watch it to learn
     *  exactly what to put into routes / out lists / call pkg filters. */
    val logAll: Boolean,
    /** Whether the RELOAD_CONFIG broadcast is honored. Absent = true. */
    val reload: Boolean,
    /** Notification source. Null or disabled = the NLS is not listened
     *  to (no classification, no bookkeeping). */
    val notifications: NotificationsCfg?,
    /** Settings watched for user-visible toggle changes. Each maps a
     *  Settings table entry to an event type on the bus and carries the
     *  routes that forward its on/off changes. */
    val settings: List<SettingCfg>,
    /** System broadcasts observed via one dynamic receiver. Each entry
     *  registers one intent action, emits a bus event and carries the
     *  routes that forward it. Covers screen, plug/unplug, battery level
     *  - anything the framework broadcasts, never a hardcoded action. */
    val broadcasts: List<BroadcastCfg>,
    /** Global routing table, matched across every source. */
    private val globalRoutes: List<Route>
) {

    /** A delivery endpoint; type "socket" or "logcat". */
    data class SinkCfg(val type: String, val name: String) {
        val isSocket: Boolean get() = type == "socket"
    }

    /** The notification source: [enabled] gate and the section-local
     *  [routes]. Call classification runs on any package - the markers
     *  (category CALL / "call"-ish channel / answer-decline actions) are
     *  built in; which package renders as a SIM call (RING_*) versus a
     *  VOIP call is decided per route by its [Route.pkg]. Route scope is
     *  confined to notify/call/missed events. */
    data class NotificationsCfg(
        val enabled: Boolean,
        val routes: List<Route>
    )

    /** One observed Settings entry. [table] = system|global|secure,
     *  [name] = settings key, [event] = bus event type to emit (default
     *  "pulse"), [defaultOn] = polarity when the key is missing -
     *  unreadable (the classic LED toggle reads as on). A change emits
     *  BridgeEvent(event, "on"/"off", on = polarity, setting = name)
     *  through routes scoped to [event]. */
    data class SettingCfg(
        val table: String,
        val name: String,
        val event: String,
        val defaultOn: Boolean,
        val routes: List<Route>
    )

    /** One observed system broadcast. [action] = intent action to listen
     *  for, [event] = bus event type emitted. The bus action is
     *  [polarity] ("on"/"off" - also sets e.on) when set, else
     *  [eventAction], else the raw intent action; a raw action carries
     *  on = false. [snapshot] = true means: on socket connect re-emit
     *  the current screen state (PowerManager) as this event. [fields]
     *  maps intent extras (BatteryManager.EXTRA_* etc.) to $vars; a
     *  field key identical to a built-in var is skipped. Routes are
     *  scoped to [event]. */
    data class BroadcastCfg(
        val action: String,
        val event: String,
        val eventAction: String?,
        val polarity: Boolean?,
        val snapshot: Boolean,
        val fields: Map<String, String>,
        val routes: List<Route>
    )

    /** One forwarding route. [when] matches "<type>.<action>", each side
     *  "*" wildcard; sole "*" matches everything. [pkg] = exact package,
     *  "prefix*" glob or "*" (meaningful for notification events).
     *  [scope] confines the route to a fixed set of event types - set by
     *  the owning section, null for global routes. [to] = sink key,
     *  [line] = template with $vars to forward. Ever matching route fires
     *  (routing is a fan-out): to split one event per package, list
     *  specific-pkg routes and keep '*' routes for the same when off that
     *  sink, or the package matches both and the sink gets two lines. */
    data class Route(
        val whenPat: String,
        val pkg: String,
        val to: String,
        val line: String,
        val scope: Set<String>?
    ) {
        fun matches(e: BridgeEvent): Boolean {
            if (scope != null && e.type !in scope) return false
            if (pkg != "*") {
                if (pkg.endsWith("*")) {
                    if (!e.pkg.startsWith(pkg.dropLast(1))) return false
                } else if (pkg != e.pkg) return false
            }
            return whenMatches(whenPat, e)
        }

        companion object {
            fun whenMatches(pat: String, e: BridgeEvent): Boolean {
                if (pat == "*") return true
                val p = pat.split(".", limit = 2)
                if (p.size != 2) return pat == "${e.type}.${e.action}"
                if (p[0] != "*" && p[0] != e.type) return false
                if (p[1] != "*" && p[1] != e.action) return false
                return true
            }
        }
    }

    /** Whether the config asks the bridge to do anything at all. Any
     *  enabled source keeps the bridge alive - so logAll discovery works
     *  without a single route. */
    val isActive: Boolean
        get() = (notifications?.enabled == true) ||
            broadcasts.isNotEmpty() || settings.isNotEmpty()

    val socketCount: Int get() = sinks.count { it.isSocket }

    /** Every route the bus should try, sections first then global,
     *  exact duplicates collapsed. Flattened from the RESOLVED sections,
     *  so a fragment that replaces a broadcast/setting entry drops the
     *  replaced entry's routes with it. */
    val routes: List<Route> by lazy {
        val out = ArrayList<Route>()
        notifications?.routes?.let { out.addAll(it) }
        broadcasts.forEach { out.addAll(it.routes) }
        settings.forEach { out.addAll(it.routes) }
        out.addAll(globalRoutes)
        distinctLast(out) { "${it.whenPat}|${it.pkg}|${it.to}|${it.line}" }
    }

    companion object {
        const val CONFIG_PATH = "/data/local/tmp/notifybridge.json"
        const val CONFIG_DIR = "/data/local/tmp/notifybridge.d"
        private const val SINK_LOG = "log"
        private val SETTING_TABLES = setOf("system", "global", "secure")

        private val NOTIFY_TYPES = setOf("notify", "call", "missed")
        private val NOTIFY_ACTIONS = setOf("posted", "removed", "on", "off")
        private val NOTIFY_SCOPE = NOTIFY_TYPES

        /** Default line for a route without an explicit [line]. */
        const val DEFAULT_LINE = "\$type \$action \$pkg \$id"

        /** The config file plus every *.json fragment in [CONFIG_DIR]
         *  (alphabetical), the main file first. Missing pieces are simply
         *  absent from the list. */
        private fun orderedFragments(): List<File> {
            val list = ArrayList<File>()
            val main = File(CONFIG_PATH)
            if (main.exists()) list.add(main)
            val dir = File(CONFIG_DIR)
            if (dir.isDirectory) {
                val extra = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                if (extra != null) list.addAll(extra.sortedBy { it.name })
            }
            return list
        }

        /** Read every fragment and merge them. null = no config at all
         *  (nothing to do, bridge stays passive). A broken fragment is
         *  logged with its path and skipped - it never kills the rest. */
        fun load(): BridgeConfig? {
            val files = orderedFragments()
            if (files.isEmpty()) return null
            var merged: BridgeConfig? = null
            for (f in files) {
                val c = try {
                    parse(JSONObject(f.readText()))
                } catch (t: Throwable) {
                    android.util.Log.e(BLog.CORE,
                        "fragment ${f.absolutePath} invalid, skipped: $t")
                    null
                } ?: continue
                merged = if (merged == null) c else merge(merged, c)
            }
            return merged
        }

        /** Merge two fragments, first fragment keeping priority for
         *  single-value fields, later ones overriding keyed repeats:
         *  sinks one per name (last wins); broadcasts one per action
         *  (last wins); settings one per table/name (last wins);
         *  notifications.out by route key (append); global routes exact
         *  duplicates collapse (last wins); logAll OR; reload AND. */
        private fun merge(a: BridgeConfig, b: BridgeConfig): BridgeConfig =
            BridgeConfig(
                sinks = keyedLast(a.sinks + b.sinks, { it.name }),
                logAll = a.logAll || b.logAll,
                reload = a.reload && b.reload,
                notifications = mergeNotifications(a.notifications, b.notifications),
                settings = keyedLast(a.settings + b.settings) { "${it.table}/${it.name}" },
                broadcasts = keyedLast(a.broadcasts + b.broadcasts) { it.action },
                globalRoutes = distinctLast(a.globalRoutes + b.globalRoutes) { routeKey(it) }
            )

        private fun mergeNotifications(
            a: NotificationsCfg?, b: NotificationsCfg?
        ): NotificationsCfg? {
            if (a == null) return b
            if (b == null) return a
            return NotificationsCfg(
                enabled = a.enabled && b.enabled,
                routes = distinctLast(a.routes + b.routes) { routeKey(it) }
            )
        }

        private fun routeKey(r: Route): String =
            "${r.whenPat}|${r.pkg}|${r.to}|${r.line}"

        /** Keep one entry per key, the LAST occurrence winning. */
        private fun <T> keyedLast(list: List<T>, key: (T) -> String): List<T> {
            val m = LinkedHashMap<String, T>()
            for (x in list) m[key(x)] = x
            return m.values.toList()
        }

        /** Exact duplicates collapse, last occurrence survives. */
        private fun <T> distinctLast(list: List<T>, key: (T) -> String): List<T> {
            val seen = LinkedHashSet<String>()
            val out = ArrayList<T>(list.size)
            for (x in list.reversed()) if (seen.add(key(x))) out.add(x)
            out.reverse()
            return out
        }

        /** Parse exactly what the file says. Absent keys = empty/inactive,
         *  never filled from built-ins; bad entries are dropped. */
        fun parse(o: JSONObject): BridgeConfig {
            val sinks = o.optJSONArray("sinks")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    parseSink(a.optJSONObject(i) ?: return@mapNotNull null)
                }
            } ?: emptyList()
            val notifications = parseNotifications(o.optJSONObject("notifications"))
            val broadcasts = o.optJSONArray("broadcasts")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    parseBroadcast(a.optJSONObject(i) ?: return@mapNotNull null)
                }
            } ?: emptyList()
            val settings = o.optJSONArray("settings")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    parseSetting(a.optJSONObject(i) ?: return@mapNotNull null)
                }
            } ?: emptyList()
            val globalRoutes = o.optJSONArray("routes")?.let { a ->
                parseRoutes(a, scope = null)
            } ?: emptyList()

            return BridgeConfig(
                sinks = sinks,
                logAll = o.optBoolean("logAll", false),
                reload = o.optBoolean("reload", true),
                notifications = notifications,
                settings = settings,
                broadcasts = broadcasts,
                globalRoutes = globalRoutes
            )
        }

        private fun parseNotifications(o: JSONObject?): NotificationsCfg? {
            if (o == null) return null
            val enabled = o.optBoolean("enabled", true)
            val routes = parseRoutes(o.optJSONArray("out"), NOTIFY_SCOPE)
            return NotificationsCfg(enabled, routes)
        }

        /** Parse one broadcast entry; requires a non-empty action and
         *  event. Extras are mapped by their intent key (e.g. "level",
         *  "status", "plugged", "scale") to bus variable names; reserved
         *  built-in names are skipped. Routes are scoped to the entry's
         *  event. */
        private fun parseBroadcast(v: Any?): BroadcastCfg? {
            if (v !is JSONObject) return null
            val action = v.optString("action").takeIf { it.isNotEmpty() } ?: return null
            val event = v.optString("event").takeIf { it.isNotEmpty() } ?: return null
            val eventAction = v.optString("eventAction").takeIf { it.isNotEmpty() }
            val polarity = when {
                v.has("polarity") -> when (v.optString("polarity")) {
                    "on" -> true
                    "off" -> false
                    else -> null
                }
                else -> null
            }
            val snapshot = v.optBoolean("snapshot", false)
            val fields = LinkedHashMap<String, String>()
            val fo = v.optJSONObject("fields")
            if (fo != null) {
                val it = fo.keys()
                while (it.hasNext()) {
                    val k = it.next() as String
                    if (k in RESERVED_FIELDS) continue
                    val vv = fo.optString(k).takeIf { n -> n.isNotEmpty() }
                    if (vv != null) fields[k] = vv
                }
            }
            val routes = parseRoutes(v.optJSONArray("out"), setOf(event))
            return BroadcastCfg(
                action, event, eventAction, polarity, snapshot, fields, routes
            )
        }

        /** Parse one settings entry; rejects unknown tables, empty names
         *  and empty events. [table] optional (system), [event] optional
         *  (pulse), [defaultOn] optional (false). */
        private fun parseSetting(v: Any?): SettingCfg? {
            if (v !is JSONObject) return null
            val table = v.optString("table", "system")
            if (table !in SETTING_TABLES) return null
            val name = v.optString("name").takeIf { it.isNotEmpty() } ?: return null
            val event = v.optString("event", "pulse").takeIf { it.isNotEmpty() } ?: return null
            val defaultOn = if (v.has("defaultOn")) v.optBoolean("defaultOn", false) else false
            val routes = parseRoutes(v.optJSONArray("out"), setOf(event))
            return SettingCfg(table, name, event, defaultOn, routes)
        }

        private fun parseRoutes(a: org.json.JSONArray?, scope: Set<String>?): List<Route> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val v = a.optJSONObject(i) ?: return@mapNotNull null
                parseRoute(v, scope)
            }
        }

        /** One route: requires a sink [to]; [when]/[pkg]/[line] optional.
         *  Notification routes are scope-checked against the fixed
         *  vocabulary so a typo never becomes a silently dead route. */
        private fun parseRoute(o: JSONObject, scope: Set<String>?): Route? {
            val to = o.optString("to").takeIf { it.isNotEmpty() } ?: return null
            val whenPat = o.optString("when", "*")
            if (scope == NOTIFY_SCOPE && !validNotifyPattern(whenPat)) {
                android.util.Log.w(BLog.CORE,
                    "notifications route dropped: unknown 'when' '$whenPat' " +
                        "(use <type>.<action>: notify.*, call.on, *.off, *)")
                return null
            }
            val line = o.optString("line").takeIf { it.isNotEmpty() } ?: DEFAULT_LINE
            val pkg = if (o.has("pkg")) o.optString("pkg") else "*"
            return Route(whenPat, pkg, to, line, scope)
        }

        private fun validNotifyPattern(p: String): Boolean {
            if (p == "*") return true
            val parts = p.split(".", limit = 2)
            if (parts.size != 2) return false
            return (parts[0] in NOTIFY_TYPES || parts[0] == "*") &&
                (parts[1] in NOTIFY_ACTIONS || parts[1] == "*")
        }

        /** Vars a broadcast field must not shadow - the built-in line
         *  replacements always win. */
        private val RESERVED_FIELDS = setOf(
            "type", "action", "pkg", "id", "key", "reason", "incoming", "setting", "on"
        )

        /** A sink needs a usable name - a nameless one can never be the
         *  target of a route, so it is dropped. */
        private fun parseSink(o: JSONObject): SinkCfg? {
            val type = o.optString("type", "socket")
            if (type != "socket" && type != "logcat") return null
            val name = if (type == "logcat" && !o.has("name")) {
                SINK_LOG
            } else {
                o.optString("name").takeIf { it.isNotEmpty() } ?: return null
            }
            return SinkCfg(type, name)
        }
    }
}