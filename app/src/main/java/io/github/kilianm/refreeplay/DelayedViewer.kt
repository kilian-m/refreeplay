package io.github.kilianm.refreeplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/**
 * Continuously shows what the camera recorded [delayNs] ago: decodes frames from the
 * [FrameRingBuffer] and displays each one exactly `delay` after it was recorded.
 */
class DelayedViewer(
    private val buffer: FrameRingBuffer,
    /** Seconds until the delayed picture starts, or null while it is playing. Called on the viewer thread. */
    private val onStatus: (Double?) -> Unit,
) {
    companion object {
        private const val TAG = "ReFreePlay"

        /** Frames are handed to the decoder this much before they are due. */
        private const val LEAD_NS = 150_000_000L
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start(surface: Surface, delayNs: Long) {
        stop()
        running = true
        thread = Thread({ loop(surface, delayNs) }, "DelayedViewer").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
    }

    private fun loop(surface: Surface, delayNs: Long) {
        var codec: MediaCodec? = null
        var gen = -1
        var lastPts = Long.MIN_VALUE
        var prerollUntilNs = 0L
        val arrivals = ArrayDeque<Long>()
        val info = MediaCodec.BufferInfo()
        var lastStatus: Double? = -1.0

        fun reset() {
            codec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            codec = null
            arrivals.clear()
        }

        fun status(seconds: Double?) {
            val changed = if (seconds == null || lastStatus == null) seconds != lastStatus
            else kotlin.math.abs(seconds - lastStatus!!) >= 0.25
            if (changed) {
                lastStatus = seconds
                onStatus(seconds)
            }
        }

        try {
            while (running) {
                val now = System.nanoTime()
                val target = now - delayNs
                if (codec != null && buffer.generation != gen) reset()

                if (codec == null) {
                    gen = buffer.generation
                    val fmt = buffer.currentFormat()
                    val start = if (fmt != null) buffer.keyFrameArrivedBy(target) else null
                    if (fmt == null || start == null) {
                        val oldest = buffer.oldestArrivalNs()
                        status(if (oldest != null) (oldest + delayNs - now) / 1e9 else delayNs / 1e9)
                        Thread.sleep(50)
                        continue
                    }
                    val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                    c.configure(MediaFormat(fmt), surface, null, 0)
                    c.start()
                    codec = c
                    lastPts = start.ptsUs - 1
                    // Frames between the key frame and "now - delay" are decoded but not shown.
                    prerollUntilNs = target - 20_000_000L
                    status(null)
                }
                val c = codec ?: continue

                // Feed every frame that is due soon.
                while (true) {
                    val f = buffer.nextAfter(lastPts) ?: break
                    if (f.arrivalNs > target + LEAD_NS) break
                    val idx = c.dequeueInputBuffer(0)
                    if (idx < 0) break
                    val ib = c.getInputBuffer(idx) ?: break
                    ib.clear()
                    val n = buffer.readInto(f, gen, ib)
                    if (n < 0) {
                        // Fell behind the buffer (or it was reset): start over at a fresh key frame.
                        reset()
                        break
                    }
                    c.queueInputBuffer(idx, 0, n, f.ptsUs, if (f.isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    arrivals.addLast(f.arrivalNs)
                    lastPts = f.ptsUs
                }
                val cc = codec ?: continue

                val out = cc.dequeueOutputBuffer(info, 10_000)
                if (out >= 0) {
                    val arrival = arrivals.removeFirstOrNull() ?: target
                    if (arrival < prerollUntilNs) {
                        cc.releaseOutputBuffer(out, false)
                        continue
                    }
                    val waitNs = arrival + delayNs - System.nanoTime()
                    if (waitNs > 0) Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
                    cc.releaseOutputBuffer(out, true)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "delayed viewer stopped", e)
        } finally {
            reset()
        }
    }
}
