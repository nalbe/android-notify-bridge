package com.bastet.lednls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
/**

 * External reload entry point: `adb shell am broadcast
 * -a com.bastet.lednls.RELOAD_CONFIG` (or su/root scripts, or any app)
 * re-reads /data/local/tmp/lednls_bridge.json and rebuilds sinks/rules.
 *
 * No startService here: background-start rules make a receiver's
 * startService() a silent no-op on Android 8+, and the config applies on
 * the first bind anyway when the process is dead. A LIVE app is poked
 * directly through its instance instead - instant, no restrictions.
 */
class ReloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LedNotificationListenerService.pokeReload()
    }
}