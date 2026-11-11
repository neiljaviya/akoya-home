package com.akoya.home.livetv

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Panel { NONE, CHANNELS, PLAYLISTS }

/** What is on screen in Live TV and what the remote does. Lives as long as the Live TV activity. */
class LiveTvSession(val store: LiveTvStore, val player: LivePlayer) {
    var panel by mutableStateOf(Panel.NONE)
    var current by mutableStateOf<Channel?>(null)
        private set
    var groupKey by mutableStateOf(GROUP_ALL)
    var optionsFor by mutableStateOf<Channel?>(null)

    /** Bumped whenever the channel banner should pop up. */
    var bannerTick by mutableIntStateOf(0)
        private set

    var index: ChannelIndex = ChannelIndex(emptyList(), emptyList())
    var favorites: List<Channel> = emptyList()

    private var zapList: List<Channel> = emptyList()
    private var zapKey: String = GROUP_ALL
    private var previous: Channel? = null
    private var pendingChannelId: String? = null
    private var pendingPanel: Panel? = null
    private var stopped = false

    /** Reads what the launcher asked for: a channel to tune to and/or a panel to open. */
    fun request(intent: Intent?) {
        pendingChannelId = intent?.getStringExtra(EXTRA_CHANNEL)
        pendingPanel = when (intent?.getStringExtra(EXTRA_OPEN)) {
            OPEN_CHANNELS -> Panel.CHANNELS
            OPEN_PLAYLISTS -> Panel.PLAYLISTS
            else -> null
        }
    }

    /** Called whenever the channel list is (re)loaded. Picks a channel on first run. */
    fun sync(index: ChannelIndex, favorites: List<Channel>, ready: Boolean) {
        this.index = index
        this.favorites = favorites
        if (!ready) return
        if (index.all.isEmpty()) {
            if (current == null) panel = Panel.PLAYLISTS
            return
        }
        val wanted = pendingChannelId?.let { index.byId[it] }
        pendingChannelId = null
        if (wanted != null || current == null) {
            val ch = wanted
                ?: store.current.lastChannelId?.let { index.byId[it] }
                ?: favorites.firstOrNull()
                ?: index.all.first()
            val fav = favorites.any { it.id == ch.id }
            groupKey = if (fav) GROUP_FAV else GROUP_ALL
            tune(ch, if (fav) favorites else index.all)
        }
        pendingPanel?.let { want ->
            pendingPanel = null
            if (want == Panel.CHANNELS) openChannels() else panel = want
        }
    }

    fun tune(ch: Channel, list: List<Channel>) {
        if (current != null && current?.id != ch.id) previous = current
        current = ch
        zapList = list
        zapKey = groupKey
        store.setLastChannel(ch.id)
        player.play(ch)
        bannerTick++
    }

    fun openChannels() {
        groupKey = if (index.hasGroup(zapKey)) zapKey else GROUP_ALL
        panel = Panel.CHANNELS
    }

    fun showBanner() { bannerTick++ }

    private fun zap(delta: Int) {
        val list = zapList.ifEmpty { index.all }
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == current?.id }
        val next = if (i < 0) list.first() else list[(i + delta).mod(list.size)]
        groupKey = zapKey
        tune(next, list)
    }

    private fun recall() {
        val back = previous ?: return
        tune(back, zapList)
    }

    fun onStart() {
        if (stopped) { stopped = false; player.resume(); bannerTick++ }
    }

    fun onStop() {
        stopped = true
        player.stop()
    }

    /**
     * Remote keys while only the picture is showing. Returns true when the key was used.
     * With a panel or dialog open the keys go to that instead.
     */
    fun handleKey(event: KeyEvent): Boolean {
        if (panel != Panel.NONE || optionsFor != null || current == null) return false
        val first = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> if (first) zap(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> if (first) zap(+1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_GUIDE -> if (first) openChannels()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> if (first) showBanner()
            KeyEvent.KEYCODE_MENU -> if (event.action == KeyEvent.ACTION_UP) optionsFor = current
            KeyEvent.KEYCODE_LAST_CHANNEL -> if (first) recall()
            else -> return false
        }
        return true
    }

    companion object {
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_OPEN = "open"
        const val OPEN_CHANNELS = "channels"
        const val OPEN_PLAYLISTS = "playlists"

        /** Opens Live TV, optionally on a channel or with a panel showing. */
        fun launch(context: Context, channelId: String? = null, open: String? = null) {
            val intent = Intent(context, LiveTvActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_CHANNEL, channelId)
                .putExtra(EXTRA_OPEN, open)
            runCatching { context.startActivity(intent) }
        }
    }
}
