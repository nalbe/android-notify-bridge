package com.bastet.notifybridge

/**
 * A single observed event on the bridge bus, fully normalized before it
 * ever reaches a route:
 *  type   = notify | call | missed | screen | pulse | battery (or any
 *            event declared in a settings or broadcast config entry)
 *  action = posted | removed | on | off  (per type)
 *  pkg/id/key = the notification identity ("" / -1 / "" when n/a),
 *  reason     = onNotificationRemoved reason code,
 *  incoming   = freshly classified call direction (call.on only),
 *  setting    = watched setting name that produced this event,
 *  on         = screen / pulse / broadcast polarity,
 *  fields     = broadcast-sourced extras (intent extra -> bus var name),
 *               exposed to route lines as the matching $vars,
 *  source     = which bridge source produced this (BLog.SRC_*): decides
 *               the logcat tag for the EVENT mirror and the log sink.
 */
data class BridgeEvent(
    val type: String,
    val action: String,
    val pkg: String = "",
    val id: Int = -1,
    val key: String = "",
    val reason: Int = 0,
    val incoming: Boolean = false,
    val setting: String = "",
    val on: Boolean = false,
    val fields: Map<String, String> = emptyMap(),
    val source: String = ""
) {
    fun render(line: String): String = line
        .replace("\$type", type)
        .replace("\$action", action)
        .replace("\$pkg", pkg)
        .replace("\$id", id.toString())
        .replace("\$key", key)
        .replace("\$reason", reason.toString())
        .replace("\$incoming", if (incoming) "1" else "0")
        .replace("\$setting", setting)
        .replace("\$on", if (on) "1" else "0")
        .let { out ->
            var r = out
            for ((k, v) in fields) r = r.replace("\$$k", v)
            r
        }
}

/**
 * Routing engine: fans every BridgeEvent out to every matching route,
 * each rendered into its sink's text line. Routes are scope-confined by
 * their owning section (see [BridgeConfig.Route.scope]); global routes
 * (top-level `routes[]`) match across every source. A route is inert
 * until config places it - no route means the event is simply not
 * forwarded.
 */
object EventRouter {

    /** Discovery mirror: with logAll=true every event lands here BEFORE
     *  route matching, rendered with all normalized fields. Independent
     *  of sinks/routes - a config file is not even needed to watch the
     *  bus. Tagged per source (BLog.tagOf) so the three event streams
     *  stay separable in logcat. */
    fun mirror(e: BridgeEvent, config: BridgeConfig) {
        if (!config.logAll) return
        val fields = if (e.fields.isEmpty()) ""
            else " fields=" + e.fields.entries.joinToString(",") { "${it.key}=${it.value}" }
        android.util.Log.i(BLog.tagOf(e),
            "EVENT ${e.type} ${e.action} pkg=${e.pkg} id=${e.id} key=${e.key} " +
                "reason=${e.reason} incoming=${if (e.incoming) "1" else "0"} " +
                "setting=${e.setting} on=${if (e.on) "1" else "0"}$fields")
    }

    fun emit(e: BridgeEvent, config: BridgeConfig, sinks: SinkRegistry) =
        emitInto(e, config, sinks, null)

    /** Route [e] through every matching route. With [onlySink] set only
     *  routes targeting that sink are rendered - used by the connect
     *  replay so a freshly connected consumer gets its own state without
     *  re-stamping other connected sinks. */
    fun emitInto(
        e: BridgeEvent,
        config: BridgeConfig,
        sinks: SinkRegistry,
        onlySink: String?
    ) {
        mirror(e, config)
        for (r in config.routes) {
            if (!r.matches(e)) continue
            if (onlySink != null && r.to != onlySink) continue
            val sink = sinks[r.to] ?: continue
            sink.send(e.render(r.line), BLog.tagOf(e))
        }
    }
}