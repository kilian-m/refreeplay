package io.github.kilianm.refreeplay

import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min

/** H.264 helpers: the encoder delivers Annex-B (start codes), MP4 files need length prefixes. */
object Avc {
    /** Calls [onNal] with (start, end) of each NAL unit's payload (without its start code). */
    inline fun forEachNal(b: ByteArray, size: Int, onNal: (Int, Int) -> Unit) {
        var i = 0
        var nalStart = -1
        while (i + 2 < size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                if (nalStart >= 0) {
                    var end = i
                    while (end > nalStart && b[end - 1].toInt() == 0) end-- // 4-byte start code / trailing zeros
                    onNal(nalStart, end)
                }
                nalStart = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (nalStart >= 0) onNal(nalStart, size) else if (size > 0) onNal(0, size)
    }

    /** Size of the frame after replacing every start code by a 4-byte length. */
    fun avccSize(b: ByteArray, size: Int): Int {
        var total = 0
        forEachNal(b, size) { s, e -> total += 4 + (e - s) }
        return total
    }

    /** Converts an Annex-B frame into length-prefixed form; [dst] must hold [avccSize] bytes. */
    fun toAvcc(src: ByteArray, size: Int, dst: ByteArray) {
        var o = 0
        forEachNal(src, size) { s, e ->
            val n = e - s
            dst[o] = (n ushr 24).toByte()
            dst[o + 1] = (n ushr 16).toByte()
            dst[o + 2] = (n ushr 8).toByte()
            dst[o + 3] = n.toByte()
            System.arraycopy(src, s, dst, o + 4, n)
            o += 4 + n
        }
    }

    /** The first NAL unit in a codec-specific-data buffer (SPS or PPS), without start code. */
    fun firstNal(csd: ByteBuffer): ByteArray {
        val d = csd.duplicate()
        d.position(0)
        val b = ByteArray(d.remaining())
        d.get(b)
        var result = b
        var found = false
        forEachNal(b, b.size) { s, e ->
            if (!found) {
                result = b.copyOfRange(s, e)
                found = true
            }
        }
        return result
    }
}

/**
 * An MP4 file that exists only virtually: the header (sample tables) is generated from a pinned
 * [ReplaySnapshot], and the sample data is read straight from the ring buffer when the player
 * asks for it. Opening a replay therefore takes milliseconds, whatever its length; nothing is
 * written to storage.
 */
class VirtualMp4(val snapshot: ReplaySnapshot, rotationDegrees: Int) {
    private val frames = snapshot.frames
    private val header: ByteArray

    /** Start of each frame inside the mdat payload (length-prefixed sizes), plus the end. */
    private val dataOffsets = LongArray(frames.size + 1)
    val length: Long

    /** Timestamps (relative to the clip start) and key flags, for frame stepping and cutting. */
    val index: ClipIndex

    init {
        val pts = LongArray(frames.size)
        val keys = BooleanArray(frames.size)
        var last = -1L
        for (i in frames.indices) {
            var rel = frames[i].ptsUs - snapshot.startPtsUs
            if (rel <= last) rel = last + 1
            last = rel
            pts[i] = rel
            keys[i] = frames[i].isKey
            dataOffsets[i + 1] = dataOffsets[i] + frames[i].avccSize
        }
        index = ClipIndex(pts, keys)
        // The chunk offsets depend on the header size, so build it twice: with dummy and real offsets.
        val probe = buildHeader(snapshot.format, rotationDegrees, 0L)
        header = buildHeader(snapshot.format, rotationDegrees, probe.size.toLong())
        check(header.size == probe.size)
        length = header.size + dataOffsets[frames.size]
    }

    // ------------------------------------------------------------------------- reading

    private var cachedFrame = -1
    private var cachedData = ByteArray(0)
    private var raw = ByteArray(0)

    /** Reads up to [len] bytes at [position]. Returns the count, or -1 if the buffer was reset. */
    @Synchronized
    fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (position >= length || len <= 0) return 0
        if (position < header.size) {
            val n = min(len.toLong(), header.size - position).toInt()
            System.arraycopy(header, position.toInt(), dst, off, n)
            return n
        }
        val rel = position - header.size
        val i = frameAt(rel)
        if (i != cachedFrame) {
            val f = frames[i]
            if (raw.size < f.size) raw = ByteArray(f.size)
            if (!snapshot.read(f, raw)) return -1
            if (cachedData.size < f.avccSize) cachedData = ByteArray(f.avccSize)
            Avc.toAvcc(raw, f.size, cachedData)
            cachedFrame = i
        }
        val inFrame = (rel - dataOffsets[i]).toInt()
        val n = min(len, frames[i].avccSize - inFrame)
        System.arraycopy(cachedData, inFrame, dst, off, n)
        return n
    }

    private fun frameAt(rel: Long): Int {
        var lo = 0
        var hi = frames.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (dataOffsets[mid] <= rel) lo = mid else hi = mid - 1
        }
        return lo
    }

    // ------------------------------------------------------------------------- header

    private fun buildHeader(format: MediaFormat, rotation: Int, dataStart: Long): ByteArray {
        val n = frames.size
        val durationUs = index.ptsUs.last() + lastDelta()
        val durationMs = (durationUs + 999) / 1000
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val sps = Avc.firstNal(format.getByteBuffer("csd-0")!!)
        val pps = Avc.firstNal(format.getByteBuffer("csd-1")!!)
        val payloadStart = dataStart // the sample data follows the whole header (incl. the mdat box header)

        val out = Box()
        out.box("ftyp") {
            fourcc("isom"); int(0x200); fourcc("isom"); fourcc("iso2"); fourcc("avc1"); fourcc("mp41")
        }
        out.box("moov") {
            box("mvhd") {
                int(0); int(0); int(0); int(1000); int(durationMs.toInt())
                int(0x00010000); short(0x0100); short(0); int(0); int(0)
                matrix(0)
                repeat(6) { int(0) }
                int(2)
            }
            box("trak") {
                box("tkhd") {
                    int(0x00000007); int(0); int(0); int(1); int(0); int(durationMs.toInt())
                    int(0); int(0); short(0); short(0); short(0); short(0)
                    matrix(rotation)
                    int(width shl 16); int(height shl 16)
                }
                box("mdia") {
                    box("mdhd") {
                        int(0x01000000); long(0); long(0); int(1_000_000); long(durationUs)
                        short(0x55C4); short(0)
                    }
                    box("hdlr") {
                        int(0); int(0); fourcc("vide"); int(0); int(0); int(0)
                        bytes("VideoHandler".toByteArray()); byte(0)
                    }
                    box("minf") {
                        box("vmhd") { int(1); short(0); short(0); short(0); short(0) }
                        box("dinf") {
                            box("dref") {
                                int(0); int(1)
                                box("url ") { int(1) }
                            }
                        }
                        box("stbl") {
                            box("stsd") {
                                int(0); int(1)
                                box("avc1") {
                                    repeat(6) { byte(0) }; short(1)
                                    short(0); short(0); int(0); int(0); int(0)
                                    short(width); short(height)
                                    int(0x00480000); int(0x00480000); int(0); short(1)
                                    repeat(32) { byte(0) }
                                    short(0x0018); short(-1)
                                    box("avcC") {
                                        byte(1); byte(sps[1].toInt()); byte(sps[2].toInt()); byte(sps[3].toInt())
                                        byte(0xFF); byte(0xE1); short(sps.size); bytes(sps)
                                        byte(1); short(pps.size); bytes(pps)
                                    }
                                }
                            }
                            box("stts") {
                                val runs = ArrayList<LongArray>()
                                for (i in 0 until n) {
                                    val d = if (i + 1 < n) index.ptsUs[i + 1] - index.ptsUs[i] else lastDelta()
                                    val r = runs.lastOrNull()
                                    if (r != null && r[1] == d) r[0]++ else runs.add(longArrayOf(1, d))
                                }
                                int(0); int(runs.size)
                                for (r in runs) { int(r[0].toInt()); int(r[1].toInt()) }
                            }
                            box("stss") {
                                val keyIdx = index.keys.indices.filter { index.keys[it] }
                                int(0); int(keyIdx.size)
                                for (k in keyIdx) int(k + 1)
                            }
                            box("stsc") { int(0); int(1); int(1); int(1); int(1) }
                            box("stsz") {
                                int(0); int(0); int(n)
                                for (f in frames) int(f.avccSize)
                            }
                            box("co64") {
                                int(0); int(n)
                                for (i in 0 until n) long(payloadStart + dataOffsets[i])
                            }
                        }
                    }
                }
            }
        }
        // mdat header only; its payload is served from the ring buffer.
        out.int(1); out.fourcc("mdat"); out.long(16 + dataOffsets[n])
        return out.toByteArray()
    }

    private fun lastDelta(): Long {
        val p = index.ptsUs
        return if (p.size >= 2) (p[p.size - 1] - p[p.size - 2]).coerceAtLeast(1) else 16_667
    }

    /** Minimal MP4 box writer. */
    private class Box {
        private val bytes = ByteArrayOutputStream()
        private val out = DataOutputStream(bytes)

        fun int(v: Int) = out.writeInt(v)
        fun long(v: Long) = out.writeLong(v)
        fun short(v: Int) = out.writeShort(v)
        fun byte(v: Int) = out.writeByte(v)
        fun bytes(b: ByteArray) = out.write(b)
        fun fourcc(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))

        /** Composition matrix for a clockwise rotation, like MediaMuxer writes it. */
        fun matrix(rotation: Int) {
            val (a, b, c, d) = when (rotation) {
                90 -> listOf(0, 0x10000, -0x10000, 0)
                180 -> listOf(-0x10000, 0, 0, -0x10000)
                270 -> listOf(0, -0x10000, 0x10000, 0)
                else -> listOf(0x10000, 0, 0, 0x10000)
            }
            int(a); int(b); int(0); int(c); int(d); int(0); int(0); int(0); int(0x40000000)
        }

        fun box(type: String, body: Box.() -> Unit) {
            val inner = Box()
            inner.body()
            val content = inner.toByteArray()
            int(8 + content.size)
            fourcc(type)
            bytes(content)
        }

        fun toByteArray(): ByteArray {
            out.flush()
            return bytes.toByteArray()
        }
    }
}

/** Lets ExoPlayer read a [VirtualMp4] like a file. */
@OptIn(UnstableApi::class)
class VirtualMp4DataSource(private val mp4: VirtualMp4) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        position = dataSpec.position
        if (position > mp4.length) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        remaining = mp4.length - position
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) remaining = min(remaining, dataSpec.length)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = mp4.read(position, buffer, offset, min(length.toLong(), remaining).toInt())
        if (n < 0) throw IOException("The replay buffer was reset")
        if (n == 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
