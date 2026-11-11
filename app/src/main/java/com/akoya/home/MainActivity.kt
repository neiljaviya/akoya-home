package com.akoya.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.akoya.home.ui.LauncherController
import com.akoya.home.ui.LauncherRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var controller: LauncherController

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            lifecycleScope.launch { controller.apps.refresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = LauncherController(applicationContext)
        setContent { LauncherRoot(controller) }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        lifecycleScope.launch { controller.apps.refresh() }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.akoya.home.server.CompanionServer.ensureStarted(applicationContext)
            // Read the saved channel list now, so the Live TV tab opens without a pause.
            com.akoya.home.livetv.LiveTvStore.get(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        controller.onResume()
        lifecycleScope.launch { controller.apps.refresh() }
    }

    override fun onPause() {
        controller.onPause()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button pressed while Akoya is already in front.
        if (intent.action == Intent.ACTION_MAIN) controller.onHomePressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        com.akoya.home.ui.components.ScrollPace.onKey(event)
        controller.touch()
        if (controller.screensaverVisible) {
            // Any button wakes the screensaver and is swallowed.
            if (event.action == KeyEvent.ACTION_DOWN) controller.screensaverVisible = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packageReceiver) }
        super.onDestroy()
    }
}
