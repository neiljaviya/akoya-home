package com.akoya.home.livetv

import android.content.Intent
import android.os.Bundle
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
        session.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        session.player.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        session.handleKey(event) || super.dispatchKeyEvent(event)
}
