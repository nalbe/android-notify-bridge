package com.bastet.notifybridge

/**
 * A single observed event on the bridge bus, fully normalized before it
 * ever reaches a route:
 *  type     = notify | screen | charge | battery (or any event declared
 *             in a settings or broadcast config entry)
 *  action   = posted | removed | on | off  (per type)
 *  category = the per-section routing split: for notifications the
 *             normalized category (real / "call" / "missed_call" / ""),
 *             for broadcasts the entry event, for settings the key name
 *  pkg/id/key = the notification identity ("" / -1 / "" when n/a),
 *  reason     = onNotificationRemoved reason code,
 *  incoming   = freshly classified call direction (live-call events only),
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
    val category: String = "",
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
        .replace("\$category", category)
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
 * Routing engine: forwards every BridgeEvent out to the matching
 * routes. Per sink the MOST specific matching route wins
 * ([BridgeConfig.Route.specificity] - how many of action/category/pkg
 * are pinned), so a specific-pkg call route suppresses the raw "*"
 * route for the same call on that sink; on a tie every equally specific
 * route fires. Routes are scope-confined by their owning section (see
 * [BridgeConfig.Route.scope]); global routes (top-level `routes[]`)
 * match across every source. A route is inert until config places it -
 * no route means the event is simply not forwarded.
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
            "EVENT ${e.type} ${e.action} category=${e.category} pkg=${e.pkg} " +
                "id=${e.id} key=${e.key} " +
                "reason=${e.reason} incoming=${if (e.incoming) "1" else "0"} " +
                "setting=${e.setting} on=${if (e.on) "1" else "0"}$fields")
    }

    fun emit(e: BridgeEvent, config: BridgeConfig, sinks: SinkRegistry) =
        emitInto(e, config, sinks, null)

    /** Route [e] through the matching routes. With [onlySink] set only
     *  routes targeting that sink are considered - used by the connect
     *  replay so a freshly connected consumer gets its own state without
     *  re-stamping other connected sinks. Per sink the most specific
     *  route wins; equal specificity = every tied route fires. */
    fun emitInto(
        e: BridgeEvent,
        config: BridgeConfig,
        sinks: SinkRegistry,
        onlySink: String?
    ) {
        mirror(e, config)
        val bySink = HashMap<String, ArrayList<BridgeConfig.Route>>()
        for (r in config.routes) {
            if (!r.matches(e)) continue
            if (onlySink != null && r.to != onlySink) continue
            bySink.getOrPut(r.to) { ArrayList() }.add(r)
        }
        for ((sinkName, routes) in bySink) {
            val best = routes.maxOf { it.specificity }
            val sink = sinks[sinkName] ?: continue
            for (r in routes) {
                if (r.specificity != best) continue
                sink.send(e.render(r.line), BLog.tagOf(e))
            }
        }
    }
}