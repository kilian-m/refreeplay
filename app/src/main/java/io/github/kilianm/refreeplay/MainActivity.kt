package io.github.kilianm.refreeplay

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback, CameraController.Listener,
    ReplayController.Listener {

    companion object {
        const val TAG = "ReFreePlay"

        /** Swiping across the full width of the video moves this much time. */
        const val SWIPE_WINDOW_MS = 4000f
        val SPEEDS = floatArrayOf(1f, 0.5f, 0.25f, 0.125f)

        /** Extra buffer in delay mode, so the decoder can always start at a key frame. */

        /** Folder under Movies/ used by quick save. */
        const val SAVE_FOLDER = "ReFreePlay"

        /** Reviews up to this size are copied, so recording continues freely while reviewing. */
        const val REVIEW_COPY_LIMIT = 256L shl 20
    }

    private lateinit var prefs: Prefs
    private lateinit var catalog: CameraCatalog
    private lateinit var camera: CameraController
    private lateinit var replay: ReplayController
    private lateinit var viewer: DelayedViewer
    private val buffer = FrameRingBuffer()
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var previewContainer: FrameLayout
    private lateinit var previewView: AspectSurfaceView
    private lateinit var playerView: PlayerView
    private lateinit var delayView: TextureView
    private lateinit var topBar: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var liveActions: LinearLayout
    private lateinit var btnBig: MaterialButton
    private lateinit var btnQuickSave: MaterialButton
    private lateinit var delayBadge: TextView
    private lateinit var replayControls: View
    private lateinit var replayBadge: TextView
    private lateinit var messageText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeText: TextView
    private lateinit var frameText: TextView
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnSpeed: MaterialButton
    private lateinit var buttonRows: LinearLayout
    private lateinit var groupTransport: LinearLayout
    private lateinit var groupExtras: LinearLayout
    private lateinit var trimPanel: View
    private lateinit var trimSlider: RangeSlider
    private lateinit var trimLabel: TextView

    // Capture pipeline
    private var config: CaptureConfig? = null
    private var encoder: VideoEncoder? = null
    private var wantRunning = false
    private var cameraActive = false
    private var keepBufferWhilePaused = false
    private var surfaceReady = false
    private var surfaceW = 0
    private var surfaceH = 0
    private var permissionAsked = false

    // Delayed view
    private var delaySurface: Surface? = null
    private var viewerRunning = false
    private var showingDelayWait = false

    // Replay screen
    private var inReplay = false
    private var replayFrozeBuffer = false
    /** The replay being shown; its snapshot stays pinned in the buffer until the replay is closed. */
    private var replayMp4: VirtualMp4? = null
    private var replayIndex: ClipIndex? = null
    private var replayRotation = 0
    private var seekBarTracking = false

    // Cutting / saving
    private var trimming = false

    /** While the cut part is previewed: where playback stops (ms), otherwise -1. */
    private var trimPlayEndMs = -1L
    private lateinit var btnTrimPlay: MaterialButton
    private lateinit var trimPlayhead: View
    private lateinit var miniBar: View
    private lateinit var miniTime: TextView
    private lateinit var miniProgress: MiniTimeline

    /** Replay screen: controls hidden (tap the video) so the whole picture is visible. */
    private var controlsHidden = false
    private var lastTrim = floatArrayOf(0f, 1f)
    private var pendingSaveFile: File? = null
    private var quickSaving = false

    private var lastFrames = 0L
    private var lastCaptured = 0L
    private var lastFpsTime = 0L
    private var measuredFps = 0.0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPipeline()
            else showMessage("Camera permission is required.\nGrant it in Android Settings → Apps → ReFreePlay.")
        }

    /** Opens the system "Save as" dialog; the user picks folder (or cloud drive) and file name. */
    private val saveAsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
            val file = pendingSaveFile
            pendingSaveFile = null
            if (uri != null && file != null) copyToUri(file, uri)
        }

    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            if (inReplay) {
                updateReplayProgress()
                checkTrimPlayback()
                if (trimming) updateTrimLabel()
            }
            ui.postDelayed(this, 40)
        }
    }

    private val hideMessage = Runnable { messageText.visibility = View.GONE }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            // Also catches 180° turns, which do not trigger a configuration change.
            updateDelayTransform()
            applyPreviewAspect()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        catalog = CameraCatalog(this)
        camera = CameraController(this).also { it.listener = this }

        root = findViewById(R.id.root)
        previewContainer = findViewById(R.id.previewContainer)
        previewView = findViewById(R.id.previewView)
        playerView = findViewById(R.id.playerView)
        delayView = findViewById(R.id.delayView)
        topBar = findViewById(R.id.topBar)
        statusText = findViewById(R.id.statusText)
        liveActions = findViewById(R.id.liveActions)
        btnBig = findViewById(R.id.btnBig)
        btnQuickSave = findViewById(R.id.btnQuickSave)
        delayBadge = findViewById(R.id.delayBadge)
        replayControls = findViewById(R.id.replayControls)
        replayBadge = findViewById(R.id.replayBadge)
        messageText = findViewById(R.id.messageText)
        seekBar = findViewById(R.id.seekBar)
        timeText = findViewById(R.id.timeText)
        frameText = findViewById(R.id.frameText)
        btnPlay = findViewById(R.id.btnPlay)
        btnSpeed = findViewById(R.id.btnSpeed)
        buttonRows = findViewById(R.id.buttonRows)
        groupTransport = findViewById(R.id.groupTransport)
        groupExtras = findViewById(R.id.groupExtras)
        trimPanel = findViewById(R.id.trimPanel)
        trimSlider = findViewById(R.id.trimSlider)
        trimLabel = findViewById(R.id.trimLabel)

        previewView.holder.addCallback(this)
        replay = ReplayController(this, playerView, this)
        viewer = DelayedViewer(buffer) { seconds -> ui.post { onDelayStatus(seconds) } }

        setupLiveControls()
        setupDelayView()
        setupReplayControls()
        setupTrimPanel()
        setupGestures()

        onBackPressedDispatcher.addCallback(this) {
            when {
                trimming -> closeTrim()
                inReplay -> exitReplay()
                else -> finish()
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(c.left, c.top, c.right, c.bottom)
            insets
        }

        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, ui)
        applyIntentSettings(intent)
        logCapabilities()
        // Fresh start (not a rotation or return from the background): warn again if the
        // stored settings need more memory than this phone can safely give.
        if (savedInstanceState == null) ui.post { warnIfStoredSettingsTooLarge() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntentSettings(intent)
    }

    /**
     * Optional launch extras, handy for testing over adb, e.g.
     * `adb shell am start -n io.github.kilianm.refreeplay/.MainActivity --ef replay 30 --ef delay 3`
     */
    private fun applyIntentSettings(intent: Intent?) {
        val ex = intent?.extras ?: return
        if (ex.containsKey("camera")) prefs.cameraKey = ex.getString("camera")
        if (ex.containsKey("w")) prefs.width = ex.getInt("w")
        if (ex.containsKey("h")) prefs.height = ex.getInt("h")
        if (ex.containsKey("fps")) prefs.fps = ex.getInt("fps")
        if (ex.containsKey("quality")) prefs.quality = ex.getInt("quality")
        if (ex.containsKey("replay")) prefs.replaySeconds = ex.getFloat("replay")
        if (ex.containsKey("delay")) prefs.delaySeconds = ex.getFloat("delay")
    }

    private fun logCapabilities() {
        runCatching {
            for (cam in catalog.cameras()) {
                Log.i(TAG, "camera ${cam.key}: ${cam.label.trim()}")
                for (s in catalog.resolutions(cam)) {
                    val fps = catalog.fpsOptions(cam, s).joinToString(" ") {
                        it.fps.toString() + (if (it.unofficial) "?" else "")
                    }
                    Log.i(TAG, "   ${s.width}x${s.height}: $fps")
                }
            }
            Log.i(TAG, "RAM %.1f GB, max buffer %d MB".format(BufferSizing.totalRam(this) / 1e9, BufferSizing.maxBytes(this) shr 20))
        }.onFailure { Log.w(TAG, "capability dump failed", it) }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        ui.post(ticker)
        applyOrientationLayout()
        applyLiveUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startPipeline()
            keepBufferWhilePaused = false
        } else if (!permissionAsked) {
            permissionAsked = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
        if (inReplay) replay.pause()
        stopViewer()
        if (keepBufferWhilePaused || inReplay) {
            // A system dialog (e.g. "Save as") is on top, or a replay (which reads from the buffer)
            // is shown: stop the camera, but keep the encoder and the buffered video.
            wantRunning = false
            pauseCamera()
        } else {
            stopPipeline()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        viewer.stop()
        replay.release()
        camera.shutdown()
        encoder?.release()
        encoder = null
        buffer.release()
        io.shutdown()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------------------------------------------------------- capture pipeline

    private fun displayRotationDegrees(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= 30) {
            display?.rotation ?: Surface.ROTATION_90
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun startPipeline() {
        wantRunning = true
        val cfg = try {
            catalog.resolve(prefs)
        } catch (e: Exception) {
            showMessage(e.message ?: "Camera setup failed")
            return
        }
        config = cfg
        Log.i(TAG, "start: camera ${cfg.camera.key} ${cfg.width}x${cfg.height} preview " +
            "${cfg.previewWidth}x${cfg.previewHeight} ${cfg.fps} fps range=${cfg.fpsRange} sensor=${cfg.sensorOrientation}")
        applyPreviewAspect()
        updateDelayTransform()
        previewView.holder.setFixedSize(cfg.previewWidth, cfg.previewHeight)
        maybeStartCamera()
    }

    /** Starts encoder + camera once the preview surface exists with the requested size. */
    private fun maybeStartCamera() {
        if (!wantRunning || cameraActive) return
        val cfg = config ?: return
        if (!surfaceReady || surfaceW != cfg.previewWidth || surfaceH != cfg.previewHeight) return
        var enc = encoder
        if (enc != null && enc.config != cfg) {
            enc.release()
            enc = null
            encoder = null
        }
        if (enc == null) {
            if (!configureBuffer(cfg, resetFormat = true)) return
            enc = try {
                lateinit var created: VideoEncoder
                created = VideoEncoder(cfg, buffer) { msg -> ui.post { onEncoderError(created, msg) } }
                created
            } catch (e: Exception) {
                showMessage("The video encoder cannot record ${cfg.width}×${cfg.height} at ${cfg.fps} fps.\nPlease choose other settings.")
                return
            }
            enc.start()
            encoder = enc
        }
        lastFpsTime = 0
        measuredFps = 0.0
        cameraActive = true
        camera.start(cfg, previewView.holder.surface, enc.inputSurface)
    }

    /** Seconds the buffer keeps: the replay length (and enough for the display delay). */
    private fun activeBufferSeconds(): Float =
        BufferSizing.bufferSeconds(prefs.replaySeconds, prefs.delaySeconds)

    /** Sizes the buffer. Discards the buffered video. */
    private fun configureBuffer(cfg: CaptureConfig, resetFormat: Boolean): Boolean {
        val seconds = activeBufferSeconds()
        var capacity = min(BufferSizing.bytesFor(cfg.targetBitrate, seconds), BufferSizing.maxBytes(this))
        while (true) {
            try {
                buffer.configure(capacity, (seconds * 1_000_000).toLong(), resetFormat)
                Log.i(TAG, "buffer: %.1f s, %d MB".format(seconds, capacity shr 20))
                encoder?.requestKeyFrame()
                return true
            } catch (e: Throwable) {
                Log.w(TAG, "buffer allocation of ${capacity shr 20} MB failed", e)
                capacity /= 2
                if (capacity < (16L shl 20)) {
                    showMessage("Not enough memory for the video buffer.")
                    return false
                }
            }
        }
    }

    /** Stops only the camera; the encoder and the buffered video are kept. */
    private fun pauseCamera() {
        camera.stop()
        cameraActive = false
    }

    private fun stopCapture() {
        stopViewer()
        pauseCamera()
        encoder?.release()
        encoder = null
    }

    private fun stopPipeline() {
        wantRunning = false
        stopCapture()
    }

    private fun restartPipeline() {
        stopPipeline()
        startPipeline()
    }

    private val encoderFailures = ArrayDeque<Long>()

    /**
     * The encoder can fail at runtime, e.g. when Android reclaims it under memory pressure.
     * Restart recording (this clears the buffer); give up after repeated failures.
     */
    private fun onEncoderError(failed: VideoEncoder, msg: String) {
        if (failed !== encoder || !wantRunning) return
        Log.w(TAG, msg)
        if (inReplay) exitReplay() // restarting clears the buffer the replay reads from
        val now = SystemClock.elapsedRealtime()
        while (encoderFailures.isNotEmpty() && now - encoderFailures.first() > 60_000) encoderFailures.removeFirst()
        encoderFailures.addLast(now)
        if (encoderFailures.size > 3) {
            stopPipeline()
            showMessage("$msg\nRecording stopped. Try a lower resolution, quality or buffer length.")
            return
        }
        toast("Video encoder error: recording restarted")
        restartPipeline()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        surfaceW = width
        surfaceH = height
        maybeStartCamera()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        // Keep the encoder and buffer while a picker is open or a replay is shown (it reads from the buffer).
        if (keepBufferWhilePaused || inReplay) pauseCamera() else stopCapture()
    }

    override fun onCameraRunning() {
        if (messageText.visibility == View.VISIBLE && !showingDelayWait) {
            messageText.visibility = View.GONE
        }
        maybeStartViewer()
    }

    override fun onCameraError(message: String) {
        Log.w(TAG, "camera error: $message")
        showMessage(message)
    }

    // ---------------------------------------------------------------- layout

    /** The camera image is rotated relative to the buffer in portrait: swap the aspect ratio. */
    private fun applyPreviewAspect() {
        val cfg = config ?: return
        if (cfg.rotationFor(displayRotationDegrees()) % 180 != 0) {
            previewView.setAspect(cfg.previewHeight, cfg.previewWidth)
        } else {
            previewView.setAspect(cfg.previewWidth, cfg.previewHeight)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationLayout()
    }

    /** Adapts the layout to portrait or landscape without restarting the camera. */
    private fun applyOrientationLayout() {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val d = resources.displayMetrics.density
        applyPreviewAspect()
        updateDelayTransform()

        topBar.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        topBar.gravity = if (portrait) Gravity.START else Gravity.CENTER_VERTICAL
        (statusText.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = if (portrait) 0 else (10 * d).toInt()
            topMargin = if (portrait) (8 * d).toInt() else 0
            statusText.layoutParams = this
        }

        // Buttons in one row at the bottom: centred in portrait, bottom left in landscape. Not at the
        // middle of the screen, where a half-folded foldable phone has its fold.
        liveActions.orientation = LinearLayout.HORIZONTAL
        (liveActions.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (portrait) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.BOTTOM or Gravity.START
            marginStart = if (portrait) 0 else (10 * d).toInt()
            marginEnd = 0
            bottomMargin = ((if (portrait) 32 else 10) * d).toInt()
            liveActions.layoutParams = this
        }
        (btnQuickSave.layoutParams as LinearLayout.LayoutParams).apply {
            width = ((if (portrait) 160 else 168) * d).toInt()
            btnQuickSave.layoutParams = this
        }
        btnBig.layoutParams = btnBig.layoutParams.apply { width = ((if (portrait) 160 else 168) * d).toInt() }
        // Delay badge: below the buttons in landscape, above them in portrait (clear of the status line).
        (delayBadge.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (portrait) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.BOTTOM or Gravity.END
            val m = (14 * d).toInt()
            setMargins(m, m, if (portrait) m else (20 * d).toInt(), if (portrait) ((32 + 56 + 20 + 12) * d).toInt() else m)
            delayBadge.layoutParams = this
        }

        buttonRows.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (group in listOf(groupTransport, groupExtras)) {
            (group.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (portrait) LinearLayout.LayoutParams.MATCH_PARENT else 0
                weight = if (portrait) 0f else if (group === groupTransport) 5f else 4f
                group.layoutParams = this
            }
            for (i in 0 until group.childCount) {
                (group.getChildAt(i) as? MaterialButton)?.apply {
                    isSingleLine = true
                    textSize = if (portrait) 16f else 18f
                    val p = (4 * d).toInt()
                    setPadding(p, paddingTop, p, paddingBottom)
                }
            }
        }
        frameText.visibility = if (portrait) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- live screen

    private fun fmtSeconds(v: Float): String =
        if (abs(v - v.roundToInt()) < 0.05f) v.roundToInt().toString() else "%.1f".format(v)

    private fun fmtBytes(b: Long): String =
        if (b >= 1_000_000_000L) "%.1f GB".format(b / 1e9) else "%d MB".format(b / 1_000_000)

    private fun setupLiveControls() {
        btnBig.setOnClickListener { triggerReplay() }
        btnQuickSave.setOnClickListener { quickSaveBuffer() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        delayBadge.setOnClickListener { showSettings() }
    }

    /** Live screen: live preview, or the delayed picture when a display delay is set. */
    private fun applyLiveUi() {
        val delayed = prefs.delaySeconds > 0f
        btnBig.text = "REPLAY"
        delayBadge.text = "⏱ DELAY ${fmtSeconds(prefs.delaySeconds)} s"
        if (inReplay) return
        delayView.visibility = if (delayed) View.VISIBLE else View.GONE
        delayBadge.visibility = if (delayed) View.VISIBLE else View.GONE
        setPreviewHidden(delayed)
        if (!delayed && showingDelayWait) {
            showingDelayWait = false
            messageText.visibility = View.GONE
        }
    }

    /** Hides the live preview by moving it off-screen (an invisible SurfaceView would stop the camera). */
    private fun setPreviewHidden(hidden: Boolean) {
        previewContainer.translationX = if (hidden) 100_000f else 0f
    }

    // ---------------------------------------------------------------- delayed view

    private fun setupDelayView() {
        delayView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                delaySurface = Surface(st)
                updateDelayTransform()
                maybeStartViewer()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                updateDelayTransform()
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                stopViewer()
                delaySurface?.release()
                delaySurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    private fun maybeStartViewer() {
        if (prefs.delaySeconds <= 0f || inReplay || viewerRunning) return
        val surface = delaySurface ?: return
        if (encoder == null || !cameraActive) return
        viewer.start(surface, (prefs.delaySeconds * 1e9).toLong())
        viewerRunning = true
        updateDelayTransform()
    }

    private fun stopViewer() {
        if (!viewerRunning) return
        viewer.stop()
        viewerRunning = false
    }

    private fun onDelayStatus(waitSeconds: Double?) {
        if (prefs.delaySeconds <= 0f || inReplay) return
        if (waitSeconds == null) {
            if (showingDelayWait) {
                showingDelayWait = false
                messageText.visibility = View.GONE
            }
            return
        }
        showingDelayWait = true
        val delayUs = (prefs.delaySeconds * 1e6).toLong()
        if (buffer.limitedByMemory && buffer.bufferedUs() < delayUs) {
            showMessage("The delay is too long for this phone's memory at this quality.\nReduce the delay or the quality in Settings.")
        } else {
            showMessage("Delayed view starts in %d s…".format(max(1, ceil(waitSeconds).toInt())))
        }
    }

    /** Rotates, mirrors (front camera) and fits the decoded video into the TextureView. */
    private fun updateDelayTransform() {
        val cfg = config ?: return
        val vw = delayView.width.toFloat()
        val vh = delayView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val bw = cfg.width.toFloat()
        val bh = cfg.height.toFloat()
        val rot = cfg.rotationFor(displayRotationDegrees())
        val cw = if (rot % 180 != 0) bh else bw
        val ch = if (rot % 180 != 0) bw else bh
        val s = min(vw / cw, vh / ch)
        val m = Matrix()
        m.setScale(bw / vw, bh / vh) // undo the default stretch to the view size
        m.postTranslate(-bw / 2f, -bh / 2f)
        m.postRotate(rot.toFloat())
        m.postScale(if (cfg.front) -s else s, s)
        m.postTranslate(vw / 2f, vh / 2f)
        delayView.setTransform(m)
    }

    /** Parses a number of seconds ("2.5" or "2,5"); null if it is not a number. */
    private fun parseSeconds(text: String): Float? = text.trim().replace(',', '.').toFloatOrNull()

    /** Stores new durations; resizes the buffer if its length changed, restarts the delayed view if needed. */
    private fun applyDurations(replaySeconds: Float, delaySeconds: Float) {
        val oldBuffer = activeBufferSeconds()
        val oldDelay = prefs.delaySeconds
        prefs.replaySeconds = replaySeconds
        prefs.delaySeconds = delaySeconds
        if (activeBufferSeconds() == oldBuffer && delaySeconds == oldDelay) {
            applyLiveUi()
            return
        }
        if (inReplay) exitReplay()
        stopViewer()
        if (activeBufferSeconds() != oldBuffer) {
            val cfg = config
            if (cfg != null && encoder != null) configureBuffer(cfg, resetFormat = false)
        }
        applyLiveUi()
        maybeStartViewer()
    }

    // ---------------------------------------------------------------- status

    private fun updateStatus() {
        val cfg = config
        val enc = encoder
        val now = SystemClock.elapsedRealtime()
        if (enc != null && now - lastFpsTime >= 1000) {
            val frames = enc.framesEncoded
            if (lastFpsTime > 0) {
                measuredFps = (frames - lastFrames) * 1000.0 / (now - lastFpsTime)
                Log.d(TAG, "measured %.1f fps, buffer %.1f s, %d kB".format(
                    measuredFps, buffer.bufferedUs() / 1e6, buffer.bufferedBytes() / 1000))
            }
            lastCaptured = camera.capturedFrames.get()
            lastFrames = frames
            lastFpsTime = now
        }
        statusText.text = if (cfg == null || enc == null) {
            "Camera stopped"
        } else {
            // Written for the user: how much video is ready, plus warnings only when something is off.
            val have = buffer.bufferedUs() / 1e6f
            val want = prefs.replaySeconds
            val parts = ArrayList<String>()
            parts += if (have + 0.3f >= want) "Replay ready: last ${fmtSeconds(want)} s"
            else "Recording… %d of %s s".format(have.toInt(), fmtSeconds(want))
            if (buffer.limitedByMemory) parts += "⚠ memory full"
            if (measuredFps > 0 && measuredFps < cfg.fps * 0.85) {
                parts += "⚠ only %d of %d fps".format(measuredFps.roundToInt(), cfg.fps)
            }
            val zoom = camera.zoomRatio
            if (abs(zoom - 1f) > 0.01f) parts += "Zoom %.1f×".format(zoom)
            "● " + parts.joinToString("  ·  ")
        }
    }

    // ---------------------------------------------------------------- replay / review

    /**
     * Opens the replay screen with the last replay length.
     * Without a display delay (referee use) the buffer is frozen, so the decision break is not
     * recorded. With a display delay (practice) recording continues, so the delayed picture goes on.
     */
    private fun triggerReplay() {
        val seconds = prefs.replaySeconds
        val review = prefs.delaySeconds > 0f
        if (inReplay) return
        val cfg = config ?: return
        val pinned = buffer.snapshot((seconds * 1_000_000).toLong())
        if (pinned == null || pinned.frames.size < 2) {
            toast("Nothing recorded yet")
            return
        }
        val freeze = !review
        if (freeze) buffer.setFrozen(true)
        if (review) stopViewer()
        showingDelayWait = false
        val rotation = cfg.rotationFor(displayRotationDegrees())
        // The replay is played straight from the buffer (no file is written), so it opens at once.
        val t0 = SystemClock.elapsedRealtime()
        var snap: ReplaySnapshot = pinned
        if (review && pinned.totalBytes <= REVIEW_COPY_LIMIT) {
            // A review keeps recording: work on a copy, so the pinned frames don't block the buffer.
            val copy = runCatching { buffer.copyOf(pinned) }.getOrNull()
            if (copy != null) {
                pinned.release()
                snap = copy
            }
        }
        val mp4 = try {
            VirtualMp4(snap, rotation)
        } catch (e: Exception) {
            snap.release()
            if (freeze) resumeRecording()
            if (review) maybeStartViewer()
            showMessage("Could not prepare replay: ${e.message}")
            return
        }
        Log.i(TAG, "replay %.1fs: %d frames, %d kB, prepared in %d ms".format(
            seconds, snap.frames.size, mp4.length / 1000, SystemClock.elapsedRealtime() - t0))
        messageText.visibility = View.GONE
        enterReplay(mp4, rotation, (snap.endPtsUs - snap.startPtsUs) / 1e6, freeze)
    }

    private fun resumeRecording() {
        buffer.setFrozen(false)
        encoder?.requestKeyFrame()
    }

    private fun enterReplay(mp4: VirtualMp4, rotation: Int, seconds: Double, froze: Boolean) {
        inReplay = true
        replayFrozeBuffer = froze
        replayMp4 = mp4
        replayIndex = mp4.index
        replayRotation = rotation
        playerView.visibility = View.VISIBLE
        delayView.visibility = View.GONE
        delayBadge.visibility = View.GONE
        setPreviewHidden(true)
        liveActions.visibility = View.GONE
        topBar.visibility = View.GONE
        setControlsHidden(false)
        maybeShowControlsHint()
        replayBadge.text = "REPLAY · last %.1f s".format(seconds)
        replay.load(mp4, prefs.playbackSpeed)
        seekBar.max = replay.durationMs.toInt()
        updateSpeedButton()
        updatePlayButton()
        updateReplayProgress()
    }

    private fun exitReplay() {
        if (!inReplay) return
        if (trimming) closeTrim()
        inReplay = false
        replay.stop()
        replayMp4?.let { it.snapshot.release() }
        replayMp4 = null
        replayIndex = null
        if (replayFrozeBuffer) resumeRecording()
        replayFrozeBuffer = false
        playerView.visibility = View.GONE
        replayControls.visibility = View.GONE
        replayBadge.visibility = View.GONE
        miniBar.visibility = View.GONE
        controlsHidden = false
        liveActions.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
        applyLiveUi()
        maybeStartViewer()
    }

    private fun setupReplayControls() {
        findViewById<View>(R.id.btnLive).setOnClickListener { exitReplay() }
        findViewById<View>(R.id.btnBack1).setOnClickListener { replay.jumpBy(-1000) }
        findViewById<View>(R.id.btnFwd1).setOnClickListener { replay.jumpBy(1000) }
        findViewById<View>(R.id.btnPrevFrame).setOnClickListener { replay.stepFrame(-1) }
        findViewById<View>(R.id.btnNextFrame).setOnClickListener { replay.stepFrame(1) }
        findViewById<View>(R.id.btnCut).setOnClickListener { openTrim() }
        findViewById<View>(R.id.btnQuickSaveClip).setOnClickListener { quickSaveClip() }
        btnPlay.setOnClickListener { replay.togglePlay() }
        btnSpeed.setOnClickListener {
            val i = SPEEDS.indexOfFirst { abs(it - prefs.playbackSpeed) < 0.001f }
            val next = SPEEDS[(i + 1).mod(SPEEDS.size)]
            prefs.playbackSpeed = next
            replay.setSpeed(next)
            updateSpeedButton()
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) replay.scrubTo(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                seekBarTracking = true
                replay.pause()
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                seekBarTracking = false
            }
        })
    }

    private fun updateSpeedButton() {
        btnSpeed.text = when (prefs.playbackSpeed) {
            1f -> "1×"
            0.5f -> "½×"
            0.25f -> "¼×"
            0.125f -> "⅛×"
            else -> "%.2f×".format(prefs.playbackSpeed)
        }
    }

    private fun updatePlayButton() {
        btnPlay.text = if (replay.isPlaying) "❚❚" else "▶︎"
    }

    private fun updateReplayProgress() {
        val pos = replay.positionMs()
        if (!seekBarTracking) seekBar.progress = pos.toInt()
        // Time relative to the moment the button was pressed (0.00 = that moment).
        val t = ((pos - replay.durationMs) / 1000.0).let { if (it > -0.005) 0.0 else it }
        timeText.text = "%.2f s".format(t)
        frameText.text = "frame %d/%d".format(replay.currentFrameIndex() + 1, replay.frameCount)
        if (controlsHidden) {
            miniTime.text = if (trimming) {
                val v = trimSlider.values
                "%s   ✂ %.1f s".format(timeText.text, v[1] - v[0])
            } else {
                timeText.text
            }
            val sel = if (trimming) trimSlider.values.let { it[0] / trimSlider.valueTo to it[1] / trimSlider.valueTo } else null
            miniProgress.update(if (replay.durationMs > 0) pos.toFloat() / replay.durationMs else 0f, sel)
        }
    }

    /** Hides the controls (whole picture visible, thin progress line instead) or shows them again. */
    private fun setControlsHidden(hidden: Boolean) {
        controlsHidden = hidden
        // In the cut view the cut panel is hidden instead of the replay controls.
        val panel = if (trimming) trimPanel else replayControls
        panel.visibility = if (hidden) View.GONE else View.VISIBLE
        replayBadge.visibility = if (hidden) View.GONE else View.VISIBLE
        miniBar.visibility = if (hidden) View.VISIBLE else View.GONE
        if (inReplay) updateReplayProgress()
    }

    /** Explains the tap gestures the first two times a replay is opened. */
    private fun maybeShowControlsHint() {
        val sp = getSharedPreferences("hints", MODE_PRIVATE)
        val shown = sp.getInt("controlsHint", 0)
        if (shown >= 2) return
        sp.edit().putInt("controlsHint", shown + 1).apply()
        toast("Tap the video to hide the controls · double-tap to play/pause")
    }

    override fun onReplayStateChanged() {
        updatePlayButton()
    }

    override fun onReplayError(message: String) {
        showMessage("Replay error: $message")
    }

    // ---------------------------------------------------------------- cutting

    private fun setupTrimPanel() {
        trimSlider.setMinSeparationValue(0.2f)
        trimSlider.addOnChangeListener { s, _, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val v = s.values
            val moved = if (abs(v[0] - lastTrim[0]) >= abs(v[1] - lastTrim[1])) v[0] else v[1]
            lastTrim = floatArrayOf(v[0], v[1])
            replay.scrubTo((moved * 1000).toLong())
            updateTrimLabel()
        }
        trimSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) = stopTrimPlayback()
            override fun onStopTrackingTouch(slider: RangeSlider) = snapTrimStart()
        })
        findViewById<View>(R.id.btnTrimCancel).setOnClickListener { closeTrim() }
        findViewById<View>(R.id.btnTrimSaveAs).setOnClickListener { saveClipAs() }
        btnTrimPlay = findViewById(R.id.btnTrimPlay)
        trimPlayhead = findViewById(R.id.trimPlayhead)
        miniBar = findViewById(R.id.miniBar)
        miniTime = findViewById(R.id.miniTime)
        miniProgress = findViewById(R.id.miniProgress)
        btnTrimPlay.setOnClickListener { toggleTrimPlayback() }
    }

    private fun openTrim() {
        if (replayIndex == null) return
        replay.pause()
        val dur = max(replay.durationMs / 1000f, 0.5f)
        trimSlider.valueFrom = 0f
        trimSlider.valueTo = dur
        trimSlider.values = listOf(0f, dur)
        lastTrim = floatArrayOf(0f, dur)
        trimming = true
        replayControls.visibility = View.GONE
        trimPanel.visibility = View.VISIBLE
        updateTrimLabel()
    }

    private fun closeTrim() {
        stopTrimPlayback()
        trimming = false
        trimPanel.visibility = View.GONE
        if (inReplay) setControlsHidden(false)
    }

    /** Plays exactly the part that will be saved (from the snapped start to the end handle), or stops. */
    private fun toggleTrimPlayback() {
        if (trimPlayEndMs >= 0) {
            stopTrimPlayback()
            return
        }
        snapTrimStart()
        val v = trimSlider.values
        trimPlayEndMs = (v[1] * 1000).toLong()
        replay.playFrom((v[0] * 1000).toLong())
        btnTrimPlay.text = "❚❚ Stop"
    }

    private fun stopTrimPlayback() {
        replay.pause()
        if (trimPlayEndMs < 0) return
        trimPlayEndMs = -1
        btnTrimPlay.text = "▶︎ Play"
    }

    /** Called by the ticker: stops the preview at the end handle. */
    private fun checkTrimPlayback() {
        if (trimPlayEndMs < 0) return
        if (replay.positionMs() >= trimPlayEndMs || !replay.isPlaying) {
            val end = trimPlayEndMs
            stopTrimPlayback()
            replay.scrubTo(end) // show exactly the last frame that will be saved
        }
    }

    /** Moves the start handle back to the nearest key frame, where a cut without re-encoding can start. */
    private fun snapTrimStart() {
        val idx = replayIndex ?: return
        val v = trimSlider.values
        val startUs = (v[0] * 1_000_000).toLong() + 500
        var snapped = 0L
        for (i in idx.ptsUs.indices) {
            if (idx.ptsUs[i] > startUs) break
            if (idx.keys[i]) snapped = idx.ptsUs[i]
        }
        val s = snapped / 1e6f
        if (abs(s - v[0]) > 0.001f) {
            trimSlider.values = listOf(s, max(v[1], s + 0.2f).coerceAtMost(trimSlider.valueTo))
            lastTrim = floatArrayOf(trimSlider.values[0], trimSlider.values[1])
            replay.scrubTo((s * 1000).toLong())
        }
        updateTrimLabel()
    }

    /** Updates the cut label and moves the playhead to the current video position. */
    private fun updateTrimLabel() {
        val v = trimSlider.values
        val end = trimSlider.valueTo
        val pos = (replay.positionMs() / 1000f).coerceIn(0f, end)
        trimLabel.text = "Keep %.2f s … %.2f s   (%.1f s)      ▶ now %.2f s".format(v[0] - end, v[1] - end, v[1] - v[0], pos - end)
        val width = trimSlider.trackWidth
        if (width > 0 && end > 0f) {
            val x = trimSlider.left + trimSlider.trackSidePadding + pos / end * width
            trimPlayhead.translationX = x - trimPlayhead.width / 2f
        }
    }

    /** The part chosen with the cut sliders, or the whole replay when the cut panel is closed. */
    private fun selectedClip(): ReplaySnapshot? {
        val snap = replayMp4?.snapshot ?: return null
        if (!trimming) return snap
        snapTrimStart()
        val v = trimSlider.values
        val whole = v[0] <= 0.001f && v[1] >= trimSlider.valueTo - 0.001f
        return if (whole) snap else snap.slice((v[0] * 1_000_000).toLong(), (v[1] * 1_000_000).toLong())
    }

    /** Quick save of the replay (or the cut part) into Movies/ReFreePlay. */
    private fun quickSaveClip() {
        val clip = selectedClip() ?: return
        saveSnapshotToGallery(clip, replayRotation) { ok -> if (ok && trimming) closeTrim() }
    }

    /** Writes the replay (or the cut part) to a temporary file, then lets the user choose where to save it. */
    private fun saveClipAs() {
        val clip = selectedClip() ?: return
        if (!clip.retain()) return
        val out = File(cacheDir, "clip.mp4")
        val rotation = replayRotation
        io.execute {
            val result = runCatching { Mp4Writer.write(clip, out, rotation) }
            clip.release()
            ui.post {
                result.onSuccess {
                    pendingSaveFile = out
                    keepBufferWhilePaused = true
                    try {
                        saveAsLauncher.launch(defaultFileName())
                    } catch (e: Exception) {
                        keepBufferWhilePaused = false
                        toast("No file picker available: ${e.message}")
                    }
                }.onFailure { toast("Saving failed: ${it.message}") }
            }
        }
    }

    // ---------------------------------------------------------------- saving

    private fun defaultFileName(): String =
        "ReFreePlay_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".mp4"

    /** Creates a pending video entry in Movies/ReFreePlay. */
    private fun newGalleryEntry(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, defaultFileName())
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + SAVE_FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the file")
    }

    private fun publishGalleryEntry(uri: Uri) {
        contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
    }

    /** Quick save of the whole buffer (live / delay screen): straight into Movies/ReFreePlay. */
    private fun quickSaveBuffer() {
        if (quickSaving) return
        val cfg = config ?: return
        val snap = buffer.snapshot(buffer.durationUs)
        if (snap == null || snap.frames.size < 2) {
            toast("Nothing recorded yet")
            return
        }
        quickSaving = true
        saveSnapshotToGallery(snap, cfg.rotationFor(displayRotationDegrees())) { quickSaving = false }
        snap.release() // the save holds its own pin
    }

    /**
     * Writes [snap] (copying the compressed frames, no re-encoding) into a new video in
     * Movies/ReFreePlay, then calls [onDone] on the UI thread.
     */
    private fun saveSnapshotToGallery(snap: ReplaySnapshot, rotation: Int, onDone: (Boolean) -> Unit) {
        if (!snap.retain()) {
            toast("Saving failed: the buffer was cleared")
            onDone(false)
            return
        }
        val seconds = (snap.endPtsUs - snap.startPtsUs) / 1e6
        toast("Saving %.1f s…".format(seconds))
        io.execute {
            val result = runCatching {
                val uri = newGalleryEntry()
                try {
                    contentResolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        Mp4Writer.write(snap, muxer, rotation)
                    }
                    publishGalleryEntry(uri)
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
            }
            snap.release()
            ui.post {
                result.onSuccess { toast("Saved %.1f s to Movies/%s".format(seconds, SAVE_FOLDER)) }
                    .onFailure { toast("Saving failed: ${it.message}") }
                onDone(result.isSuccess)
            }
        }
    }

    private fun copyToUri(file: File, uri: Uri) {
        io.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            }
            ui.post {
                result.onSuccess {
                    toast("Video saved")
                    if (trimming) closeTrim()
                }.onFailure { toast("Saving failed: ${it.message}") }
            }
        }
    }

    // ---------------------------------------------------------------- gestures & keys

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        // Live preview: pinch to zoom, double-tap resets the zoom.
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!inReplay) camera.setZoom(camera.zoomRatio * detector.scaleFactor)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!inReplay) camera.setZoom(1f)
                return true
            }
        })
        previewContainer.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            tap.onTouchEvent(e)
            true
        }

        // Replay video (also in the cut view): swipe left/right to scrub finely, tap to hide/show the
        // controls or the cut panel,
        // double-tap to play/pause (in the cut view: play the selected part).
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()
        val singleTap = Runnable { if (inReplay) setControlsHidden(!controlsHidden) }
        playerView.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var startMs = 0L
            var dragging = false
            var lastTapUp = 0L

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        startMs = replay.positionMs()
                        dragging = false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.x - downX
                        if (!dragging && abs(dx) > slop) {
                            dragging = true
                            if (trimming) stopTrimPlayback() else replay.pause()
                            downX = e.x
                        }
                        if (dragging) {
                            val ms = startMs + ((e.x - downX) / v.width * SWIPE_WINDOW_MS).roundToInt()
                            replay.scrubTo(ms)
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!dragging) {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastTapUp <= doubleTapMs) {
                                // Double tap: play/pause, and don't toggle the controls.
                                ui.removeCallbacks(singleTap)
                                lastTapUp = 0
                                // In the cut view: play or stop the selected part.
                                if (trimming) toggleTrimPlayback() else replay.togglePlay()
                            } else {
                                lastTapUp = now
                                ui.postDelayed(singleTap, doubleTapMs)
                            }
                            v.performClick()
                        }
                    }
                }
                return true
            }
        })
    }

    private fun isTriggerKey(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_CAMERA -> true

        else -> false
    }

    /**
     * Volume keys / Bluetooth remotes: on the live screen any key starts a replay;
     * on the replay screen Volume Down goes back, other keys play/pause.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isTriggerKey(keyCode)) return super.onKeyDown(keyCode, event)
        if (event.repeatCount > 0) return true
        when {
            inReplay -> if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) exitReplay() else replay.togglePlay()
            else -> triggerReplay()
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (isTriggerKey(keyCode)) true else super.onKeyUp(keyCode, event)

    // ---------------------------------------------------------------- settings

    /** Settings as chosen in the dialog, before they are checked and applied. */
    private data class SettingsDraft(
        val cameraKey: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val quality: Int,
        val replayText: String,
        val delayText: String,
    )

    /**
     * Shows the settings. Changes are applied when the dialog is closed; settings that can't
     * work (e.g. not enough memory) are rejected with a short explanation.
     */
    private fun showSettings(initial: SettingsDraft? = null) {
        val cams = runCatching { catalog.cameras() }.getOrDefault(emptyList())
        if (cams.isEmpty()) {
            toast("No camera available")
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val spCamera = view.findViewById<Spinner>(R.id.spCamera)
        val spResolution = view.findViewById<Spinner>(R.id.spResolution)
        val spFps = view.findViewById<Spinner>(R.id.spFps)
        val spQuality = view.findViewById<Spinner>(R.id.spQuality)
        val etReplay = view.findViewById<EditText>(R.id.etReplay)
        val etDelay = view.findViewById<EditText>(R.id.etDelay)
        val infoText = view.findViewById<TextView>(R.id.infoText)

        fun adapter(items: List<String>) =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

        val cur = config
        var wantW = initial?.width ?: cur?.width ?: prefs.width
        var wantH = initial?.height ?: cur?.height ?: prefs.height
        var wantFps = initial?.fps ?: cur?.fps ?: prefs.fps
        var sizes: List<Size> = emptyList()
        var fpsList: List<FpsOption> = emptyList()

        fun draft(): SettingsDraft {
            val size = sizes.getOrNull(spResolution.selectedItemPosition)
            return SettingsDraft(
                cameraKey = cams[spCamera.selectedItemPosition].key,
                width = size?.width ?: wantW,
                height = size?.height ?: wantH,
                fps = fpsList.getOrNull(spFps.selectedItemPosition)?.fps ?: wantFps,
                quality = spQuality.selectedItemPosition,
                replayText = etReplay.text.toString(),
                delayText = etDelay.text.toString(),
            )
        }

        fun updateInfo() {
            val f = fpsList.getOrNull(spFps.selectedItemPosition)
            if (sizes.getOrNull(spResolution.selectedItemPosition) == null || f == null) {
                infoText.text = "No supported frame rate for this resolution."
                return
            }
            val d = draft()
            val br = estimateBitrate(d.width, d.height, d.fps, d.quality)
            val sb = StringBuilder()
            val replay = parseSeconds(d.replayText)
            val delay = parseSeconds(d.delayText)
            if (replay != null && delay != null) {
                val need = BufferSizing.bytesFor(br, BufferSizing.bufferSeconds(replay, delay))
                val max = BufferSizing.maxBytes(this)
                sb.append("Memory: needs ≈ %s of ≈ %s this phone can safely use".format(fmtBytes(need), fmtBytes(max)))
                if (need > max) sb.append(" ⚠ too much")
                sb.append(".\n")
            }
            sb.append("At these settings (≈ %.0f Mbit/s) up to ≈ %.0f s fit into memory."
                .format(br / 1e6, min(BufferSizing.maxSeconds(this, br), BufferSizing.MAX_SECONDS)))
            if (f.unofficial) {
                sb.append("\nThis camera does not officially offer ${f.fps} fps. If the status line shows a lower measured rate, choose 30 fps.")
            }
            infoText.text = sb
        }

        fun fillFps() {
            val size = sizes.getOrNull(spResolution.selectedItemPosition)
            val cam = cams[spCamera.selectedItemPosition]
            fpsList = if (size == null) emptyList() else catalog.fpsOptions(cam, size)
            spFps.adapter = adapter(fpsList.map { it.label })
            val idx = fpsList.indexOfFirst { it.fps == wantFps }
            if (idx >= 0) spFps.setSelection(idx)
            updateInfo()
        }

        fun fillSizes() {
            val cam = cams[spCamera.selectedItemPosition]
            sizes = catalog.resolutions(cam)
            spResolution.adapter = adapter(sizes.map { sizeLabel(it) })
            var idx = sizes.indexOfFirst { it.width == wantW && it.height == wantH }
            if (idx < 0) idx = sizes.indexOfFirst { it.width == 1920 && it.height == 1080 }
            if (idx >= 0) spResolution.setSelection(idx)
            fillFps()
        }

        etReplay.setText(initial?.replayText ?: fmtSeconds(prefs.replaySeconds))
        etDelay.setText(initial?.delayText ?: fmtSeconds(prefs.delaySeconds))
        spCamera.adapter = adapter(cams.map { it.label })
        val camKey = initial?.cameraKey ?: cur?.camera?.key ?: prefs.cameraKey
        spCamera.setSelection(cams.indexOfFirst { it.key == camKey }.coerceAtLeast(0))
        spQuality.adapter = adapter(listOf("Standard", "High", "Maximum"))
        spQuality.setSelection((initial?.quality ?: prefs.quality).coerceIn(0, 2))
        fillSizes()

        spCamera.onItemSelectedListener = SimpleItemSelected { fillSizes() }
        spResolution.onItemSelectedListener = SimpleItemSelected { pos ->
            sizes.getOrNull(pos)?.let {
                wantW = it.width
                wantH = it.height
            }
            fillFps()
        }
        spFps.onItemSelectedListener = SimpleItemSelected { pos ->
            fpsList.getOrNull(pos)?.let { wantFps = it.fps }
            updateInfo()
        }
        spQuality.onItemSelectedListener = SimpleItemSelected { updateInfo() }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateInfo()
        }
        etReplay.addTextChangedListener(watcher)
        etDelay.addTextChangedListener(watcher)

        MaterialAlertDialogBuilder(this)
            .setTitle("Settings")
            .setView(view)
            .setOnDismissListener {
                hideSystemBars()
                if (!isFinishing && !isDestroyed) applySettings(draft())
            }
            .show()
    }

    /** Why [d] can't be used, in one or two sentences; null if it is fine. */
    private fun settingsProblem(d: SettingsDraft): String? {
        val replay = parseSeconds(d.replayText)
        val delay = parseSeconds(d.delayText)
        val minS = BufferSizing.MIN_SECONDS
        val maxS = BufferSizing.MAX_SECONDS
        if (replay == null || replay < minS || replay > maxS) {
            return "The replay length must be between ${fmtSeconds(minS)} and ${fmtSeconds(maxS)} seconds."
        }
        if (delay == null || delay < 0f || delay > maxS) {
            return "The display delay must be between 0 (live picture) and ${fmtSeconds(maxS)} seconds."
        }
        if (delay > replay) {
            return "The display delay (${fmtSeconds(delay)} s) can't be longer than the replay length " +
                "(${fmtSeconds(replay)} s): the delayed picture comes from the replay memory."
        }
        val br = estimateBitrate(d.width, d.height, d.fps, d.quality)
        val seconds = BufferSizing.bufferSeconds(replay, delay)
        val need = BufferSizing.bytesFor(br, seconds)
        val max = BufferSizing.maxBytes(this)
        if (need > max) {
            return "Not enough memory: ${fmtSeconds(seconds)} s at these settings need ≈ ${fmtBytes(need)}, " +
                "but this phone can safely use only ≈ ${fmtBytes(max)} (≈ ${fmtSeconds(BufferSizing.maxSeconds(this, br).toInt().toFloat())} s). " +
                "Shorten the replay length or lower resolution, frame rate or quality."
        }
        return null
    }

    /**
     * Stored settings can need more memory than is safe (set by an older version, on another
     * phone, or via launch extras). They are not changed automatically: the user is warned at
     * every fresh start and can change them or continue anyway.
     */
    private fun warnIfStoredSettingsTooLarge() {
        if (isFinishing || isDestroyed) return
        val stored = SettingsDraft(
            cameraKey = prefs.cameraKey ?: "",
            width = prefs.width,
            height = prefs.height,
            fps = prefs.fps,
            quality = prefs.quality,
            replayText = fmtSeconds(prefs.replaySeconds),
            delayText = fmtSeconds(prefs.delaySeconds),
        )
        val problem = settingsProblem(stored) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Check your settings")
            .setMessage("$problem\n\nIf you continue anyway, the replay may be shorter than set and the phone may run low on memory.")
            .setPositiveButton("Change settings") { _, _ -> showSettings() }
            .setNegativeButton("Continue anyway", null)
            .setOnDismissListener { hideSystemBars() }
            .show()
    }

    /** Applies the settings from the dialog, or explains why they can't be used. */
    private fun applySettings(d: SettingsDraft) {
        val captureChanged = d.cameraKey != (config?.camera?.key ?: prefs.cameraKey) ||
            d.width != prefs.width || d.height != prefs.height || d.fps != prefs.fps || d.quality != prefs.quality
        if (!captureChanged && parseSeconds(d.replayText) == prefs.replaySeconds &&
            parseSeconds(d.delayText) == prefs.delaySeconds
        ) return // nothing changed
        val problem = settingsProblem(d)
        if (problem != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Settings not applied")
                .setMessage(problem)
                .setPositiveButton("Change settings") { _, _ -> showSettings(d) }
                .setNegativeButton("Discard changes", null)
                .setOnDismissListener { hideSystemBars() }
                .show()
            return
        }
        val replay = parseSeconds(d.replayText)!!
        val delay = parseSeconds(d.delayText)!!
        if (captureChanged) {
            prefs.cameraKey = d.cameraKey
            prefs.width = d.width
            prefs.height = d.height
            prefs.fps = d.fps
            prefs.quality = d.quality
            // Restarting creates a new buffer anyway.
            prefs.replaySeconds = replay
            prefs.delaySeconds = delay
            if (inReplay) exitReplay()
            messageText.visibility = View.GONE
            applyLiveUi()
            restartPipeline()
        } else {
            applyDurations(replay, delay)
        }
    }

    private fun sizeLabel(s: Size): String {
        val name = when (s.height) {
            2160 -> "4K"
            1440 -> "1440p"
            1080 -> "1080p"
            720 -> "720p"
            480 -> "480p"
            else -> "${s.height}p"
        }
        return "${s.width}×${s.height}  ($name)"
    }

    // ---------------------------------------------------------------- helpers

    private fun showMessage(text: String, autoHideMs: Long = 0) {
        ui.removeCallbacks(hideMessage)
        messageText.text = text
        messageText.visibility = View.VISIBLE
        if (autoHideMs > 0) ui.postDelayed(hideMessage, autoHideMs)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private class SimpleItemSelected(val onSelected: (Int) -> Unit) : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected(position)
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
}
