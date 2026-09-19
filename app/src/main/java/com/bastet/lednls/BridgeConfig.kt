package com.bastet.lednls

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Runtime bridge configuration.
 *
 * Optional JSON at [CONFIG_PATH] ("/data/local/tmp/lednls_bridge.json",
 * adb-writable) turns the bridge into a routing engine: each observed
 * event is matched against [Rule]s and forwarded to a [SinkCfg] as a
 * rendered text line. When the file is absent every field falls back to
 * the built-in defaults below, which reproduce the classic chgd
 * contract 1:1 (ENQ/CAN, RING_ON/OFF, VOIP_ON/OFF, SCREEN, PULSE).
 * Re-read happens on mtime change; a reload broadcast also applies.
 */
data class BridgeConfig(
    /** Explicit keepalive cadence from config. null = let the consumer
     *  push "WD <ms>" over the socket (the classic chgd channel). */
    val watchdogMs: Long?,
    val daemonName: String,
    val daemonPath: String,
    val statusPath: String,
    val dialerPkg: String,
    val voipPkgs: Set<String>,
    val sinks: List<SinkCfg>,
    val rules: List<Rule>
) {

    /** A delivery endpoint. type = "socket" (abstract Unix domain) or
     *  "logcat" (debug mirror). name = abstract socket name for socket. */
    data class SinkCfg(val type: String, val name: String) {
        val isSocket: Boolean get() = type == "socket"
    }

    /** One routing rule. [event] = notify|ring|voip|screen|pulse or "*",
     *  [action] = posted|removed|on|off or "*"/null for any,
     *  [pkg] = exact package, "prefix*" glob or "*",
     *  [to] = sink name, [line] = template with $vars to forward. */
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
        const val CONFIG_PATH = "/data/local/tmp/lednls_bridge.json"
        const val SINK_DEFAULT = "chgd"
        const val SINK_LOG = "log"

        val DEFAULT_SINKS = listOf(
            SinkCfg("socket", "chgd_noty"),
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
            watchdogMs = null,
            daemonName = "chgd",
            daemonPath = "/data/adb/modules/led_hal_root/chgd",
            statusPath = "/data/local/tmp/lednls.status",
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
            rules = DEFAULT_RULES
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
                android.util.Log.w("led-nls", "config parse failed, defaults: $t")
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

            val wd = o.opt("watchdogMs")
            return BridgeConfig(
                watchdogMs = when (wd) {
                    is Int -> wd.toLong()
                    is Long -> wd
                    is Double -> wd.toLong()
                    else -> DEFAULT.watchdogMs
                },
                daemonName = o.optString("daemonName", DEFAULT.daemonName),
                daemonPath = o.optString("daemonPath", DEFAULT.daemonPath),
                statusPath = o.optString("statusPath", DEFAULT.statusPath),
                dialerPkg = o.optString("dialerPkg", DEFAULT.dialerPkg),
                voipPkgs = arr(o, "voipPkgs").ifEmpty { DEFAULT.voipPkgs.toList() }.toSet(),
                sinks = sinks.ifEmpty { DEFAULT_SINKS },
                rules = rules.ifEmpty { DEFAULT_RULES }
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
                name = if (name.isEmpty() && o.optString("type", "socket") == "socket") "chgd_noty" else name
            )
        }
    }
}