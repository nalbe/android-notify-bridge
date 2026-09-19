# noty-bridge - headless notification bridge

A config-driven `NotificationListenerService` that turns Android
notification events into text lines for any consumer over abstract Unix
sockets. No UI, headless, single APK (`com.bastet.lednls`).

Decoupled from the [shark8-led-daemon](https://github.com/nalbe/shark8-led-daemon)
project: **which event goes where, and in what form, is device-side config
(`/data/local/tmp/lednls_bridge.json`) - no rebuild to serve a different
daemon.**

## What it does

A normalized event bus plus a routing engine:

1. Observe: posted / removed notifications, screen on/off, the system
   "Notification light" toggle.
2. Classify: SIM calls (dialer package) and messenger calls (VoIP package
   list) are detected by built-in heuristics - a raw notification has no
   "this is a call" marker, so pkg + channelId/category/action titles are
   checked (details below). Both package lists are config.
3. Route: every event is matched against the config rule list; each match
   renders a text line and sends it to the configured sink (abstract Unix
   socket or logcat).

### Events

Every event carries all normalized fields; irrelevant ones are empty/`-1`/`0`:

| type   | action            | populated fields              |
|--------|-------------------|-------------------------------|
| notify | `posted` / `removed` | `$pkg` `$id` `$key` (+ `$reason` on removed) |
| ring   | `on` / `off`      | `$pkg` `$id` `$incoming` (on); `$pkg` (off) |
| voip   | `on` / `off`      | `$pkg` `$id` (on); `$pkg` (off) |
| screen | `on` / `off`      | `$on`                         |
| pulse  | `on` / `off`      | `$on`                         |

`$incoming` renders `1`/`0` (freshly classified SIM-call direction),
`$on` as `1`/`0`, `$reason` as the removal reason code. Available `$vars`
in a rule `line`: `$type $action $pkg $id $key $reason $incoming $on`.

### The default contract (no config file)

No `lednls_bridge.json` = built-in defaults that reproduce the classic
chgd contract 1:1 - the default rule set renders:

```
ENQ <pkg> <id>          notification posted
CAN <pkg> <id>          notification removed
RING_ON <0|1>           SIM call posted (1 incoming, 0 outgoing/ongoing)
RING_OFF                last SIM call notification gone
VOIP_ON <pkg>           messenger call posted
VOIP_OFF <pkg>          messenger call notification gone
SCREEN <0|1>            screen off/on
PULSE <0|1>             Settings.System notification_light_pulse changed
```

On every socket connect the app writes `PING` (liveness probe, the
consumer answers `PONG`) and **replays** its live state into that sink -
screen, active RING/VOIP, every active notification - so a consumer
restart mid-call re-arms cleanly. Only consumer -> app traffic is
`PONG` (ignored) and `WD <ms>` (keepalive cadence push).

## Config

Optional JSON at **`/data/local/tmp/lednls_bridge.json`** (adb-writable).
Apply without touching the running service:

```
adb shell am broadcast -a com.bastet.lednls.RELOAD_CONFIG
```

The broadcast pokes the live service directly. The config is also
re-checked by mtime on every watchdog tick, so editing the file with any
tool applies within one cadence even without the broadcast.

```jsonc
{
  // discovery mode: mirror every normalized event to logcat ("led-nls"
  // tag, "EVENT" prefix) BEFORE rule matching; independent of
  // rules/sinks. Turn it on, watch the bus, write your rules - see
  // "Discover what to route".
  "logAll": false,

  // keepalive cadence for daemon supervision, ms; 0 = off.
  // the consumer's "WD <ms>" push OVERWRITES this field (persisted
  // back into this file via su), so the value survives app restarts
  "watchdogMs": 60000,

  // daemon to supervise (pidof name) and restart path
  "daemonName": "chgd",
  "daemonPath": "/data/adb/modules/led_hal_root/chgd",

  // bridge state mirror for consumers (GUI etc.) - written by the
  // DAEMON, read by the app's consumers; the app itself never writes it
  "statusPath": "/data/local/tmp/lednls.status",

  // call classification package lists
  "dialerPkg": "com.google.android.dialer",
  "voipPkgs": ["org.telegram.messenger", "com.whatsapp"],

  // delivery endpoints: "socket" (abstract Unix) or "logcat"
  "sinks": [
    { "type": "socket", "name": "chgd_noty" },
    { "type": "logcat", "name": "log" }
  ],

  // routing: event -> rule filter -> sink, line rendered with $vars.
  // RULES BELOW ARE THE BUILT-IN DEFAULTS, listed verbatim from code:
  // every rule must pin the action explicitly - a missing/`*` action
  // matches BOTH polarities (e.g. an unfiltered "ring" rule would render
  // RING_ON for ring-off too).
  "rules": [
    { "event": "notify", "action": "posted",  "pkg": "*", "to": "chgd", "line": "ENQ $pkg $id" },
    { "event": "notify", "action": "removed", "pkg": "*", "to": "chgd", "line": "CAN $pkg $id" },
    { "event": "ring",   "action": "on",      "pkg": "*", "to": "chgd", "line": "RING_ON $incoming" },
    { "event": "ring",   "action": "off",     "pkg": "*", "to": "chgd", "line": "RING_OFF" },
    { "event": "voip",   "action": "on",      "pkg": "*", "to": "chgd", "line": "VOIP_ON $pkg" },
    { "event": "voip",   "action": "off",     "pkg": "*", "to": "chgd", "line": "VOIP_OFF $pkg" },
    { "event": "screen", "action": "on",      "pkg": "*", "to": "chgd", "line": "SCREEN 1" },
    { "event": "screen", "action": "off",     "pkg": "*", "to": "chgd", "line": "SCREEN 0" },
    { "event": "pulse",  "action": "on",      "pkg": "*", "to": "chgd", "line": "PULSE 1" },
    { "event": "pulse",  "action": "off",     "pkg": "*", "to": "chgd", "line": "PULSE 0" }
  ]
}
```

Rule fields: `event` (`notify`|`ring`|`voip`|`screen`|`pulse`|`*`),
`action` (`posted`|`removed`|`on`|`off`, or omit/`*` for any), `pkg`
(exact, `prefix*` glob, or `*`), `to` (sink name), `line` (template).

**Extending without code:** prune the rules to forward only what you
need; point `to` at your own socket; switch a `line` to your own format;
route events to `log` for a logcat mirror; add a second socket sink for a
second consumer.

## Discover what to route

Not sure which events a package produces, or what a rule should render?
Set `"logAll": true`, apply, and watch the raw bus:

```
adb shell am broadcast -a com.bastet.lednls.RELOAD_CONFIG
adb logcat -s led-nls:* | grep ' EVENT '
```

Every event is mirrored to logcat **before** rule matching (rules are
not needed - only the `logAll` flag in the config file):

```
EVENT notify posted pkg=org.telegram.messenger id=123 key=0|... reason=0 incoming=0 on=0
EVENT ring on pkg=com.google.android.dialer id=7 key=... reason=0 incoming=1 on=0
EVENT screen off pkg= id=-1 key= reason=0 incoming=0 on=0
```

`incoming=1` tells you the call classification fired (ring/voip);
`$pkg`, `$action`, `$on` are exactly what you copy into `voipPkgs`,
`dialerPkg`, rules and `line` templates.

## Call classification

- **ring (SIM calls)** - package == `dialerPkg` and NOT a missed-call row
  (channel id containing `missed`). Fired when the notification has
  `CATEGORY_CALL`, a `call`-ish channel id, or answer/decline/reject/
  end-call/hang-up actions. `$incoming` = 1 when an answer/decline/
  reject action or an `incoming`/`ring` channel is present.
- **voip (messenger calls)** - package in `voipPkgs` with
  `CATEGORY_CALL`, a `call`-ish channel id, or answer/decline/reject/
  end-call/hang-up actions. A plain chat message matches none of those
  and takes the normal notify path.

## Daemon supervision (watchdog)

At `watchdogMs` cadence (config value, or consumer `WD` push, or 60000
default; `0` disables) the app asks su whether the daemon binary is
alive (`pidof`) and restarts it when not (`setsid <daemonPath> &`).
The system rebinds notification listeners on its own, so the supervisor
outlives any shell keepalive.

Pulse disarms fall back to `kill -USR2` over su only when no socket is
connected (a non-root path unthinkable on this kernel); with a live
socket the pulse event carries it.

## Requirements

- **Notification access** (Settings -> Special app access -> Notification
  access -> LED NLS) - required for the listener itself.
- **Root** (KernelSU / Magisk) - only for the watchdog (pidof/restart),
  the `WD`-persist write-back and the SIGUSR2 fallback. Plain event
  forwarding to a socket needs no root.
- Android 10+ (minSdk 29).

## Build

```
gradle.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Self-test from the shell (posts a test notification through the bridge):
`adb shell am startservice -n com.bastet.lednls/.LedNotificationListenerService -a com.bastet.lednls.POST_TEST`

## Files

- `LedNotificationListenerService.kt` - observer front, call classifiers, replay, watchdog
- `BridgeConfig.kt` - config parse + defaults, `WD` persist
- `EventRouter.kt` - `BridgeEvent` + rule matching + `$vars` render + `logAll` mirror
- `Sinks.kt` - socket / logcat sinks, per-sink reconnect loops
- `ReloadReceiver.kt` - RELOAD_CONFIG broadcast entry (live-instance poke)
- `RootShell.kt` / `Su.kt` / `SuShell.kt` - root shell plumbing for the watchdog paths