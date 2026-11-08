package io.github.kilianm.refreeplay

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.ExtractorsFactory
import kotlin.math.ceil

/**
 * Plays a replay clip with frame-accurate seeking.
 * Scrubbing is throttled: while one seek is being decoded, only the newest requested
 * position is kept, so dragging stays responsive without queuing hundreds of seeks.
 */
@OptIn(UnstableApi::class)
class ReplayController(context: Context, playerView: PlayerView, private val listener: Listener) {

    interface Listener {
        fun onReplayStateChanged()
        fun onReplayError(message: String)
    }

    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private val handler = Handler(Looper.getMainLooper())

    /** Presentation time (µs, relative to clip start) of every frame, ascending. */
    private var framePts = LongArray(0)

    var durationMs = 0L
        private set

    private var seekInFlight = false
    private var pendingSeekMs = -1L
    private var targetMs = -1L
    private var stepIndex = -1

    private val seekTimeout = Runnable { onSeekFinished() }

    init {
        player.setSeekParameters(SeekParameters.EXACT)
        player.repeatMode = Player.REPEAT_MODE_OFF
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() = onSeekFinished()

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) onSeekFinished()
                listener.onReplayStateChanged()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onReplayStateChanged()

            override fun onPlayerError(error: PlaybackException) {
                listener.onReplayError(error.message ?: "Playback error")
            }
        })
    }

    val frameCount: Int get() = framePts.size

    fun load(mp4: VirtualMp4, speed: Float) {
        val ptsUs = mp4.index.ptsUs
        framePts = ptsUs
        durationMs = ceil(ptsUs.last() / 1000.0).toLong()
        seekInFlight = false
        pendingSeekMs = -1
        targetMs = -1
        stepIndex = -1
        handler.removeCallbacks(seekTimeout)
        val source = ProgressiveMediaSource.Factory(
            DataSource.Factory { VirtualMp4DataSource(mp4) },
            ExtractorsFactory { arrayOf(Mp4Extractor()) },
        )
            .createMediaSource(MediaItem.fromUri(Uri.parse("refreeplay://replay/${++loadCounter}")))
        player.setMediaSource(source)
        player.setPlaybackSpeed(speed)
        player.prepare()
        player.playWhenReady = true
    }

    private var loadCounter = 0

    val isPlaying: Boolean
        get() = player.playWhenReady && player.playbackState != Player.STATE_ENDED

    fun positionMs(): Long =
        if (targetMs >= 0) targetMs else player.currentPosition.coerceIn(0, durationMs)

    /** Index of the frame currently on screen (approximate while playing). */
    fun currentFrameIndex(): Int {
        if (framePts.isEmpty()) return 0
        if (stepIndex >= 0) return stepIndex
        return indexAtOrBefore(positionMs() * 1000)
    }

    fun play() {
        stepIndex = -1
        if (player.playbackState == Player.STATE_ENDED || player.currentPosition >= durationMs) {
            resetSeekState()
            player.seekTo(0)
        }
        player.play()
    }

    fun pause() {
        player.pause()
    }

    /** Plays from [ms] (used to preview the part chosen for cutting). */
    fun playFrom(ms: Long) {
        handler.removeCallbacks(seekTimeout)
        resetSeekState()
        stepIndex = -1
        player.seekTo(ms.coerceIn(0, durationMs))
        player.play()
    }

    fun togglePlay() {
        if (isPlaying) pause() else play()
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    /** Seek used for scrubbing (seek bar or swipe on the video). */
    fun scrubTo(ms: Long) {
        val t = ms.coerceIn(0, durationMs)
        stepIndex = indexAtOrAfter(t * 1000)
        requestSeek(t)
    }

    fun jumpBy(deltaMs: Long) {
        player.pause()
        scrubTo(positionMs() + deltaMs)
    }

    /** Shows exactly the previous (dir = -1) or next (dir = +1) frame. */
    fun stepFrame(dir: Int) {
        player.pause()
        if (framePts.isEmpty()) return
        val next = (currentFrameIndex() + dir).coerceIn(0, framePts.size - 1)
        stepIndex = next
        // Seeking to the first millisecond >= the frame's timestamp shows exactly that frame.
        requestSeek(ceil(framePts[next] / 1000.0).toLong().coerceIn(0, durationMs))
    }

    fun stop() {
        handler.removeCallbacks(seekTimeout)
        resetSeekState()
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        handler.removeCallbacks(seekTimeout)
        player.release()
    }

    private fun resetSeekState() {
        seekInFlight = false
        pendingSeekMs = -1
        targetMs = -1
    }

    private fun requestSeek(ms: Long) {
        targetMs = ms
        if (seekInFlight) {
            pendingSeekMs = ms
            return
        }
        seekInFlight = true
        player.seekTo(ms)
        handler.removeCallbacks(seekTimeout)
        handler.postDelayed(seekTimeout, 300)
    }

    private fun onSeekFinished() {
        if (!seekInFlight) return
        seekInFlight = false
        handler.removeCallbacks(seekTimeout)
        if (pendingSeekMs >= 0) {
            val p = pendingSeekMs
            pendingSeekMs = -1
            requestSeek(p)
        } else {
            targetMs = -1
        }
    }

    private fun indexAtOrBefore(us: Long): Int {
        var lo = 0
        var hi = framePts.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (framePts[mid] <= us) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun indexAtOrAfter(us: Long): Int {
        var lo = 0
        var hi = framePts.size - 1
        var ans = framePts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (framePts[mid] >= us) {
                ans = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return ans
    }
}
