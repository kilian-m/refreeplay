package io.github.kilianm.refreeplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Hardware H.264 encoder fed by the camera through an input Surface.
 * Every compressed frame goes straight into the [FrameRingBuffer].
 */
class VideoEncoder(
    val config: CaptureConfig,
    private val sink: FrameRingBuffer,
    private val onError: (String) -> Unit,
) {
    private val thread = HandlerThread("VideoEncoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val codec: MediaCodec = MediaCodec.createEncoderByType(VIDEO_MIME)
    private val frameCounter = AtomicLong()

    @Volatile
    private var released = false

    lateinit var inputSurface: Surface
        private set

    var bitrate: Int = 0
        private set

    val framesEncoded: Long get() = frameCounter.get()

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (released) return
            try {
                val buf = codec.getOutputBuffer(index)
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (buf != null && info.size > 0 && !isConfig) {
                    buf.position(info.offset)
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    sink.add(buf, info.size, info.presentationTimeUs, key)
                    frameCounter.incrementAndGet()
                }
                codec.releaseOutputBuffer(index, false)
            } catch (_: IllegalStateException) {
                // Codec is being stopped.
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            sink.setFormat(format)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (!released) onError("Video encoder error: ${e.diagnosticInfo}")
        }
    }

    init {
        try {
            val caps = codec.codecInfo.getCapabilitiesForType(VIDEO_MIME).videoCapabilities
            bitrate = caps.bitrateRange.clamp(config.targetBitrate)
            // Short GOP so that exact seeking / frame stepping during replay stays fast.
            val gopSeconds = (30f / config.fps).coerceIn(0.1f, 0.5f)
            fun format(withOperatingRate: Boolean) =
                MediaFormat.createVideoFormat(VIDEO_MIME, config.width, config.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                    setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, gopSeconds)
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    if (withOperatingRate && config.fps > 60) {
                        setInteger(MediaFormat.KEY_OPERATING_RATE, config.fps)
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                    }
                }
            codec.setCallback(callback, handler)
            try {
                codec.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                codec.reset()
                codec.setCallback(callback, handler)
                codec.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = codec.createInputSurface()
        } catch (e: Exception) {
            runCatching { codec.release() }
            thread.quitSafely()
            throw e
        }
    }

    fun start() {
        codec.start()
    }

    /** Asks the encoder to emit a key frame as soon as possible. */
    fun requestKeyFrame() {
        if (released) return
        runCatching {
            codec.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    fun release() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        handler.post {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        runCatching { inputSurface.release() }
        thread.quitSafely()
    }
}
