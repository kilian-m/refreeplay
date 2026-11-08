package io.github.kilianm.refreeplay

import android.app.ActivityManager
import android.content.Context
import kotlin.math.max

/** Persistent user settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** null = default (widest back camera). */
    var cameraKey: String?
        get() = sp.getString("camera", null)
        set(v) = sp.edit().putString("camera", v).apply()

    var width: Int
        get() = sp.getInt("width", 1920)
        set(v) = sp.edit().putInt("width", v).apply()

    var height: Int
        get() = sp.getInt("height", 1080)
        set(v) = sp.edit().putInt("height", v).apply()

    var fps: Int
        get() = sp.getInt("fps", 60)
        set(v) = sp.edit().putInt("fps", v).apply()

    /** 0 = standard, 1 = high, 2 = maximum. */
    var quality: Int
        get() = sp.getInt("quality", 1)
        set(v) = sp.edit().putInt("quality", v).apply()

    /** Replay length in seconds: how much video the buffer keeps. */
    var replaySeconds: Float
        get() = sp.getFloat("replayLength", 20f)
        set(v) = sp.edit().putFloat("replayLength", v).apply()

    /** Delay of the displayed picture in seconds; 0 = live view. At most [replaySeconds]. */
    var delaySeconds: Float
        get() = sp.getFloat("displayDelay", 0f)
        set(v) = sp.edit().putFloat("displayDelay", v).apply()

    var playbackSpeed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) = sp.edit().putFloat("speed", v).apply()
}

/** How much memory a buffer of a given length needs, and how much this phone can give. */
object BufferSizing {
    const val MIN_SECONDS = 1f
    const val MAX_SECONDS = 600f

    /** The delayed view needs a little more than the delay itself in the buffer. */
    const val DELAY_MARGIN_S = 0.6f

    /** Seconds the buffer must hold for a replay length and a delay (0 = live view). */
    fun bufferSeconds(replaySeconds: Float, delaySeconds: Float): Float =
        if (delaySeconds > 0f) max(replaySeconds, delaySeconds + DELAY_MARGIN_S) else replaySeconds

    /**
     * Room for the target bitrate with 20 % headroom (the real bitrate varies with the scene)
     * plus 3 s for the key-frame granularity and short bursts.
     */
    fun bytesFor(bitrate: Int, seconds: Float): Long =
        (bitrate / 8.0 * (seconds * 1.2 + 3)).toLong().coerceAtLeast(16L shl 20)

    fun totalRam(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem
    }

    /**
     * Memory the buffer can safely use: 30 % of the phone's RAM. Tested on an 8 GB phone: with
     * about 3.3 GB (45 %) the frame rate dropped and Android reclaimed the video encoder.
     */
    fun maxBytes(context: Context): Long = (totalRam(context) * 0.30).toLong().coerceAtLeast(256L shl 20)

    /** Longest buffer (seconds) that fits into [maxBytes] at this bitrate. */
    fun maxSeconds(context: Context, bitrate: Int): Float =
        ((maxBytes(context) / (bitrate / 8.0) - 3) / 1.2).toFloat().coerceAtLeast(0f)
}
