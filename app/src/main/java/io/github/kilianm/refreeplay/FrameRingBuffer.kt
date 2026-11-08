package io.github.kilianm.refreeplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SharedMemory
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/** Index entry for one compressed (H.264) frame stored in the buffer's memory arena. */
class FrameRef(
    val offset: Long,
    val size: Int,
    val ptsUs: Long,
    val isKey: Boolean,
    /** System.nanoTime() when the frame arrived from the encoder (used by the delayed view). */
    val arrivalNs: Long,
    /** Size in MP4 (length-prefixed) form, see [Avc]. */
    val avccSize: Int,
)

/** Frames for one clip, starting at a key frame. Pinned in its buffer until [release]. */
class ReplaySnapshot internal constructor(
    /** The buffer holding the frame data. */
    val source: FrameRingBuffer,
    val frames: List<FrameRef>,
    val format: MediaFormat,
    internal val pinPts: Long,
    internal val generation: Int,
) {
    val startPtsUs: Long get() = frames.first().ptsUs
    val endPtsUs: Long get() = frames.last().ptsUs
    val maxFrameSize: Int = frames.maxOf { it.size }
    val totalBytes: Long = frames.sumOf { it.size.toLong() }

    /** Copies a frame of this snapshot into [dst]. Returns false if the buffer was reset. */
    fun read(f: FrameRef, dst: ByteArray): Boolean = source.read(f, generation, dst)

    /** Pins the frames once more; each [retain] needs its own [release]. Returns false if they are gone. */
    fun retain(): Boolean = source.retain(this)

    fun release() = source.unpin(this)

    /**
     * The part from [fromUs] to [toUs] (relative to the clip start), starting at the key frame at
     * or before [fromUs]. Shares this snapshot's pin.
     */
    fun slice(fromUs: Long, toUs: Long): ReplaySnapshot {
        val base = startPtsUs
        var s = 0
        for (i in frames.indices) {
            if (frames[i].ptsUs - base > fromUs + 500) break
            if (frames[i].isKey) s = i
        }
        var e = s
        while (e + 1 < frames.size && frames[e + 1].ptsUs - base <= toUs + 500) e++
        if (e == s && e + 1 < frames.size) e++
        return ReplaySnapshot(source, frames.subList(s, e + 1).toList(), format, pinPts, generation)
    }
}

/** Frame timestamps and key-frame flags of a written clip. */
class ClipIndex(val ptsUs: LongArray, val keys: BooleanArray)

/**
 * Keeps the last [durationUs] of compressed video.
 *
 * The data lives in native shared memory (not the limited Java heap), split into segments of at
 * most [SEGMENT] bytes so the total can exceed 2 GB. It is written as a circular log; a frame never
 * straddles two segments. A small index of [FrameRef]s describes the frames. Old frames are dropped
 * one GOP (key frame to key frame) at a time, so the buffer always starts with a key frame.
 * Memory pages are only committed when they are first written.
 */
class FrameRingBuffer {
    private var shms: Array<SharedMemory> = emptyArray()
    private var arenas: Array<ByteBuffer> = emptyArray()
    var capacityBytes = 0L
        private set

    private fun maxFrameBytes(): Long = min(usableBytes / 8, SEGMENT.toLong())

    /** A view of the segment holding [offset], positioned there. */
    private fun view(offset: Long, size: Int): ByteBuffer {
        val d = arenas[(offset / SEGMENT).toInt()].duplicate()
        val local = (offset % SEGMENT).toInt()
        d.limit(local + size)
        d.position(local)
        return d
    }

    @Volatile
    var durationUs = 0L
        private set

    private val frames = ArrayDeque<FrameRef>()
    private var writePos = 0L
    private var bytes = 0L
    private var format: MediaFormat? = null

    /** Changes whenever the stored frames are discarded; readers must restart then. */
    @Volatile
    var generation = 0
        private set

    // Freezing (referee replay): new frames are not stored while a replay is watched.
    private var frozen = false
    private var awaitKey = false
    private var offsetUs = 0L
    private var lastIntervalUs = 16_667L

    /** Start timestamps of snapshots that are still being read; those frames must not be overwritten. */
    private val pins = ArrayList<Long>()

    private var scratch = ByteArray(0)

    /** True if frames inside the wanted duration had to be dropped for lack of memory recently. */
    val limitedByMemory: Boolean
        get() = lastLimitedNs != 0L && System.nanoTime() - lastLimitedNs < 10_000_000_000L

    @Volatile
    private var lastLimitedNs = 0L

    /**
     * The part of the memory the circular log currently uses. It starts at the estimated need and
     * grows (up to [capacityBytes]) when the real bitrate is higher, so the buffer still holds the
     * whole duration. Memory beyond it is never touched, so it doesn't cost anything.
     */
    @Volatile
    var usableBytes = 0L
        private set

    /** Allocates fresh memory (pages are committed only when written) and sets the duration. Discards all stored frames. */
    @Synchronized
    fun configure(capacity: Long, usable: Long, durationUs: Long, resetFormat: Boolean) {
        run {
            // Always fresh memory: pages written for an earlier, longer buffer would stay in use otherwise.
            freeArena()
            val count = ((capacity + SEGMENT - 1) / SEGMENT).toInt()
            val newShms = ArrayList<SharedMemory>()
            val newArenas = ArrayList<ByteBuffer>()
            try {
                for (i in 0 until count) {
                    val size = min(SEGMENT.toLong(), capacity - i.toLong() * SEGMENT).toInt()
                    val m = SharedMemory.create("refreeplay-buffer-$i", size)
                    newShms.add(m)
                    newArenas.add(m.mapReadWrite())
                }
            } catch (e: Throwable) {
                newArenas.forEach { SharedMemory.unmap(it) }
                newShms.forEach { it.close() }
                throw e
            }
            shms = newShms.toTypedArray()
            arenas = newArenas.toTypedArray()
            capacityBytes = capacity
        }
        this.durationUs = durationUs
        usableBytes = usable.coerceIn(min(capacityBytes, 16L shl 20), capacityBytes)
        if (resetFormat) format = null
        resetIndex()
    }

    @Synchronized
    fun release() {
        freeArena()
        resetIndex()
    }

    private fun freeArena() {
        arenas.forEach { SharedMemory.unmap(it) }
        arenas = emptyArray()
        shms.forEach { it.close() }
        shms = emptyArray()
        capacityBytes = 0
    }

    private fun resetIndex() {
        frames.clear()
        writePos = 0L
        bytes = 0
        frozen = false
        awaitKey = false
        offsetUs = 0
        pins.clear()
        lastLimitedNs = 0L
        generation++
    }

    @Synchronized
    fun setFormat(f: MediaFormat) {
        format = f
    }

    @Synchronized
    fun currentFormat(): MediaFormat? = format

    /**
     * While frozen, incoming frames are dropped. After unfreezing, storing resumes at the
     * next key frame and timestamps are shifted so the recording continues without a gap.
     */
    @Synchronized
    fun setFrozen(value: Boolean) {
        if (value == frozen) return
        frozen = value
        if (!value) awaitKey = true
    }

    /** Stores one encoded frame; [src] is positioned at the frame data. */
    @Synchronized
    fun add(src: ByteBuffer, size: Int, rawPtsUs: Long, isKey: Boolean) {
        if (arenas.isEmpty()) return
        if (frozen) return
        if (size <= 0 || size > maxFrameBytes()) {
            awaitKey = true
            return
        }
        if (awaitKey) {
            if (!isKey) return
            awaitKey = false
            if (frames.isNotEmpty()) offsetUs = rawPtsUs - (frames.last().ptsUs + lastIntervalUs)
        }
        if (frames.isEmpty() && !isKey) return
        val pts = rawPtsUs - offsetUs
        if (frames.isNotEmpty()) {
            val delta = pts - frames.last().ptsUs
            if (delta <= 0) return
            if (delta < 200_000) lastIntervalUs = delta
        }
        val pos = reserve(size, pts)
        if (pos < 0) {
            // No space without overwriting a clip that is being saved: skip until the next key frame.
            awaitKey = true
            return
        }
        if (scratch.size < size) scratch = ByteArray(size + (size shr 2))
        src.get(scratch, 0, size)
        view(pos, size).put(scratch, 0, size)
        frames.addLast(FrameRef(pos, size, pts, isKey, System.nanoTime(), Avc.avccSize(scratch, size)))
        bytes += size
        trimByTime()
    }

    private fun minPin(): Long = pins.minOrNull() ?: Long.MAX_VALUE

    private fun evictHead(newPts: Long): Boolean {
        val h = frames.firstOrNull() ?: return false
        if (h.ptsUs >= minPin()) return false
        if (h.ptsUs > newPts - durationUs) lastLimitedNs = System.nanoTime()
        frames.removeFirst()
        bytes -= h.size
        return true
    }

    /** Finds room for [n] bytes, evicting the oldest frames if needed. Returns -1 if impossible. */
    private fun reserve(n: Int, newPts: Long): Long {
        var p = writePos
        var limit = usableBytes
        var segEnd = min((p / SEGMENT + 1) * SEGMENT, limit)
        if (p + n > segEnd && segEnd >= limit && limit < capacityBytes) {
            // About to wrap around while the oldest frame is still needed: the video needs more
            // memory than estimated, so use more of the reserved memory instead of dropping it.
            val head = frames.firstOrNull()
            if (head != null && head.ptsUs > newPts - durationUs) {
                limit = min(capacityBytes, max(limit + limit / 4, p + n))
                usableBytes = limit
                segEnd = min((p / SEGMENT + 1) * SEGMENT, limit)
            }
        }
        if (p + n > segEnd) {
            // Skip the rest of this segment (to the next one, or wrap around to the start):
            // frames still stored in the skipped part are the oldest ones and must go first.
            while (frames.isNotEmpty() && frames.first().offset in p until segEnd) {
                if (!evictHead(newPts)) return -1
            }
            p = if (segEnd >= limit) 0L else segEnd
        }
        while (frames.isNotEmpty()) {
            val h = frames.first()
            if (h.offset >= p + n || h.offset + h.size <= p) break
            if (!evictHead(newPts)) return -1
        }
        // A clip must start with a key frame: frames left over from a partly evicted GOP are useless.
        while (frames.isNotEmpty() && !frames.first().isKey) {
            if (!evictHead(newPts)) break
        }
        writePos = p + n
        return p
    }

    private fun trimByTime() {
        val cutoff = frames.last().ptsUs - durationUs
        val pin = minPin()
        while (frames.first().ptsUs < cutoff) {
            var nextKey = -1
            for (i in 1 until frames.size) {
                if (frames[i].isKey) {
                    nextKey = i
                    break
                }
            }
            if (nextKey < 0 || frames[nextKey].ptsUs > cutoff) break
            if (frames[nextKey - 1].ptsUs >= pin) break
            repeat(nextKey) { bytes -= frames.removeFirst().size }
        }
    }

    @Synchronized
    fun bufferedUs(): Long = if (frames.size < 2) 0 else frames.last().ptsUs - frames.first().ptsUs

    @Synchronized
    fun bufferedBytes(): Long = bytes

    /** Returns the frames covering (at least) the last [durationUs], starting at a key frame, and pins them. */
    @Synchronized
    fun snapshot(durationUs: Long): ReplaySnapshot? {
        val fmt = format ?: return null
        if (frames.size < 2) return null
        val target = frames.last().ptsUs - durationUs
        var start = 0
        for (i in frames.indices) {
            val f = frames[i]
            if (f.ptsUs > target) break
            if (f.isKey) start = i
        }
        val list = ArrayList(frames.subList(start, frames.size))
        pins.add(list.first().ptsUs)
        return ReplaySnapshot(this, list, fmt, list.first().ptsUs, generation)
    }

    @Synchronized
    fun unpin(s: ReplaySnapshot) {
        if (s.generation == generation) pins.remove(s.pinPts)
        if (releaseWhenUnpinned && pins.isEmpty()) release()
    }

    private var releaseWhenUnpinned = false

    /** Frees the memory as soon as no snapshot of this buffer is in use any more. */
    @Synchronized
    fun releaseWhenUnpinned() {
        releaseWhenUnpinned = true
        if (pins.isEmpty()) release()
    }

    /**
     * Copies the frames of [s] into a new buffer of their own and returns a pinned snapshot of
     * the copy. The original snapshot is not released. Free the copy with [releaseWhenUnpinned].
     */
    fun copyOf(s: ReplaySnapshot): ReplaySnapshot? {
        val copy = FrameRingBuffer()
        // Room for all frames, plus what may be left unused at the end of each segment.
        val slack = (s.totalBytes / SEGMENT + 2) * s.maxFrameSize
        val size = maxOf(s.totalBytes + slack, s.maxFrameSize * 8L + slack)
        copy.configure(size, size, Long.MAX_VALUE / 4, true)
        copy.setFormat(s.format)
        val tmp = ByteArray(s.maxFrameSize)
        for (f in s.frames) {
            if (!s.read(f, tmp)) {
                copy.release()
                return null
            }
            copy.add(ByteBuffer.wrap(tmp, 0, f.size), f.size, f.ptsUs, f.isKey)
        }
        val snap = copy.snapshot(Long.MAX_VALUE / 4)
        if (snap == null || snap.frames.size != s.frames.size) {
            copy.release()
            return null
        }
        copy.releaseWhenUnpinned = true
        return snap
    }

    /** Pins a snapshot once more (each [retain] needs its own [unpin]). Returns false if it is gone. */
    @Synchronized
    fun retain(s: ReplaySnapshot): Boolean {
        if (s.generation != generation) return false
        pins.add(s.pinPts)
        return true
    }

    /** Copies a pinned frame into [dst]. Returns false if the buffer was reset meanwhile. */
    @Synchronized
    fun read(f: FrameRef, generation: Int, dst: ByteArray): Boolean {
        if (generation != this.generation || arenas.isEmpty()) return false
        view(f.offset, f.size).get(dst, 0, f.size)
        return true
    }

    // ------------------------------------------------------------- live readers (delayed view)

    /** Copies a frame into [dst] if it is still stored. Returns the size, or -1 if it is gone. */
    @Synchronized
    fun readInto(f: FrameRef, generation: Int, dst: ByteBuffer): Int {
        if (generation != this.generation || arenas.isEmpty()) return -1
        if (frames.isEmpty() || f.ptsUs < frames.first().ptsUs) return -1
        if (dst.remaining() < f.size) return -1
        dst.put(view(f.offset, f.size))
        return f.size
    }

    /** The newest key frame that arrived at or before [arrivalNs]. */
    @Synchronized
    fun keyFrameArrivedBy(arrivalNs: Long): FrameRef? {
        var result: FrameRef? = null
        for (f in frames) {
            if (f.arrivalNs > arrivalNs) break
            if (f.isKey) result = f
        }
        return result
    }

    @Synchronized
    fun oldestArrivalNs(): Long? = frames.firstOrNull()?.arrivalNs

    /** The first frame with a timestamp greater than [ptsUs]. */
    @Synchronized
    fun nextAfter(ptsUs: Long): FrameRef? {
        var lo = 0
        var hi = frames.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (frames[mid].ptsUs > ptsUs) {
                ans = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return if (ans >= 0) frames[ans] else null
    }

    companion object {
        /** Size of one shared-memory segment (one block can't exceed 2 GB). */
        const val SEGMENT = 256 shl 20
    }
}

object Mp4Writer {
    /**
     * Writes a pinned snapshot into [muxer] (copying samples, no re-encoding) and releases the muxer.
     * Returns the timestamps (relative to the clip start) and key-frame flags of all frames.
     */
    fun write(snapshot: ReplaySnapshot, muxer: MediaMuxer, rotationDegrees: Int): ClipIndex {
        var started = false
        try {
            muxer.setOrientationHint(rotationDegrees)
            val track = muxer.addTrack(snapshot.format)
            muxer.start()
            started = true
            val info = MediaCodec.BufferInfo()
            val tmp = ByteArray(snapshot.maxFrameSize)
            val pts = LongArray(snapshot.frames.size)
            val keys = BooleanArray(snapshot.frames.size)
            var last = -1L
            snapshot.frames.forEachIndexed { i, f ->
                if (!snapshot.read(f, tmp)) error("The buffer was reset while saving.")
                var rel = f.ptsUs - snapshot.startPtsUs
                if (rel <= last) rel = last + 1
                last = rel
                pts[i] = rel
                keys[i] = f.isKey
                info.set(0, f.size, rel, if (f.isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track, ByteBuffer.wrap(tmp, 0, f.size), info)
            }
            muxer.stop()
            started = false
            return ClipIndex(pts, keys)
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    fun write(snapshot: ReplaySnapshot, file: File, rotationDegrees: Int): ClipIndex {
        file.delete()
        return write(snapshot, MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), rotationDegrees)
    }
}

