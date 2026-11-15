package io.github.kilianm.refreeplay

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
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
import android.view.PixelCopy
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback, CameraController.Listener,
    ReplayController.Listener, StreamReceiver.Listener {

    companion object {
        const val TAG = "ReFreePlay"

        /** Swiping across the full width of the video moves this much time. */
        const val SWIPE_WINDOW_MS = 4000f
        val SPEEDS = floatArrayOf(1f, 0.5f, 0.25f, 0.125f)

        /** Folder under Movies/ used by quick save. */
        const val SAVE_FOLDER = "ReFreePlay"

        /** Reviews up to this size are copied, so recording continues freely while reviewing. */
        const val REVIEW_COPY_LIMIT = 256L shl 20

        /** How long a camera phone tries to join a lost hotspot again before it gives up. */
        private const val REJOIN_TIMEOUT_MS = 20_000

        private const val SCAN_HINT =
            "Point this camera at the QR code on the main phone.\n(Main phone: ⚙ → Add a camera phone)"
    }

    /**
     * One camera whose video the main phone keeps: its own ([peer] is null) or a camera phone's.
     * Only touched on the main thread; the buffer has its own lock.
     */
    private class Feed(val peer: StreamReceiver.Peer?) {
        val buffer = FrameRingBuffer()

        /** A camera phone's video as it announced it; null before its first stream. */
        var remoteMeta: StreamMeta? = null

        /** What a camera phone's cameras can do and what is set; null until it told. */
        var capabilities: CameraRemote.Capabilities? = null

        /** Buffer generation in which the buffer was full once (status shows "ready" from then on). */
        var readyGeneration = -1

        // For the frame rate and data rate in the status line.
        var lastFrames = 0L
        var lastBytes = 0L
        var fps = 0.0
        var mbit = 0.0
    }

    private lateinit var prefs: Prefs
    private lateinit var catalog: CameraCatalog
    private lateinit var camera: CameraController
    private lateinit var replay: ReplayController
    private lateinit var viewer: DelayedViewer

    /** Main phone: this phone's own camera. */
    private val ownFeed = Feed(null)

    /** Main phone: all cameras, [ownFeed] first, then the camera phones in the order they joined. */
    private val feeds = arrayListOf(ownFeed)

    /** Main phone: the camera on the live screen. */
    private var shownFeed = ownFeed
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

    // Video stream. Camera phone: the encoder's output goes through [sender] to the main phone.
    // Main phone: [receiver] hands what the camera phones send to their feeds' buffers.
    private var sender: StreamSender? = null
    private var receiver: StreamReceiver? = null

    // Main phone: the hotspot the camera phones join, and the QR code that tells them how
    private var hotspot: HotspotHost? = null
    private var hotspotCredentials: HotspotHost.Credentials? = null
    private var hotspotStarting = false
    private var wifiPermissionAsked = false

    /** The QR code is on screen (asked for in the settings) until a camera phone connects or it is tapped away. */
    private var pairPanelWanted = false
    private var shownQrText: String? = null
    private var logPairing = false

    /** Testing only, see [applyIntentSettings]: the main phone also connects to itself as a camera phone. */
    private var selfTest = false

    // Camera phone ([hotspotClient] and [pairing] are read by the sender's thread when it connects)
    @Volatile
    private var hotspotClient: HotspotClient? = null

    @Volatile
    private var pairing: PairingInfo? = null
    private var joinState: HotspotClient.State? = null
    private var rejoinTried = false
    private var scanning = false
    private var scanThread: HandlerThread? = null
    private var scanBitmap: Bitmap? = null
    private var linkMbit = 0.0
    private var lastLinkBytes = 0L
    private var lastOverflows = 0L
    private var lastOverflowTime = 0L

    /** What this phone's cameras can do, as text for the main phone; worked out once. */
    private var capabilitiesText: String? = null

    /** Main phone with several cameras: switch the view, on the live screen and in a replay. */
    private lateinit var btnFeed: MaterialButton
    private lateinit var btnReplayFeed: MaterialButton
    private lateinit var pairPanel: View
    private lateinit var qrImage: ImageView
    private lateinit var pairText: TextView
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
    /** The clip being shown; its snapshot stays pinned in the buffer until the replay is closed. */
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

    private var lastCaptured = 0L
    private var lastFpsTime = 0L

    /** Frame rate of this phone's own encoder. */
    private var measuredFps = 0.0
    private var lastFrames = 0L

    /** Testing only, see [applyIntentSettings]. */
    private var estimateScale = 1f

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPipeline()
            else showMessage("Camera permission is required.\nGrant it in Android Settings → Apps → ReFreePlay.")
        }

    /** Main phone: Android's permission for opening the hotspot. */
    private val wifiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startHotspot()
            else showMessage("Without this permission the phone can't open the hotspot for camera phones.\nGrant it in Android Settings → Apps → ReFreePlay → Permissions.")
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
            updateAutoScan()
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
            // The main phone must know how the camera is held now.
            localVideoInfo()?.let { sender?.setMeta(it) }
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
        pairPanel = findViewById(R.id.pairPanel)
        qrImage = findViewById(R.id.qrImage)
        pairText = findViewById(R.id.pairText)
        pairPanel.setOnClickListener {
            pairPanelWanted = false
            updatePairPanel()
        }
        btnFeed = findViewById(R.id.btnFeed)
        btnReplayFeed = findViewById(R.id.btnReplayFeed)
        btnFeed.setOnClickListener { switchLiveFeed() }
        btnReplayFeed.setOnClickListener { switchReplayFeed() }

        previewView.holder.addCallback(this)
        replay = ReplayController(this, playerView, this)
        viewer = DelayedViewer { seconds -> ui.post { onDelayStatus(seconds) } }

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
        // Testing only (not stored): scales the memory estimate, e.g. 0.5 to check that the buffer grows.
        if (ex.containsKey("estimateScale")) estimateScale = ex.getFloat("estimateScale")
        // The extras below choose where the camera's video goes, which any other app could do
        // too by starting this one. So they only work in a debug build (gradlew assembleDebug).
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        if (ex.containsKey("mode")) prefs.mode = ex.getInt("mode") // --ei mode 0 (main phone), 1 (camera phone)
        // Not stored:
        // - logPairing: the main phone writes the QR code's text to the log
        // - pair: a camera phone pairs with that text without scanning
        // - share: the main phone shows the QR code, as "Add a camera phone" in the settings does
        // - selfTest: the main phone also connects to itself as a camera phone (same picture twice)
        if (ex.containsKey("logPairing")) logPairing = ex.getBoolean("logPairing")
        if (ex.containsKey("selfTest")) selfTest = ex.getBoolean("selfTest")
        ex.getString("pair")?.let { text -> ui.post { if (isCameraOnly) onQrText(text) } }
        if (ex.getBoolean("share", false)) ui.post { addCameraPhone() }
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
        startForMode()
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
        releaseEncoder()
        scanning = false
        ui.removeCallbacks(scanTick)
        scanThread?.quitSafely()
        hotspotClient?.leave()
        stopSharing()
        ownFeed.buffer.release()
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

    // ---------------------------------------------------------------- roles (main phone / camera phone)

    /** The role that is set up now; follows Prefs.mode in [startForMode]. */
    private var activeMode = -1
    private val isMain: Boolean get() = activeMode == Prefs.MODE_MAIN
    private val isCameraOnly: Boolean get() = activeMode == Prefs.MODE_CAMERA

    /** A display delay only exists where the video is watched. */
    private fun displayDelay(): Float = if (isCameraOnly) 0f else prefs.delaySeconds

    /** This phone's camera video as the other end needs to know it; null while no camera is set up. */
    private fun localVideoInfo(): StreamMeta? = config?.let {
        StreamMeta(it.width, it.height, it.fps, it.targetBitrate, it.rotationFor(displayRotationDegrees()), it.front)
    }

    /** What is known about a camera's video: from this phone's camera, or as a camera phone announced it. */
    private fun feedInfo(feed: Feed): StreamMeta? = if (feed.peer == null) localVideoInfo() else feed.remoteMeta

    /** The video of the camera on the live screen. */
    private fun videoInfo(): StreamMeta? = feedInfo(shownFeed)

    /** "This phone", "Camera 1", "Camera 2", … */
    private fun feedName(feed: Feed): String = if (feed.peer == null) "This phone" else "Camera ${feeds.indexOf(feed)}"

    private fun feedLabel(feed: Feed): String = "⇄ " + feedName(feed)

    private fun feedOf(peer: StreamReceiver.Peer): Feed? = feeds.firstOrNull { it.peer === peer }

    /** Main phone with several cameras: shows the next one on the live screen. */
    private fun switchLiveFeed() {
        if (feeds.size < 2 || inReplay) return
        showFeed(feeds[(feeds.indexOf(shownFeed) + 1) % feeds.size])
    }

    private fun showFeed(feed: Feed) {
        shownFeed = feed
        stopViewer()
        if (showingDelayWait) {
            showingDelayWait = false
            messageText.visibility = View.GONE
        }
        applyLiveUi()
        updateDelayTransform()
        maybeStartViewer()
    }

    /** Starts what this phone's role needs, after ending what an earlier role left running. */
    private fun startForMode() {
        if (activeMode != prefs.mode) {
            leaveMode(activeMode)
            activeMode = prefs.mode
        }
        applyLiveUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startPipeline()
            keepBufferWhilePaused = false
        } else if (!permissionAsked) {
            permissionAsked = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun leaveMode(old: Int) {
        if (inReplay) exitReplay()
        stopPipeline()
        when (old) {
            Prefs.MODE_MAIN -> stopSharing()
            Prefs.MODE_CAMERA -> {
                stopScan()
                hotspotClient?.leave()
                hotspotClient = null
                pairing = null
                joinState = null
            }
        }
        ownFeed.buffer.release()
        config = null
        showingDelayWait = false
        messageText.visibility = View.GONE
        pairPanel.visibility = View.GONE
    }

    // ---------------------------------------------------------------- main phone: camera phones

    /** Cameras of the camera phones, as they told; kept by camera phone id (it arrives before its video). */
    private val peerCapabilities = HashMap<String, CameraRemote.Capabilities>()

    /** Opens the hotspot for camera phones (the first time) and shows the QR code they scan. */
    private fun addCameraPhone() {
        if (!isMain || !ensureReceiver()) return
        pairPanelWanted = true
        startHotspot()
        updatePairPanel()
    }

    private fun ensureReceiver(): Boolean {
        if (receiver != null) return true
        receiver = try {
            StreamReceiver(this)
        } catch (e: Exception) {
            showMessage("Cannot wait for camera phones (${e.message}).")
            return false
        }
        return true
    }

    /** Ends the connections to all camera phones and forgets them and their video. */
    private fun stopSharing() {
        receiver?.close()
        receiver = null
        hotspot?.stop()
        hotspot = null
        hotspotCredentials = null
        hotspotStarting = false
        pairPanelWanted = false
        shownQrText = null
        peerCapabilities.clear()
        for (f in feeds) if (f !== ownFeed) f.buffer.release()
        feeds.retainAll { it === ownFeed }
        shownFeed = ownFeed
    }

    /** Opens the hotspot the camera phones join; asks for Android's permission first if needed. */
    private fun startHotspot(retry: Boolean = true) {
        if (!isMain || hotspotCredentials != null || hotspotStarting) return
        val permission =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            if (!wifiPermissionAsked) {
                wifiPermissionAsked = true
                wifiPermissionLauncher.launch(permission)
            } else {
                pairPanelWanted = false
                updatePairPanel()
            }
            return
        }
        hotspotStarting = true
        val host = hotspot ?: HotspotHost(this).also { hotspot = it }
        host.start(
            started = { c ->
                hotspotStarting = false
                hotspotCredentials = c
                Log.i(TAG, "hotspot started: ${c.ssid} (${c.security})")
                updatePairPanel()
                // The hotspot's address can appear a moment later; it is part of the code.
                ui.postDelayed({ updatePairPanel() }, 1500)
            },
            failed = { msg ->
                hotspotCredentials = null
                Log.w(TAG, "hotspot: $msg")
                if (retry && isMain) {
                    // Try once more: when the app's screen was just rebuilt, Android may still be
                    // closing the hotspot of the old one and refuses a second request.
                    ui.postDelayed({
                        hotspotStarting = false
                        if (!isDestroyed) startHotspot(retry = false)
                    }, 1500)
                } else {
                    hotspotStarting = false
                    pairPanelWanted = false
                    updatePairPanel()
                    if (isMain && !inReplay) showMessage("$msg\nTo try again: ⚙ → Add a camera phone.", 8000)
                }
            },
        )
    }

    /** Shows the QR code for camera phones, or hides it. */
    private fun updatePairPanel() {
        val r = receiver
        if (!isMain || inReplay || !pairPanelWanted || r == null) {
            pairPanel.visibility = View.GONE
            return
        }
        val c = hotspotCredentials
        if (c == null) {
            qrImage.visibility = View.GONE
            pairText.text = "Starting the hotspot for camera phones…"
        } else {
            val text = PairingInfo(c.ssid, c.passphrase, c.security, HotspotHost.localAddresses(), r.port, r.token).toText()
            if (text != shownQrText) {
                val dm = resources.displayMetrics
                qrImage.setImageBitmap(QrCodes.encode(text, (min(dm.widthPixels, dm.heightPixels) * 0.55f).toInt()))
                shownQrText = text
                if (logPairing) Log.i(TAG, "pairing: $text")
            }
            qrImage.visibility = View.VISIBLE
            pairText.text = "On the other phone: open ReFreePlay, tap ⚙ and set \"This phone is\" to \"Camera phone\". " +
                "It then looks for this code with its camera.\nHotspot: ${c.ssid}" +
                (if (feeds.size > 1) "\n⚠ More than one camera phone is not tested and may overload this phone." else "") +
                "\n(tap to close)"
        }
        pairPanel.visibility = View.VISIBLE
    }

    /** Connection thread: waits until the main thread has prepared the camera phone's buffer. */
    override fun onStreamFormat(peer: StreamReceiver.Peer, meta: StreamMeta, format: MediaFormat): FrameSink? {
        val sink = java.util.concurrent.atomic.AtomicReference<FrameSink?>()
        val done = CountDownLatch(1)
        ui.post {
            try {
                if (!isDestroyed && isMain && receiver != null) sink.set(prepareForStream(peer, meta, format))
            } finally {
                done.countDown()
            }
        }
        done.await(5, TimeUnit.SECONDS)
        return sink.get()
    }

    override fun onStreamMeta(peer: StreamReceiver.Peer, meta: StreamMeta) {
        ui.post {
            // Only how the camera is held may change here; a different video is set up in prepareForStream.
            val feed = feedOf(peer) ?: return@post
            if (feed.remoteMeta?.sameVideo(meta) == true) {
                feed.remoteMeta = meta
                if (feed === shownFeed) updateDelayTransform()
            }
        }
    }

    override fun onCapabilities(peer: StreamReceiver.Peer, json: String) {
        val caps = CameraRemote.parseCapabilities(json) ?: return
        ui.post { peerCapabilities[peer.id] = caps }
    }

    /**
     * A camera phone (re)connected. A new one gets its own buffer and a share of the memory. If a
     * known one sends the same video as before, its buffered video is kept and continues;
     * otherwise its buffer is set up for the new video. Returns the buffer for its frames.
     */
    private fun prepareForStream(peer: StreamReceiver.Peer, meta: StreamMeta, format: MediaFormat): FrameSink? {
        var feed = feedOf(peer)
        val same = feed != null && feed.remoteMeta?.sameVideo(meta) == true && feed.buffer.capacityBytes > 0 &&
            StreamProtocol.sameCodecConfig(feed.buffer.currentFormat(), format)
        Log.i(TAG, "camera phone ${peer.name} connected: ${meta.width}x${meta.height} ${meta.fps} fps, " +
            "${meta.bitrate / 1000} kbit/s, rotation ${meta.rotation}" + if (same) " (continues)" else "")
        if (feed != null && same) {
            feed.remoteMeta = meta
            feed.buffer.continueAfterGap()
            if (feed === shownFeed) updateDelayTransform()
            return feed.buffer
        }
        if (inReplay) exitReplay()
        stopViewer()
        if (feed == null) {
            feed = Feed(peer)
            feeds.add(feed)
            // One more camera shares the memory: size the buffers of the others anew.
            reconfigureBuffers()
            // Show what just arrived.
            shownFeed = feed
            pairPanelWanted = false
            updatePairPanel()
            toast("${feedName(feed)} connected (${peer.name})")
            if (feeds.size > 2) warnAboutManyCameras()
        }
        feed.remoteMeta = meta
        val ok = configureBuffer(feed.buffer, meta.bitrate, resetFormat = true)
        if (ok) feed.buffer.setFormat(format)
        messageText.visibility = View.GONE
        applyLiveUi()
        updateDelayTransform()
        maybeStartViewer()
        return if (ok) feed.buffer else null
    }

    private fun warnAboutManyCameras() {
        MaterialAlertDialogBuilder(this)
            .setTitle("More than one camera phone")
            .setMessage(
                "This is not tested. Receiving and showing several cameras may overload this phone: " +
                    "dropped frames, a stuttering picture, heat. Watch the status line for warnings."
            )
            .setPositiveButton("OK", null)
            .setOnDismissListener { hideSystemBars() }
            .show()
    }

    // ---------------------------------------------------------------- camera phone

    private val scanTick = object : Runnable {
        override fun run() {
            if (!scanning) return
            val tick = this
            val thread = scanThread
            val w = previewView.width
            val h = previewView.height
            if (thread == null || !cameraActive || w <= 0 || h <= 0 || !previewView.holder.surface.isValid) {
                ui.postDelayed(tick, 300)
                return
            }
            // A copy of the preview picture, at most 1920 pixels wide or high: the code is still
            // readable when it is small in the picture.
            val scale = min(1f, 1920f / max(w, h))
            val bw = (w * scale).toInt()
            val bh = (h * scale).toInt()
            val bmp = scanBitmap?.takeIf { it.width == bw && it.height == bh }
                ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { scanBitmap = it }
            try {
                PixelCopy.request(previewView, bmp, { result ->
                    val text = if (result == PixelCopy.SUCCESS) QrCodes.decode(bmp) else null
                    ui.post {
                        if (scanning && (text == null || !onQrText(text))) ui.postDelayed(tick, 150)
                    }
                }, Handler(thread.looper))
            } catch (e: IllegalArgumentException) {
                ui.postDelayed(tick, 300)
            }
        }
    }

    /**
     * A camera phone looks for the main phone's QR code in its picture whenever it has no main
     * phone to send to: when it was not paired yet, or the hotspot it knew is gone. That also
     * covers the time it tries to join a lost hotspot again: the main phone may be showing a new
     * code by then.
     */
    private fun updateAutoScan() {
        val want = isCameraOnly && cameraActive &&
            (pairing == null || joinState == HotspotClient.State.UNAVAILABLE || joinState == HotspotClient.State.LOST)
        if (want && !scanning) startScan() else if (!want && scanning) stopScan()
        // The camera clears messages when it starts, and the reason for scanning can change.
        val hint = scanHint()
        if (scanning && (messageText.visibility != View.VISIBLE || messageText.text.toString() != hint)) showMessage(hint)
    }

    private fun scanHint(): String =
        if (joinState == HotspotClient.State.LOST) {
            "The main phone's hotspot is gone. Joining again as soon as it is back…\n" +
                "If the main phone shows a new QR code, point this camera at it."
        } else {
            SCAN_HINT
        }

    private fun startScan() {
        if (scanning) return
        scanning = true
        showMessage(scanHint())
        if (scanThread == null) scanThread = HandlerThread("QrScan").apply { start() }
        ui.post(scanTick)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        ui.removeCallbacks(scanTick)
        messageText.visibility = View.GONE
    }

    /** Handles a scanned code. Returns false if it is not a pairing code of this app. */
    private fun onQrText(text: String): Boolean {
        val info = PairingInfo.parse(text) ?: return false
        pairing = info
        rejoinTried = false
        joinState = null
        stopScan()
        showMessage("Joining the main phone's hotspot…\nConfirm the connection if Android asks.")
        joinHotspot(info)
        return true
    }

    private fun joinHotspot(info: PairingInfo, timeoutMs: Int = 0) {
        val client = hotspotClient ?: HotspotClient(this).also { hotspotClient = it }
        try {
            client.join(info, timeoutMs) { state -> onJoinState(info, state) }
        } catch (e: Exception) {
            Log.w(TAG, "joining the hotspot failed", e)
            joinState = HotspotClient.State.UNAVAILABLE
        }
    }

    private fun onJoinState(info: PairingInfo, state: HotspotClient.State) {
        if (pairing !== info || !isCameraOnly) return
        Log.i(TAG, "hotspot ${info.ssid}: $state")
        joinState = state
        when (state) {
            HotspotClient.State.JOINED -> {
                rejoinTried = false
                messageText.visibility = View.GONE
            }

            // Android does not rejoin on its own: ask once more, for a limited time. The camera
            // phone looks for a new code meanwhile (see updateAutoScan).
            HotspotClient.State.LOST -> if (!rejoinTried) {
                rejoinTried = true
                ui.postDelayed({
                    if (pairing === info && joinState == HotspotClient.State.LOST && isCameraOnly) joinHotspot(info, REJOIN_TIMEOUT_MS)
                }, 2000)
            } else {
                joinState = HotspotClient.State.UNAVAILABLE
            }

            // The camera phone scans again (see updateAutoScan); a toast says why.
            HotspotClient.State.UNAVAILABLE -> {
                messageText.visibility = View.GONE
                Toast.makeText(this, "Could not join the main phone's hotspot. Scan its code again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Sender thread: opens the connection to the main phone, over the hotspot's network. */
    private fun connectToMain(): StreamSender.Link {
        val info = pairing ?: throw IOException("not paired yet")
        val client = hotspotClient
        val network = client?.network ?: throw IOException("the main phone's hotspot is not joined")
        var failure: IOException? = null
        for (host in (listOfNotNull(client.gateway) + info.hosts).distinct()) {
            val s = network.socketFactory.createSocket()
            try {
                s.connect(InetSocketAddress(host, info.port), 1500)
                return StreamSender.Link(s, info.token)
            } catch (e: IOException) {
                runCatching { s.close() }
                failure = e
            }
        }
        throw failure ?: IOException("the main phone's address is not known yet")
    }

    /** Sender thread, self test: opens the connection to the receiver on this phone. */
    private fun connectLocal(port: Int, token: String): StreamSender.Link {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(StreamReceiver.LOCALHOST, port), 2000)
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
        return StreamSender.Link(s, token)
    }

    /** A sender for this phone's camera video, with what the main phone needs to set the camera from there. */
    private fun newSender(cfg: CaptureConfig, meta: StreamMeta, connect: () -> StreamSender.Link): StreamSender {
        val link = StreamSender(meta, prefs.deviceId, Build.MODEL ?: "Phone", connect)
        val cameras = capabilitiesText ?: CameraRemote.describeCameras(catalog).also { capabilitiesText = it }
        link.setCapabilities(CameraRemote.capabilities(cameras, cfg, prefs.quality))
        link.onConfigRequest = { json -> ui.post { applyRemoteConfig(json) } }
        return link
    }

    /** The main phone changed this camera phone's camera settings. */
    private fun applyRemoteConfig(json: String) {
        if (isDestroyed || !(isCameraOnly || selfTest)) return
        val c = CameraRemote.parseChoice(json) ?: return
        Log.i(TAG, "camera settings from the main phone: ${c.cameraKey} ${c.width}x${c.height} ${c.fps} fps, quality ${c.quality}")
        prefs.cameraKey = c.cameraKey
        prefs.width = c.width
        prefs.height = c.height
        prefs.fps = c.fps
        prefs.quality = c.quality.coerceIn(0, 2)
        toast("Camera settings changed by the main phone")
        if (inReplay) exitReplay()
        restartPipeline()
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
            releaseEncoder()
            enc = null
        }
        if (enc == null) {
            val meta = localVideoInfo() ?: return
            val link: StreamSender?
            val sink: FrameSink
            if (isCameraOnly) {
                // The main phone keeps the video; this phone only sends it.
                ownFeed.buffer.release()
                link = newSender(cfg, meta, ::connectToMain)
                sink = link
            } else {
                if (!configureBuffer(ownFeed.buffer, cfg.targetBitrate, resetFormat = true)) return
                // Self test: the video also goes to this phone's own receiver, as from a camera phone.
                val r = if (selfTest && ensureReceiver()) receiver else null
                link = r?.let { newSender(cfg, meta) { connectLocal(it.port, it.token) } }
                sink = if (link != null) TeeSink(ownFeed.buffer, link) else ownFeed.buffer
            }
            enc = try {
                lateinit var created: VideoEncoder
                created = VideoEncoder(cfg, sink) { msg -> ui.post { onEncoderError(created, msg) } }
                created
            } catch (e: Exception) {
                link?.close()
                showMessage("The video encoder cannot record ${cfg.width}×${cfg.height} at ${cfg.fps} fps.\nPlease choose other settings.")
                return
            }
            link?.onKeyFrameRequest = enc::requestKeyFrame
            sender = link
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

    /** Memory one buffer may use: the safe maximum, shared by all cameras whose video this phone keeps. */
    private fun bufferBudget(): Long = BufferSizing.maxBytes(this) / feeds.size

    /** Sizes all buffers in use anew (another length, or another share of the memory). Discards their video. */
    private fun reconfigureBuffers() {
        if (isCameraOnly) return // keeps no video
        for (feed in feeds) {
            val bitrate = if (feed === ownFeed) config?.targetBitrate?.takeIf { encoder != null } else feed.remoteMeta?.bitrate
            if (bitrate != null) configureBuffer(feed.buffer, bitrate, resetFormat = false)
        }
    }

    /** Sizes [target] for video of [bitrate]. Discards the video it holds. */
    private fun configureBuffer(target: FrameRingBuffer, bitrate: Int, resetFormat: Boolean): Boolean {
        val seconds = activeBufferSeconds()
        // Reserve up to the safe maximum, but start with the estimated need; the buffer grows only if
        // the real bitrate is higher (unused memory is never touched, so it costs nothing).
        val estimate = (BufferSizing.bytesFor(bitrate, seconds) * estimateScale).toLong()
        var capacity = bufferBudget()
        while (true) {
            try {
                target.configure(capacity, min(estimate, capacity), (seconds * 1_000_000).toLong(), resetFormat)
                Log.i(TAG, "buffer: %.1f s, %d MB estimated, up to %d MB".format(seconds, min(estimate, capacity) shr 20, capacity shr 20))
                requestKeyFrame()
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
        releaseEncoder()
    }

    private fun releaseEncoder() {
        encoder?.release()
        encoder = null
        sender?.close()
        sender = null
    }

    /** Asks all encoders for a key frame: this phone's own and, over their connections, the camera phones'. */
    private fun requestKeyFrame() {
        receiver?.requestKeyFrame()
        encoder?.requestKeyFrame()
    }

    /**
     * The live screen shows video decoded from a buffer: a camera phone's picture, and any picture
     * with a display delay. Otherwise it shows this phone's camera preview directly.
     */
    private fun liveFromBuffer(): Boolean = !isCameraOnly && (shownFeed !== ownFeed || prefs.delaySeconds > 0f)

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
        val delayed = displayDelay() > 0f
        btnBig.text = "REPLAY"
        delayBadge.text = "⏱ DELAY ${fmtSeconds(prefs.delaySeconds)} s"
        btnFeed.visibility = if (isMain && feeds.size > 1) View.VISIBLE else View.GONE
        btnFeed.text = feedLabel(shownFeed)
        if (inReplay) return
        // A camera phone has nothing to replay or save.
        liveActions.visibility = if (isCameraOnly) View.GONE else View.VISIBLE
        updatePairPanel()
        val decoded = liveFromBuffer()
        // No picture of the camera phone before it connects (the view would show an old one).
        delayView.visibility = if (decoded && videoInfo() != null) View.VISIBLE else View.GONE
        delayBadge.visibility = if (delayed) View.VISIBLE else View.GONE
        setPreviewHidden(decoded)
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
        if (!liveFromBuffer() || inReplay || viewerRunning) return
        val surface = delaySurface ?: return
        if (shownFeed !== ownFeed) {
            if (shownFeed.remoteMeta == null) return
        } else if (encoder == null || !cameraActive) {
            return
        }
        viewer.start(shownFeed.buffer, surface, (displayDelay() * 1e9).toLong())
        viewerRunning = true
        updateDelayTransform()
    }

    private fun stopViewer() {
        if (!viewerRunning) return
        viewer.stop()
        viewerRunning = false
    }

    private fun onDelayStatus(waitSeconds: Double?) {
        if (displayDelay() <= 0f || inReplay) return
        if (waitSeconds == null) {
            if (showingDelayWait) {
                showingDelayWait = false
                messageText.visibility = View.GONE
            }
            return
        }
        showingDelayWait = true
        val delayUs = (prefs.delaySeconds * 1e6).toLong()
        val shown = shownFeed.buffer
        if (shown.limitedByMemory && shown.bufferedUs() < delayUs) {
            showMessage("The delay is too long for this phone's memory at this quality.\nReduce the delay or the quality in Settings.")
        } else {
            showMessage("Delayed view starts in %d s…".format(max(1, ceil(waitSeconds).toInt())))
        }
    }

    /** Rotates, mirrors (front camera) and fits the decoded video into the TextureView. */
    private fun updateDelayTransform() {
        val info = videoInfo() ?: return
        val vw = delayView.width.toFloat()
        val vh = delayView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val bw = info.width.toFloat()
        val bh = info.height.toFloat()
        val rot = info.rotation
        val cw = if (rot % 180 != 0) bh else bw
        val ch = if (rot % 180 != 0) bw else bh
        val s = min(vw / cw, vh / ch)
        val m = Matrix()
        m.setScale(bw / vw, bh / vh) // undo the default stretch to the view size
        m.postTranslate(-bw / 2f, -bh / 2f)
        m.postRotate(rot.toFloat())
        m.postScale(if (info.front) -s else s, s)
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
            reconfigureBuffers()
        }
        applyLiveUi()
        maybeStartViewer()
    }

    // ---------------------------------------------------------------- status

    private fun updateStatus() {
        val s = sender
        val now = SystemClock.elapsedRealtime()
        val enc = encoder
        if (now - lastFpsTime >= 1000) {
            val dt = (now - lastFpsTime).toDouble()
            val first = lastFpsTime == 0L
            // This phone's own camera and, on a camera phone, what it sends.
            val frames = enc?.framesEncoded ?: 0
            val sent = s?.bytesSent?.get() ?: 0
            if (!first) {
                measuredFps = max(0L, frames - lastFrames) * 1000.0 / dt
                linkMbit = max(0L, sent - lastLinkBytes) * 8.0 / 1000.0 / dt
                Log.d(TAG, "measured %.1f fps, buffer %.1f s, %d kB, using %d MB".format(
                    measuredFps, ownFeed.buffer.bufferedUs() / 1e6, ownFeed.buffer.bufferedBytes() / 1000, ownFeed.buffer.usableBytes shr 20) +
                    (if (s != null) ", sending %.1f Mbit/s, %d overflows".format(linkMbit, s.overflows.get()) else ""))
            }
            lastFrames = frames
            lastLinkBytes = sent
            val overflows = s?.overflows?.get() ?: 0
            if (overflows > lastOverflows) lastOverflowTime = now
            lastOverflows = overflows
            // Main phone: what arrives from each camera phone.
            for (feed in feeds) {
                val peer = feed.peer ?: continue
                val f = peer.framesReceived.get()
                val b = peer.bytesReceived.get()
                if (!first) {
                    feed.fps = max(0L, f - feed.lastFrames) * 1000.0 / dt
                    feed.mbit = max(0L, b - feed.lastBytes) * 8.0 / 1000.0 / dt
                    Log.d(TAG, "%s: %.1f fps, %.1f Mbit/s, buffer %.1f s".format(feedName(feed), feed.fps, feed.mbit, feed.buffer.bufferedUs() / 1e6) +
                        (if (peer.clockRoundTripMs >= 0) ", clocks compared over %.1f ms".format(peer.clockRoundTripMs) else ""))
                }
                feed.lastFrames = f
                feed.lastBytes = b
            }
            lastCaptured = camera.capturedFrames.get()
            lastFpsTime = now
        }

        // Written for the user: what is ready, plus warnings only when something is off.
        val parts = ArrayList<String>()
        fun addFpsWarning(measured: Double, fps: Int) {
            if (measured > 0 && measured < fps * 0.85) parts += "⚠ only %d of %d fps".format(measured.roundToInt(), fps)
        }
        fun addZoom() {
            val zoom = camera.zoomRatio
            if (abs(zoom - 1f) > 0.01f) parts += "Zoom %.1f×".format(zoom)
        }
        val own = localVideoInfo()
        statusText.text = when {
            isCameraOnly -> if (own == null || enc == null) "Camera stopped" else {
                parts += when {
                    s?.connected == true -> "Sending to the main phone ⇄ %.0f Mbit/s".format(linkMbit)
                    pairing == null || joinState == HotspotClient.State.UNAVAILABLE -> "Camera phone: looking for the main phone's QR code"
                    joinState == HotspotClient.State.LOST -> "Camera phone: hotspot lost, joining again…"
                    joinState == null -> "Camera phone: joining the hotspot…"
                    else -> "Camera phone: connecting…"
                }
                if (lastOverflowTime > 0 && now - lastOverflowTime < 5000) parts += "⚠ connection too slow"
                addFpsWarning(measuredFps, own.fps)
                addZoom()
                "● " + parts.joinToString("  ·  ")
            }

            else -> {
                // The camera on screen first, then anything wrong with the other cameras.
                val shown = shownFeed
                val peer = shown.peer
                val info = feedInfo(shown)
                if (peer == null) {
                    if (info != null && enc != null) {
                        parts += bufferStatus(shown)
                        addFpsWarning(measuredFps, info.fps)
                        addZoom()
                    } else {
                        parts += "Camera stopped"
                    }
                } else if (info != null) {
                    parts += bufferStatus(shown)
                    if (peer.connected) {
                        parts += "⇄ %.0f Mbit/s".format(shown.mbit)
                        addFpsWarning(shown.fps, info.fps)
                    }
                }
                for (feed in feeds) {
                    val p = feed.peer ?: continue
                    if (!p.connected) parts += "⚠ ${feedName(feed)} not connected"
                }
                if (own != null && enc != null && shown !== ownFeed && measuredFps > 0 && measuredFps < own.fps * 0.85) {
                    parts += "⚠ this phone only %d of %d fps".format(measuredFps.roundToInt(), own.fps)
                }
                "● " + parts.joinToString("  ·  ")
            }
        }
    }

    /** How much video of a camera is ready for a replay, and a warning if memory limits it. */
    private fun bufferStatus(feed: Feed): String {
        val b = feed.buffer
        val have = b.bufferedUs() / 1e6f
        val want = prefs.replaySeconds
        // Once the buffer was full, stay "ready" (no flipping back to "Recording…"); if it holds
        // a little less for a moment, show the real length instead.
        if (have + 0.3f >= want) feed.readyGeneration = b.generation
        val ready = when {
            feed.readyGeneration != b.generation -> "Recording… %d of %s s".format(have.toInt(), fmtSeconds(want))
            have + 0.5f >= want -> "Replay ready: last ${fmtSeconds(want)} s"
            else -> "Replay ready: last %d s".format(have.toInt())
        }
        return if (b.limitedByMemory) "$ready  ·  ⚠ memory full" else ready
    }

    // ---------------------------------------------------------------- replay / review

    /** One camera's part of an open replay. Its snapshot stays pinned in the buffer until the replay is closed. */
    private class ReplayClip(val feed: Feed, val mp4: VirtualMp4, val rotation: Int) {
        val snapshot: ReplaySnapshot get() = mp4.snapshot

        /** See [ReplaySnapshot.anchorNs]. */
        val anchorNs: Long = mp4.snapshot.anchorNs()

        /** When the last frame left its encoder, on this phone's clock. */
        val endNs: Long get() = snapshot.endPtsUs * 1000 + anchorNs
        val seconds: Double get() = (snapshot.endPtsUs - snapshot.startPtsUs) / 1e6
    }

    /** The open replay: one clip for every camera that has video, in the order of [feeds]. */
    private val replayClips = ArrayList<ReplayClip>()

    /** The clip that is playing. */
    private var replayClip: ReplayClip? = null

    /** How long before the end of the clip that reaches furthest the playing one ends, in ms. */
    private var replayEndGapMs = 0L

    /**
     * Opens the replay screen with the last replay length.
     * Without a display delay (referee use) the buffers are frozen, so the decision break is not
     * recorded. With a display delay (practice) recording continues, so the delayed picture goes on.
     * With several cameras there is a clip of each, to switch between while watching.
     */
    private fun triggerReplay() {
        if (inReplay || isCameraOnly) return
        val seconds = prefs.replaySeconds
        val review = displayDelay() > 0f
        // The replay is played straight from the buffers (no file is written), so it opens at once.
        val t0 = SystemClock.elapsedRealtime()
        val clips = feeds.mapNotNull { prepareClip(it, seconds, review) }
        // Start with the camera that was on screen, if it has video.
        val first = clips.firstOrNull { it.feed === shownFeed } ?: clips.firstOrNull()
        if (first == null) {
            toast("Nothing recorded yet")
            return
        }
        val freeze = !review
        if (freeze) feeds.forEach { it.buffer.setFrozen(true) }
        stopViewer()
        showingDelayWait = false
        Log.i(TAG, "replay %.1fs: %d frames, %d kB, prepared in %d ms".format(
            seconds, first.snapshot.frames.size, first.mp4.length / 1000, SystemClock.elapsedRealtime() - t0) +
            (if (clips.size > 1) ", ${clips.size} cameras, ends " +
                clips.joinToString { "%d ms".format((clips.maxOf { c -> c.endNs } - it.endNs) / 1_000_000) } + " before the last" else ""))
        messageText.visibility = View.GONE
        replayClips.clear()
        replayClips.addAll(clips)
        enterReplay(first, freeze)
    }

    /** The last [seconds] of a camera as a clip, or null if it has no video. */
    private fun prepareClip(feed: Feed, seconds: Float, review: Boolean): ReplayClip? {
        val info = feedInfo(feed) ?: return null
        val source = feed.buffer
        val pinned = source.snapshot((seconds * 1_000_000).toLong()) ?: return null
        if (pinned.frames.size < 2) {
            pinned.release()
            return null
        }
        var snap: ReplaySnapshot = pinned
        if (review && pinned.totalBytes <= REVIEW_COPY_LIMIT) {
            // A review keeps recording: work on a copy, so the pinned frames do not block the buffer.
            val copy = runCatching { source.copyOf(pinned) }.getOrNull()
            if (copy != null) {
                pinned.release()
                snap = copy
            }
        }
        return try {
            ReplayClip(feed, VirtualMp4(snap, info.rotation), info.rotation)
        } catch (e: Exception) {
            Log.w(TAG, "could not prepare the replay", e)
            snap.release()
            null
        }
    }

    private fun resumeRecording() {
        feeds.forEach { it.buffer.setFrozen(false) }
        requestKeyFrame()
    }

    private fun enterReplay(first: ReplayClip, froze: Boolean) {
        inReplay = true
        replayFrozeBuffer = froze
        playerView.visibility = View.VISIBLE
        delayView.visibility = View.GONE
        delayBadge.visibility = View.GONE
        pairPanel.visibility = View.GONE
        setPreviewHidden(true)
        liveActions.visibility = View.GONE
        topBar.visibility = View.GONE
        setControlsHidden(false)
        maybeShowControlsHint()
        updateSpeedButton()
        showReplayClip(first, 0, play = true)
        updatePlayButton()
    }

    /** Plays one camera's clip of the open replay, from [startMs]. */
    private fun showReplayClip(clip: ReplayClip, startMs: Long, play: Boolean) {
        replayClip = clip
        replayMp4 = clip.mp4
        replayIndex = clip.mp4.index
        replayRotation = clip.rotation
        // The time display counts towards the end of the clip that reaches furthest: a camera
        // phone's clip ends a little earlier, by the time its video takes to arrive.
        replayEndGapMs = ((replayClips.maxOf { it.endNs } - clip.endNs) / 1_000_000).coerceAtLeast(0)
        replayBadge.text = "REPLAY · last %.1f s".format(clip.seconds)
        btnReplayFeed.text = feedLabel(clip.feed)
        replay.load(clip.mp4, prefs.playbackSpeed, startMs, play)
        seekBar.max = replay.durationMs.toInt()
        updateReplayProgress()
    }

    /** Several cameras: shows the next one's clip at the same moment. */
    private fun switchReplayFeed() {
        val from = replayClip ?: return
        if (!inReplay || replayClips.size < 2) return
        val to = replayClips[(replayClips.indexOf(from) + 1) % replayClips.size]
        if (trimming) closeTrim()
        // The moment on screen, on this phone's clock, and where the other clip has it.
        val momentNs = (from.snapshot.startPtsUs + replay.positionMs() * 1000) * 1000 + from.anchorNs
        val startMs = ((momentNs - to.anchorNs) / 1000 - to.snapshot.startPtsUs) / 1000
        showReplayClip(to, startMs, replay.isPlaying)
    }

    private fun exitReplay() {
        if (!inReplay) return
        if (trimming) closeTrim()
        inReplay = false
        replay.stop()
        replayClips.forEach { it.snapshot.release() }
        replayClips.clear()
        replayClip = null
        replayMp4 = null
        replayIndex = null
        if (replayFrozeBuffer) resumeRecording()
        replayFrozeBuffer = false
        playerView.visibility = View.GONE
        replayControls.visibility = View.GONE
        replayBadge.visibility = View.GONE
        btnReplayFeed.visibility = View.GONE
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
        val t = ((pos - replay.durationMs - replayEndGapMs) / 1000.0).let { if (it > -0.005) 0.0 else it }
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
        // Switching cameras is offered when there are two clips, and not while cutting one of them.
        btnReplayFeed.visibility =
            if (!hidden && inReplay && !trimming && replayClips.size > 1) View.VISIBLE else View.GONE
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
        btnReplayFeed.visibility = View.GONE
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
        replay.setSpeed(1f) // the preview always plays at normal speed
        replay.playFrom((v[0] * 1000).toLong())
        btnTrimPlay.text = "❚❚ Stop"
    }

    private fun stopTrimPlayback() {
        replay.pause()
        if (trimPlayEndMs < 0) return
        trimPlayEndMs = -1
        replay.setSpeed(prefs.playbackSpeed) // back to the speed chosen on the replay screen
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
        // With two cameras: the one on screen.
        val info = videoInfo() ?: return
        val shown = shownFeed.buffer
        val snap = shown.snapshot(shown.durationUs)
        if (snap == null || snap.frames.size < 2) {
            toast("Nothing recorded yet")
            return
        }
        quickSaving = true
        saveSnapshotToGallery(snap, info.rotation) { quickSaving = false }
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
        /** Prefs.MODE_… */
        val mode: Int,
    )

    private fun spinnerAdapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    /**
     * Shows the settings. Changes are applied when the dialog is closed; settings that can't
     * work (e.g. not enough memory) are rejected with a short explanation.
     *
     * A main phone sets everything here, also for its camera phones (under "More cameras"). A
     * camera phone only has the choice to become a main phone again.
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
        val spMode = view.findViewById<Spinner>(R.id.spMode)
        val modeHint = view.findViewById<TextView>(R.id.modeHint)
        val groupCamera = view.findViewById<View>(R.id.groupCamera)
        val groupDurations = view.findViewById<View>(R.id.groupDurations)
        val cameraPhones = view.findViewById<LinearLayout>(R.id.cameraPhones)
        val btnAddCamera = view.findViewById<View>(R.id.btnAddCamera)

        val cur = config
        var wantW = initial?.width ?: cur?.width ?: prefs.width
        var wantH = initial?.height ?: cur?.height ?: prefs.height
        var wantFps = initial?.fps ?: cur?.fps ?: prefs.fps
        var sizes: List<Size> = emptyList()
        var fpsList: List<FpsOption> = emptyList()

        /** What to do after the dialog was closed and its settings were applied. */
        var afterwards: (() -> Unit)? = null

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
                mode = spMode.selectedItemPosition,
            )
        }

        fun updateInfo() {
            if (spMode.selectedItemPosition == Prefs.MODE_CAMERA) {
                infoText.text = "Camera, picture size, frame rate and replay length of a camera phone are set on its main phone."
                return
            }
            val f = fpsList.getOrNull(spFps.selectedItemPosition)
            if (sizes.getOrNull(spResolution.selectedItemPosition) == null || f == null) {
                infoText.text = "No supported frame rate for this resolution."
                return
            }
            val d = draft()
            val bitrates = keptBitrates(d)
            val budget = budgetFor(d)
            val sb = StringBuilder()
            val replay = parseSeconds(d.replayText)
            val delay = parseSeconds(d.delayText)
            if (replay != null && delay != null) {
                val needs = bitrates.map { BufferSizing.bytesFor(it, BufferSizing.bufferSeconds(replay, delay)) }
                sb.append("Memory ≈ %s of %s".format(fmtBytes(needs.sum()), fmtBytes(BufferSizing.maxBytes(this))))
                if (needs.any { it > budget }) sb.append("  ⚠ too much")
                sb.append("  ·  ")
            }
            sb.append("max ≈ %.0f s".format(min(bitrates.minOf { secondsFitting(budget, it) }, BufferSizing.MAX_SECONDS)))
            if (feeds.size > 1 && isMain) sb.append("  ·  ${feeds.size} cameras share the memory")
            infoText.text = sb
        }

        fun fillFps() {
            val size = sizes.getOrNull(spResolution.selectedItemPosition)
            val cam = cams[spCamera.selectedItemPosition]
            fpsList = if (size == null) emptyList() else catalog.fpsOptions(cam, size)
            spFps.adapter = spinnerAdapter(fpsList.map { it.label })
            val idx = fpsList.indexOfFirst { it.fps == wantFps }
            if (idx >= 0) spFps.setSelection(idx)
            updateInfo()
        }

        fun fillSizes() {
            val cam = cams[spCamera.selectedItemPosition]
            sizes = catalog.resolutions(cam)
            spResolution.adapter = spinnerAdapter(sizes.map { sizeLabel(it) })
            var idx = sizes.indexOfFirst { it.width == wantW && it.height == wantH }
            if (idx < 0) idx = sizes.indexOfFirst { it.width == 1920 && it.height == 1080 }
            if (idx >= 0) spResolution.setSelection(idx)
            fillFps()
        }

        etReplay.setText(initial?.replayText ?: fmtSeconds(prefs.replaySeconds))
        etDelay.setText(initial?.delayText ?: fmtSeconds(prefs.delaySeconds))
        spMode.adapter = spinnerAdapter(listOf("Main phone", "Camera phone"))
        spMode.setSelection(initial?.mode ?: prefs.mode)
        fun showGroups() {
            // What the dialog shows follows the choice; the rows of connected camera phones only
            // exist while this phone really is a main phone.
            val main = spMode.selectedItemPosition == Prefs.MODE_MAIN
            groupCamera.visibility = if (main) View.VISIBLE else View.GONE
            groupDurations.visibility = if (main) View.VISIBLE else View.GONE
            cameraPhones.visibility = if (main && isMain) View.VISIBLE else View.GONE
            btnAddCamera.visibility = if (main && isMain) View.VISIBLE else View.GONE
            modeHint.text = if (main) {
                "Records with its own camera and shows the replays. Other phones can be added as more cameras."
            } else {
                "Only sends its picture to a main phone. It looks for the main phone's QR code with its camera " +
                    "(main phone: ⚙ → Add a camera phone)."
            }
        }
        showGroups()
        spMode.onItemSelectedListener = SimpleItemSelected {
            showGroups()
            updateInfo()
        }
        spCamera.adapter = spinnerAdapter(cams.map { it.label })
        val camKey = initial?.cameraKey ?: cur?.camera?.key ?: prefs.cameraKey
        spCamera.setSelection(cams.indexOfFirst { it.key == camKey }.coerceAtLeast(0))
        spQuality.adapter = spinnerAdapter(listOf("Standard", "High", "Maximum"))
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

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Settings")
            .setView(view)
            .setOnDismissListener {
                hideSystemBars()
                if (!isFinishing && !isDestroyed) {
                    applySettings(draft())
                    afterwards?.invoke()
                }
            }
            .create()

        // One row per camera phone: its name and state, and a button for its camera settings.
        for (feed in feeds) {
            val peer = feed.peer ?: continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = "${feedName(feed)} · ${peer.name}" + if (peer.connected) "" else " · not connected"
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Camera settings"
                setOnClickListener {
                    afterwards = { showCameraPhoneSettings(feed) }
                    dialog.dismiss()
                }
            })
            cameraPhones.addView(row)
        }
        btnAddCamera.setOnClickListener {
            afterwards = { addCameraPhone() }
            dialog.dismiss()
        }
        dialog.show()
    }

    /** Main phone: the camera settings of one camera phone. They are sent to it when the dialog is closed. */
    private fun showCameraPhoneSettings(feed: Feed) {
        val peer = feed.peer ?: return
        val caps = peerCapabilities[peer.id]
        if (caps == null || caps.cameras.isEmpty()) {
            toast("${feedName(feed)} has not told what its cameras can do yet")
            return
        }
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        fun row(label: String): Spinner {
            box.addView(TextView(this).apply {
                text = label
                setPadding(0, (8 * d).toInt(), 0, 0)
            })
            return Spinner(this).also { box.addView(it, LinearLayout.LayoutParams.MATCH_PARENT, (48 * d).toInt()) }
        }
        val spCamera = row("Camera")
        val spResolution = row("Resolution")
        val spFps = row("Frame rate")
        val spQuality = row("Quality (bitrate)")
        val info = TextView(this).apply { setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt()) }
        box.addView(info)

        val current = caps.current
        var wantW = current.width
        var wantH = current.height
        var wantFps = current.fps
        fun camera() = caps.cameras[spCamera.selectedItemPosition.coerceIn(0, caps.cameras.size - 1)]
        fun mode() = camera().modes.getOrNull(spResolution.selectedItemPosition)
        fun choice(): CameraRemote.Choice? {
            val m = mode() ?: return null
            val f = m.fps.getOrNull(spFps.selectedItemPosition) ?: return null
            return CameraRemote.Choice(camera().key, m.width, m.height, f.fps, spQuality.selectedItemPosition)
        }

        /** Why the choice can't be used; null if it is fine. */
        fun problem(c: CameraRemote.Choice): String? {
            val seconds = activeBufferSeconds()
            val need = BufferSizing.bytesFor(estimateBitrate(c.width, c.height, c.fps, c.quality), seconds)
            val budget = bufferBudget()
            return if (need <= budget) null else
                "Not enough memory: ${fmtSeconds(seconds)} s at these settings need ≈ ${fmtBytes(need)}, but this phone " +
                    "can safely use only ≈ ${fmtBytes(budget)} for each camera. Lower resolution, frame rate or quality, or shorten the replay length."
        }
        fun updateInfo() {
            val c = choice()
            info.text = when {
                c == null -> "No supported frame rate for this resolution."
                problem(c) != null -> "⚠ needs too much memory on this phone"
                else -> ""
            }
        }
        fun fillFps() {
            val rates = mode()?.fps ?: emptyList()
            spFps.adapter = spinnerAdapter(rates.map { it.label })
            val idx = rates.indexOfFirst { it.fps == wantFps }
            if (idx >= 0) spFps.setSelection(idx)
            updateInfo()
        }
        fun fillSizes() {
            val modes = camera().modes
            spResolution.adapter = spinnerAdapter(modes.map { sizeLabel(Size(it.width, it.height)) })
            var idx = modes.indexOfFirst { it.width == wantW && it.height == wantH }
            if (idx < 0) idx = modes.indexOfFirst { it.width == 1920 && it.height == 1080 }
            if (idx >= 0) spResolution.setSelection(idx)
            fillFps()
        }
        spCamera.adapter = spinnerAdapter(caps.cameras.map { it.label })
        spCamera.setSelection(caps.cameras.indexOfFirst { it.key == current.cameraKey }.coerceAtLeast(0))
        spQuality.adapter = spinnerAdapter(listOf("Standard", "High", "Maximum"))
        spQuality.setSelection(current.quality.coerceIn(0, 2))
        fillSizes()
        spCamera.onItemSelectedListener = SimpleItemSelected { fillSizes() }
        spResolution.onItemSelectedListener = SimpleItemSelected {
            mode()?.let {
                wantW = it.width
                wantH = it.height
            }
            fillFps()
        }
        spFps.onItemSelectedListener = SimpleItemSelected {
            mode()?.fps?.getOrNull(it)?.let { f -> wantFps = f.fps }
            updateInfo()
        }
        spQuality.onItemSelectedListener = SimpleItemSelected { updateInfo() }

        MaterialAlertDialogBuilder(this)
            .setTitle("${feedName(feed)} · ${peer.name}")
            .setView(ScrollView(this).apply { addView(box) })
            .setOnDismissListener {
                hideSystemBars()
                val c = choice()
                if (isFinishing || isDestroyed || c == null || c == current) return@setOnDismissListener
                val why = problem(c)
                when {
                    why != null -> MaterialAlertDialogBuilder(this)
                        .setTitle("Settings not applied")
                        .setMessage(why)
                        .setPositiveButton("OK", null)
                        .setOnDismissListener { hideSystemBars() }
                        .show()

                    receiver?.sendConfig(peer, CameraRemote.choiceText(c)) == true -> toast("Sent to ${feedName(feed)}")
                    else -> toast("${feedName(feed)} is not connected")
                }
            }
            .show()
    }

    /** The bitrates of all videos this phone would keep with the settings [d]: its own camera's and the camera phones'. */
    private fun keptBitrates(d: SettingsDraft): List<Int> =
        listOf(estimateBitrate(d.width, d.height, d.fps, d.quality)) +
            (if (isMain) feeds.mapNotNull { it.remoteMeta?.bitrate.takeIf { _ -> it.peer != null } } else emptyList())

    /** Memory each kept video may use with the settings [d]: all cameras share the safe maximum. */
    private fun budgetFor(d: SettingsDraft): Long =
        BufferSizing.maxBytes(this) / (if (isMain && d.mode == Prefs.MODE_MAIN) feeds.size else 1)

    /** Longest buffer (seconds) that fits into [budget] bytes at [bitrate], see [BufferSizing.bytesFor]. */
    private fun secondsFitting(budget: Long, bitrate: Int): Float =
        ((budget / (bitrate / 8.0) - 3) / 1.2).toFloat().coerceAtLeast(0f)

    /** Why [d] can't be used, in one or two sentences; null if it is fine. */
    private fun settingsProblem(d: SettingsDraft): String? {
        if (d.mode == Prefs.MODE_CAMERA) return null // a camera phone keeps no video
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
        val seconds = BufferSizing.bufferSeconds(replay, delay)
        val budget = budgetFor(d)
        val several = budget < BufferSizing.maxBytes(this)
        for (br in keptBitrates(d)) {
            val need = BufferSizing.bytesFor(br, seconds)
            if (need > budget) {
                return "Not enough memory: ${fmtSeconds(seconds)} s at these settings need ≈ ${fmtBytes(need)}, " +
                    "but this phone can safely use only ≈ ${fmtBytes(budget)}" + (if (several) " for each camera" else "") +
                    " (≈ ${fmtSeconds(secondsFitting(budget, br).toInt().toFloat())} s). " +
                    "Shorten the replay length or lower resolution, frame rate or quality."
            }
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
            mode = prefs.mode,
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
        val modeChanged = d.mode != prefs.mode
        if (d.mode == Prefs.MODE_CAMERA) {
            // A camera phone has no settings of its own: it only changes its role.
            if (modeChanged) {
                if (inReplay) exitReplay()
                messageText.visibility = View.GONE
                prefs.mode = d.mode
                startForMode()
            }
            return
        }
        val captureChanged = d.cameraKey != (config?.camera?.key ?: prefs.cameraKey) ||
            d.width != prefs.width || d.height != prefs.height || d.fps != prefs.fps || d.quality != prefs.quality
        val durationsChanged =
            parseSeconds(d.replayText) != prefs.replaySeconds || parseSeconds(d.delayText) != prefs.delaySeconds
        if (!modeChanged && !captureChanged && !durationsChanged) return // nothing changed
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
        if (!modeChanged && !captureChanged) {
            applyDurations(replay, delay)
            return
        }
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
        if (modeChanged) {
            prefs.mode = d.mode
            startForMode()
        } else {
            applyLiveUi()
            restartPipeline()
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
