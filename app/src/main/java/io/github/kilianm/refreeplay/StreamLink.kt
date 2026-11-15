package io.github.kilianm.refreeplay

import android.media.MediaFormat
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
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

    /**
     * Takes one encoded frame; [src] is positioned at the frame data. [encodedNs] is when the frame
     * left its encoder, on this phone's clock (System.nanoTime()); 0 means now.
     */
    fun add(src: ByteBuffer, size: Int, rawPtsUs: Long, isKey: Boolean, encodedNs: Long = 0L)
}

/** What the main phone must know about the video, besides the codec format. */
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
 * The video stream between a camera phone ([StreamSender]) and the main phone ([StreamReceiver]),
 * over one TCP connection that the sender opens. All numbers are big-endian.
 *
 * Sender to receiver: [MAGIC], the receiver's token, the sender's id and its name (all UTF), then
 * messages:
 * - [MSG_META], int length, a [StreamMeta] (first message, and again when the camera is turned)
 * - [MSG_CAPS], int length, UTF-8 text: what the cameras can do and what is set (see CameraRemote)
 * - [MSG_FORMAT], the encoder's output format (see [writeFormat])
 * - [MSG_FRAME], long ptsUs, long sentNs, byte flags ([FLAG_KEY]), int size, the frame data
 * - [MSG_PING] while there is nothing else to send, so the receiver can tell a quiet connection
 *   from a dead one
 * - [MSG_TIME], long t0, long t1: the answer to [CTRL_TIME], with the sender's clock t1
 *
 * Receiver to sender:
 * - [CTRL_KEY_FRAME] asks for a key frame as soon as possible
 * - [CTRL_TIME], long t0 (the receiver's clock) asks for the sender's clock. From the answers the
 *   receiver works out how the two clocks differ, to place the frames on its own timeline.
 * - [CTRL_CONFIG], int length, UTF-8 text: the camera settings to use (see CameraRemote)
 */
object StreamProtocol {
    const val MAGIC = 0x52465033 // "RFP3"
    const val MSG_FORMAT = 1
    const val MSG_FRAME = 2
    const val MSG_META = 3
    const val MSG_PING = 4
    const val MSG_TIME = 5
    const val MSG_CAPS = 6
    const val FLAG_KEY = 1
    const val CTRL_KEY_FRAME = 1
    const val CTRL_TIME = 2
    const val CTRL_CONFIG = 3

    /** Longest text message, in bytes. */
    private const val MAX_TEXT = 1 shl 18

    /** A complete text message of the given type. */
    fun textPacket(type: Int, text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(5 + bytes.size).put(type.toByte()).putInt(bytes.size).put(bytes).array()
    }

    /** Reads what follows the type byte of a text message. */
    fun readText(inp: DataInputStream): String {
        val n = inp.readInt()
        if (n < 0 || n > MAX_TEXT) throw IOException("bad text message")
        val bytes = ByteArray(n)
        inp.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
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
 * Camera phone: sends everything the encoder produces to the main phone's [StreamReceiver], and
 * takes its requests. [id] tells the receiver which camera phone this is, also after reconnecting;
 * [name] is shown to the user.
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
    private val id: String,
    private val name: String,
    private val connect: () -> Link,
) : FrameSink {
    /** An open connection and the token its receiver expects. */
    class Link(val socket: Socket, val token: String)

    private class Packet(val bytes: ByteArray, val ptsUs: Long, val isFrame: Boolean)

    /** Called (on any thread) when the stream needs a key frame. */
    @Volatile
    var onKeyFrameRequest: (() -> Unit)? = null

    /** Called (on a connection thread) with the camera settings the main phone wants, see CameraRemote. */
    @Volatile
    var onConfigRequest: ((String) -> Unit)? = null

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
    private var capsPacket: ByteArray? = null
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

    /** Tells the receiver what this phone's cameras can do and what is set (see CameraRemote). */
    fun setCapabilities(json: String) {
        val packet = StreamProtocol.textPacket(StreamProtocol.MSG_CAPS, json)
        lock.withLock {
            capsPacket = packet
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

    override fun add(src: ByteBuffer, size: Int, rawPtsUs: Long, isKey: Boolean, encodedNs: Long) {
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
                    it.writeUTF(id)
                    it.writeUTF(name)
                }
                val first = lock.withLock {
                    queue.clear()
                    awaitKey = true
                    keyRequested = true // requested below
                    connected = true
                    listOfNotNull(metaPacket, capsPacket, formatPacket)
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
            val inp = DataInputStream(s.getInputStream())
            while (true) {
                when (inp.read()) {
                    -1 -> break
                    StreamProtocol.CTRL_KEY_FRAME -> onKeyFrameRequest?.invoke()
                    StreamProtocol.CTRL_CONFIG -> StreamProtocol.readText(inp).let { onConfigRequest?.invoke(it) }
                    StreamProtocol.CTRL_TIME -> {
                        val bytes = ByteBuffer.allocate(17)
                            .put(StreamProtocol.MSG_TIME.toByte())
                            .putLong(inp.readLong())
                            .putLong(System.nanoTime())
                            .array()
                        // In front of the waiting frames: the answer must not be held up.
                        lock.withLock {
                            if (connected) {
                                queue.addFirst(Packet(bytes, 0, false))
                                changed.signalAll()
                            }
                        }
                    }
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

/** Hands everything to two sinks. Testing only: lets a phone keep its video and send it to itself. */
class TeeSink(private val first: FrameSink, private val second: FrameSink) : FrameSink {
    override fun setFormat(f: MediaFormat) {
        first.setFormat(f)
        second.setFormat(f)
    }

    override fun add(src: ByteBuffer, size: Int, rawPtsUs: Long, isKey: Boolean, encodedNs: Long) {
        first.add(src.duplicate(), size, rawPtsUs, isKey, encodedNs)
        second.add(src, size, rawPtsUs, isKey, encodedNs)
    }
}

/**
 * Main phone: accepts the connections of camera phones ([StreamSender]) and hands their frames to
 * the sinks the [listener] names.
 *
 * Only a sender that knows the [token] is accepted. Every camera phone introduces itself with an
 * id. A new connection with the same id replaces the old one: after a Wi-Fi dropout the old
 * connection can look alive for a long time, and the sender's new one must not wait behind it.
 */
class StreamReceiver(private val listener: Listener) {

    /** A camera phone that has connected at least once. */
    class Peer internal constructor(val id: String, val name: String) {
        val framesReceived = AtomicLong()
        val bytesReceived = AtomicLong()

        @Volatile
        var connected = false
            internal set

        /** Travel time of the clock comparison in use, in ms; -1 while the clocks are not compared yet. */
        @Volatile
        var clockRoundTripMs = -1.0
            internal set

        // The rest is guarded by the receiver's lock.
        internal var socket: Socket? = null
        internal var sink: FrameSink? = null

        /** Travel time (ns) and clock offset (ns) of the last comparisons. */
        internal val clockSamples = ArrayDeque<LongArray>()

        /**
         * How far this camera phone's clock is ahead of the main phone's (System.nanoTime() on
         * both), or [UNKNOWN] until it was measured. Each comparison can be off by half the time
         * its question and answer travelled, so the fastest of the last [CLOCK_SAMPLES] counts.
         */
        @Volatile
        internal var clockOffsetNs = UNKNOWN
    }

    /** All calls come on a connection's own thread. */
    interface Listener {
        /**
         * A stream starts (after every connect). Returns where its frames go, already prepared
         * for this video and its format, or null if the stream is not wanted.
         */
        fun onStreamFormat(peer: Peer, meta: StreamMeta, format: MediaFormat): FrameSink?

        /** The stream info was received or changed, e.g. the camera was turned. */
        fun onStreamMeta(peer: Peer, meta: StreamMeta)

        /** The camera phone told what its cameras can do and what is set (JSON, see CameraRemote). */
        fun onCapabilities(peer: Peer, json: String)
    }

    private val server = ServerSocket(0, 8)

    /** The port the senders must connect to. */
    val port: Int get() = server.localPort

    /** What a sender must present to be accepted. */
    val token: String = newToken()

    private val lock = Any()
    private val peers = LinkedHashMap<String, Peer>()

    /** Socket writes are not allowed on the main thread. */
    private val control = Executors.newSingleThreadScheduledExecutor().apply {
        // Compares the clocks, see Peer.clockOffsetNs.
        scheduleWithFixedDelay({
            val ask = ByteBuffer.allocate(9).put(StreamProtocol.CTRL_TIME.toByte()).putLong(0)
            for (s in sockets()) {
                ask.putLong(1, System.nanoTime())
                runCatching { s.getOutputStream().write(ask.array()) }
            }
        }, 300, CLOCK_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }
    private val thread = Thread(::run, "StreamReceiver").apply { start() }

    private fun sockets(): List<Socket> = synchronized(lock) { peers.values.mapNotNull { it.socket } }

    /** Asks all camera phones for a key frame as soon as possible (after a buffer was cleared or unfrozen). */
    fun requestKeyFrame() {
        val all = sockets()
        control.execute { all.forEach { s -> runCatching { s.getOutputStream().write(StreamProtocol.CTRL_KEY_FRAME) } } }
    }

    /** Tells a camera phone which camera settings to use (JSON, see CameraRemote). False if it is not connected. */
    fun sendConfig(peer: Peer, json: String): Boolean {
        val s = synchronized(lock) { peer.socket } ?: return false
        val text = json.toByteArray(Charsets.UTF_8)
        val bytes = ByteBuffer.allocate(5 + text.size).put(StreamProtocol.CTRL_CONFIG.toByte()).putInt(text.size).put(text).array()
        control.execute { runCatching { s.getOutputStream().write(bytes) } }
        return true
    }

    private fun run() {
        while (!server.isClosed) {
            val s = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            // One thread per connection: several camera phones, and a new connection of one of
            // them is seen while its old one is still open.
            Thread({
                try {
                    serve(s)
                } catch (e: IOException) {
                    if (!server.isClosed && !s.isClosed) Log.w(TAG, "stream receiver: ${e.message}")
                } finally {
                    runCatching { s.close() }
                }
            }, "StreamReceiverConnection").start()
        }
    }

    private fun serve(s: Socket) {
        s.tcpNoDelay = true
        s.soTimeout = HELLO_TIMEOUT_MS
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 18))
        if (inp.readInt() != StreamProtocol.MAGIC) throw IOException("not a ReFreePlay stream (or another version of the app)")
        val presented = inp.readUTF()
        val id = inp.readUTF().take(64)
        val name = inp.readUTF().take(64)
        val peer = synchronized(lock) {
            if (presented != token) throw IOException("connection with a wrong token refused")
            val p = peers[id] ?: run {
                if (peers.size >= MAX_PEERS) throw IOException("too many camera phones")
                Peer(id, name).also { peers[id] = it }
            }
            runCatching { p.socket?.close() }
            p.socket = s
            p.sink = null
            p.clockSamples.clear()
            p.clockOffsetNs = UNKNOWN
            p.clockRoundTripMs = -1.0
            p.connected = true
            p
        }
        try {
            // The sender writes at least a ping every second.
            s.soTimeout = READ_TIMEOUT_MS
            read(s, inp, peer)
        } finally {
            synchronized(lock) {
                if (peer.socket === s) {
                    peer.socket = null
                    peer.sink = null
                    peer.connected = false
                }
            }
        }
    }

    private fun read(s: Socket, inp: DataInputStream, peer: Peer) {
        /** True while [s] is this camera phone's connection, not replaced by a newer one. */
        fun current(): Boolean = synchronized(lock) { peer.socket === s }
        var meta: StreamMeta? = null
        var data = ByteArray(1 shl 16)
        while (true) {
            when (val type = inp.read()) {
                -1 -> return
                StreamProtocol.MSG_PING -> Unit
                StreamProtocol.MSG_TIME -> {
                    val t0 = inp.readLong()
                    val t1 = inp.readLong()
                    val t2 = System.nanoTime()
                    synchronized(lock) {
                        if (peer.socket === s) {
                            // The sender's clock showed t1 when ours was halfway between t0 and t2.
                            peer.clockSamples.addLast(longArrayOf(t2 - t0, t1 - (t0 + (t2 - t0) / 2)))
                            if (peer.clockSamples.size > CLOCK_SAMPLES) peer.clockSamples.removeFirst()
                            val best = peer.clockSamples.minBy { it[0] }
                            peer.clockOffsetNs = best[1]
                            peer.clockRoundTripMs = best[0] / 1e6
                        }
                    }
                }

                StreamProtocol.MSG_META -> {
                    val m = StreamProtocol.readMeta(inp)
                    meta = m
                    if (current()) listener.onStreamMeta(peer, m)
                }

                StreamProtocol.MSG_CAPS -> {
                    val json = StreamProtocol.readText(inp)
                    if (current()) listener.onCapabilities(peer, json)
                }

                StreamProtocol.MSG_FORMAT -> {
                    val f = StreamProtocol.readFormat(inp)
                    if (!current()) return
                    val sink = listener.onStreamFormat(peer, meta ?: throw IOException("format before stream info"), f)
                    synchronized(lock) { if (peer.socket === s) peer.sink = sink }
                }

                StreamProtocol.MSG_FRAME -> {
                    val ptsUs = inp.readLong()
                    val sentNs = inp.readLong()
                    val key = inp.readUnsignedByte() and StreamProtocol.FLAG_KEY != 0
                    val size = inp.readInt()
                    if (size <= 0 || size > StreamProtocol.MAX_FRAME) throw IOException("bad frame size $size")
                    if (data.size < size) data = ByteArray(size + (size shr 2))
                    inp.readFully(data, 0, size)
                    // When the frame left the camera phone's encoder, on this phone's clock.
                    val offset = peer.clockOffsetNs
                    val now = System.nanoTime()
                    val encodedNs = (sentNs - offset).takeIf { offset != UNKNOWN && it <= now && it > now - MAX_AGE_NS } ?: 0L
                    synchronized(lock) {
                        if (peer.socket !== s) return
                        peer.sink?.add(ByteBuffer.wrap(data, 0, size), size, ptsUs, key, encodedNs)
                    }
                    peer.framesReceived.incrementAndGet()
                    peer.bytesReceived.addAndGet(size.toLong())
                }

                else -> throw IOException("unknown message $type")
            }
        }
    }

    fun close() {
        runCatching { server.close() }
        synchronized(lock) {
            for (p in peers.values) {
                runCatching { p.socket?.close() }
                p.socket = null
                p.sink = null
                p.connected = false
            }
        }
        control.shutdown()
        thread.join(1000)
    }

    companion object {
        private const val TAG = "ReFreePlay"

        /** For a sender on the same phone (testing). */
        const val LOCALHOST = "127.0.0.1"

        /** A connection that doesn't introduce itself in this time is dropped. */
        private const val HELLO_TIMEOUT_MS = 3000

        /** A connection that stays silent this long is dead (Wi-Fi gone without a goodbye). */
        private const val READ_TIMEOUT_MS = 4000

        private const val MAX_PEERS = 8
        private const val UNKNOWN = Long.MIN_VALUE
        private const val CLOCK_INTERVAL_MS = 1000L
        private const val CLOCK_SAMPLES = 20

        /** A frame that claims to be older than this was timed with a wrong clock offset. */
        private const val MAX_AGE_NS = 5_000_000_000L

        private fun newToken(): String {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
