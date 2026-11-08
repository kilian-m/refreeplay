package io.github.kilianm.refreeplay

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.roundToInt

const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC

/** Frame rates offered to the user; the first one available is the default. */
val SUPPORTED_FPS = intArrayOf(60, 30)

/** A selectable camera: a logical camera, or one physical lens behind a logical multi-camera. */
data class CameraOption(
    val cameraId: String,
    val physicalId: String?,
    val label: String,
    val back: Boolean,
    /** Horizontal field of view in degrees (0 if unknown). */
    val fov: Double,
) {
    val key: String get() = if (physicalId == null) cameraId else "$cameraId:$physicalId"
}

data class FpsOption(
    val fps: Int,
    val range: Range<Int>,
    /** Not advertised by the camera; may silently fall back to a lower rate. */
    val unofficial: Boolean = false,
) {
    val label: String get() = if (unofficial) "$fps fps  (not officially supported – check measured fps)" else "$fps fps"
}

data class CaptureConfig(
    val camera: CameraOption,
    val width: Int,
    val height: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val fps: Int,
    val fpsRange: Range<Int>,
    val quality: Int,
    val sensorOrientation: Int,
    val front: Boolean,
) {
    val targetBitrate: Int get() = estimateBitrate(width, height, fps, quality)

    /**
     * Clockwise rotation that makes the camera image upright for the given display rotation.
     * Stored in the MP4 so the replay is upright, and used to orient the live preview.
     */
    fun rotationFor(displayRotationDegrees: Int): Int =
        if (front) (sensorOrientation + displayRotationDegrees) % 360
        else (sensorOrientation - displayRotationDegrees + 360) % 360
}

fun estimateBitrate(width: Int, height: Int, fps: Int, quality: Int): Int {
    val bitsPerPixel = when (quality) {
        0 -> 0.12
        2 -> 0.32
        else -> 0.2
    }
    // Higher frame rates need more bits, but less than linearly (frames are more similar).
    val fpsFactor = (fps / 30.0).pow(0.7)
    return (width.toDouble() * height * 30 * bitsPerPixel * fpsFactor)
        .toLong().coerceIn(2_000_000L, 60_000_000L).toInt() // 60 Mbit/s ≈ 230 MB for 31 s
}

/** Enumerates cameras, resolutions and frame rates supported by both camera and encoder. */
class CameraCatalog(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val charCache = HashMap<String, CameraCharacteristics>()

    fun chars(id: String): CameraCharacteristics =
        charCache.getOrPut(id) { manager.getCameraCharacteristics(id) }

    private fun streamChars(cam: CameraOption) = chars(cam.physicalId ?: cam.cameraId)

    private val encoderCaps: List<MediaCodecInfo.VideoCapabilities> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(VIDEO_MIME, true) } }
            .mapNotNull { runCatching { it.getCapabilitiesForType(VIDEO_MIME).videoCapabilities }.getOrNull() }
    }

    private fun encoderSupports(size: Size, fps: Int): Boolean =
        encoderCaps.isEmpty() || encoderCaps.any {
            runCatching { it.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) }.getOrDefault(false)
        }

    fun cameras(): List<CameraOption> {
        val ids = manager.cameraIdList.toList()
        val out = ArrayList<CameraOption>()
        for (id in ids) {
            val ch = runCatching { chars(id) }.getOrNull() ?: continue
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) continue
            val back = ch.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
            val facing = facingName(ch)
            val multi = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
            val desc = if (multi) " · auto lens switching (pinch to zoom)" else lensDescription(ch)
            out += CameraOption(id, null, "$facing camera $id$desc", back, fieldOfView(ch))
            if (multi) {
                for (pid in ch.physicalCameraIds.sorted()) {
                    if (pid in ids) continue
                    val pch = runCatching { chars(pid) }.getOrNull() ?: continue
                    out += CameraOption(id, pid, "    $facing lens $pid${lensDescription(pch)}", back, fieldOfView(pch))
                }
            }
        }
        return out
    }

    private fun facingName(ch: CameraCharacteristics) = when (ch.get(CameraCharacteristics.LENS_FACING)) {
        CameraMetadata.LENS_FACING_FRONT -> "Front"
        CameraMetadata.LENS_FACING_BACK -> "Back"
        else -> "External"
    }

    private fun fieldOfView(ch: CameraCharacteristics): Double {
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0.0
        val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0.0
        return Math.toDegrees(2 * atan(sensor.width / (2.0 * focal)))
    }

    private fun lensDescription(ch: CameraCharacteristics): String {
        val fov = fieldOfView(ch)
        if (fov <= 0) return ""
        val kind = when {
            fov > 95 -> "ultra-wide"
            fov < 45 -> "telephoto"
            else -> "wide"
        }
        return " · $kind ~${fov.roundToInt()}°"
    }

    private fun videoSizes(cam: CameraOption): List<Size> {
        val map = streamChars(cam).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        return map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty()
    }

    fun resolutions(cam: CameraOption): List<Size> {
        val all = videoSizes(cam).distinct()
        fun isGood(s: Size) = s.width in 640..3840 && s.height in 360..2160 &&
            abs(s.width.toFloat() / s.height - 16f / 9f) < 0.03f
        val list = all.filter(::isGood).ifEmpty { all.filter { it.width <= 1920 } }
        return list.sortedByDescending { it.width * it.height }
    }

    fun fpsOptions(cam: CameraOption, size: Size): List<FpsOption> {
        val map = streamChars(cam).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        val minDuration = runCatching { map.getOutputMinFrameDuration(MediaCodec::class.java, size) }.getOrDefault(0L)
        val maxFps = if (minDuration > 0) (1e9 / minDuration + 0.5).toInt() else 30
        // Fixed ranges (e.g. [60,60]) first: they give a constant frame rate.
        val ranges = chars(cam.cameraId).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty().sortedBy { it.upper - it.lower }
        val result = ArrayList<FpsOption>()
        for (fps in SUPPORTED_FPS) {
            val range = ranges.firstOrNull { it.upper == fps }
            if (range != null && fps <= maxFps && encoderSupports(size, fps)) {
                result += FpsOption(fps, range)
            } else if (fps == 60 && encoderSupports(size, fps)) {
                // Some vendors (e.g. Samsung) hide 60 fps from third-party apps.
                result += FpsOption(fps, Range(60, 60), unofficial = true)
            }
        }
        return result
    }

    private fun previewSize(cam: CameraOption, video: Size): Size {
        val map = streamChars(cam).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return video
        val ratio = video.width.toFloat() / video.height
        return map.getOutputSizes(SurfaceHolder::class.java).orEmpty()
            .filter { abs(it.width.toFloat() / it.height - ratio) < 0.03f && it.width <= 1920 && it.width <= video.width }
            .maxByOrNull { it.width * it.height } ?: video
    }

    /** Default camera: the back camera (or lens) with the widest field of view. */
    fun defaultCamera(cams: List<CameraOption>): CameraOption =
        cams.filter { it.back }.maxByOrNull { it.fov } ?: cams.first()

    /** Turns saved preferences into a configuration that this device supports. */
    fun resolve(prefs: Prefs): CaptureConfig {
        val cams = cameras()
        if (cams.isEmpty()) throw IllegalStateException("No usable camera found on this device.")
        val cam = cams.firstOrNull { it.key == prefs.cameraKey } ?: defaultCamera(cams)
        val sizes = resolutions(cam)
        if (sizes.isEmpty()) throw IllegalStateException("Camera ${cam.key} reports no video sizes.")
        val size = sizes.firstOrNull { it.width == prefs.width && it.height == prefs.height }
            ?: sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: sizes.first()
        val fpsList = fpsOptions(cam, size).ifEmpty { listOf(FpsOption(30, Range(30, 30))) }
        val fps = fpsList.firstOrNull { it.fps == prefs.fps } ?: fpsList.first()
        val preview = previewSize(cam, size)
        val sch = streamChars(cam)
        val sensorOrientation = sch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val front = sch.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        return CaptureConfig(
            camera = cam,
            width = size.width,
            height = size.height,
            previewWidth = preview.width,
            previewHeight = preview.height,
            fps = fps.fps,
            fpsRange = fps.range,
            quality = prefs.quality,
            sensorOrientation = sensorOrientation,
            front = front,
        )
    }
}
