package com.bastet.lednls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * External reload entry point: `adb shell am broadcast
 * -a com.bastet.lednls.RELOAD_CONFIG` (or su/root scripts, or any app)
 * wakes the process, pushes the intent into the listener service, which
 * re-reads /data/local/tmp/lednls_bridge.json and rebuilds sinks/rules.
 */
class ReloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val i = Intent(context, LedNotificationListenerService::class.java)
            .setAction(LedNotificationListenerService.ACTION_RELOAD)
        runCatching { context.startService(i) }
    }
}