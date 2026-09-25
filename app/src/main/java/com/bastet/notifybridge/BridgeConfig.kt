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
 *  - [routes]         Optional global routing table, matched across every
 *    source (this is the "raw tube": a route with `action: "*"` and
 *    `category: "*"` mirrors everything into one sink). Section routes
 *    are scope-confined, global routes are not.
 *
 * Every route splits on two axes: `action` is the event action
 * ("posted"/"removed"/"on"/"off", "*" = any) and `category` is the
 * routing axis - for notifications the normalized category ("call" /
 * "missed_call" / the real one / "" = no category, "*" = any), for
 * broadcast events the entry event, for settings the watched key. A typo
 * in a notification `action` is dropped with a warning before it can
 * become a silently dead route.
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
    /** Settings source: the [SettingsCfg.enabled] gate and the flat
     *  [SettingsCfg.entries] inline routes that forward on/off changes.
     *  Null = no observers, settings disabled. */
    val settings: SettingsCfg?,
    /** Broadcasts source: the [BroadcastsCfg.enabled] gate and the flat
     *  [BroadcastsCfg.entries] inline routes. One dynamic receiver is
     *  registered for every distinct intent action the entries listen
     *  for. Null = no receiver, broadcasts disabled. */
    val broadcasts: BroadcastsCfg?,
    /** Global routing table, matched across every source. */
    private val globalRoutes: List<Route>
) {

    /** A delivery endpoint; type "socket" or "logcat". */
    data class SinkCfg(val type: String, val name: String) {
        val isSocket: Boolean get() = type == "socket"
    }

    /** The notification source: [enabled] gate and the section-local
     *  [routes]. Every notification (call included) surfaces as one
     *  `notify` type; live-call / missed-call classification runs on any
     *  package by built-in markers and shows through the route's
     *  [Route.category] - "call" / "missed_call" / the app's own one.
     *  SIM-versus-VOIP rendering is decided per route by [Route.pkg].
     *  Route scope is confined to the notify event. */
    data class NotificationsCfg(
        val enabled: Boolean,
        val routes: List<Route>
    )

    /** One watched Settings key as an inline route. [table] =
     *  system|global|secure, [name] = settings key, [event] = bus event
     *  type (default "pulse"), [defaultOn] = polarity when the key is
     *  missing/unreadable (the classic LED toggle reads as on). A change
     *  emits BridgeEvent(event, "on"/"off", on = polarity, setting =
     *  name) through the registered route ([to]/[line]). */
    data class SettingCfg(
        val table: String,
        val name: String,
        val event: String,
        val defaultOn: Boolean,
        val to: String,
        val line: String
    )

    /** The settings source: [enabled] gate and the flat [entries] list.
     *  Merge collapses exact duplicates; one table/name may appear on
     *  any number of entries (fan-out to different sinks). */
    data class SettingsCfg(
        val enabled: Boolean,
        val entries: List<SettingCfg>
    )

    /** One observed system broadcast as an inline route. [action] = the
     *  intent action to listen for, [event] = bus event type (the route
     *  category). The bus action is [polarity] ("on"/"off" - also sets
     *  e.on) when set, else [eventAction], else the raw intent action
     *  (on = false). [snapshot] = true means: on socket connect re-emit
     *  the current screen state (PowerManager) as this event. [fields]
     *  maps intent extras (BatteryManager.EXTRA_* etc.) to $vars; a
     *  field key identical to a built-in var is skipped. The emitted
     *  event goes to [to] rendered with [line]. One intent action may
     *  appear on any number of entries (fan-out) - every matching entry
     *  emits its own event. */
    data class BroadcastCfg(
        val action: String,
        val event: String,
        val eventAction: String?,
        val polarity: Boolean?,
        val snapshot: Boolean,
        val fields: Map<String, String>,
        val to: String,
        val line: String
    )

    /** The broadcasts source: [enabled] gate and the flat [entries]
     *  list. One dynamic receiver handles every distinct action among
     *  the entries. Merge collapses exact duplicates. */
    data class BroadcastsCfg(
        val enabled: Boolean,
        val entries: List<BroadcastCfg>
    )

    /** One forwarding route. [action] matches the event action
     *  ("posted"/"removed"/"on"/"off", "*" = any). [category] splits the
     *  same action by its routing axis - for notifications a normalized
     *  category: "call" (live call), "missed_call" (tombstone), the app's
     *  own Notification category, or "" (the app set none); "*" = any,
     *  "" on the route = only notifications carrying no category. For
     *  broadcast events [category] is the entry event, for settings the
     *  watched key. [pkg] = exact package, "prefix*" glob or "*"
     *  (meaningful for notification events). [scope] confines the route
     *  to a fixed set of event types - set by the owning section, null
     *  for global routes. [to] = sink key, [line] = template with $vars
     *  to forward. Routing per sink is winner-take-most: only the MOST
     *  specific matching routes fire ([Route.specificity] - how many of
     *  action/category/pkg are pinned), so a specific-pkg call route
     *  suppresses the raw "*" route for the same call on that sink; on a
     *  tie (e.g. two equally specific routes to one sink) every tied
     *  route fires. Split one event per package by giving each package a
     *  more specific route than the "*" fallback. */
    data class Route(
        val action: String,
        val category: String,
        val pkg: String,
        val to: String,
        val line: String,
        val scope: Set<String>?
    ) {
        /** How many of action/category/pkg are pinned down ("*" counts as
         *  0, an exact package or a "prefix*" glob counts as pinned).
         *  Per sink the MOST specific matching route wins; on a tie every
         *  tied route fires. So a specific-pkg call route suppresses the
         *  raw '*' route for the same call on that sink. */
        val specificity: Int
            get() = (if (action != "*") 1 else 0) +
                (if (category != "*") 1 else 0) +
                (if (pkg != "*") 1 else 0)

        fun matches(e: BridgeEvent): Boolean {
            if (scope != null && e.type !in scope) return false
            if (action != "*" && action != e.action) return false
            if (category != "*" && category != e.category) return false
            if (pkg != "*") {
                if (pkg.endsWith("*")) {
                    if (!e.pkg.startsWith(pkg.dropLast(1))) return false
                } else if (pkg != e.pkg) return false
            }
            return true
        }
    }

    /** Whether the config asks the bridge to do anything at all. Any
     *  enabled source keeps the bridge alive - so logAll discovery works
     *  without a single route. */
    val isActive: Boolean
        get() = (notifications?.enabled == true) ||
            (broadcasts?.enabled == true) || (settings?.enabled == true)

    val socketCount: Int get() = sinks.count { it.isSocket }

    /** Every route the bus should try, sections first then global,
     *  exact duplicates collapsed. Broadcast/settings entries resolve to
     *  one route each (filtered to enabled sections only). Flattened
     *  from the RESOLVED sections, so a fragment that replaces a
     *  broadcast/setting route drops the replaced route with it. */
    val routes: List<Route> by lazy {
        val out = ArrayList<Route>()
        notifications?.routes?.let { out.addAll(it) }
        broadcasts?.takeIf { it.enabled }?.entries?.forEach {
            out.add(broadcastRoute(it))
        }
        settings?.takeIf { it.enabled }?.entries?.forEach {
            out.add(settingRoute(it))
        }
        out.addAll(globalRoutes)
        distinctLast(out) { routeKey(it) }
    }

    companion object {
        const val CONFIG_PATH = "/data/local/tmp/notifybridge.json"
        const val CONFIG_DIR = "/data/local/tmp/notifybridge.d"
        private const val SINK_LOG = "log"
        private val SETTING_TABLES = setOf("system", "global", "secure")

        private val NOTIFY_TYPES = setOf("notify")
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
        /** Drop-in merge: sinks, broadcasts and settings last-wins per
         *  entry, notifications and per-entry route duplicates collapse
         *  (last wins); logAll OR; reload AND. */
        private fun merge(a: BridgeConfig, b: BridgeConfig): BridgeConfig =
            BridgeConfig(
                sinks = keyedLast(a.sinks + b.sinks, { it.name }),
                logAll = a.logAll || b.logAll,
                reload = a.reload && b.reload,
                notifications = mergeNotifications(a.notifications, b.notifications),
                settings = mergeSettings(a.settings, b.settings),
                broadcasts = mergeBroadcasts(a.broadcasts, b.broadcasts),
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

        private fun mergeBroadcasts(
            a: BroadcastsCfg?, b: BroadcastsCfg?
        ): BroadcastsCfg? {
            if (a == null) return b
            if (b == null) return a
            return BroadcastsCfg(
                enabled = a.enabled && b.enabled,
                entries = distinctLast(a.entries + b.entries) { broadcastKey(it) }
            )
        }

        private fun mergeSettings(
            a: SettingsCfg?, b: SettingsCfg?
        ): SettingsCfg? {
            if (a == null) return b
            if (b == null) return a
            return SettingsCfg(
                enabled = a.enabled && b.enabled,
                entries = distinctLast(a.entries + b.entries) { settingKey(it) }
            )
        }

        private fun routeKey(r: Route): String =
            "${r.action}|${r.category}|${r.pkg}|${r.to}|${r.line}"

        private fun broadcastKey(b: BroadcastCfg): String =
            "${b.action}|${b.event}|${b.to}|${b.line}"

        private fun settingKey(s: SettingCfg): String =
            "${s.table}|${s.name}|${s.to}|${s.line}"

        /** Resolve a broadcast inline route to its bus route:
         *  category = event, scope = the event type only. */
        private fun broadcastRoute(b: BroadcastCfg): Route =
            Route("*", b.event, "*", b.to, b.line, setOf(b.event))

        /** Resolve a settings inline route to its bus route:
         *  category = the watched key, scope = the event type only. */
        private fun settingRoute(s: SettingCfg): Route =
            Route("*", s.name, "*", s.to, s.line, setOf(s.event))

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
            val broadcasts = parseBroadcasts(o.optJSONObject("broadcasts"))
            val settings = parseSettings(o.optJSONObject("settings"))
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

        /** The broadcasts source is one section like notifications: an
         *  [enabled] gate (absent = true) and a flat [out] list where
         *  every route is self-contained (trigger + output inline). The
         *  old per-entry "out" nesting is gone - routes are parsed
         *  directly, so one intent action may fan out over any number of
         *  routes to different sinks. */
        private fun parseBroadcasts(o: JSONObject?): BroadcastsCfg? {
            if (o == null) return null
            val enabled = o.optBoolean("enabled", true)
            val entries = o.optJSONArray("out")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val v = a.optJSONObject(i) ?: return@mapNotNull null
                    parseBroadcast(v)
                }
            } ?: emptyList()
            return BroadcastsCfg(enabled, entries)
        }

        /** One broadcast route: an intent [action], a bus [event] (the
         *  category), a sink [to]. The retired "when" key and the old
         *  nested "out" are dropped with a warning; unknown trigger keys
         *  are ignored. */
        private fun parseBroadcast(o: JSONObject): BroadcastCfg? {
            val action = o.optString("action").takeIf { it.isNotEmpty() }
            val event = o.optString("event").takeIf { it.isNotEmpty() }
            val to = o.optString("to").takeIf { it.isNotEmpty() }
            if (o.has("when") || o.has("out")) {
                android.util.Log.w(BLog.CORE,
                    "broadcast route dropped: 'when'/'out' nesting is gone - " +
                        "one inline route per entry")
                return null
            }
            if (action == null || event == null || to == null) {
                android.util.Log.w(BLog.CORE,
                    "broadcast route dropped: 'action', 'event' and 'to' are required")
                return null
            }
            val eventAction = o.optString("eventAction").takeIf { it.isNotEmpty() }
            val polarity = when {
                o.has("polarity") -> when (o.optString("polarity")) {
                    "on" -> true
                    "off" -> false
                    else -> null
                }
                else -> null
            }
            val snapshot = o.optBoolean("snapshot", false)
            val fields = LinkedHashMap<String, String>()
            val fo = o.optJSONObject("fields")
            if (fo != null) {
                val it = fo.keys()
                while (it.hasNext()) {
                    val k = it.next() as String
                    if (k in RESERVED_FIELDS) continue
                    val vv = fo.optString(k).takeIf { n -> n.isNotEmpty() }
                    if (vv != null) fields[k] = vv
                }
            }
            val line = o.optString("line").takeIf { it.isNotEmpty() } ?: DEFAULT_LINE
            return BroadcastCfg(action, event, eventAction, polarity, snapshot, fields, to, line)
        }

        /** The settings source is organized the same way: [enabled] gate
         *  (absent = true) and a flat [out] list of inline routes. */
        private fun parseSettings(o: JSONObject?): SettingsCfg? {
            if (o == null) return null
            val enabled = o.optBoolean("enabled", true)
            val entries = o.optJSONArray("out")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val v = a.optJSONObject(i) ?: return@mapNotNull null
                    parseSetting(v)
                }
            } ?: emptyList()
            return SettingsCfg(enabled, entries)
        }

        /** One settings route: [table] optional (system), [event] optional
         *  (pulse), [defaultOn] optional (false), sink [to] required. The
         *  retired "when" key and the old nested "out" are dropped with a
         *  warning. */
        private fun parseSetting(o: JSONObject): SettingCfg? {
            val table = o.optString("table", "system")
            if (table !in SETTING_TABLES) return null
            val name = o.optString("name").takeIf { it.isNotEmpty() }
            val event = o.optString("event", "pulse").takeIf { it.isNotEmpty() } ?: "pulse"
            val to = o.optString("to").takeIf { it.isNotEmpty() }
            if (o.has("when") || o.has("out")) {
                android.util.Log.w(BLog.CORE,
                    "settings route dropped: 'when'/'out' nesting is gone - " +
                        "one inline route per entry")
                return null
            }
            if (name == null || to == null) {
                android.util.Log.w(BLog.CORE,
                    "settings route dropped: 'name' and 'to' are required")
                return null
            }
            val defaultOn = if (o.has("defaultOn")) o.optBoolean("defaultOn", false) else false
            val line = o.optString("line").takeIf { it.isNotEmpty() } ?: DEFAULT_LINE
            return SettingCfg(table, name, event, defaultOn, to, line)
        }

        private fun parseRoutes(a: org.json.JSONArray?, scope: Set<String>?): List<Route> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val v = a.optJSONObject(i) ?: return@mapNotNull null
                parseRoute(v, scope)
            }
        }

        /** One route: requires a sink [to]; [action]/[category]/[pkg]/[line]
         *  optional (defaults "*"/"*"/"*"/DEFAULT_LINE). The old "when"
         *  key is gone - it is dropped with a warning so a stale config
         *  never silently comes alive. Notification routes are
         *  action-checked so a typo never becomes a silently dead route. */
        private fun parseRoute(o: JSONObject, scope: Set<String>?): Route? {
            val to = o.optString("to").takeIf { it.isNotEmpty() } ?: return null
            if (o.has("when")) {
                android.util.Log.w(BLog.CORE,
                    "route dropped: 'when' is gone - use 'action' + 'category'")
                return null
            }
            val action = o.optString("action", "*")
            if (scope == NOTIFY_SCOPE && action != "*" && action !in NOTIFY_ACTIONS) {
                android.util.Log.w(BLog.CORE,
                    "notifications route dropped: unknown 'action' '$action' " +
                        "(use posted|removed|on|off or *)")
                return null
            }
            val line = o.optString("line").takeIf { it.isNotEmpty() } ?: DEFAULT_LINE
            val category = o.optString("category", "*")
            val pkg = o.optString("pkg", "*")
            return Route(action, category, pkg, to, line, scope)
        }

        /** Vars a broadcast field must not shadow - the built-in line
         *  replacements always win. */
        private val RESERVED_FIELDS = setOf(
            "type", "action", "category", "pkg", "id", "key", "reason", "incoming", "setting", "on"
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