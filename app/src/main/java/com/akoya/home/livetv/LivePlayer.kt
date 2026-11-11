package com.akoya.home.livetv

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

sealed interface PlayStatus {
    data object Idle : PlayStatus
    data object Loading : PlayStatus
    data object Playing : PlayStatus
    data class Failed(val message: String) : PlayStatus
}

/**
 * Plays one live channel at a time and works through the usual ways a link goes wrong:
 *  - links don't always say whether they are HLS or a raw MPEG-TS feed, so a stream that
 *    fails to parse is retried as the other kind;
 *  - a server that refuses the request is asked once more as a different player;
 *  - a stream that connects but never shows a picture is given up on after a wait;
 *  - when a link is dead, the channel's backup links (if any) are tried in turn.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LivePlayer(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    // Live TV wants a quick start more than a deep buffer.
    val exo: ExoPlayer = ExoPlayer.Builder(appContext)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(8_000, 30_000, 1_200, 2_500)
                .build(),
        )
        .build()

    var status by mutableStateOf<PlayStatus>(PlayStatus.Idle)
        private set

    /** e.g. "1080p"; empty until the picture size is known. */
    var quality by mutableStateOf("")
        private set

    /** Picture width divided by height. */
    var aspect by mutableFloatStateOf(16f / 9f)
        private set

    /** True when the channel's own link failed and a backup link is playing. */
    var onBackup by mutableStateOf(false)
        private set

    /** True while a catch-up recording is playing instead of the live channel. */
    var archive by mutableStateOf(false)
        private set

    var paused by mutableStateOf(false)
        private set

    /** The video's frame rate once it has been measured (e.g. 25.0); 0 until then. */
    var fps by mutableFloatStateOf(0f)
        private set

    /** Told once per tune whether the channel ended up playing. */
    var onResult: ((channelId: String, played: Boolean) -> Unit)? = null

    /** A catch-up recording played to its end. */
    var onArchiveEnded: (() -> Unit)? = null

    /** The frame rate of what's playing became known. */
    var onFrameRate: ((Float) -> Unit)? = null

    /** How far into the current stream playback is, in milliseconds. */
    val positionMs: Long get() = exo.currentPosition.coerceAtLeast(0)

    // Frame timestamps for measuring the frame rate; written on the playback thread.
    private val stamps = LongArray(48)
    @Volatile private var stampCount = 0
    @Volatile private var fpsKnown = true

    private var channel: Channel? = null
    private var links: List<Channel> = emptyList()
    private var linkIndex = 0
    private var asHls = false
    private var altAgent = false
    private var triedOtherKind = false
    private var triedAltAgent = false
    private var retries = 0
    private var reported = false

    private var hadPicture = false

    // No picture at all: move on. Froze after playing: it's worth reconnecting first.
    private val watchdog = Runnable {
        if (hadPicture) retryOrGiveUp("This channel stopped sending a picture")
        else giveUpOnLink("This channel isn't sending a picture")
    }
    private val retry = Runnable { links.getOrNull(linkIndex)?.let { start(it) } }

    init {
        exo.playWhenReady = true
        exo.setVideoFrameMetadataListener { presentationTimeUs, _, format, _ ->
            if (fpsKnown) return@setVideoFrameMetadataListener
            val n = stampCount
            if (n < stamps.size) { stamps[n] = presentationTimeUs; stampCount = n + 1 }
            if (n + 1 >= stamps.size) {
                fpsKnown = true
                val measured = FrameRate.fromTimestamps(stamps, stamps.size)
                val rate = if (measured > 0f) measured else FrameRate.snap(format.frameRate)
                if (rate > 0f) handler.post { fps = rate; onFrameRate?.invoke(rate) }
            }
        }
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        if (status !is PlayStatus.Failed) status = PlayStatus.Loading
                        armWatchdog()
                    }
                    Player.STATE_READY -> {
                        handler.removeCallbacks(watchdog)
                        status = PlayStatus.Playing
                        hadPicture = true
                        retries = 0
                        report(true)
                    }
                    Player.STATE_ENDED -> if (archive) onArchiveEnded?.invoke() else retryOrGiveUp("The stream stopped")
                    else -> Unit
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                quality = if (videoSize.height >= 2000) "4K" else "${videoSize.height}p"
            }

            override fun onPlayerError(error: PlaybackException) {
                val link = links.getOrNull(linkIndex) ?: return
                when {
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        exo.seekToDefaultPosition()
                        exo.prepare()
                    }
                    !triedOtherKind && error.errorCode in FORMAT_ERRORS -> {
                        triedOtherKind = true
                        asHls = !asHls
                        start(link)
                    }
                    !triedAltAgent && link.userAgent.isBlank() && error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                        triedAltAgent = true
                        altAgent = true
                        start(link)
                    }
                    // A missing or refused link won't get better by asking again.
                    error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> giveUpOnLink(describe(error))
                    else -> retryOrGiveUp(describe(error))
                }
            }
        })
    }

    /** Tunes to [ch]. [backups] are other links for the same channel, tried in order if it fails. */
    fun play(ch: Channel, backups: List<Channel> = emptyList()) {
        cancelPending()
        channel = ch
        links = listOf(ch) + backups
        linkIndex = 0
        reported = false
        quality = ""
        onBackup = false
        archive = false
        beginLink()
    }

    /** Plays a recording of [ch] from the provider's archive. [url] is the catch-up link. */
    fun playArchive(ch: Channel, url: String) {
        cancelPending()
        channel = ch
        links = listOf(ch.copy(url = url))
        linkIndex = 0
        // A recording that won't play says nothing about whether the live channel works.
        reported = true
        quality = ""
        onBackup = false
        archive = true
        beginLink()
    }

    fun togglePause() {
        exo.playWhenReady = !exo.playWhenReady
        paused = !exo.playWhenReady
    }

    /** Stops the stream (the TV went to another app); [resume] tunes back in. */
    fun stop() {
        cancelPending()
        exo.stop()
        exo.clearMediaItems()
        status = PlayStatus.Idle
    }

    fun resume() {
        val ch = channel ?: return
        if (!archive) play(ch, links.drop(1))
    }

    fun release() {
        cancelPending()
        exo.release()
    }

    private fun cancelPending() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(retry)
    }

    private fun beginLink() {
        val link = links.getOrNull(linkIndex) ?: return
        triedOtherKind = false
        triedAltAgent = false
        altAgent = false
        hadPicture = false
        retries = 0
        asHls = looksLikeHls(link.url)
        start(link)
    }

    private fun start(link: Channel) {
        val agent = when {
            link.userAgent.isNotBlank() -> link.userAgent
            altAgent -> ALT_AGENT
            else -> LiveTvStore.PLAYLIST_AGENT
        }
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(12_000)
            .setUserAgent(agent)
        if (link.referrer.isNotBlank()) http.setDefaultRequestProperties(mapOf("Referer" to link.referrer))
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, http))
        val item = MediaItem.Builder().setUri(link.url).apply { if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        status = PlayStatus.Loading
        paused = false
        fps = 0f
        stampCount = 0
        fpsKnown = false
        exo.setMediaSource(sources.createMediaSource(item))
        exo.prepare()
        exo.playWhenReady = true
        armWatchdog()
    }

    /** A stream that sits on "loading" this long is treated as dead. */
    private fun armWatchdog() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, STALL_MS)
    }

    private fun retryOrGiveUp(message: String) {
        handler.removeCallbacks(watchdog)
        // With backups waiting, one retry is plenty before moving on.
        val allowed = if (linkIndex < links.lastIndex) 1 else 2
        if (retries < allowed) {
            retries++
            status = PlayStatus.Loading
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, 1_200)
        } else {
            giveUpOnLink(message)
        }
    }

    private fun giveUpOnLink(message: String) {
        cancelPending()
        if (linkIndex < links.lastIndex) {
            linkIndex++
            onBackup = true
            beginLink()
        } else {
            exo.stop()
            status = PlayStatus.Failed(if (archive) "This programme isn't available on catch-up" else message)
            report(false)
        }
    }

    private fun report(played: Boolean) {
        if (reported) return
        reported = true
        channel?.let { onResult?.invoke(it.id, played) }
    }

    private fun looksLikeHls(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach this channel's server"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The channel's server refused the stream"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This channel's link is no longer there"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "This TV can't decode the channel's video format"
        else -> "This channel isn't playing right now"
    }

    private companion object {
        const val STALL_MS = 15_000L
        const val ALT_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
        val FORMAT_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
    }
}
