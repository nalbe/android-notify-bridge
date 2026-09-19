# LED NLS - standalone notification bridge

Config-driven `NotificationListenerService` that turns Android notification
events into a text stream for any consumer on an abstract Unix socket. No
UI, headless, one APK (`com.bastet.lednls`).

Built for the [shark8-led-daemon](https://github.com/nalbe/shark8-led-daemon)
project, but decoupled: **which event goes where, and in what form, is
device-side config - no rebuild to serve a different daemon.**

## What it does

A bridge bus plus a routing engine:

1. Observe: posted / removed notifications, screen on/off, the system
   "Notification light" toggle.
2. Classify: SIM calls (dialer) and messenger calls (VoIP packages) are
   detected by built-in heuristics - a raw notification carries no
   "this is a call" marker. The package lists are config.
3. Route: every event is matched against the config rule list; each match
   renders a text line and sends it to the configured sink.

### Events

| type   | action                         | fields                                  |
|--------|--------------------------------|-----------------------------------------|
| notify | `posted` / `removed`           | `$pkg $id $key $reason`                 |
| ring   | `on` (incoming/outgoing) `off` | `$pkg $id $incoming`                    |
| voip   | `on` / `off`                   | `$pkg $id`                              |
| screen | `on` / `off`                   | `$on`                                   |
| pulse  | `on` / `off`                   | `$on`                                   |

`$incoming` renders `1`/`0` (freshly classified SIM-call direction),
`$on` as `1`/`0`, `$reason` as the removal reason code.

### Consumer protocol

Text protocol, one command per line, over the abstract Unix socket:

```
ENQ <pkg> <id>          notification posted
CAN <pkg> <id>          notification removed
RING_ON <0|1>           SIM call posted (1 incoming, 0 outgoing/ongoing)
RING_OFF                last SIM call notification gone
VOIP_ON <pkg>           messenger call posted
VOIP_OFF <pkg>          messenger call notification gone
SCREEN <0|1>            screen off/on
PULSE <0|1>             Settings.System notification_light_pulse changed
PING                    client liveness probe -> consumer answers PONG
WD <ms>                 consumer -> app: keepalive cadence push
```

The client sends `PING` on connect and replays its live state (screen,
active RING/VOIP, active notifications). Only consumer -> app traffic is
`PONG` / `WD <ms>`.

## Config

Optional JSON at **`/data/local/tmp/lednls_bridge.json`** (adb-writable).
Absent = built-in defaults that reproduce the classic chgd contract 1:1.
Apply changes with a reload broadcast:

```
adb shell am broadcast -a com.bastet.lednls.RELOAD_CONFIG
```

(also re-checked by mtime on each watchdog tick).

```jsonc
{
  // keepalive cadence for daemon supervision, ms; 0 = off.
  // absent -> consumer's "WD <ms>" push wins (chgd channel)
  "watchdogMs": 60000,

  // daemon to supervise (pidof name + restart path)
  "daemonName": "chgd",
  "daemonPath": "/data/adb/modules/led_hal_root/chgd",

  // bridge state mirror for consumers (GUI etc.), written by the daemon
  "statusPath": "/data/local/tmp/lednls.status",

  // call classification package lists
  "dialerPkg": "com.google.android.dialer",
  "voipPkgs": ["org.telegram.messenger", "com.whatsapp"],

  // delivery endpoints
  "sinks": [
    { "type": "socket", "name": "chgd_noty" },
    { "type": "logcat", "name": "log" }
  ],

  // routing: event -> rule filter -> sink, line rendered with $vars
  "rules": [
    { "event": "notify", "action": "posted",  "pkg": "*", "to": "chgd", "line": "ENQ $pkg $id" },
    { "event": "notify", "action": "removed", "pkg": "*", "to": "chgd", "line": "CAN $pkg $id" },
    { "event": "ring",   "pkg": "*", "to": "chgd", "line": "RING_ON $incoming" },
    { "event": "ring",   "action": "off", "pkg": "*", "to": "chgd", "line": "RING_OFF" },
    { "event": "voip",   "pkg": "*", "to": "chgd", "line": "VOIP_ON $pkg" },
    { "event": "voip",   "action": "off", "pkg": "*", "to": "chgd", "line": "VOIP_OFF $pkg" },
    { "event": "screen", "pkg": "*", "to": "chgd", "line": "SCREEN $on" },
    { "event": "pulse",  "pkg": "*", "to": "chgd", "line": "PULSE $on" }
  ]
}
```

Rule fields: `event` (`notify`|`ring`|`voip`|`screen`|`pulse`|`*`),
`action` (`posted`|`removed`|`on`|`off`|`*`, or omit for any), `pkg`
(exact, `prefix*` glob, or `*`), `to` (sink name), `line` (template).

**Extending without code:** forward only what you need by pruning rules;
point `to` at your own socket; switch a `line` to your own format; send
events to `log` for a logcat mirror; add a second socket sink for a second
consumer.

## Daemon supervision

At `watchdogMs` cadence (config, or consumer `WD` push, or 60000 default)
the app asks su whether the daemon binary is alive and restarts it when
not. The system rebinds notification listeners on its own, so the
supervisor outlives any shell keepalive.

## Requirements

- **Notification access** (Settings -> Special app access -> Notification
  access -> LED NLS), and **root** (KernelSU / Magisk) for the watchdog.
- Android 10+ (minSdk 29).

## Build

```
gradle.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Self-test from the shell:
`adb shell am startservice -n com.bastet.lednls/.LedNotificationListenerService -a com.bastet.lednls.POST_TEST`

## Files

- `LedNotificationListenerService.kt` - observer front, call classifiers, replay
- `BridgeConfig.kt` - config parse + defaults
- `EventRouter.kt` - `BridgeEvent` + rule matching + `$vars` render
- `Sinks.kt` - socket / logcat sinks, per-sink reconnect loops
- `ReloadReceiver.kt` - RELOAD_CONFIG broadcast entry
- `RootShell.kt` / `Su.kt` / `SuShell.kt` - persistent root shell