package io.github.kilianm.refreeplay

import android.media.MediaFormat
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Where the encoder's output goes: the buffer itself, or a connection to the buffer on the other end. */
interface FrameSink {
    fun setFormat(f: MediaFormat)

    /** Takes one encoded frame; [src] is positioned at the frame data. */
    fun add(src: ByteBuffer, size: Int, rawPtsUs: Long, isKey: Boolean)
}

/** What the referee side must know about the video, besides the codec format. */
data class StreamMeta(
    val width: Int,
    val height: Int,
    val fps: Int,
    /** Target bitrate, for sizing the buffer. */
    val bitrate: Int,
    /** Clockwise rotation that makes the picture upright, as the camera is held now. */
    val rotation: Int,
    /** Front camera: the live picture is shown mirrored. */
    val front: Boolean,
) {
    /** True if [o] describes the same video; only how the camera is held may differ. */
    fun sameVideo(o: StreamMeta): Boolean =
        width == o.width && height == o.height && fps == o.fps && bitrate == o.bitrate && front == o.front
}

/**
 * The video stream between the recording side ([StreamSender]) and the referee side
 * ([StreamReceiver]), over one TCP connection that the sender opens. All numbers are big-endian.
 *
 * Sender to receiver: [MAGIC], the receiver's token (UTF), then messages:
 * - [MSG_META], int length, a [StreamMeta] (first message, and again when the camera is turned)
 * - [MSG_FORMAT], the encoder's output format (see [writeFormat])
 * - [MSG_FRAME], long ptsUs, long sentNs, byte flags ([FLAG_KEY]), int size, the frame data
 * - [MSG_PING] while there is nothing else to send, so the receiver can tell a quiet connection
 *   from a dead one
 *
 * Receiver to sender, one byte each: [CTRL_KEY_FRAME] asks for a key frame as soon as possible.
 */
object StreamProtocol {
    const val MAGIC = 0x52465032 // "RFP2"
    const val MSG_FORMAT = 1
    const val MSG_FRAME = 2
    const val MSG_META = 3
    const val MSG_PING = 4
    const val FLAG_KEY = 1
    const val CTRL_KEY_FRAME = 1
    const val FRAME_HEADER = 1 + 8 + 8 + 1 + 4
    const val MAX_FRAME = 32 shl 20

    private val TYPES = setOf(
        MediaFormat.TYPE_INTEGER, MediaFormat.TYPE_LONG, MediaFormat.TYPE_FLOAT,
        MediaFormat.TYPE_STRING, MediaFormat.TYPE_BYTE_BUFFER,
    )

    /** Writes all entries of [f], so the other end gets the same format the encoder reported. */
    fun writeFormat(out: DataOutputStream, f: MediaFormat) {
        val keys = f.keys.filter { f.getValueTypeForKey(it) in TYPES }
        out.writeInt(keys.size)
        for (k in keys) {
            val type = f.getValueTypeForKey(k)
            out.writeUTF(k)
            out.writeByte(type)
            when (type) {
                MediaFormat.TYPE_INTEGER -> out.writeInt(f.getInteger(k))
                MediaFormat.TYPE_LONG -> out.writeLong(f.getLong(k))
                MediaFormat.TYPE_FLOAT -> out.writeFloat(f.getFloat(k))
                MediaFormat.TYPE_STRING -> out.writeUTF(f.getString(k)!!)
                MediaFormat.TYPE_BYTE_BUFFER -> {
                    val bytes = bytesOf(f.getByteBuffer(k)!!)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
            }
        }
    }

    fun readFormat(inp: DataInputStream): MediaFormat {
        val f = MediaFormat()
        val count = inp.readInt()
        if (count < 0 || count > 1000) throw IOException("bad format message")
        repeat(count) {
            val k = inp.readUTF()
            when (val type = inp.readUnsignedByte()) {
                MediaFormat.TYPE_INTEGER -> f.setInteger(k, inp.readInt())
                MediaFormat.TYPE_LONG -> f.setLong(k, inp.readLong())
                MediaFormat.TYPE_FLOAT -> f.setFloat(k, inp.readFloat())
                MediaFormat.TYPE_STRING -> f.setString(k, inp.readUTF())
                MediaFormat.TYPE_BYTE_BUFFER -> {
                    val n = inp.readInt()
                    if (n < 0 || n > (1 shl 20)) throw IOException("bad format message")
                    val bytes = ByteArray(n)
                    inp.readFully(bytes)
                    f.setByteBuffer(k, ByteBuffer.wrap(bytes))
                }

                else -> throw IOException("unknown format value type $type")
            }
        }
        return f
    }

    /** The complete [MSG_META] message for [m]. */
    fun metaPacket(m: StreamMeta): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use {
            it.writeInt(m.width)
            it.writeInt(m.height)
            it.writeInt(m.fps)
            it.writeInt(m.bitrate)
            it.writeInt(m.rotation)
            it.writeBoolean(m.front)
        }
        val packet = ByteArrayOutputStream()
        DataOutputStream(packet).use {
            it.writeByte(MSG_META)
            it.writeInt(body.size())
            body.writeTo(it)
        }
        return packet.toByteArray()
    }

    /** Reads what follows the [MSG_META] byte. Fields added by a newer version are skipped. */
    fun readMeta(inp: DataInputStream): StreamMeta {
        val n = inp.readInt()
        if (n < 21 || n > 4096) throw IOException("bad stream info")
        val bytes = ByteArray(n)
        inp.readFully(bytes)
        val d = DataInputStream(ByteArrayInputStream(bytes))
        return StreamMeta(d.readInt(), d.readInt(), d.readInt(), d.readInt(), d.readInt(), d.readBoolean())
    }

    private fun bytesOf(b: ByteBuffer): ByteArray {
        val d = b.duplicate()
        d.rewind()
        return ByteArray(d.remaining()).also { d.get(it) }
    }

    /** True if both formats decode the same way (same picture size and codec configuration). */
    fun sameCodecConfig(a: MediaFormat?, b: MediaFormat?): Boolean {
        if (a == null || b == null) return false
        for (k in listOf(MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT)) {
            if (!a.containsKey(k) || !b.containsKey(k) || a.getInteger(k) != b.getInteger(k)) return false
        }
        for (k in listOf("csd-0", "csd-1")) {
            val x = a.getByteBuffer(k) ?: return false
            val y = b.getByteBuffer(k) ?: return false
            if (!bytesOf(x).contentEquals(bytesOf(y))) return false
        }
        return true
    }
}

/**
 * Recording side: sends everything the encoder produces to a [StreamReceiver].
 *
 * [connect] opens the connection (it is called again after every failure, so it may fail while
 * the other end is not known or not reachable yet). Frames wait in a queue for a writer thread,
 * so a slow connection never blocks the encoder. If more than [MAX_LAG_US] of video is waiting,
 * the connection is too slow: the waiting frames are dropped and the stream continues at the next
 * key frame. If nothing at all gets through for [STALL_NS], the connection is taken for dead and
 * opened again. Every (re)connection starts with the stream info, the format and a key frame.
 */
class StreamSender(
    meta: StreamMeta,
    private val connect: () -> Link,
) : FrameSink {
    /** An open connection and the token its receiver expects. */
    class Link(val socket: Socket, val token: String)

    private class Packet(val bytes: ByteArray, val ptsUs: Long, val isFrame: Boolean)

    /** Called (on any thread) when the stream needs a key frame. */
    @Volatile
    var onKeyFrameRequest: (() -> Unit)? = null

    /** How often waiting frames had to be dropped because the connection was too slow. */
    val overflows = AtomicLong()
    val bytesSent = AtomicLong()

    @Volatile
    var connected = false
        private set

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queue = ArrayDeque<Packet>()
    private var metaPacket = StreamProtocol.metaPacket(meta)
    private var formatPacket: ByteArray? = null
    private var awaitKey = true
    private var keyRequested = false

    @Volatile
    private var closed = false

    @Volatile
    private var socket: Socket? = null

    /** When the writer last got something out. */
    @Volatile
    private var lastProgressNs = 0L

    private val thread = Thread(::run, "StreamSender").apply { start() }

    /** Tells the receiver that the camera is held differently now. */
    fun setMeta(meta: StreamMeta) {
        val packet = StreamProtocol.metaPacket(meta)
        lock.withLock {
            if (packet.contentEquals(metaPacket)) return
            metaPacket = packet
            if (connected) {
                queue.addLast(Packet(packet, 0, false))
                changed.signalAll()
            }
        }
    }

    override fun setFormat(f: MediaFormat) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeByte(StreamProtocol.MSG_FORMAT)
            StreamProtocol.writeFormat(it, f)
        }
        val packet = bytes.toByteArray()
        lock.withLock {
            formatPacket = packet
            if (connected) {
                queue.addLast(Packet(packet, 0, false))
                changed.signalAll()
            }
        }
    }

    override fun add(src: ByteBuffer, size: Int, rawPtsUs: Long, isKey: Boolean) {
        if (size <= 0 || size > StreamProtocol.MAX_FRAME) return
        var needKey = false
        var stalled = false
        lock.withLock {
            if (!connected) return
            val oldest = queue.firstOrNull { it.isFrame }
            if (oldest != null && rawPtsUs - oldest.ptsUs > MAX_LAG_US) {
                queue.removeAll { it.isFrame }
                awaitKey = true
                keyRequested = false
                overflows.incrementAndGet()
                stalled = System.nanoTime() - lastProgressNs > STALL_NS
            }
            if (awaitKey && !isKey) {
                // Ask once; the encoder needs a few frames to answer.
                needKey = !keyRequested
                keyRequested = true
            } else {
                awaitKey = false
                val bytes = ByteArray(StreamProtocol.FRAME_HEADER + size)
                ByteBuffer.wrap(bytes)
                    .put(StreamProtocol.MSG_FRAME.toByte())
                    .putLong(rawPtsUs)
                    .putLong(System.nanoTime())
                    .put((if (isKey) StreamProtocol.FLAG_KEY else 0).toByte())
                    .putInt(size)
                src.get(bytes, StreamProtocol.FRAME_HEADER, size)
                queue.addLast(Packet(bytes, rawPtsUs, true))
                changed.signalAll()
            }
        }
        // Closing the socket ends the writer's blocked write; it then connects again.
        if (stalled) runCatching { socket?.close() }
        if (needKey) onKeyFrameRequest?.invoke()
    }

    private fun run() {
        var failing = false
        while (!closed) {
            var opened: Socket? = null
            try {
                val link = connect()
                val s = link.socket
                opened = s
                socket = s
                if (closed) break
                s.tcpNoDelay = true
                val out = s.getOutputStream()
                val hello = ByteArrayOutputStream()
                DataOutputStream(hello).use {
                    it.writeInt(StreamProtocol.MAGIC)
                    it.writeUTF(link.token)
                }
                val first = lock.withLock {
                    queue.clear()
                    awaitKey = true
                    keyRequested = true // requested below
                    connected = true
                    listOfNotNull(metaPacket, formatPacket)
                }
                failing = false
                lastProgressNs = System.nanoTime()
                out.write(hello.toByteArray())
                first.forEach { out.write(it) }
                onKeyFrameRequest?.invoke()
                Thread({ readControl(s) }, "StreamSenderControl").start()
                while (true) {
                    val bytes = lock.withLock {
                        if (queue.isEmpty() && !closed && !s.isClosed) changed.await(PING_MS, TimeUnit.MILLISECONDS)
                        if (closed || s.isClosed) null else queue.removeFirstOrNull()?.bytes ?: PING
                    } ?: break
                    out.write(bytes)
                    lastProgressNs = System.nanoTime()
                    bytesSent.addAndGet(bytes.size.toLong())
                }
            } catch (e: IOException) {
                // Once per outage, not for every failed attempt to reconnect.
                if (!closed && !failing) Log.w(TAG, "stream sender: ${e.message}")
                failing = true
            } catch (_: InterruptedException) {
            } finally {
                lock.withLock {
                    connected = false
                    queue.clear()
                }
                runCatching { opened?.close() }
            }
            if (!closed) runCatching { Thread.sleep(RECONNECT_MS) }
        }
    }

    /** Reads the receiver's requests until the connection ends. */
    private fun readControl(s: Socket) {
        try {
            val inp = s.getInputStream()
            while (true) {
                when (inp.read()) {
                    -1 -> break
                    StreamProtocol.CTRL_KEY_FRAME -> onKeyFrameRequest?.invoke()
                }
            }
        } catch (_: IOException) {
        } finally {
            // Wakes the writer, which then reconnects.
            runCatching { s.close() }
            lock.withLock { changed.signalAll() }
        }
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
        lock.withLock { changed.signalAll() }
        thread.interrupt()
        thread.join(1000)
    }

    companion object {
        private const val TAG = "ReFreePlay"
        private const val MAX_LAG_US = 1_000_000L
        private const val STALL_NS = 3_000_000_000L
        private const val RECONNECT_MS = 300L
        private const val PING_MS = 1000L
        private val PING = byteArrayOf(StreamProtocol.MSG_PING.toByte())
    }
}

/**
 * Referee side: accepts the connection of a [StreamSender] and puts the received frames into [sink].
 *
 * Only a sender that knows the [token] is accepted. With [local] set, it listens for connections
 * from this device only (loopback test); otherwise on all of the device's networks.
 *
 * A new connection replaces the current one: after a Wi-Fi dropout the old connection can look
 * alive for a long time, and the sender's new one must not wait behind it.
 */
class StreamReceiver(
    private val sink: FrameSink,
    val local: Boolean,
    private val listener: Listener? = null,
) {
    interface Listener {
        /**
         * A stream starts (after every connect). Called on the receiver thread; when it returns,
         * [sink] must be ready for the frames of this stream, including its format.
         */
        fun onStreamFormat(meta: StreamMeta, format: MediaFormat)

        /** The stream info was received or changed, e.g. the camera was turned. Called on the receiver thread. */
        fun onStreamMeta(meta: StreamMeta)
    }

    // The loopback address is spelled out: InetAddress.getLoopbackAddress() is ::1 on Android.
    private val server =
        if (local) ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) else ServerSocket(0, 4)

    /** The port the sender must connect to. */
    val port: Int get() = server.localPort

    val framesReceived = AtomicLong()
    val bytesReceived = AtomicLong()

    /** True while a sender's connection is being read. */
    val connected: Boolean get() = synchronized(lock) { client != null }

    /**
     * Smoothed time from the encoder handing a frame to the sender until it was stored here, in ms.
     * Only meaningful when both ends run on the same device (same clock).
     */
    @Volatile
    var transitMs = 0.0
        private set

    private val lock = Any()
    private var client: Socket? = null

    /** What a sender must present to be accepted. */
    var token: String = newToken()
        get() = synchronized(lock) { field }
        private set

    /** Socket writes are not allowed on the main thread. */
    private val control = Executors.newSingleThreadExecutor()
    private val thread = Thread(::run, "StreamReceiver").apply { start() }

    /**
     * Ends the current stream and returns a new token for the next sender. After this, frames of
     * the earlier stream that are still on their way no longer reach [sink].
     */
    fun newStream(): String = synchronized(lock) {
        runCatching { client?.close() }
        client = null
        token = newToken()
        token
    }

    /** Asks the sender for a key frame as soon as possible (after the buffer was cleared or unfrozen). */
    fun requestKeyFrame() {
        val s = synchronized(lock) { client } ?: return
        control.execute { runCatching { s.getOutputStream().write(StreamProtocol.CTRL_KEY_FRAME) } }
    }

    private fun run() {
        while (!server.isClosed) {
            val s = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            // One thread per connection, so a new connection is seen while an old one is still open.
            Thread({
                try {
                    serve(s)
                } catch (e: IOException) {
                    if (!server.isClosed && !s.isClosed) Log.w(TAG, "stream receiver: ${e.message}")
                } finally {
                    synchronized(lock) { if (client === s) client = null }
                    runCatching { s.close() }
                }
            }, "StreamReceiverConnection").start()
        }
    }

    /** True while [s] is the connection in use (not replaced by a newer one, not ended by [newStream]). */
    private fun isCurrent(s: Socket): Boolean = client === s

    private fun serve(s: Socket) {
        s.tcpNoDelay = true
        s.soTimeout = HELLO_TIMEOUT_MS
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 18))
        if (inp.readInt() != StreamProtocol.MAGIC) throw IOException("not a ReFreePlay stream")
        val presented = inp.readUTF()
        synchronized(lock) {
            if (presented != token) throw IOException("connection with a wrong token refused")
            runCatching { client?.close() }
            client = s
        }
        // The sender writes at least a ping every second.
        s.soTimeout = READ_TIMEOUT_MS
        transitMs = 0.0
        var meta: StreamMeta? = null
        var data = ByteArray(1 shl 16)
        while (true) {
            when (val type = inp.read()) {
                -1 -> return
                StreamProtocol.MSG_PING -> Unit
                StreamProtocol.MSG_META -> {
                    val m = StreamProtocol.readMeta(inp)
                    meta = m
                    if (synchronized(lock) { isCurrent(s) }) listener?.onStreamMeta(m)
                }

                StreamProtocol.MSG_FORMAT -> {
                    val f = StreamProtocol.readFormat(inp)
                    if (listener != null) {
                        if (!synchronized(lock) { isCurrent(s) }) return
                        listener.onStreamFormat(meta ?: throw IOException("format before stream info"), f)
                    } else {
                        synchronized(lock) {
                            if (!isCurrent(s)) return
                            sink.setFormat(f)
                        }
                    }
                }

                StreamProtocol.MSG_FRAME -> {
                    val ptsUs = inp.readLong()
                    val sentNs = inp.readLong()
                    val key = inp.readUnsignedByte() and StreamProtocol.FLAG_KEY != 0
                    val size = inp.readInt()
                    if (size <= 0 || size > StreamProtocol.MAX_FRAME) throw IOException("bad frame size $size")
                    if (data.size < size) data = ByteArray(size + (size shr 2))
                    inp.readFully(data, 0, size)
                    synchronized(lock) {
                        if (!isCurrent(s)) return
                        sink.add(ByteBuffer.wrap(data, 0, size), size, ptsUs, key)
                    }
                    framesReceived.incrementAndGet()
                    bytesReceived.addAndGet(size.toLong())
                    val t = (System.nanoTime() - sentNs) / 1e6
                    transitMs = if (transitMs == 0.0) t else transitMs * 0.9 + t * 0.1
                }

                else -> throw IOException("unknown message $type")
            }
        }
    }

    fun close() {
        runCatching { server.close() }
        synchronized(lock) {
            runCatching { client?.close() }
            client = null
        }
        control.shutdown()
        thread.join(1000)
    }

    companion object {
        private const val TAG = "ReFreePlay"

        /** The address a [local] receiver listens on. */
        const val HOST = "127.0.0.1"

        /** A connection that doesn't introduce itself in this time is dropped, so it can't block the real one. */
        private const val HELLO_TIMEOUT_MS = 3000

        /** A connection that stays silent this long is dead (Wi-Fi gone without a goodbye). */
        private const val READ_TIMEOUT_MS = 4000

        private fun newToken(): String {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
