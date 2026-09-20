package com.bastet.notifybridge

import org.json.JSONObject
import java.io.File

/**
 * Runtime bridge configuration.
 *
 * Optional JSON at [CONFIG_PATH] ("/data/local/tmp/notifybridge.json",
 * adb-writable) turns the bridge into a routing engine: each observed
 * event is matched against [Rule]s and forwarded to a [SinkCfg] as a
 * rendered text line.
 *
 * Configuration is drop-in: every *.json in [CONFIG_DIR]
 * ("/data/local/tmp/notifybridge.d/") is merged with the main file
 * (main first, fragments alphabetical). Each app owns its own fragment
 * file and pushes/replaces it without touching anyone else's - order
 * rules: sinks/watchedSettings keep one entry per key (last wins),
 * rules collapse exact duplicates (last wins), voipPkgs union, dialerPkg
 * first non-empty, logAll OR. A broken fragment is skipped and logged,
 * it never kills the rest.
 *
 * WITHOUT any config file the bridge is fully passive: no sinks, no
 * rules, no observers, no classification - nothing to do, nothing sent.
 * There are no built-in default rules: the config file IS the whole
 * policy, a bad or absent file just means "stay quiet". A fresh example
 * lives in the project at config/notifybridge.example.json (see
 * config/deploy-config.ps1).
 *
 * Re-read happens on the RELOAD_CONFIG broadcast (or when the service
 * (re)starts) - no polling. On a parse failure the previous config, if
 * any, stays active and the reason is logged - an accidental typo never
 * kills a working bridge.
 *
 * Observed Settings entries (LED/other user toggles) are listed in
 * [watchedSettings] - never hardcoded: whatever you want forwarded as a
 * 0/1 toggle event lives there.
 */
data class BridgeConfig(
    val dialerPkg: String,
    val voipPkgs: Set<String>,
    val sinks: List<SinkCfg>,
    val rules: List<Rule>,
    /** Discovery mode. When true every normalized event on the bus is
     *  mirrored to logcat (tag "notifybridge", prefix "EVENT") before rule
     *  matching - independent of sinks/rules. Watch it to learn exactly
     *  what to put into rules / voipPkgs / dialerPkg. */
    val logAll: Boolean,
    /** Settings watched for user-visible toggle changes. Each maps a
     *  Settings table entry to an event type on the bus (see WatchedSetting);
     *  a change is forwarded as <event> on/off with $setting set. Not a
     *  hidden feature: everything observable lives here. */
    val watchedSettings: List<WatchedSetting>
) {

    /** A delivery endpoint. type = "socket" (abstract Unix domain) or
     *  "logcat" (debug mirror). name = sink key; for a socket it is also
     *  the abstract socket name. */
    data class SinkCfg(val type: String, val name: String) {
        val isSocket: Boolean get() = type == "socket"
    }

    /** One observed Settings entry. [table] = system|global|secure,
     *  [name] = settings key, [event] = bus event type to emit (default
     *  "pulse"), [defaultOn] = polarity when the key is missing/unreadable
     *  (the classic LED toggle reads as on). A change emits
     *  BridgeEvent(event, "on"/"off", setting = name). */
    data class WatchedSetting(
        val table: String,
        val name: String,
        val event: String,
        val defaultOn: Boolean
    )

    /** One routing rule. [event] = notify|ring|voip|screen|pulse or "*",
     *  [action] = posted|removed|on|off or "*"/null for any,
     *  [pkg] = exact package, "prefix*" glob or "*",
     *  [setting] = watched setting name filter (for watchedSettings
     *  events; "*" matches any),
     *  [to] = sink key, [line] = template with $vars to forward. */
    data class Rule(
        val event: String,
        val action: String?,
        val pkg: String,
        val setting: String,
        val to: String,
        val line: String
    ) {
        fun matches(e: BridgeEvent): Boolean {
            if (event != "*" && event != e.type) return false
            if (action != null && action != "*" && action != e.action) return false
            if (pkg != "*") {
                if (pkg.endsWith("*")) {
                    if (!e.pkg.startsWith(pkg.dropLast(1))) return false
                } else if (pkg != e.pkg) return false
            }
            if (setting != "*" && setting != e.setting) return false
            return true
        }
    }

    /** Whether the config asks the bridge to do anything at all. Without
     *  rules there is nothing to forward - watchers alone would only
     *  burn observers for events nobody routes. */
    val isActive: Boolean get() = rules.isNotEmpty()

    val socketCount: Int get() = sinks.count { it.isSocket }

    companion object {
        const val CONFIG_PATH = "/data/local/tmp/notifybridge.json"
        const val CONFIG_DIR = "/data/local/tmp/notifybridge.d"
        private const val SINK_LOG = "log"
        private val SETTING_TABLES = setOf("system", "global", "secure")

        private fun arr(o: JSONObject, key: String): List<String> {
            if (!o.has(key)) return emptyList()
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optString(it).takeIf { v -> v.isNotEmpty() } }
        }

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
                    android.util.Log.e("notifybridge",
                        "fragment ${f.absolutePath} invalid, skipped: $t")
                    null
                } ?: continue
                merged = if (merged == null) c else merge(merged, c)
            }
            return merged
        }

        /** Merge two fragments, first fragment keeping priority for
         *  single-value fields, later ones overriding keyed repeats:
         *  rules - concatenated, exact duplicates collapse (last wins);
         *  sinks - one per name, last wins; watchedSettings - one per
         *  (table,name), last wins; voipPkgs - union; dialerPkg - first
         *  non-empty; logAll - any true wins. */
        private fun merge(a: BridgeConfig, b: BridgeConfig): BridgeConfig =
            BridgeConfig(
                dialerPkg = a.dialerPkg.ifEmpty { b.dialerPkg },
                voipPkgs = a.voipPkgs + b.voipPkgs,
                sinks = keyedLast(a.sinks + b.sinks, { it.name }),
                rules = distinctLast(a.rules + b.rules) { r ->
                    "${r.event}|${r.action}|${r.pkg}|${r.setting}|${r.to}|${r.line}"
                },
                logAll = a.logAll || b.logAll,
                watchedSettings = keyedLast(a.watchedSettings + b.watchedSettings) {
                    "${it.table}/${it.name}"
                }
            )

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
            val rules = o.optJSONArray("rules")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    parseRule(a.optJSONObject(i) ?: return@mapNotNull null)
                }
            } ?: emptyList()
            val sinks = o.optJSONArray("sinks")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    parseSink(a.optJSONObject(i) ?: return@mapNotNull null)
                }
            } ?: emptyList()
            val watched = o.optJSONArray("watchedSettings")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    parseWatched(a.optJSONObject(i) ?: return@mapNotNull null)
                }
            } ?: emptyList()

            return BridgeConfig(
                dialerPkg = o.optString("dialerPkg"),
                voipPkgs = arr(o, "voipPkgs").toSet(),
                sinks = sinks,
                rules = rules,
                logAll = o.optBoolean("logAll", false),
                watchedSettings = watched
            )
        }

        private fun parseRule(o: JSONObject): Rule? {
            val line = o.optString("line").takeIf { it.isNotEmpty() } ?: return null
            val to = o.optString("to").takeIf { it.isNotEmpty() } ?: return null
            return Rule(
                event = o.optString("event", "*"),
                action = if (o.has("action")) o.optString("action") else null,
                pkg = o.optString("pkg", "*"),
                setting = o.optString("setting", "*"),
                to = to,
                line = line
            )
        }

        /** Parse one watchedSetting entry; rejects unknown tables, empty
         *  names and empty events. [table] optional (system), [event]
         *  optional (pulse), [defaultOn] optional (false). */
        private fun parseWatched(v: Any?): WatchedSetting? {
            if (v !is JSONObject) return null
            val table = v.optString("table", "system")
            if (table !in SETTING_TABLES) return null
            val name = v.optString("name").takeIf { it.isNotEmpty() } ?: return null
            val event = v.optString("event", "pulse").takeIf { it.isNotEmpty() } ?: return null
            val defaultOn = if (v.has("defaultOn")) v.optBoolean("defaultOn", false) else false
            return WatchedSetting(table, name, event, defaultOn)
        }

        /** A sink needs a usable name - a nameless one can never be the
         *  target of a rule, so it is dropped. */
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