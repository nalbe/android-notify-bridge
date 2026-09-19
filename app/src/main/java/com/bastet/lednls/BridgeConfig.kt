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
    /** Keepalive cadence. Lived from config, but the consumer's "WD <ms>"
     *  push overwrites it (persisted back into the config file), so the
     *  consumer owns the value until its own keepalive key is gone. */
    val watchdogMs: Long?,
    val daemonName: String,
    val daemonPath: String,
    val statusPath: String,
    val dialerPkg: String,
    val voipPkgs: Set<String>,
    val sinks: List<SinkCfg>,
    val rules: List<Rule>,
    /** Discovery mode. When true every normalized event on the bus is
     *  mirrored to logcat (tag "led-nls", prefix "EVENT") before rule
     *  matching - independent of sinks/rules. Watch it to learn exactly
     *  what to put into rules / voipPkgs / dialerPkg. */
    val logAll: Boolean
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
                rules = rules.ifEmpty { DEFAULT_RULES },
                logAll = o.optBoolean("logAll", DEFAULT.logAll)
            )
        }

        /** Write the authoritative keepalive cadence back into the device
         *  config file, so the consumer's "WD <ms>" push is persistent and
         *  survives a process restart. The JSON is shipped to su as base64 -
         *  no shell quoting hazards. Best effort: runtime-only on su failure. */
        fun persistWatchdogMs(ms: Long) {
            val f = File(CONFIG_PATH)
            val cur = try {
                if (f.exists()) JSONObject(f.readText()) else JSONObject()
            } catch (_: Exception) {
                JSONObject()
            }
            cur.put("watchdogMs", ms)
            val json = cur.toString()
            val b64 = android.util.Base64.encodeToString(
                json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
            )
            try {
                SuShell.exec("echo $b64 | base64 -d > $CONFIG_PATH")
                android.util.Log.i("led-nls", "watchdogMs=$ms persisted to $CONFIG_PATH")
            } catch (e: Exception) {
                android.util.Log.w("led-nls", "watchdogMs persist failed (no su?): $e")
            }
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