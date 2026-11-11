package com.akoya.home.livetv

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Panel { NONE, CHANNELS, GUIDE, PLAYLISTS }

/** A catch-up recording being watched: [from] is the moment the current stream began at. */
data class ArchivePlay(val channel: Channel, val title: String, val start: Long, val stop: Long, val from: Long)

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

    /** Set while a catch-up recording is playing instead of the live channel. */
    var archive by mutableStateOf<ArchivePlay?>(null)
        private set

    /** The channels the guide lists: the group that was being watched. */
    var guideChannels: List<Channel> = emptyList()
        private set

    init {
        player.onResult = { id, played -> store.reportPlayback(id, played) }
        player.onArchiveEnded = { backToLive() }
    }

    fun openGuide() {
        guideChannels = zapList.ifEmpty { index.all }
        if (guideChannels.isNotEmpty()) panel = Panel.GUIDE
    }

    /** Plays [programme] from the provider's archive. False when the channel has no catch-up for it. */
    fun playArchive(ch: Channel, programme: Programme, from: Long = programme.start): Boolean {
        val now = System.currentTimeMillis()
        if (!Catchup.covers(ch, from, now)) return false
        val url = Catchup.url(ch, from, programme.stop, now, store.zoneFor(ch)) ?: return false
        if (current?.id != ch.id) { previous = current; zapList = guideChannels.ifEmpty { zapList } }
        current = ch
        archive = ArchivePlay(ch, programme.title, programme.start, programme.stop, from)
        player.playArchive(ch, url)
        panel = Panel.NONE
        bannerTick++
        return true
    }

    /** Skips within the recording by asking the provider for it again from a new moment. */
    private fun seekArchive(deltaMs: Long) {
        val a = archive ?: return
        val now = System.currentTimeMillis()
        val target = (a.from + player.positionMs + deltaMs).coerceAtLeast(a.start)
        if (target >= minOf(a.stop, now) - 10_000) { backToLive(); return }
        playArchive(a.channel, Programme(a.start, a.stop, a.title), from = target)
    }

    fun backToLive() {
        val ch = archive?.channel ?: current ?: return
        tune(ch, zapList.ifEmpty { index.all })
    }


    /** Reads what the launcher asked for: a channel to tune to and/or a panel to open. */
    fun request(intent: Intent?) {
        pendingChannelId = intent?.getStringExtra(EXTRA_CHANNEL)
        pendingPanel = when (intent?.getStringExtra(EXTRA_OPEN)) {
            OPEN_CHANNELS -> Panel.CHANNELS
            OPEN_PLAYLISTS -> Panel.PLAYLISTS
            OPEN_GUIDE -> Panel.GUIDE
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
            when (want) {
                Panel.CHANNELS -> openChannels()
                Panel.GUIDE -> openGuide()
                else -> panel = want
            }
        }
    }

    fun tune(ch: Channel, list: List<Channel>) {
        if (current != null && current?.id != ch.id) previous = current
        archive = null
        current = ch
        zapList = list
        zapKey = groupKey
        store.setLastChannel(ch.id)
        // Backup links that are known to be dead go to the back of the queue.
        val state = store.current
        player.play(ch, index.alternates(ch).sortedBy { state.isOffAir(it.id) })
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
        var next = if (i < 0) list.first() else list[(i + delta).mod(list.size)]
        // Step over channels that failed twice running (they stay in the list, dimmed).
        if (i >= 0) {
            val state = store.current
            var j = i + delta
            var steps = 0
            while (state.isOffAir(next.id) && steps < list.size) {
                j += delta; steps++
                next = list[j.mod(list.size)]
            }
        }
        groupKey = zapKey
        tune(next, list)
    }

    private fun recall() {
        val back = previous ?: return
        tune(back, zapList)
    }

    fun onStart() {
        if (!stopped) return
        stopped = false
        // Coming back from another app: a recording doesn't pick up mid-way, so return to live.
        if (archive != null) backToLive() else { player.resume(); bannerTick++ }
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
        if (archive != null) {
            // Watching a recording: Left/Right skip, OK pauses, Back returns to live.
            // Holding Left/Right skips five minutes at a time.
            val held = event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && event.repeatCount % 8 == 0
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (first) seekArchive(-60_000) else if (held) seekArchive(-300_000); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (first) seekArchive(60_000) else if (held) seekArchive(300_000); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { if (first) seekArchive(-300_000); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (first) seekArchive(300_000); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (first) { player.togglePause(); bannerTick++ }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> { if (event.action == KeyEvent.ACTION_UP) backToLive(); return true }
            }
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> if (first) zap(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> if (first) zap(+1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT -> if (first) openChannels()
            KeyEvent.KEYCODE_GUIDE -> if (first) openGuide()
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
        const val OPEN_GUIDE = "guide"

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
