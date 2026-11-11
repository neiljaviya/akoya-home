package com.akoya.home.livetv

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.akoya.home.data.ConfigStore

/** Full-screen live TV: the picture, with the channel list and playlists as overlays. */
class LiveTvActivity : ComponentActivity() {

    private lateinit var session: LiveTvSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        session = LiveTvSession(LiveTvStore.get(this), LivePlayer(this))
        session.request(intent)
        session.player.onFrameRate = { fps -> matchDisplayTo(fps) }
        val configStore = ConfigStore.get(this)
        setContent { LiveTvRoot(session, configStore, onExit = { finish() }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        session.request(intent)
        session.sync(session.index, session.favorites, ready = true)
    }

    override fun onStart() {
        super.onStart()
        session.store.refreshStale()
        session.onStart()
    }

    override fun onStop() {
        setDisplayMode(0)
        session.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        session.player.release()
        super.onDestroy()
    }

    /**
     * Auto frame rate: asks the TV for a refresh rate the video divides into evenly
     * (25 fps on 50 Hz, 30 on 60), at the resolution it is already using.
     */
    private fun matchDisplayTo(fps: Float) {
        if (!session.store.current.autoFrameRate) { setDisplayMode(0); return }
        runCatching {
            @Suppress("DEPRECATION")
            val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay) ?: return
            val mode = display.mode
            val sameSize = display.supportedModes.filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            val rate = FrameRate.pick(fps, sameSize.map { it.refreshRate }, mode.refreshRate) ?: return
            val target = sameSize.firstOrNull { kotlin.math.abs(it.refreshRate - rate) < 0.01f } ?: return
            Log.i("AkoyaLiveTv", "Auto frame rate: $fps fps, display ${mode.refreshRate} -> ${target.refreshRate} Hz")
            setDisplayMode(target.modeId)
        }
    }

    /** 0 hands the choice back to the TV. */
    private fun setDisplayMode(modeId: Int) {
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId != modeId) {
            attrs.preferredDisplayModeId = modeId
            window.attributes = attrs
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        com.akoya.home.ui.components.ScrollPace.onKey(event)
        return session.handleKey(event) || super.dispatchKeyEvent(event)
    }
}
