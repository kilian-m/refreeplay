package io.github.kilianm.refreeplay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Everything the camera phone needs to reach the main phone: the main phone's hotspot and where
 * its [StreamReceiver] listens. The main phone shows it as a QR code; the camera phone scans it.
 */
data class PairingInfo(
    val ssid: String,
    val passphrase: String,
    /** [WPA2], [WPA3] or [OPEN]. */
    val security: String,
    /** Addresses of the main phone to try, after the hotspot's own gateway address. */
    val hosts: List<String>,
    val port: Int,
    val token: String,
) {
    fun toText(): String = Uri.Builder().scheme(SCHEME).authority(AUTHORITY)
        .appendQueryParameter("v", "1")
        .appendQueryParameter("s", ssid)
        .appendQueryParameter("p", passphrase)
        .appendQueryParameter("t", security)
        .appendQueryParameter("h", hosts.joinToString(","))
        .appendQueryParameter("port", port.toString())
        .appendQueryParameter("k", token)
        .build().toString()

    companion object {
        const val WPA2 = "WPA2"
        const val WPA3 = "WPA3"
        const val OPEN = "OPEN"
        private const val SCHEME = "refreeplay"
        private const val AUTHORITY = "pair"
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /** The pairing described by a scanned QR code, or null if it is not one of ours. */
        fun parse(text: String): PairingInfo? = runCatching {
            val u = Uri.parse(text)
            if (u.scheme != SCHEME || u.authority != AUTHORITY) return null
            val port = u.getQueryParameter("port")!!.toInt()
            if (port !in 1..65535) return null
            PairingInfo(
                ssid = u.getQueryParameter("s")!!,
                passphrase = u.getQueryParameter("p") ?: "",
                security = u.getQueryParameter("t") ?: WPA2,
                // Only plain addresses: a host name would be looked up, possibly somewhere else.
                hosts = (u.getQueryParameter("h") ?: "").split(',').filter { IPV4.matches(it) },
                port = port,
                token = u.getQueryParameter("k")!!,
            )
        }.getOrNull()
    }
}

/**
 * Main phone: opens a Wi-Fi hotspot that only serves this app (no internet is shared), for the
 * camera phone to join. Android chooses its name and password; they are different every time.
 */
class HotspotHost(context: Context) {
    class Credentials(val ssid: String, val passphrase: String, val security: String)

    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var generation = 0

    /** Starts the hotspot. Exactly one of the callbacks is called, on the main thread. */
    @SuppressLint("MissingPermission")
    fun start(started: (Credentials) -> Unit, failed: (String) -> Unit) {
        val gen = ++generation
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    if (gen != generation) {
                        r.close()
                        return
                    }
                    reservation = r
                    val c = credentials(r)
                    if (c == null) failed("Android did not report the hotspot's name and password.") else started(c)
                }

                override fun onStopped() {
                    if (gen == generation) {
                        reservation = null
                        failed("The hotspot was switched off.")
                    }
                }

                override fun onFailed(reason: Int) {
                    if (gen != generation) return
                    failed(
                        when (reason) {
                            ERROR_NO_CHANNEL -> "No free Wi-Fi channel for the hotspot."
                            ERROR_INCOMPATIBLE_MODE -> "The hotspot can't start while the phone's own hotspot or tethering is on."
                            ERROR_TETHERING_DISALLOWED -> "Hotspots are not allowed on this phone."
                            else -> "The hotspot could not be started (is Wi-Fi allowed and location switched on?)."
                        }
                    )
                }
            }, main)
        } catch (e: Exception) {
            // Missing permission, location switched off, or a request that is still open.
            failed("The hotspot could not be started: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun credentials(r: WifiManager.LocalOnlyHotspotReservation): Credentials? {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            val ssid = c.ssid ?: return null
            val security = when (c.securityType) {
                SoftApConfiguration.SECURITY_TYPE_OPEN -> PairingInfo.OPEN
                SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> PairingInfo.WPA3
                else -> PairingInfo.WPA2 // also the WPA2/WPA3 mixed mode
            }
            return Credentials(ssid, c.passphrase ?: "", security)
        }
        val c = r.wifiConfiguration ?: return null
        val ssid = c.SSID ?: return null
        val key = c.preSharedKey
        return Credentials(ssid, key ?: "", if (key.isNullOrEmpty()) PairingInfo.OPEN else PairingInfo.WPA2)
    }

    fun stop() {
        generation++
        runCatching { reservation?.close() }
        reservation = null
    }

    companion object {
        /** This phone's private IPv4 addresses, Wi-Fi interfaces first. */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.contains("wlan") || it.name.contains("ap")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is Inet4Address && it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
                .take(4)
        }.getOrDefault(emptyList())
    }
}

/** Camera phone: joins the main phone's hotspot. Android asks the user to confirm the connection. */
class HotspotClient(context: Context) {
    enum class State { JOINED, LOST, UNAVAILABLE }

    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** The hotspot's network while joined. Connections to the main phone must be opened on it. */
    @Volatile
    var network: Network? = null
        private set

    /** The address of the phone that runs the hotspot, once known. */
    @Volatile
    var gateway: String? = null
        private set

    /**
     * Asks Android to join the hotspot. [onState] is called on the main thread. With [timeoutMs],
     * the attempt ends as [State.UNAVAILABLE] after that time; without, it waits (the first time
     * Android asks the user, who may take a while).
     */
    fun join(info: PairingInfo, timeoutMs: Int = 0, onState: (State) -> Unit) {
        leave()
        val specifier = WifiNetworkSpecifier.Builder().setSsid(info.ssid).apply {
            when (info.security) {
                PairingInfo.OPEN -> Unit
                PairingInfo.WPA3 -> setWpa3Passphrase(info.passphrase)
                else -> setWpa2Passphrase(info.passphrase)
            }
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                if (callback !== this) return
                network = n
                holdWifiLock()
                onState(State.JOINED)
            }

            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                if (callback !== this) return
                gateway = lp.routes.firstNotNullOfOrNull { r -> (r.gateway as? Inet4Address)?.takeIf { !it.isAnyLocalAddress } }?.hostAddress
                    ?: if (Build.VERSION.SDK_INT >= 30) lp.dhcpServerAddress?.hostAddress else null
            }

            override fun onLost(n: Network) {
                if (callback !== this) return
                network = null
                gateway = null
                onState(State.LOST)
            }

            override fun onUnavailable() {
                if (callback !== this) return
                network = null
                onState(State.UNAVAILABLE)
            }
        }
        callback = cb
        if (timeoutMs > 0) cm.requestNetwork(request, cb, main, timeoutMs) else cm.requestNetwork(request, cb, main)
    }

    /** Keeps Wi-Fi out of its power-saving mode, which delays packets. */
    private fun holdWifiLock() {
        if (wifiLock != null) return
        wifiLock = runCatching {
            wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "refreeplay:stream").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    fun leave() {
        callback?.let { cb -> runCatching { cm.unregisterNetworkCallback(cb) } }
        callback = null
        network = null
        gateway = null
        runCatching { wifiLock?.release() }
        wifiLock = null
    }
}

object QrCodes {
    /** A black-on-white QR code of [sizePx] × [sizePx] pixels for [text]. */
    fun encode(text: String, sizePx: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 2)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** The text of a QR code visible in [bitmap], or null if there is none. */
    fun decode(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels)))
        return try {
            QRCodeReader().decode(image, mapOf(DecodeHintType.TRY_HARDER to true)).text
        } catch (_: ReaderException) {
            null
        }
    }
}
