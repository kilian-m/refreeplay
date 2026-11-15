package io.github.kilianm.refreeplay

import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Where the encoder's output goes: the buffer itself, or a connection to the buffer on the other end. */
interface FrameSink {
    fun setFormat(f: MediaFormat)

    /** Takes one encoded frame; [src] is positioned at the frame data. */
    fun add(src: ByteBuffer, size: Int, rawPtsUs: Long, isKey: Boolean)
}

/**
 * The video stream between the recording side ([StreamSender]) and the referee side
 * ([StreamReceiver]), over one TCP connection that the sender opens. All numbers are big-endian.
 *
 * Sender to receiver: [MAGIC], the stream id, then messages:
 * - [MSG_FORMAT], the encoder's output format (see [writeFormat])
 * - [MSG_FRAME], long ptsUs, long sentNs, byte flags ([FLAG_KEY]), int size, the frame data
 *
 * Receiver to sender, one byte each: [CTRL_KEY_FRAME] asks for a key frame as soon as possible.
 */
object StreamProtocol {
    const val MAGIC = 0x52465031 // "RFP1"
    const val MSG_FORMAT = 1
    const val MSG_FRAME = 2
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
                    val d = f.getByteBuffer(k)!!.duplicate()
                    d.rewind()
                    val bytes = ByteArray(d.remaining())
                    d.get(bytes)
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
}

/**
 * Recording side: sends everything the encoder produces to a [StreamReceiver].
 *
 * Frames wait in a queue for a writer thread, so a slow connection never blocks the encoder.
 * If more than [MAX_LAG_US] of video is waiting, the connection is too slow: the waiting frames
 * are dropped and the stream continues at the next key frame. It reconnects on its own; every
 * (re)connection starts with the format and a key frame.
 */
class StreamSender(
    private val host: String,
    private val port: Int,
    private val streamId: Int,
) : FrameSink {
    private class Packet(val bytes: ByteArray, val ptsUs: Long, val isFrame: Boolean)

    /** Called (on any thread) when the stream needs a key frame. */
    @Volatile
    var onKeyFrameRequest: (() -> Unit)? = null

    /** How often waiting frames had to be dropped because the connection was too slow. */
    val overflows = AtomicLong()

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queue = ArrayDeque<Packet>()
    private var formatPacket: ByteArray? = null
    private var connected = false
    private var awaitKey = true
    private var keyRequested = false

    @Volatile
    private var closed = false

    @Volatile
    private var socket: Socket? = null

    private val thread = Thread(::run, "StreamSender").apply { start() }

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
        lock.withLock {
            if (!connected) return
            val oldest = queue.firstOrNull { it.isFrame }
            if (oldest != null && rawPtsUs - oldest.ptsUs > MAX_LAG_US) {
                queue.removeAll { it.isFrame }
                awaitKey = true
                keyRequested = false
                overflows.incrementAndGet()
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
        if (needKey) onKeyFrameRequest?.invoke()
    }

    private fun run() {
        var failing = false
        while (!closed) {
            val s = Socket()
            socket = s
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 2000)
                val out = s.getOutputStream()
                val hello = ByteBuffer.allocate(8).putInt(StreamProtocol.MAGIC).putInt(streamId).array()
                val format = lock.withLock {
                    queue.clear()
                    awaitKey = true
                    keyRequested = true // requested below
                    connected = true
                    formatPacket
                }
                failing = false
                out.write(hello)
                format?.let { out.write(it) }
                onKeyFrameRequest?.invoke()
                Thread({ readControl(s) }, "StreamSenderControl").start()
                while (!closed) {
                    val p = lock.withLock {
                        while (queue.isEmpty() && !closed && !s.isClosed) changed.await()
                        queue.removeFirstOrNull()
                    } ?: break
                    out.write(p.bytes)
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
                runCatching { s.close() }
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
        private const val RECONNECT_MS = 300L
    }
}

/**
 * Referee side: accepts the connection of a [StreamSender] and puts the received frames into [sink].
 *
 * Only the stream announced by the latest [expectStream] is used, so frames of an earlier stream
 * that are still on their way can never end up in a buffer that was set up for a new one.
 */
class StreamReceiver(private val sink: FrameSink) {
    // Local connections only for now. (Not InetAddress.getLoopbackAddress(): that is ::1 on Android.)
    private val server = ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))

    /** The port the sender must connect to, on [HOST]. */
    val port: Int get() = server.localPort

    val framesReceived = AtomicLong()
    val bytesReceived = AtomicLong()

    @Volatile
    var connected = false
        private set

    /**
     * Smoothed time from the encoder handing a frame to the sender until it was stored here, in ms.
     * Only meaningful when both ends run on the same device (same clock).
     */
    @Volatile
    var transitMs = 0.0
        private set

    private val lock = Any()
    private var expectedId = 0
    private var client: Socket? = null

    /** Socket writes are not allowed on the main thread. */
    private val control = Executors.newSingleThreadExecutor()
    private val thread = Thread(::run, "StreamReceiver").apply { start() }

    /** Ends the current stream and returns the id the next sender must announce. */
    fun expectStream(): Int = synchronized(lock) {
        runCatching { client?.close() }
        client = null
        ++expectedId
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
            try {
                serve(s)
            } catch (e: IOException) {
                if (!server.isClosed && !s.isClosed) Log.w(TAG, "stream receiver: ${e.message}")
            } finally {
                connected = false
                synchronized(lock) { if (client === s) client = null }
                runCatching { s.close() }
            }
        }
    }

    private fun serve(s: Socket) {
        s.tcpNoDelay = true
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 18))
        if (inp.readInt() != StreamProtocol.MAGIC) throw IOException("not a ReFreePlay stream")
        val id = inp.readInt()
        synchronized(lock) {
            if (id != expectedId) return
            client = s
        }
        connected = true
        transitMs = 0.0
        var data = ByteArray(1 shl 16)
        while (true) {
            when (val type = inp.read()) {
                -1 -> return
                StreamProtocol.MSG_FORMAT -> {
                    val f = StreamProtocol.readFormat(inp)
                    synchronized(lock) {
                        if (id != expectedId) return
                        sink.setFormat(f)
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
                        if (id != expectedId) return
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

        /** The address the receiver listens on. */
        const val HOST = "127.0.0.1"
    }
}
