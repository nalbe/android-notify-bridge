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

1. Observe: posted / removed notifications, system broadcasts (screen
   on/off, plug/unplug, battery level - any action the framework
   broadcasts, listed in the config), system Settings toggles (the
   "Notification light" LED switch and anything else you want as a 0/1
   event).
2. Classify: live calls (SIM telephony or messenger VoIP, any package)
   by notification markers - category CALL, a "call"-ish channel id, or
answer/decline/reject/end-call actions (details below). A missed-call
    tombstone (channel contains "missed") is classified as `missed_call`.
   Which package renders as a SIM call is a route concern (`pkg` filter),
   not a code concern.
3. Route: every event is matched against the merged route list; each match
   renders a text line and sends it to the configured sink (abstract Unix
   socket or logcat). Routes live in their owning source section
   (`notifications` / `broadcasts` / `settings`) plus an optional global
   `routes` table matched across every source.

### Events

Every event carries all normalized fields; irrelevant ones are empty/`-1`/`0`:

| type    | action                 | populated fields              |
|---------|------------------------|-------------------------------|
| notify  | `posted` / `removed`   | `$category` `$pkg` `$id` `$key` (+ `$reason` on removed, `$incoming` on live calls) |
| screen / charge / battery (any broadcast event) | `on`/`off`, `eventAction`, or raw intent action | `$category` (=route event) `$on` (when `polarity`) + any mapped broadcast extra |
| any settings event | `on` / `off` | `$category` (=watched key) `$setting` `$on` |

For notifications `$category` is the routing split: the normalized
`call` / `missed_call`, an app's own `Notification.category` value, or
empty when the app set none. `$incoming` renders `1`/`0` (freshly
classified call direction), `$on` as `1`/`0`, `$reason` as the removal
reason code. Available `$vars` in a route `line`: `$type $action
$category $pkg $id $key $reason $incoming $setting $on`, plus any
broadcast extra mapped through a broadcast route's `fields` (see below)
- e.g. `$level`, `$status`, `$plugged` for battery events. Broadcast
extras are rendered after the built-ins; a field asked to shadow a
built-in name is skipped at parse.

### Route matching

A route matches on two axes: `action` is the event action
(`posted`/`removed`/`on`/`off`, each may be `*`) and `category` is the
routing split - for notifications the normalized category (exact value,
`*` = any, `""` = only notifications carrying no category), for
broadcast events the route event, for settings the watched key:

| action / category     | matches                                        |
|-----------------------|------------------------------------------------|
| `posted` / `call`     | the start of a live call (any matching package)|
| `posted` / `reminder` | a reminder-category notification posted        |
| `removed` / `*`       | every notification removal                     |
| `on` / `screen`       | screen on/off polarity events                  |
| `*` / `*`             | anything the route's scope sees                |

Section routes are **scope-confined**: a `*` inside
`notifications.out` never escapes the notify event, and every
broadcast/settings route is confined to its own event type (the `event`
its trigger declares). Global `routes` are **unconfined** - a route with
`action: "*"`+`category: "*"` there is the raw tube: one entry mirroring
everything the bus emits into one sink.

### No sources = passive bridge

There is no built-in default contract anymore. The bridge is fully
passive - no sinks, no routes, no observers, nothing sent - whenever no
source is active: no `notifybridge.json` and no fragments at all, a
broken main file, or a valid config with absent/disabled `notifications`,
`broadcasts` and `settings` sections. The config IS the
whole policy - the closest thing to the old classic contract is
`config/notifybridge.example.json` (deploy with
`config/deploy-config.ps1`).

On every socket connect the app **replays** its live state into that
sink through that sink's own routes - screen polarity (from
`snapshot: true` broadcast entries), active CALL/MISSED, every active
notification - so a consumer restart mid-call re-arms cleanly. The
socket is strictly one-way: there is no command channel and no consumer
line is ever acted on (the sink only reads the stream to detect EOF for
reconnect).

## Config

Optional JSON, adb-writable, re-read on demand (see "Applying",
below). **The whole policy is config and nothing else - a missing or
broken config, or a config with no active source, just means a passive
bridge.** Only explicitly listed fields take effect; there are no
built-in defaults to fall back to.

The config is organized as self-contained **sections - one per event
source** - and each section both says what it listens to and how to
route what it yields:

- `notifications` (optional) - the NotificationListenerService source:
  `enabled` and `out` routes split on `action` + `category`. Every
  notification is one `notify` event; live-call / missed-call
  classification runs on any package by notification markers (see "Call
  classification") and picks the route category - `call` / `missed_call`
  / the app's own category / "" when none. A call from a package with no
  matching `category:"call"` route still hits any raw `category:"*"`
  route. Omitting the section or `enabled: false` stops notification
  listening.
- `broadcasts` (object) - system broadcasts: `enabled` plus a flat `out`
  list. Every route is self-contained: its trigger (`action` intent,
  `event` bus type / category, `polarity`, `snapshot`, `fields`) picks
  the event, `to`/`line` route it. One intent action may fan out over
  several routes to different sinks. Absent or `enabled: false` = no
  receiver registered.
- `settings` (object) - Settings toggles observed via ContentObservers:
  `enabled` plus a flat `out` list of inline routes (`table`, `name`,
  `event`, `defaultOn`, `to`, `line`). One key may fan out over several
  routes. Absent or `enabled: false` = no observers registered.
- `routes` (optional, top-level) - the global routing table, matched
  across every source (`action: "*"` + `category: "*"` = raw tube of
  everything).
- `sinks` - delivery endpoints; `logAll` - discovery mirror; `reload` -
  whether the RELOAD_CONFIG broadcast is honored.

The config is **drop-in**: `notifybridge.json` (optional) is merged
with every `*.json` inside `/data/local/tmp/notifybridge.d/` (optional
too), main file first, fragments in alphabetical order. Each app or
helper keeps its own fragment and pushes/replaces it without touching
anyone else's file.

Merge rules: `sinks` one per name, all others append - `broadcasts.out`,
`settings.out`, `notifications.out` and global `routes` collapse exact
duplicates (last occurrence wins), so fragments freely add outputs for
the same intent action or settings key; section `enabled` merges as
AND; `logAll` any fragment with it enabled wins; `reload` merges as AND
(any fragment setting it off keeps it off). A broken fragment is skipped
and logged
(`E nb-core: fragment ... invalid, skipped: ...`) - it never kills the
rest. Example fragment: `config/notifybridge.d/fragment.example.json`.

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
  // delivery endpoints: "socket" (abstract Unix) or "logcat".
  // name = sink key; for a socket it is also the abstract socket name.
  "sinks": [
    { "type": "socket", "name": "notify_bus" },
    { "type": "logcat", "name": "log" }
  ],

  // discovery mode: mirror every event to logcat (per-source tags,
  // "EVENT" prefix) before routing - see "Discover what to route".
  "logAll": false,
  // honor the RELOAD_CONFIG broadcast (absent = true)
  "reload": true,

  // ---- source: notifications -------------------------------------
  // every notification surfaces as ONE notify event; classification
  // picks the route category: 'call' / 'missed_call' / the app's own /
  // '' when none. SIM vs messenger is decided here per route: dialers
  // render as RING_*, messenger apps as VOIP_*. Routing is a fan-out:
  // EVERY matching route fires, so split calls per package with
  // specific-pkg routes and keep a '*' category route off that sink
  // unless you want the raw pool to see the same event twice.
  "notifications": {
    "enabled": true,
    "out": [
      { "action": "posted", "category": "*", "to": "notify_bus", "line": "ENQ $pkg $id" },
      { "action": "removed", "category": "*", "to": "notify_bus", "line": "CAN $pkg $id" },
      { "action": "posted", "category": "call", "pkg": "com.google.android.dialer", "to": "notify_bus", "line": "RING_ON $incoming" },
      { "action": "removed", "category": "call", "pkg": "com.google.android.dialer", "to": "notify_bus", "line": "RING_OFF" },
      { "action": "posted", "category": "call", "pkg": "org.telegram.messenger", "to": "notify_bus", "line": "VOIP_ON $pkg" },
      { "action": "removed", "category": "call", "pkg": "org.telegram.messenger", "to": "notify_bus", "line": "VOIP_OFF $pkg" },
      { "action": "posted", "category": "missed_call", "to": "notify_bus", "line": "MISSED_ON $id" },
      { "action": "removed", "category": "missed_call", "to": "notify_bus", "line": "MISSED_OFF $id" }
    ]
  },

  // ---- source: system broadcasts ---------------------------------
  // one dynamic receiver, everything config-driven; each route is
  // self-contained: action = intent action, event = bus event type
  // (the route category), polarity = fixed on/off AND the $on bit,
  // snapshot = re-emit current screen state on socket connect,
  // fields = intent extra -> $var name. One intent action may fan
  // out over several routes to different sinks.
  "broadcasts": {
    "enabled": true,
    "out": [
      { "action": "android.intent.action.SCREEN_OFF", "event": "screen",
        "polarity": "off", "snapshot": true,
        "to": "notify_bus", "line": "SCREEN $on" },
      { "action": "android.intent.action.SCREEN_ON", "event": "screen",
        "polarity": "on", "snapshot": true,
        "to": "notify_bus", "line": "SCREEN $on" },
      { "action": "android.intent.action.POWER_CONNECTED", "event": "charge",
        "polarity": "on", "to": "notify_bus", "line": "CHG 1" },
      { "action": "android.intent.action.POWER_DISCONNECTED", "event": "charge",
        "polarity": "off", "to": "notify_bus", "line": "CHG 0" },
      { "action": "android.intent.action.BATTERY_CHANGED", "event": "battery",
        "fields": { "level": "level", "status": "status",
                    "plugged": "plugged", "scale": "scale" },
        "to": "log", "line": "CHG $status $level plugged=$plugged" }
    ]
  },

  // ---- source: settings toggles ----------------------------------
  // watched Settings keys forwarded as 0/1 bus events; watch the
  // key + choose the line, all in one inline route. A change emits
  // <event> <on>/<off> with $setting set, the route category is the
  // watched key name.
  "settings": {
    "enabled": true,
    "out": [
      { "table": "system", "name": "notification_light_pulse",
        "event": "pulse", "defaultOn": true,
        "to": "notify_bus", "line": "PULSE $on" }
    ]
  },

  // ---- global routing table ---------------------------------------
  // matched across EVERY source: the raw tube and cross-source hooks.
  "routes": [
    { "action": "on",  "category": "*", "to": "log", "line": "ANYON 1" },
    { "action": "off", "category": "*", "to": "log", "line": "ANYOFF 0" }
  ]
}
```

A config (main or fragment) is minimal by design: omit a key and it
simply does nothing in the merge - so a fragment may contain only
`settings`, or an app may only add a `posted`/`removed` route pair with
`category:"call"` for its own package plus a route. A totally broken
main file puts the bridge into passive until fixed; a broken fragment
is skipped on its own.

Route fields:
  `action` (`posted`/`removed`/`on`/`off`, or `*`), `category` (the
  routing split - for notifications the normalized category, exact
  value / `*` any / `""` only none; for broadcast events the event;
  for settings the watched key), `pkg` (exact, `prefix*` glob, or `*` -
  meaningful for notification events), `to` (sink key), `line`
  (template, optional - defaults to `$type $action $pkg $id`). A route
  without `to` is dropped at parse; an unknown `action` inside
  `notifications.out` is dropped with a warning (typos never silently
  die); the retired `when` key and the old per-entry `out` nesting are
  dropped with a warning too.

Broadcast routes: self-contained - the route IS the entry. `action` =
the intent action to register, `event` = the bus event type (the route
category), `polarity` (`on`/`off`) = fixed bus action AND the real `$on`
bit, `eventAction` = a custom bus action if you want neither polarity
nor the raw intent action, `snapshot: true` = feed the connect replay
(re-emit the current screen polarity via this route), `fields` maps
intent extras (BatteryManager.EXTRA_* etc.) to `$vars`. The bus action
is `polarity` when set, else `eventAction`, else the raw intent action
($on stays 0 for raw variadic extras). One intent action may appear on
any number of routes (fan-out) - every matching route emits its own
event. Sticky broadcasts (`BATTERY_CHANGED`) replay their current state
the moment the config is applied. Protected system broadcasts need no
permission for a dynamic receiver; only the listed actions are ever
registered.

Settings routes: also self-contained. `table` (`system`|`global`|
`secure`), `name` (watched key), `event` (bus event type, optional,
`pulse`), `defaultOn` (optional, `false`) - what to forward if the
setting is unset (on = 1, off = 0). A change emits `<event> <on>/<off>`
with `$setting` set through every route matching the table/name.

Passive sections = not listened to: absent or `enabled: false`
`broadcasts` registers no receiver, absent or `enabled: false`
`settings` registers no observer, `notifications` absent or
`enabled: false` drops every notification callback (no classification,
no bookkeeping), `reload: false` freezes the running config until the
service restarts.

**Extending without code:** edit the route lists to forward only what
you need; point `to` at your own sink; switch a `line` to your own
format; route events to `log` for a logcat mirror; add a second socket
sink for a second consumer; add a global route with `action: "*"` +
`category: "*"` for a raw tube of everything.

## Discover what to route

Not sure which events a package produces, or what a route should
render? Set `"logAll": true`, apply, and watch the raw bus:

```
adb shell am broadcast -a com.bastet.notifybridge.RELOAD_CONFIG
adb logcat -s nb-notify:* nb-bcast:* nb-settings:* nb-core:* | findstr " EVENT "
```

(On Linux/macOS swap `findstr " EVENT "` for `grep ' EVENT '`.)

Every event is mirrored to logcat **before** routing (routes are not
needed - only the `logAll` flag and at least one active source in the
config file):

```
EVENT notify posted category= pkg=org.telegram.messenger id=123 key=0|... reason=0 incoming=0 setting= on=0
EVENT notify posted category=call pkg=com.google.android.dialer id=7 key=... reason=0 incoming=1 setting= on=0
EVENT notify posted category=missed_call pkg=com.android.dialer id=9 key=... reason=0 incoming=0 setting= on=0
EVENT screen off category=screen pkg= id=-1 key= reason=0 incoming=0 setting= on=0
EVENT pulse on category=notification_light_pulse pkg= id=-1 key= reason=0 incoming=0 setting=notification_light_pulse on=1
EVENT battery android.intent.action.BATTERY_CHANGED category=battery pkg= id=-1 key= reason=0 incoming=0 setting= on=0 fields=status=2,level=55,plugged=1
```

`category=call` tells you the live-call classification fired (route it
with `category:"call"`); `$pkg`, `$action`, `$category`, `$on` are
exactly what you copy into route `pkg` filters, `category`/`action`
values and `line` templates; `setting=` names the watched Settings key;
`on=1` confirms a real polarity bit from `polarity`/`snapshot` entries.
For broadcast-sourced events a `fields=` chunk lists the mapped extras -
those names are your route line `$vars`.

### Log tags: one filterable stream per source

Every line the bridge writes goes to exactly one logcat tag, split by
bridge source instead of one shared stream:

| tag          | what lands there                                       |
|--------------|--------------------------------------------------------|
| `nb-core`    | config apply, sinks/sockets, reload, fragment errors   |
| `nb-notify`  | NLS posted/removed, call/missed classification           |
| `nb-bcast`   | registered system broadcasts (screen, charge, ...)     |
| `nb-settings`| watched Settings keys (pulse and friends)              |

The `EVENT` discovery mirror and the `log` sink are tagged by the event's
source, so a chatty notification stream never drowns the broadcast or
settings streams. Watch only what you care about:

```
adb logcat -s nb-bcast:D               # screen + charge events only
adb logcat -s nb-notify:* nb-core:D     # notifications + config plumbing
```

## Call classification

Runs on **any package**, no package lists in config. The markers are
built in and select the event's **category** - `call` (live) or
`missed_call` (tombstone):

- **`call` (live calls)** - NOT a missed-call row (channel id containing
  `missed`, which ALSO contains `call` - the tombstone check wins).
  Fired when the notification has `CATEGORY_CALL`, a `call`-ish channel
  id, or answer/decline/reject/end-call/hang-up actions. `$incoming` =
  1 when an answer/decline/reject action or an `incoming`/`ring`
  channel is present.
- **`missed_call`** - a missed-call tombstone: channel id contains
  `missed`.
- **anything else** - the app's own `Notification.category` value, or ""
  when none (a plain chat message matches no call marker and takes the
  normal path with its real category).

SIM-versus-VOIP rendering is **not** classified - it is routed. Every
event hits every matching route (fan-out), so the config lists one
`posted`/`removed` pair per package with `category:"call"`: telephony
dialers render as `RING_*`, messenger apps as `VOIP_*`; a live call
from a package with no call route still hits any `category:"*"` raw
route (drop that pair, or narrow it, to keep a live call off the raw
pool). Live-call / missed-call bookkeeping only masks the raw pool if
your config asks for it - `category:"*"` notify routes see every event,
decisions included.

## Requirements

- **Notification access** (Settings -> Special app access -> Notification
  access -> NotifyBridge) - required for the listener itself.
- **POST_NOTIFICATIONS granted** (Android 13+) - declared in the manifest,
  but the app is headless so nothing prompts for it. Without the grant the
  `POST_TEST` self-test below silently posts nothing:
  `adb shell pm grant com.bastet.notifybridge android.permission.POST_NOTIFICATIONS`
- **No root.** The app never calls `su`; it only reads its own config and
  writes to an abstract Unix socket. Whatever consumes the socket (and
  any supervision of that consumer) is the consumer's own business.
- Android 10+ (minSdk 29).

## Build

```
build-release.bat
adb install -r release\notifybridge-release.apk
```

`build-release.bat` runs `gradlew.bat assembleRelease` and stages the APK
into the project `release/` folder as `release\notifybridge-release.apk`.
`assembleRelease` alone also stages `release\notifybridge-release-debugkey.apk`
(mirrored from the gradle output). The names say it plainly: the release is
R8-minified but signed with the **debug keystore**
(`buildTypes.release.signingConfig`), so it installs over a debug build and
is never mistaken for a production-signed artifact. Debug builds are NOT
staged to `release/`.

Self-test from the shell (posts a test notification through the bridge):
`adb shell am startservice -n com.bastet.notifybridge/.NotificationBridgeService -a com.bastet.notifybridge.POST_TEST`

## Files

Kotlin sources (`app/src/main/java/com/bastet/notifybridge/`):

- `NotificationBridgeService.kt` - observer front, call classifiers, replay + snapshot, broadcast receivers, config reload on broadcast
- `BridgeConfig.kt` - config parse, drop-in fragment merge, per-source sections (notifications/broadcasts/settings) + global routes
- `EventRouter.kt` - `BridgeEvent` (+ source + broadcast extras) + scope-aware route matching + `$vars` render + `logAll` mirror
- `Sinks.kt` - socket / logcat sinks, per-sink reconnect loops; the log sink echoes routed lines under the event's source tag
- `BLog.kt` - the four per-source logcat tags (`nb-core` / `nb-notify` / `nb-bcast` / `nb-settings`)
- `ReloadReceiver.kt` - RELOAD_CONFIG broadcast entry (live-instance poke)

Project files:

- `build-release.bat` - build + stage the release APK into `release/`
- `gradlew.bat` - gradle wrapper (what `build-release.bat` calls)
- `config/deploy-config.ps1` - push main config or a drop-in fragment over adb + optional restart
- `config/notifybridge.example.json` - the canonical full example config
- `config/notifybridge.d/fragment.example.json` - example drop-in fragment
- `app/src/main/AndroidManifest.xml` - NLS declaration, `POST_NOTIFICATIONS`, RELOAD_CONFIG receiver