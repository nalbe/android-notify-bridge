package com.bastet.notifybridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Runtime bridge configuration.
 *
 * Optional JSON at [CONFIG_PATH] ("/data/local/tmp/notifybridge.json",
 * adb-writable) turns the bridge into a routing engine: each observed
 * event is matched against [Rule]s and forwarded to a [SinkCfg] as a
 * rendered text line. When the file is absent every field falls back to
 * the built-in defaults below. Re-read happens on the RELOAD_CONFIG
 * broadcast (or when the service (re)starts) - no polling.
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
    val logAll: Boolean
) {

    /** A delivery endpoint. type = "socket" (abstract Unix domain) or
     *  "logcat" (debug mirror). name = sink key; for a socket it is also
     *  the abstract socket name. */
    data class SinkCfg(val type: String, val name: String) {
        val isSocket: Boolean get() = type == "socket"
    }

    /** One routing rule. [event] = notify|ring|voip|screen|pulse or "*",
     *  [action] = posted|removed|on|off or "*"/null for any,
     *  [pkg] = exact package, "prefix*" glob or "*",
     *  [to] = sink key, [line] = template with $vars to forward. */
    data class Rule(
        val event: String,
        val action: String?,
        val pkg: String,
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
            return true
        }
    }

    val socketCount: Int get() = sinks.count { it.isSocket }

    companion object {
        const val CONFIG_PATH = "/data/local/tmp/notifybridge.json"
        const val SINK_DEFAULT = "notify_bus"
        const val SINK_LOG = "log"

        val DEFAULT_SINKS = listOf(
            SinkCfg("socket", SINK_DEFAULT),
            SinkCfg("logcat", "")
        )

        val DEFAULT_RULES = listOf(
            Rule("notify", "posted",  "*", SINK_DEFAULT, "ENQ \$pkg \$id"),
            Rule("notify", "removed", "*", SINK_DEFAULT, "CAN \$pkg \$id"),
            Rule("ring",   "on",      "*", SINK_DEFAULT, "RING_ON \$incoming"),
            Rule("ring",   "off",     "*", SINK_DEFAULT, "RING_OFF"),
            Rule("voip",   "on",      "*", SINK_DEFAULT, "VOIP_ON \$pkg"),
            Rule("voip",   "off",     "*", SINK_DEFAULT, "VOIP_OFF \$pkg"),
            Rule("screen", "on",      "*", SINK_DEFAULT, "SCREEN 1"),
            Rule("screen", "off",     "*", SINK_DEFAULT, "SCREEN 0"),
            Rule("pulse",  "on",      "*", SINK_DEFAULT, "PULSE 1"),
            Rule("pulse",  "off",     "*", SINK_DEFAULT, "PULSE 0")
        )

        val DEFAULT = BridgeConfig(
            dialerPkg = "com.google.android.dialer",
            voipPkgs = setOf(
                "org.telegram.messenger",
                "com.whatsapp",
                "com.viber.voip",
                "org.thoughtcrime.securesms",
                "com.snapchat",
                "com.google.android.apps.tachyon"
            ),
            sinks = DEFAULT_SINKS,
            rules = DEFAULT_RULES,
            logAll = false
        )

        private fun arr(o: JSONObject, key: String): List<String> {
            if (!o.has(key)) return emptyList()
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optString(it).takeIf { v -> v.isNotEmpty() } }
        }

        /** Parse the optional device config; errors fall back to defaults
         *  per-field so a bad file can never kill the bridge. */
        fun load(): BridgeConfig {
            val f = File(CONFIG_PATH)
            return try {
                if (f.exists()) parse(JSONObject(f.readText()))
                else DEFAULT
            } catch (t: Throwable) {
                android.util.Log.w("notifybridge", "config parse failed, defaults: $t")
                DEFAULT
            }
        }

        fun parse(o: JSONObject): BridgeConfig {
            val ruleArr = o.optJSONArray("rules")
            val rules = when {
                ruleArr != null -> (0 until ruleArr.length()).mapNotNull { i ->
                    parseRule(ruleArr.optJSONObject(i) ?: return@mapNotNull null)
                }
                else -> DEFAULT_RULES
            }
            val sinks = if (o.has("sinks")) {
                val arr = o.optJSONArray("sinks") ?: JSONArray()
                (0 until arr.length()).mapNotNull { parseSink(arr.optJSONObject(it) ?: return@mapNotNull null) }
            } else DEFAULT_SINKS

            return BridgeConfig(
                dialerPkg = o.optString("dialerPkg", DEFAULT.dialerPkg),
                voipPkgs = arr(o, "voipPkgs").ifEmpty { DEFAULT.voipPkgs.toList() }.toSet(),
                sinks = sinks.ifEmpty { DEFAULT_SINKS },
                rules = rules.ifEmpty { DEFAULT_RULES },
                logAll = o.optBoolean("logAll", DEFAULT.logAll)
            )
        }

        private fun parseRule(o: JSONObject): Rule? {
            val line = o.optString("line").takeIf { it.isNotEmpty() } ?: return null
            return Rule(
                event = o.optString("event", "*"),
                action = if (o.has("action")) o.optString("action") else null,
                pkg = o.optString("pkg", "*"),
                to = o.optString("to", SINK_DEFAULT),
                line = line
            )
        }

        private fun parseSink(o: JSONObject): SinkCfg? {
            val name = o.optString("name")
            return SinkCfg(
                type = o.optString("type", "socket"),
                name = if (name.isEmpty() && o.optString("type", "socket") == "socket") SINK_DEFAULT else name
            )
        }
    }
}