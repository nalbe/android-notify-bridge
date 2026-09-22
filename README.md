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
2. Classify: SIM calls (dialer package) and messenger calls (VoIP package
   list) are detected by built-in heuristics - a raw notification has no
   "this is a call" marker, so pkg + channelId/category/action titles are
   checked (details below). Both package lists are config.
3. Route: every event is matched against the merged route list; each match
   renders a text line and sends it to the configured sink (abstract Unix
   socket or logcat). Routes live in their owning source section
   (`notifications` / `broadcasts` / `settings`) plus an optional global
   `routes` table matched across every source.

### Events

Every event carries all normalized fields; irrelevant ones are empty/`-1`/`0`:

| type    | action                 | populated fields              |
|---------|------------------------|-------------------------------|
| notify  | `posted` / `removed`   | `$pkg` `$id` `$key` (+ `$reason` on removed) |
| ring    | `on` / `off`           | `$pkg` `$id` `$incoming` (on); `$pkg` (off) |
| voip    | `on` / `off`           | `$pkg` `$id` (on); `$pkg` (off) |
| screen  | `on` / `off`           | `$on` (on/off broadcasts and snapshot replay) |
| pulse / any settings event | `on` / `off` | `$setting` `$on`      |
| charge / any broadcast event | polarity, `eventAction`, or raw intent action | `$on` (when `polarity`) + any mapped broadcast extra |

`$incoming` renders `1`/`0` (freshly classified SIM-call direction),
`$on` as `1`/`0`, `$reason` as the removal reason code. Available `$vars`
in a route `line`: `$type $action $pkg $id $key $reason $incoming
$setting $on`, plus any broadcast extra mapped through
`broadcasts[].fields` (see below) - e.g. `$level`, `$status`, `$plugged`
for battery events. Broadcast extras are rendered after the built-ins; a
field asked to shadow a built-in name is skipped at parse.

### Route matching

A route `when` matches the dotted `<type>.<action>` id of an event. Either
side may be `*`, so the grammar is one rule with no hidden aliases:

| when        | matches                                            |
|-------------|----------------------------------------------------|
| `notify.posted` | only posted notifications                       |
| `ring.*`    | any ring event (on + off)                          |
| `*.on`      | every `on` event from the route's scope            |
| `*`         | everything the route's scope sees                  |

Section routes (`notifications.out`, an entry's `out`) are **scope-
confined**: a `*` inside `notifications.out` never escapes the
notify/ring/voip vocabulary, a `*` in a broadcast entry's `out` only
sees that entry's own event. Global `routes` are **unconfined** - a
lone `when: "*"` there is the raw tube: one entry mirroring everything
the bus emits into one sink.

### No sources = passive bridge

There is no built-in default contract anymore. The bridge is fully
passive - no sinks, no routes, no observers, nothing sent - whenever no
source is active: no `notifybridge.json` and no fragments at all, a
broken main file, or a valid config with no enabled `notifications`
section, empty `broadcasts` and empty `settings`. The config IS the
whole policy - the closest thing to the old classic contract is
`config/notifybridge.example.json` (deploy with
`config/deploy-config.ps1`).

On every socket connect the app **replays** its live state into that
sink through that sink's own routes - screen polarity (from
`snapshot: true` broadcast entries), active RING/VOIP, every active
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
  `enabled`, call classification package lists (`calls.dialer` /
  `calls.voip`) and `out` routes for the fixed notify/ring/voip events.
  Omitting the section or `enabled: false` stops notification listening.
- `broadcasts` (list) - system broadcasts, one entry per intent action.
  Each entry carries its own `out` routes plus `polarity` / `snapshot` /
  `fields` (below).
- `settings` (list) - Settings toggles observed via ContentObservers,
  one entry per key, each carrying its own `out`.
- `routes` (optional, top-level) - the global routing table, matched
  across every source (`when: "*"` = raw tube of everything).
- `sinks` - delivery endpoints; `logAll` - discovery mirror; `reload` -
  whether the RELOAD_CONFIG broadcast is honored.

The config is **drop-in**: `notifybridge.json` (optional) is merged
with every `*.json` inside `/data/local/tmp/notifybridge.d/` (optional
too), main file first, fragments in alphabetical order. Each app or
helper keeps its own fragment and pushes/replaces it without touching
anyone else's file.

Merge rules: `sinks` one per name, `broadcasts` one per action,
`settings` one per table/name (all last occurrence wins);
`notifications.out` and global `routes` append with exact duplicates
collapsed; `notifications.calls.voip` union; `notifications.calls.dialer`
first non-empty; `logAll` any fragment with it enabled wins; `reload`
merges as AND (any fragment setting it off keeps it off). A broken
fragment is skipped and logged
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
  "notifications": {
    "enabled": true,
    // call classification package lists (SIM + messenger)
    "calls": {
      "dialer": "com.google.android.dialer",
      "voip": ["org.telegram.messenger", "com.whatsapp"]
    },
    // section-local routes, scope confined to notify/ring/voip.
    // 'when' is strictly validated: notify|ring|voip x
    // posted|removed|on|off (either side may be '*').
    "out": [
      { "when": "notify.posted", "to": "notify_bus", "line": "ENQ $pkg $id" },
      { "when": "notify.removed", "to": "notify_bus", "line": "CAN $pkg $id" },
      { "when": "ring.on",  "to": "notify_bus", "line": "RING_ON $incoming" },
      { "when": "ring.off", "to": "notify_bus", "line": "RING_OFF" },
      { "when": "voip.on",  "to": "notify_bus", "line": "VOIP_ON $pkg" },
      { "when": "voip.off", "to": "notify_bus", "line": "VOIP_OFF $pkg" }
    ]
  },

  // ---- source: system broadcasts ---------------------------------
  // one dynamic receiver, everything config-driven. action = intent
  // action, event = bus event type, polarity = fixed on/off AND the
  // $on bit, snapshot = re-emit current screen state on socket
  // connect, fields = intent extra -> $var name in the rendered line.
  "broadcasts": [
    { "action": "android.intent.action.SCREEN_OFF", "event": "screen",
      "polarity": "off", "snapshot": true,
      "out": [ { "when": "*", "to": "notify_bus", "line": "SCREEN $on" } ] },
    { "action": "android.intent.action.SCREEN_ON", "event": "screen",
      "polarity": "on", "snapshot": true,
      "out": [ { "when": "*", "to": "notify_bus", "line": "SCREEN $on" } ] },
    { "action": "android.intent.action.POWER_CONNECTED", "event": "charge",
      "polarity": "on",
      "out": [ { "when": "*", "to": "notify_bus", "line": "CHG 1" } ] },
    { "action": "android.intent.action.POWER_DISCONNECTED", "event": "charge",
      "polarity": "off",
      "out": [ { "when": "*", "to": "notify_bus", "line": "CHG 0" } ] },
    { "action": "android.intent.action.BATTERY_CHANGED", "event": "battery",
      "fields": { "level": "level", "status": "status",
                  "plugged": "plugged", "scale": "scale" },
      "out": [ { "when": "*", "to": "log", "line": "CHG $status $level plugged=$plugged" } ] }
  ],

  // ---- source: settings toggles ----------------------------------
  // watched Settings keys forwarded as 0/1 bus events; each entry
  // carries its own out routes (scope confined to its own event).
  "settings": [
    { "table": "system", "name": "notification_light_pulse",
      "event": "pulse", "defaultOn": true,
      "out": [ { "when": "*", "to": "notify_bus", "line": "PULSE $on" } ] }
  ],

  // ---- global routing table ---------------------------------------
  // matched across EVERY source: the raw tube and cross-source hooks.
  "routes": [
    { "when": "*.on",  "to": "log", "line": "ANYON 1" },
    { "when": "*.off", "to": "log", "line": "ANYOFF 0" }
  ]
}
```

A config (main or fragment) is minimal by design: omit a key and it
simply does nothing in the merge - so a fragment may contain only
`settings`, or an app may only add `notifications.calls.voip` plus a
route. A totally broken main file puts the bridge into passive until
fixed; a broken fragment is skipped on its own.

Route fields:
  `when` (`<type>.<action>`, either side `*`, or `*`), `pkg` (exact,
  `prefix*` glob, or `*` - meaningful for notification events), `to`
  (sink key), `line` (template, optional - defaults to
  `$type $action $pkg $id`). A route without `to` is dropped at parse;
  an unknown `when` inside `notifications.out` is dropped with a
  warning (typos never silently die).

Broadcasts: the system broadcast channel is fully config-driven - there
are no hardcoded actions in the code. Register any intent action the
framework delivers to a dynamic receiver; a sticky broadcast (like
`BATTERY_CHANGED`) also replays its current state the moment the config
is applied. Extras are mapped per entry: `fields` maps an intent extra
key to the `$var` name usable in route lines (values render as
strings). The bus action for an entry is: `polarity` (`on`/`off` - also
sets real `$on`) if set, else `eventAction`, else the raw intent action
($on stays 0 for raw variadic extras). `snapshot: true` makes the entry
feed the connect replay: on a socket connect the bridge re-emits the
current screen polarity (PowerManager) through that entry's routes.
Protected system broadcasts need no permission for a dynamic receiver;
only the receiver for the listed actions is ever registered.

Settings: each entry lists a `table` (`system`|`global`|`secure`), a
`name`, the `event` type it maps to (optional, `pulse`), and `defaultOn`
(optional, `false`) - what to forward if the setting is unset
(on = 1, off = 0). A change forwards `<event> <on>/<off>` with `$setting`
set, through the entry's `out` routes.

Passive sections = not listened to: `broadcasts: []`/absent registers
no receiver, `settings: []`/absent registers no observer,
`notifications` absent or `enabled: false` drops every notification
callback (no classification, no bookkeeping), `reload: false` freezes
the running config until the service restarts.

**Extending without code:** edit the route lists to forward only what
you need; point `to` at your own sink; switch a `line` to your own
format; route events to `log` for a logcat mirror; add a second socket
sink for a second consumer; add a global route with `when: "*"` for a
raw tube of everything.

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
EVENT notify posted pkg=org.telegram.messenger id=123 key=0|... reason=0 incoming=0 setting= on=0
EVENT ring on pkg=com.google.android.dialer id=7 key=... reason=0 incoming=1 setting= on=0
EVENT screen off pkg= id=-1 key= reason=0 incoming=0 setting= on=0
EVENT pulse on pkg= id=-1 key= reason=0 incoming=0 setting=notification_light_pulse on=1
EVENT battery android.intent.action.BATTERY_CHANGED pkg= id=-1 key= reason=0 incoming=0 setting= on=0 fields=status=2,level=55,plugged=1
```

`incoming=1` tells you the call classification fired (ring/voip);
`$pkg`, `$action`, `$on` are exactly what you copy into `calls.voip`,
`calls.dialer`, `when` patterns and `line` templates; `setting=` names
the watched Settings key; `on=1` confirms a real polarity bit from
`polarity`/`snapshot` entries. For broadcast-sourced events a `fields=`
chunk lists the mapped extras - those names are your route line `$vars`.

### Log tags: one filterable stream per source

Every line the bridge writes goes to exactly one logcat tag, split by
bridge source instead of one shared stream:

| tag          | what lands there                                       |
|--------------|--------------------------------------------------------|
| `nb-core`    | config apply, sinks/sockets, reload, fragment errors   |
| `nb-notify`  | NLS posted/removed, ring/voip classification           |
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

- **ring (SIM calls)** - package == `notifications.calls.dialer` and NOT
  a missed-call row (channel id containing `missed`). Fired when the
  notification has `CATEGORY_CALL`, a `call`-ish channel id, or
  answer/decline/reject/end-call/hang-up actions. `$incoming` = 1 when
  an answer/decline/reject action or an `incoming`/`ring` channel is
  present.
- **voip (messenger calls)** - package in `notifications.calls.voip`
  with `CATEGORY_CALL`, a `call`-ish channel id, or
  answer/decline/reject/end-call/hang-up actions. A plain chat message
  matches none of those and takes the normal notify path.

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