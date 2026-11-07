package com.akoya.home.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.akoya.home.MainActivity

/**
 * "Home button takeover" for TVs where the default launcher can't be changed without adb.
 * When the stock Google TV home screen comes to the front, Akoya Home is opened instead.
 * Enable in TV Settings > System > Accessibility > Akoya Home.
 */
class HomeGuardService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in STOCK_LAUNCHERS) return
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
    }

    override fun onInterrupt() = Unit

    companion object {
        val STOCK_LAUNCHERS = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher",
        )

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, HomeGuardService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
