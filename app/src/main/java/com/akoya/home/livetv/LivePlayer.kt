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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

sealed interface PlayStatus {
    data object Idle : PlayStatus
    data object Loading : PlayStatus
    data object Playing : PlayStatus
    data class Failed(val message: String) : PlayStatus
}

/**
 * Plays one live channel at a time. Streams come as HLS (.m3u8) or as a raw MPEG-TS feed and
 * links don't always say which, so a stream that fails to parse is retried as the other kind.
 * Network hiccups get two quiet retries before the channel is reported as not working.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LivePlayer(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    val exo: ExoPlayer = ExoPlayer.Builder(appContext).build()

    var status by mutableStateOf<PlayStatus>(PlayStatus.Idle)
        private set

    /** e.g. "1080p"; empty until the picture size is known. */
    var quality by mutableStateOf("")
        private set

    /** Picture width divided by height. */
    var aspect by mutableFloatStateOf(16f / 9f)
        private set

    private var channel: Channel? = null
    private var asHls = false
    private var triedOtherKind = false
    private var retries = 0

    init {
        exo.playWhenReady = true
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> if (status !is PlayStatus.Failed) status = PlayStatus.Loading
                    Player.STATE_READY -> { status = PlayStatus.Playing; retries = 0 }
                    Player.STATE_ENDED -> retryOrFail("The stream stopped")
                    else -> Unit
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                quality = when {
                    videoSize.height >= 2000 -> "4K"
                    else -> "${videoSize.height}p"
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val ch = channel ?: return
                when {
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        exo.seekToDefaultPosition()
                        exo.prepare()
                    }
                    !triedOtherKind && error.errorCode in FORMAT_ERRORS -> {
                        triedOtherKind = true
                        start(ch, hls = !asHls)
                    }
                    else -> retryOrFail(describe(error))
                }
            }
        })
    }

    fun play(ch: Channel) {
        handler.removeCallbacksAndMessages(null)
        channel = ch
        triedOtherKind = false
        retries = 0
        quality = ""
        start(ch, hls = looksLikeHls(ch.url))
    }

    /** Stops the stream (the TV went to another app); [resume] tunes back in. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        exo.stop()
        exo.clearMediaItems()
        status = PlayStatus.Idle
    }

    fun resume() {
        channel?.let { play(it) }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        exo.release()
    }

    private fun start(ch: Channel, hls: Boolean) {
        asHls = hls
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(15_000)
            .setUserAgent(ch.userAgent.ifBlank { LiveTvStore.PLAYLIST_AGENT })
        if (ch.referrer.isNotBlank()) http.setDefaultRequestProperties(mapOf("Referer" to ch.referrer))
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, http))
        val item = MediaItem.Builder().setUri(ch.url).apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        status = PlayStatus.Loading
        exo.setMediaSource(sources.createMediaSource(item))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun retryOrFail(message: String) {
        val ch = channel ?: return
        if (retries < 2) {
            retries++
            status = PlayStatus.Loading
            handler.postDelayed({ if (channel?.id == ch.id) start(ch, asHls) }, 1_500)
        } else {
            status = PlayStatus.Failed(message)
        }
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
        val FORMAT_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
    }
}
