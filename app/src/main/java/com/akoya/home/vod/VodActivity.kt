package com.akoya.home.vod

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.akoya.home.data.ConfigStore
import com.akoya.home.livetv.LiveTvStore
import com.akoya.home.ui.components.ScrollPace

/** One thing to play: a film or an episode. */
data class Playable(
    val key: String, val title: String, val subtitle: String, val poster: String, val url: String, val series: Boolean,
    /** For recommendations: which title this is and the name of the category it came from. */
    val titleKey: String = "", val category: String = "",
)

enum class PlayerMenu { AUDIO, SUBTITLES, SPEED, QUALITY }

sealed interface VodScreen {
    data object Browse : VodScreen
    data object Categories : VodScreen
    data class Grid(val title: String, val items: List<VodItem>) : VodScreen
    data class Detail(val item: VodItem) : VodScreen
}

/** A sound or subtitle track that can be chosen. */
class AudioTrack(val label: String, val selected: Boolean, val groupIndex: Int, val trackIndex: Int)

/** Film and series playback: pause, skip, resume where you left off, next episode. */
@androidx.annotation.OptIn(UnstableApi::class)
class VodPlayer(context: Context, private val store: VodStore) {
    private val appContext = context.applicationContext
    val exo: ExoPlayer = ExoPlayer.Builder(appContext).build()

    var now by mutableStateOf<Playable?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var paused by mutableStateOf(false)
        private set
    var aspect by mutableFloatStateOf(16f / 9f)
        private set
    /** Bumped to bring the controls up. */
    var controlsTick by mutableIntStateOf(0)
        private set
    /** Where a skip in progress will land; -1 when not skipping. */
    var seekPreview by mutableLongStateOf(-1L)
        private set

    /** Subtitle lines to show right now. */
    var cues by mutableStateOf<List<String>>(emptyList())
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var subtitlesOn by mutableStateOf(false)
        private set

    private var queue: List<Playable> = emptyList()
    private var index by mutableIntStateOf(0)
    private var recorded = false
    var onFinished: (() -> Unit)? = null

    val hasNext: Boolean get() = index < queue.lastIndex
    val hasPrevious: Boolean get() = index > 0

    fun previous() { if (hasPrevious) { save(); play(queue, index - 1) } }

    fun restart() { seekPreview = -1; exo.seekTo(0); controlsTick++ }

    fun changeSpeed(value: Float) { speed = value; exo.setPlaybackSpeed(value) }

    private fun describe(f: androidx.media3.common.Format, n: Int): String {
        val lang = f.language?.takeIf { it != "und" }?.let { java.util.Locale(it).displayLanguage }?.takeIf { it.isNotBlank() }
        val sound = f.channelCount.takeIf { it > 0 }?.let { if (it >= 6) "5.1" else if (it == 2) "Stereo" else "$it ch" }
        return listOfNotNull(f.label, lang, sound).distinct().joinToString("  ·  ").ifBlank { "Track $n" }
    }

    private fun tracks(type: Int): List<AudioTrack> {
        val out = ArrayList<AudioTrack>()
        exo.currentTracks.groups.forEachIndexed { gi, g ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) out += AudioTrack(describe(g.getTrackFormat(ti), out.size + 1), g.isTrackSelected(ti), gi, ti)
        }
        return out
    }

    fun subtitleTracks(): List<AudioTrack> = tracks(C.TRACK_TYPE_TEXT)

    /** Shows the given subtitle track; null turns subtitles off. */
    fun selectSubtitle(t: AudioTrack?) {
        val b = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        val group = t?.let { exo.currentTracks.groups.getOrNull(it.groupIndex) }
        if (t == null || group == null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            cues = emptyList()
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t.trackIndex))
        }
        subtitlesOn = t != null && group != null
        exo.trackSelectionParameters = b.build()
    }

    /** Picture heights this video offers; most films come in just one. */
    fun videoHeights(): List<Int> {
        val out = sortedSetOf<Int>()
        for (g in exo.currentTracks.groups) if (g.type == C.TRACK_TYPE_VIDEO) for (i in 0 until g.length) g.getTrackFormat(i).height.let { if (it > 0) out += it }
        return out.toList().reversed()
    }

    var maxHeight by mutableIntStateOf(0)
        private set

    fun changeMaxHeight(h: Int) {
        maxHeight = h
        val b = exo.trackSelectionParameters.buildUpon()
        if (h > 0) b.setMaxVideoSize(Int.MAX_VALUE, h) else b.clearVideoSizeConstraints()
        exo.trackSelectionParameters = b.build()
    }

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                loading = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) error = null
                if (state == Player.STATE_ENDED) {
                    save(finished = true)
                    if (hasNext) play(queue, index + 1) else onFinished?.invoke()
                }
            }

            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                cues = if (subtitlesOn) cueGroup.cues.mapNotNull { it.text?.toString()?.trim()?.takeIf { t -> t.isNotEmpty() } } else emptyList()
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
            }

            override fun onPlayerError(e: PlaybackException) {
                loading = false
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the provider"
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The provider doesn't have this file right now"
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "This TV can't decode this video's format"
                    else -> "This video won't play"
                }
            }
        })
    }

    /** Plays [list] from [at], picking up where that item was left. */
    fun play(list: List<Playable>, at: Int, fromStart: Boolean = false) {
        val item = list.getOrNull(at) ?: return
        queue = list; index = at
        now = item
        error = null; paused = false; loading = true; seekPreview = -1
        cues = emptyList(); recorded = false
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setUserAgent(LiveTvStore.PLAYLIST_AGENT)
        val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, http)).createMediaSource(MediaItem.fromUri(item.url))
        val resume = if (fromStart) 0L else store.resumeFor(item.key)?.pos ?: 0L
        exo.setMediaSource(source, resume.coerceAtLeast(0))
        exo.prepare()
        exo.playWhenReady = true
        controlsTick++
    }

    fun next() { if (hasNext) { save(); play(queue, index + 1) } }

    /** Keeps the controls up a little longer (the remote was just used). */
    fun touch() { controlsTick++ }

    fun togglePause() {
        exo.playWhenReady = !exo.playWhenReady
        paused = !exo.playWhenReady
        controlsTick++
    }

    /** Skips by [deltaMs]; presses in quick succession add up before the video actually moves. */
    fun skip(deltaMs: Long) {
        val dur = exo.duration.takeIf { it > 0 } ?: return
        val from = if (seekPreview >= 0) seekPreview else exo.currentPosition
        seekPreview = (from + deltaMs).coerceIn(0, dur - 2_000)
        controlsTick++
    }

    /** Lands a skip that was being lined up. */
    fun commitSeek() {
        if (seekPreview >= 0) { exo.seekTo(seekPreview); seekPreview = -1 }
    }

    fun audioTracks(): List<AudioTrack> = tracks(C.TRACK_TYPE_AUDIO)

    fun selectAudio(t: AudioTrack) {
        val group = exo.currentTracks.groups.getOrNull(t.groupIndex) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t.trackIndex))
            .build()
    }

    fun save(finished: Boolean = false) {
        val item = now ?: return
        val dur = exo.duration.takeIf { it > 0 } ?: return
        val pos = if (finished) dur else exo.currentPosition
        // Two minutes in counts as "watched" for recommendations.
        if (!recorded && pos > 120_000 && item.titleKey.isNotBlank()) { recorded = true; store.recordWatch(item.titleKey, item.category) }
        store.saveProgress(Resume(item.key, item.title, item.subtitle, item.poster, item.url, pos, dur, System.currentTimeMillis(), item.series))
    }

    fun stop() {
        save()
        exo.stop()
        exo.clearMediaItems()
        now = null
        loading = false
        cues = emptyList()
    }

    fun release() = exo.release()
}

/** Which screen of the library is showing, and the way back. */
class VodSession(val store: VodStore, val player: VodPlayer, start: VodKind) {
    var kind by mutableStateOf(start)
    val stack = mutableStateListOf<VodScreen>(VodScreen.Browse)
    /** The player's button row is showing and has the remote. */
    var controlsOpen by mutableStateOf(false)
    var menu by mutableStateOf<PlayerMenu?>(null)

    val screen: VodScreen get() = stack.last()
    fun open(s: VodScreen) { stack += s }

    /** Returns false when there is nowhere left to go back to. */
    fun back(): Boolean {
        if (player.now != null) {
            if (controlsOpen) controlsOpen = false else player.stop()
            return true
        }
        if (stack.size > 1) { stack.removeAt(stack.lastIndex); return true }
        return false
    }

    /**
     * Remote keys while a video is playing with the controls tucked away: Left/Right skip
     * (further the longer they're held), OK pauses, Up/Down bring up the controls.
     * Once the controls are showing, the buttons take the remote.
     */
    fun playerKey(e: KeyEvent): Boolean {
        if (player.now == null || menu != null) return false
        if (controlsOpen) { player.touch(); return false }
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        val step = 10_000L * (1 + e.repeatCount / 6).coerceAtMost(12)
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (down) player.skip(-step) else player.commitSeek()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (down) player.skip(step) else player.commitSeek()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (!down) { player.togglePause(); controlsOpen = true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> if (first) player.togglePause()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> if (!down) controlsOpen = true
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (first) player.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (first) player.previous()
            else -> return false
        }
        return true
    }
}

/** The film and series library, opened from the Live TV tab. */
class VodActivity : ComponentActivity() {
    private lateinit var session: VodSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val store = VodStore.get(this)
        val kind = if (intent?.getStringExtra(EXTRA_KIND) == VodKind.SERIES.name) VodKind.SERIES else VodKind.MOVIES
        session = VodSession(store, VodPlayer(this, store), kind)
        session.player.onFinished = { session.controlsOpen = false; session.player.stop() }
        val configStore = ConfigStore.get(this)
        setContent { VodRoot(session, configStore, onExit = { finish() }) }
    }

    override fun onStop() {
        // Leaving for another app: remember the place and let go of the stream.
        if (session.player.now != null) { session.controlsOpen = false; session.menu = null; session.player.stop() }
        super.onStop()
    }

    override fun onDestroy() {
        session.player.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        ScrollPace.onKey(event)
        return session.playerKey(event) || super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_KIND = "kind"
        fun launch(context: Context, kind: VodKind) {
            runCatching {
                context.startActivity(Intent(context, VodActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_KIND, kind.name))
            }
        }
    }
}
