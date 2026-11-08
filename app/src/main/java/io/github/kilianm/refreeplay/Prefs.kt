package io.github.kilianm.refreeplay

import android.app.ActivityManager
import android.content.Context
import kotlin.math.min

const val MODE_REPLAY = 0
const val MODE_DELAY = 1

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

    /** [MODE_REPLAY] or [MODE_DELAY]. */
    var mode: Int
        get() = sp.getInt("mode", MODE_REPLAY)
        set(v) = sp.edit().putInt("mode", v).apply()

    /** Length of a replay in seconds (= buffer length in replay mode). */
    var replaySeconds: Float
        get() = sp.getFloat("replayLength", 20f)
        set(v) = sp.edit().putFloat("replayLength", v).apply()

    /** Delay of the delayed view in seconds (= buffer length in delay mode). */
    var delaySeconds: Float
        get() = sp.getFloat("delay", 5f)
        set(v) = sp.edit().putFloat("delay", v).apply()

    var playbackSpeed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) = sp.edit().putFloat("speed", v).apply()
}

/** How much memory a buffer of a given length needs, and how much this phone can give. */
object BufferSizing {
    const val MIN_SECONDS = 1f
    const val MAX_SECONDS = 600f

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
     * Largest buffer the app allocates: 30 % of the phone's RAM. Tested on an 8 GB phone: with
     * about 3.3 GB (45 %) the frame rate dropped and Android reclaimed the video encoder.
     */
    fun maxBytes(context: Context): Long = (totalRam(context) * 0.30).toLong().coerceAtLeast(256L shl 20)

    /** Longest buffer (seconds) that fits into [maxBytes] at this bitrate. */
    fun maxSeconds(context: Context, bitrate: Int): Float =
        ((maxBytes(context) / (bitrate / 8.0) - 3) / 1.2).toFloat()

    /** A short warning for the settings dialog, or null if the length is unproblematic. */
    fun warning(context: Context, bitrate: Int, seconds: Float): String? {
        val need = bytesFor(bitrate, seconds)
        return when {
            need > maxBytes(context) ->
                "⚠ Too long for this phone's memory at this quality: only about %.0f s will be kept. Lower the quality, frame rate or resolution for longer buffers."
                    .format(maxSeconds(context, bitrate))
            need > totalRam(context) * 0.20 ->
                "⚠ Uses a large part of the phone's memory (%.1f GB RAM); close other apps if Android closes this one."
                    .format(totalRam(context) / 1e9)
            else -> null
        }
    }
}
