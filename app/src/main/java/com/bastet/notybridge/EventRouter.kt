package com.bastet.notybridge

/**
 * A single observed event on the bridge bus, fully normalized before it
 * ever reaches a rule:
 *   type   = notify | ring | voip | screen | pulse
 *   action = posted | removed | on | off   (per type)
 *   pkg/id/key = the notification identity ("" / -1 / "" when n/a),
 *   reason     = onNotificationRemoved reason code,
 *   incoming   = freshly classified SIM-call direction,
 *   on         = screen / pulse polarity.
 */
data class BridgeEvent(
    val type: String,
    val action: String,
    val pkg: String = "",
    val id: Int = -1,
    val key: String = "",
    val reason: Int = 0,
    val incoming: Boolean = false,
    val on: Boolean = false
) {
    fun render(line: String): String = line
        .replace("\$type", type)
        .replace("\$action", action)
        .replace("\$pkg", pkg)
        .replace("\$id", id.toString())
        .replace("\$key", key)
        .replace("\$reason", reason.toString())
        .replace("\$incoming", if (incoming) "1" else "0")
        .replace("\$on", if (on) "1" else "0")
}

/**
 * Routing engine: fans every BridgeEvent out to every matching rule, each
 * rendered into its sink's text line. A rule is inert until config places
 * it - no rule means the event is simply not forwarded.
 */
object EventRouter {

    /** Discovery mirror: with logAll=true every event lands here BEFORE
     *  rule matching, rendered with all normalized fields. Independent of
     *  sinks/rules - a config file is not even needed to watch the bus. */
    fun mirror(e: BridgeEvent, config: BridgeConfig) {
        if (!config.logAll) return
        android.util.Log.i("notybridge",
            "EVENT ${e.type} ${e.action} pkg=${e.pkg} id=${e.id} key=${e.key} " +
                "reason=${e.reason} incoming=${if (e.incoming) "1" else "0"} " +
                "on=${if (e.on) "1" else "0"}")
    }

    fun emit(e: BridgeEvent, config: BridgeConfig, sinks: SinkRegistry) {
        mirror(e, config)
        for (r in config.rules) {
            if (!r.matches(e)) continue
            val sink = sinks[r.to] ?: continue
            sink.send(e.render(r.line))
        }
    }
}