# notify-bridge - standalone notification bridge

A config-driven `NotificationListenerService` that turns Android
notification events into text lines for any consumer over abstract Unix
sockets. No UI, headless, single APK (`com.bastet.notifybridge`).

Pure transport: the app only observes notifications and forwards them. It
needs no root, never touches `su`, and knows nothing about whatever
process listens on the socket - consumer wiring lives entirely in the
device-side config (`/data/local/tmp/notifybridge.json` plus
`/data/local/tmp/notifybridge.d/*.json` fragments).

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

### No config = passive bridge

There is no built-in default contract anymore. With no `notifybridge.json`
and no fragments the bridge is fully passive: no sinks, no rules, no
observers, nothing sent. The config IS the whole policy - the closest
thing to the old classic contract is `config/notifybridge.example.json`
(deploy with `config/deploy-config.ps1`).

On every socket connect the app **replays** its live state into that
sink - screen, active RING/VOIP, every active notification - so a
consumer restart mid-call re-arms cleanly. The socket is strictly
one-way; the app never reads a consumer line.

## Config

Optional JSON, adb-writable, re-read on demand (see "Applying",
below). **The whole policy is config and nothing else - a missing or
broken config just means a passive bridge.** Only explicitly listed
fields take effect; there are no built-in defaults to fall back to.

The config is **drop-in**: `notifybridge.json` (optional) is merged
with every `*.json` inside `/data/local/tmp/notifybridge.d/` (optional
too), main file first, fragments in alphabetical order. Each app or
helper keeps its own fragment and pushes/replaces it without touching
anyone else's file.

Merge rules: `sinks` and `watchedSettings` keep one entry per key
(last occurrence wins), `rules` are concatenated with exact duplicates
collapsed (last wins), `voipPkgs` is a union, `dialerPkg` = first
non-empty value, `logAll` = any fragment with it enabled. A broken
fragment is skipped and logged (`E notifybridge: fragment ... invalid,
skipped`) - it never kills the rest. Example fragment:
`config/notifybridge.d/fragment.example.json`.

### Applying

```
adb shell am broadcast -a com.bastet.notifybridge.RELOAD_CONFIG
```

The broadcast pokes the live service directly and re-reads + rebuilds
everything from all files. There is no polling - this broadcast (or the
service (re)start) is the only way the config is re-read. On some ROMs
a chilled process misses the broadcast ("Broadcast completed" without
"Delivering") - the reliable fallback is
`adb shell am force-stop com.bastet.notifybridge` (the rebind applies
the config). `config/deploy-config.ps1` does the whole dance:
`.\deploy-config.ps1` for the main file, `-DropInName <name>` to push a
fragment instead, `-RestartService` for the force-stop path.

```jsonc
{
  // discovery mode: mirror every normalized event to logcat ("notifybridge"
  // tag, "EVENT" prefix) BEFORE rule matching; independent of
  // rules/sinks. Turn it on, watch the bus, write your rules - see
  // "Discover what to route".
  "logAll": false,

  // call classification package lists
  "dialerPkg": "com.google.android.dialer",
  "voipPkgs": ["org.telegram.messenger", "com.whatsapp"],

  // delivery endpoints: "socket" (abstract Unix) or "logcat".
  // name = sink key; for a socket it is also the abstract socket name.
  "sinks": [
    { "type": "socket", "name": "notify_bus" },
    { "type": "logcat", "name": "log" }
  ],

  // routing: event -> rule filter -> sink, line rendered with $vars.
  // every rule must pin the action explicitly - a missing/`*` action
  // matches BOTH polarities (e.g. an unfiltered "ring" rule would render
  // RING_ON for ring-off too).
  "rules": [
    { "event": "notify", "action": "posted",  "pkg": "*", "to": "notify_bus", "line": "ENQ $pkg $id" },
    { "event": "notify", "action": "removed", "pkg": "*", "to": "notify_bus", "line": "CAN $pkg $id" },
    { "event": "ring",   "action": "on",      "pkg": "*", "to": "notify_bus", "line": "RING_ON $incoming" },
    { "event": "ring",   "action": "off",     "pkg": "*", "to": "notify_bus", "line": "RING_OFF" },
    { "event": "voip",   "action": "on",      "pkg": "*", "to": "notify_bus", "line": "VOIP_ON $pkg" },
    { "event": "voip",   "action": "off",     "pkg": "*", "to": "notify_bus", "line": "VOIP_OFF $pkg" },
    { "event": "screen", "action": "on",      "pkg": "*", "to": "notify_bus", "line": "SCREEN 1" },
    { "event": "screen", "action": "off",     "pkg": "*", "to": "notify_bus", "line": "SCREEN 0" },
    { "event": "pulse",  "action": "on",      "pkg": "*", "to": "notify_bus", "line": "PULSE 1" },
    { "event": "pulse",  "action": "off",     "pkg": "*", "to": "notify_bus", "line": "PULSE 0" }
  ]
}
```

A config (main or fragment) is minimal by design: omit a key and it
simply does nothing in the merge - so a fragment may contain only
`watchedSettings`, or an app may only add `voipPkgs`. A totally broken
main file puts the bridge into passive until fixed; a broken fragment is
skipped on its own.

Rule fields:
  `event` (`notify`|`ring`|`voip`|`screen`|`pulse`|`*`),
  `action` (`posted`|`removed`|`on`|`off`, or omit/`*` for any), `pkg`
  (exact, `prefix*` glob, or `*`), `to` (sink key), `line` (template).
  A rule without `to`/`line` is dropped at parse.

Watched settings: additional device-side toggles forwarded as 0/1 bus
events. Each entry lists a `table` (`system`|`global`|`secure`), a
`name`, the `event` type it maps to (optional, `pulse`), and `defaultOn`
(optional, `false`) - what to forward if the setting is unset
(on = 1, off = 0):

```jsonc
"watchedSettings": [
  { "table": "system", "name": "notification_light_pulse",
    "event": "pulse", "defaultOn": true }
]
```

Rules matching a `setting` filter (`"setting": "notification_light_pulse"`)
react to that setting only; the example above feeds the `pulse` event
family regardless of rule filters.

**Extending without code:** prune the rules to forward only what you
need; point `to` at your own sink; switch a `line` to your own format;
route events to `log` for a logcat mirror; add a second socket sink for a
second consumer.

## Discover what to route

Not sure which events a package produces, or what a rule should render?
Set `"logAll": true`, apply, and watch the raw bus:

```
adb shell am broadcast -a com.bastet.notifybridge.RELOAD_CONFIG
adb logcat -s notifybridge:* | grep ' EVENT '
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

## Requirements

- **Notification access** (Settings -> Special app access -> Notification
  access -> NotifyBridge) - required for the listener itself.
- **No root.** The app never calls `su`; it only reads its own config and
  writes to an abstract Unix socket. Whatever consumes the socket (and
  any supervision of that consumer) is the consumer's own business.
- Android 10+ (minSdk 29).

## Build

```
gradle.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Self-test from the shell (posts a test notification through the bridge):
`adb shell am startservice -n com.bastet.notifybridge/.NotificationBridgeService -a com.bastet.notifybridge.POST_TEST`

## Files

- `NotificationBridgeService.kt` - observer front, call classifiers, replay, config reload on broadcast
- `BridgeConfig.kt` - config parse, drop-in fragment merge, watched settings
- `EventRouter.kt` - `BridgeEvent` + rule matching + `$vars` render + `logAll` mirror
- `Sinks.kt` - socket / logcat sinks, per-sink reconnect loops
- `ReloadReceiver.kt` - RELOAD_CONFIG broadcast entry (live-instance poke)