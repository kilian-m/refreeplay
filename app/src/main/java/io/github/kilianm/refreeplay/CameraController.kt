package io.github.kilianm.refreeplay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Runs a Camera2 session that streams to the preview and to the video encoder at the same time. */
class CameraController(context: Context) {

    interface Listener {
        fun onCameraRunning()
        fun onCameraError(message: String)
    }

    var listener: Listener? = null

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("Camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val main = Handler(Looper.getMainLooper())

    // Only touched on the camera thread.
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var config: CaptureConfig? = null
    private var chars: CameraCharacteristics? = null
    private var targets: List<Surface> = emptyList()
    private var generation = 0

    /** Number of frames the camera reported as captured (for diagnostics). */
    val capturedFrames = java.util.concurrent.atomic.AtomicLong()

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult,
        ) {
            capturedFrames.incrementAndGet()
        }
    }

    @Volatile
    var zoomRatio = 1f
        private set

    @Volatile
    var zoomRange: Range<Float> = Range(1f, 1f)
        private set

    @SuppressLint("MissingPermission")
    fun start(cfg: CaptureConfig, preview: Surface, encoderSurface: Surface) {
        handler.post {
            closeInternal()
            val gen = ++generation
            config = cfg
            targets = listOf(preview, encoderSurface)
            try {
                val ch = manager.getCameraCharacteristics(cfg.camera.cameraId)
                chars = ch
                zoomRange = computeZoomRange(cfg, ch)
                zoomRatio = 1f.coerceIn(zoomRange.lower, zoomRange.upper)
                manager.openCamera(cfg.camera.cameraId, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        if (gen != generation) {
                            d.close()
                            return
                        }
                        device = d
                        createSession(d, gen)
                    }

                    override fun onDisconnected(d: CameraDevice) {
                        d.close()
                        if (gen == generation) {
                            device = null
                            session = null
                            postError("Camera disconnected (is another app using it?).")
                        }
                    }

                    override fun onError(d: CameraDevice, error: Int) {
                        d.close()
                        if (gen == generation) {
                            device = null
                            session = null
                            postError(cameraErrorText(error))
                        }
                    }
                })
            } catch (e: Exception) {
                postError("Cannot open camera: ${e.message}")
            }
        }
    }

    private fun createSession(d: CameraDevice, gen: Int) {
        val cfg = config ?: return
        try {
            val outputs = targets.map { s ->
                OutputConfiguration(s).also { oc -> cfg.camera.physicalId?.let { oc.setPhysicalCameraId(it) } }
            }
            val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation) {
                        s.close()
                        return
                    }
                    session = s
                    try {
                        applyRequest()
                        main.post { listener?.onCameraRunning() }
                    } catch (e: Exception) {
                        postError("Cannot start capture: ${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen == generation) {
                        postError(
                            "This camera cannot record ${cfg.width}×${cfg.height} at ${cfg.fps} fps " +
                                "together with the preview.\nPlease choose another combination in Settings."
                        )
                    }
                }
            })
            d.createCaptureSession(sc)
        } catch (e: Exception) {
            postError("Cannot configure camera: ${e.message}")
        }
    }

    private fun applyRequest() {
        val d = device ?: return
        val s = session ?: return
        val cfg = config ?: return
        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        targets.forEach { b.addTarget(it) }
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, cfg.fpsRange)
        val afModes = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        applyZoom(b, cfg)
        s.setRepeatingRequest(b.build(), captureCallback, handler)
    }

    private fun computeZoomRange(cfg: CaptureConfig, ch: CameraCharacteristics): Range<Float> {
        if (cfg.camera.physicalId != null) return Range(1f, 1f)
        if (Build.VERSION.SDK_INT >= 30) {
            ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { return it }
        }
        val max = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return Range(1f, max.coerceAtLeast(1f))
    }

    private fun applyZoom(b: CaptureRequest.Builder, cfg: CaptureConfig) {
        if (cfg.camera.physicalId != null) return
        val ch = chars ?: return
        if (Build.VERSION.SDK_INT >= 30 && ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        } else if (zoomRatio > 1f) {
            val a = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val w = (a.width() / zoomRatio).toInt()
            val h = (a.height() / zoomRatio).toInt()
            val l = (a.width() - w) / 2
            val t = (a.height() - h) / 2
            b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + w, t + h))
        }
    }

    fun setZoom(ratio: Float) {
        handler.post {
            val r = zoomRange
            val z = ratio.coerceIn(r.lower, r.upper)
            if (z == zoomRatio) return@post
            zoomRatio = z
            try {
                applyRequest()
            } catch (_: Exception) {
            }
        }
    }

    /** Stops the camera and waits until it is closed. */
    fun stop() {
        val latch = CountDownLatch(1)
        handler.post {
            generation++
            closeInternal()
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    fun shutdown() {
        stop()
        thread.quitSafely()
    }

    private fun closeInternal() {
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
    }

    private fun postError(msg: String) {
        main.post { listener?.onCameraError(msg) }
    }

    private fun cameraErrorText(code: Int) = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "The camera is in use by another app."
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Too many cameras are open."
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "The camera is disabled by device policy."
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Fatal camera device error."
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Fatal camera service error."
        else -> "Camera error $code"
    }
}
