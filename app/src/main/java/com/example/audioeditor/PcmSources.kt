package com.example.audioeditor

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

/**
 * 48 kHz stereo audio, read on demand from the original media file. Nothing is copied into the app.
 * Frame positions are always in 48 kHz frames. Implementations are safe to use from several threads.
 */
interface PcmData {
    val frames: Long
    fun left(frame: Long): Float
    fun right(frame: Long): Float

    /** How many times part of the audio couldn't be read (it plays as silence). */
    val readErrors: Int get() = 0
}

/** Number of 48 kHz frames produced from [srcFrames] frames at [srcRate]: output frame n sits at source position n * step. */
internal fun framesAt48k(srcFrames: Long, srcRate: Int): Long {
    if (srcRate == SAMPLE_RATE || srcFrames < 2) return srcFrames
    val step = srcRate.toDouble() / SAMPLE_RATE
    return ((srcFrames - 1) / step).toLong() + 1
}

/** Audio stored at its own sample rate, converted to 48 kHz (linear interpolation) as it's read. */
internal abstract class ResampledPcm(val srcFrames: Long, val srcRate: Int) : PcmData {
    private val resample = srcRate != SAMPLE_RATE
    private val step = srcRate.toDouble() / SAMPLE_RATE
    final override val frames: Long = framesAt48k(srcFrames, srcRate)

    /** Sample of channel [ch] (0 = left, 1 = right) at source frame [frame]; 0 outside the audio. */
    abstract fun sourceSample(frame: Long, ch: Int): Float

    final override fun left(frame: Long): Float = sample(frame, 0)
    final override fun right(frame: Long): Float = sample(frame, 1)

    private fun sample(frame: Long, ch: Int): Float {
        if (!resample) return sourceSample(frame, ch)
        val pos = frame * step
        val i = pos.toLong()
        val f = (pos - i).toFloat()
        val a = sourceSample(i, ch)
        if (f == 0f || i + 1 >= srcFrames) return a
        return a + (sourceSample(i + 1, ch) - a) * f
    }
}

/**
 * Builds the waveform overview (the loudest sample per [PEAK_BLOCK] frames at 48 kHz) from source-rate
 * samples, so files don't need resampling just to draw them.
 */
internal class PeakBuilder(srcRate: Int) {
    private val blockSrc = PEAK_BLOCK * srcRate.toDouble() / SAMPLE_RATE   // source frames per peak
    private var peaks = FloatArray(1024)
    private var count = 0
    private var index = 0L
    private var boundary = blockSrc
    private var max = 0f

    fun push(l: Float, r: Float) {
        if (index >= boundary) {
            add()
            boundary += blockSrc
        }
        val a = abs(l)
        val b = abs(r)
        if (a > max) max = a
        if (b > max) max = b
        index++
    }

    private fun add() {
        if (count == peaks.size) peaks = peaks.copyOf(count * 2)
        peaks[count++] = max
        max = 0f
    }

    /** The overview for [frames48k] frames of 48 kHz audio (one value per started block). */
    fun finish(frames48k: Long): FloatArray {
        if (index > 0) add()
        return peaks.copyOf(((frames48k + PEAK_BLOCK - 1) / PEAK_BLOCK).toInt())
    }
}

// ==================================================================== WAV, read in place

class NotHandledAsWav(msg: String) : IOException(msg)

/** Where the samples sit inside a WAV file, and how they're stored. */
internal class WavLayout(
    val format: Int, val bits: Int, val channels: Int, val sampleRate: Int,
    val dataOffset: Long, val dataSize: Long,   // dataSize is -1 when the header doesn't say
)

/**
 * A WAV file memory-mapped from where it is stored on the phone. Handles 8/16/24/32-bit integer and
 * 32/64-bit float samples, RF64 and WAVE_FORMAT_EXTENSIBLE. The mapping stays valid after the file is closed.
 */
internal class WavPcm private constructor(
    private val chunks: Array<ByteBuffer>, private val framesPerChunk: Long, srcFrames: Long,
    val layout: WavLayout,
) : ResampledPcm(srcFrames, layout.sampleRate) {
    private val bytesPerSample = layout.bits / 8
    private val frameBytes = bytesPerSample * layout.channels
    private val rightOffset = if (layout.channels > 1) bytesPerSample else 0
    private val kind = when {
        layout.format == 3 && layout.bits == 32 -> 4
        layout.format == 3 -> 5
        else -> bytesPerSample - 1          // 0..3 = 8/16/24/32-bit integer
    }

    override fun sourceSample(frame: Long, ch: Int): Float {
        if (frame < 0 || frame >= srcFrames) return 0f
        val b = chunks[(frame / framesPerChunk).toInt()]
        val o = ((frame % framesPerChunk) * frameBytes).toInt() + (if (ch == 0) 0 else rightOffset)
        return when (kind) {
            0 -> ((b.get(o).toInt() and 0xFF) - 128) / 128f
            1 -> b.getShort(o) / 32768f
            2 -> ((b.get(o).toInt() and 0xFF) or ((b.get(o + 1).toInt() and 0xFF) shl 8) or
                (b.get(o + 2).toInt() shl 16)) / 8388608f
            3 -> b.getInt(o) / 2147483648f
            4 -> b.getFloat(o)
            else -> b.getDouble(o).toFloat()
        }
    }

    /** One pass over the file for the waveform overview. */
    fun scanPeaks(): FloatArray {
        val p = PeakBuilder(srcRate)
        for (f in 0 until srcFrames) p.push(sourceSample(f, 0), sourceSample(f, 1))
        return p.finish(frames)
    }

    companion object {
        /**
         * Maps the sample data of an open WAV file. A single mapping can't exceed 2 GB, so the data is mapped
         * in chunks of at most [maxChunkBytes] that end on frame boundaries.
         */
        fun open(channel: FileChannel, maxChunkBytes: Long = 1L shl 30): WavPcm {
            channel.position(0)
            val layout = parseWav(java.nio.channels.Channels.newInputStream(channel).buffered(64 shl 10))
            val frameBytes = (layout.bits / 8) * layout.channels
            val available = channel.size() - layout.dataOffset
            val dataBytes = if (layout.dataSize < 0) available else minOf(layout.dataSize, available)
            val srcFrames = (dataBytes / frameBytes).coerceAtLeast(0L)
            val framesPerChunk = (maxChunkBytes / frameBytes).coerceAtLeast(1L)
            val count = ((srcFrames + framesPerChunk - 1) / framesPerChunk).toInt()
            val chunks = Array<ByteBuffer>(count) { i ->
                val first = i * framesPerChunk
                val n = minOf(framesPerChunk, srcFrames - first)
                channel.map(FileChannel.MapMode.READ_ONLY, layout.dataOffset + first * frameBytes, n * frameBytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
            }
            return WavPcm(chunks, framesPerChunk, srcFrames, layout)
        }

        /** Reads a WAV header up to the start of the sample data. Throws [NotHandledAsWav] for anything else. */
        fun parseWav(ins: InputStream): WavLayout {
            val header = ByteArray(12)
            if (readFully(ins, header, 12) < 12) throw NotHandledAsWav("too short")
            val riff = String(header, 0, 4, Charsets.US_ASCII)
            val wave = String(header, 8, 4, Charsets.US_ASCII)
            if ((riff != "RIFF" && riff != "RF64") || wave != "WAVE") throw NotHandledAsWav("not a WAV")

            var pos = 12L
            var format = -1
            var channels = 0
            var sampleRate = 0
            var bits = 0
            var ds64DataSize = -1L
            val chunkHeader = ByteArray(8)
            while (true) {
                if (readFully(ins, chunkHeader, 8) < 8) throw NotHandledAsWav("no data chunk")
                pos += 8
                val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = le32(chunkHeader, 4)
                val padded = size + (size and 1L)
                when (chunkId) {
                    "fmt " -> {
                        val fmt = readChunk(ins, size, 16)
                        format = le16(fmt, 0)
                        channels = le16(fmt, 2)
                        sampleRate = le32(fmt, 4).toInt()
                        bits = le16(fmt, 14)
                        if (format == 0xFFFE && fmt.size >= 26) format = le16(fmt, 24) // extensible
                        skipFully(ins, padded - size)
                        pos += padded
                    }
                    "ds64" -> {
                        // RF64 (files over 4 GB): the real data size lives here.
                        val ds = readChunk(ins, size, 24)
                        ds64DataSize = le32(ds, 8) or (le32(ds, 12) shl 32)
                        skipFully(ins, padded - size)
                        pos += padded
                    }
                    "data" -> {
                        val supported = (format == 1 && bits in intArrayOf(8, 16, 24, 32)) ||
                            (format == 3 && (bits == 32 || bits == 64))
                        // Anything unusual (ADPCM, mu-law...) is handed to Android's decoder instead.
                        if (!supported || channels < 1 || sampleRate <= 0) throw NotHandledAsWav("unsupported WAV")
                        val dataSize = when {
                            size == 0xFFFFFFFFL && ds64DataSize > 0 -> ds64DataSize
                            size == 0xFFFFFFFFL || size == 0L -> -1L   // unknown: runs to the end of the file
                            else -> size
                        }
                        return WavLayout(format, bits, channels, sampleRate, pos, dataSize)
                    }
                    else -> {
                        if (skipFully(ins, padded) < padded) throw NotHandledAsWav("no data chunk")
                        pos += padded
                    }
                }
            }
        }

        private fun readChunk(ins: InputStream, size: Long, min: Int): ByteArray {
            if (size < min || size > 4096) throw NotHandledAsWav("bad header chunk")
            val b = ByteArray(size.toInt())
            if (readFully(ins, b, b.size) < b.size) throw NotHandledAsWav("bad header chunk")
            return b
        }

        private fun readFully(ins: InputStream, buf: ByteArray, len: Int): Int {
            var total = 0
            while (total < len) {
                val n = ins.read(buf, total, len - total)
                if (n < 0) break
                total += n
            }
            return total
        }

        /** Returns how many bytes were actually skipped (less only at the end of the file). */
        private fun skipFully(ins: InputStream, count: Long): Long {
            var rem = count
            while (rem > 0) {
                val s = ins.skip(rem)
                if (s > 0) rem -= s
                else if (ins.read() == -1) break
                else rem--
            }
            return count - rem
        }

        private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        private fun le32(b: ByteArray, o: Int): Long =
            (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
                ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
    }
}

// ==================================================================== Compressed audio, decoded on demand

/** Index of the first audio track, or -1. */
internal fun audioTrackIndex(ex: MediaExtractor): Int =
    (0 until ex.trackCount).firstOrNull {
        ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    } ?: -1

/**
 * Wraps Android's decoder for one audio track. Each call to [next] returns one decoded buffer as
 * interleaved stereo floats in [out] (extra channels are dropped, mono is doubled).
 * Takes ownership of [ex]: it's released by [close], or straight away if the decoder can't be created.
 */
internal class TrackDecoder(private val ex: MediaExtractor, track: Int) : Closeable {
    private val info = MediaCodec.BufferInfo()
    private var fed = false          // any input given to the codec yet
    private var inputDone = false
    private var outputDone = false
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var channels = 1

    /** Sample rate of the decoded audio (may differ from the file's, e.g. HE-AAC). */
    var rate = 0; private set
    /** Timestamp of the buffer [next] returned last. */
    var ptsUs = 0L; private set
    var out = FloatArray(8192); private set

    private val codec: MediaCodec = try {
        if (track < 0) throw IOException("This file has no audio the phone can read")
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            c.configure(format, null, null, 0)
            c.start()
        } catch (e: Exception) {
            c.release()
            throw e
        }
        c
    } catch (e: Exception) {
        ex.release()
        throw e
    }

    /**
     * Restarts decoding at the last sync point at or before [timeUs]. A decoder that hasn't been given any
     * input yet isn't flushed: flushing that early would drop its setup data (codec-specific data), which
     * some decoders need to decode anything at all.
     */
    fun seekTo(timeUs: Long) {
        ex.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        if (fed) codec.flush()
        inputDone = false
        outputDone = false
    }

    /** Decodes the next buffer. Returns its frame count (> 0), or -1 at the end of the audio. */
    fun next(): Int {
        if (outputDone) return -1
        var idle = 0
        while (true) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(2_000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                        ex.advance()
                        fed = true
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 2_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else if (o >= 0) {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val n = convert(codec.getOutputBuffer(o)!!)
                codec.releaseOutputBuffer(o, false)
                if (eos) outputDone = true
                if (n > 0) { ptsUs = info.presentationTimeUs; return n }
                if (eos) return -1
                idle = 0
            } else if (++idle > 2_500) {
                throw IOException("The audio decoder stopped responding")   // ~10 s without any output
            }
        }
    }

    private fun convert(ob: ByteBuffer): Int {
        ob.position(info.offset)
        ob.limit(info.offset + info.size)
        ob.order(ByteOrder.nativeOrder())
        val bytes = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            else -> 2
        }
        val ch = channels.coerceAtLeast(1)
        val frames = info.size / (bytes * ch)
        if (out.size < frames * 2) out = FloatArray(frames * 2)
        val o = out
        var p = info.offset
        for (f in 0 until frames) {
            val l = sampleAt(ob, p)
            val r = if (ch > 1) sampleAt(ob, p + bytes) else l
            o[f * 2] = l
            o[f * 2 + 1] = r
            p += bytes * ch
        }
        return frames
    }

    private fun sampleAt(b: ByteBuffer, p: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> b.getFloat(p)
        AudioFormat.ENCODING_PCM_8BIT -> ((b.get(p).toInt() and 0xFF) - 128) / 128f
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> ((b.get(p).toInt() and 0xFF) or
            ((b.get(p + 1).toInt() and 0xFF) shl 8) or (b.get(p + 2).toInt() shl 16)) / 8388608f
        AudioFormat.ENCODING_PCM_32BIT -> b.getInt(p) / 2147483648f
        else -> b.getShort(p) / 32768f
    }

    override fun close() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
        ex.release()
    }
}

/**
 * Where decoding can resume exactly: the decoded buffer stamped [ptsUs] starts at source frame [frame].
 * Recorded every quarter second during the first pass over a file.
 */
internal class SeekPoints(val ptsUs: LongArray, val frames: LongArray) {
    val size get() = ptsUs.size

    /** Index of the last point at or before source frame [frame] (0 if none). */
    fun indexAtFrame(frame: Long): Int {
        var lo = 0
        var hi = frames.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (frames[mid] <= frame) lo = mid else hi = mid - 1
        }
        return lo
    }
}

/** Result of the first pass over a compressed file. */
internal class ScanResult(val srcRate: Int, val srcFrames: Long, val points: SeekPoints, val peaks: FloatArray)

/**
 * Compressed audio (MP3, AAC/M4A, FLAC, OGG, Opus, video soundtracks...) decoded straight from the
 * original file whenever it's needed. Decoded audio is kept in a small in-memory cache of blocks, and
 * the next block is decoded in the background while the current one plays.
 *
 * Jumping to a position restarts the decoder at the seek point one before the one needed and decodes
 * forward until it reaches the exact buffer the first pass recorded. That warm-up makes the output
 * identical to decoding the whole file from the start, so positions are sample-accurate.
 */
internal class DecodedPcm(
    private val openExtractor: () -> MediaExtractor,
    srcRate: Int, srcFrames: Long,
    private val points: SeekPoints,
) : ResampledPcm(srcFrames, srcRate) {

    private class Block(val index: Long, val data: FloatArray)

    // Two locks: reading the block cache never waits for a decode in progress.
    private val decodeLock = ReentrantLock()
    private val blocks = object : LinkedHashMap<Long, Block>(CACHED_BLOCKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Block>?) = size > CACHED_BLOCKS
    }
    @Volatile private var hot: Block? = null
    private val prefetching = AtomicBoolean(false)

    @Volatile override var readErrors = 0; private set

    // Decoder state (guarded by decodeLock). decPos is the source frame of out[curOff].
    private var dec: TrackDecoder? = null
    private var decPos = 0L
    private var curOff = 0
    private var curN = 0

    override fun sourceSample(frame: Long, ch: Int): Float {
        if (frame < 0 || frame >= srcFrames) return 0f
        val bi = frame ushr BLOCK_SHIFT
        var b = hot
        if (b == null || b.index != bi) {
            b = block(bi)
            hot = b
            prefetch(bi + 1)
        }
        return b.data[(((frame and BLOCK_MASK) shl 1) + ch).toInt()]
    }

    private fun cached(index: Long): Block? = synchronized(blocks) { blocks[index] }

    private fun block(index: Long): Block = cached(index) ?: decodeLock.withLock { cached(index) ?: load(index) }

    /** Decodes block [index] (holding decodeLock). A failure gives a silent block that isn't kept. */
    private fun load(index: Long): Block {
        val start = index shl BLOCK_SHIFT
        val end = minOf(start + BLOCK_FRAMES, srcFrames)
        val data = FloatArray(((end - start) * 2).toInt())
        return try {
            decodeRange(start, end, data)
            Block(index, data).also { synchronized(blocks) { blocks[index] = it } }
        } catch (e: Exception) {
            // A file that can't be read right now (moved, damaged) plays as silence instead of crashing.
            // Nothing is cached, so it's tried again, with a fresh decoder, when this part is next needed.
            closeDecoder()
            readErrors++
            android.util.Log.w("DecodedPcm", "Decoding failed at frame $start", e)
            Block(index, data)
        }
    }

    /** Decodes the block after the one being played on the background thread, unless it's busy. */
    private fun prefetch(index: Long) {
        if ((index shl BLOCK_SHIFT) >= srcFrames || cached(index) != null) return
        if (!prefetching.compareAndSet(false, true)) return
        DecoderPool.background.execute {
            try {
                if (decodeLock.tryLock()) try {
                    if (cached(index) == null && dec != null && decPos == index shl BLOCK_SHIFT) load(index)
                } finally { decodeLock.unlock() }
            } finally {
                prefetching.set(false)
            }
        }
    }

    private fun decodeRange(start: Long, end: Long, data: FloatArray) {
        var d = dec
        if (d == null || decPos > start || start - decPos > FORWARD_LIMIT) d = reposition(start)
        DecoderPool.touch(this)
        var filled = start
        while (filled < end) {
            if (curOff >= curN) {
                val n = d.next()
                if (n < 0) {
                    // Inexact files can come up a few frames short; anything more means the file changed.
                    if (srcFrames - filled > srcRate / 10) throw IOException("The file ended earlier than expected")
                    break
                }
                curOff = 0
                curN = n
            }
            if (decPos < filled) {                       // before the range: skip
                val skip = minOf((curN - curOff).toLong(), filled - decPos).toInt()
                curOff += skip; decPos += skip
                continue
            }
            val k = minOf((curN - curOff).toLong(), end - filled).toInt()
            System.arraycopy(d.out, curOff * 2, data, ((filled - start) * 2).toInt(), k * 2)
            curOff += k; decPos += k; filled += k
        }
    }

    /** Positions the decoder so that decPos <= [target], with output identical to a decode from the start. */
    private fun reposition(target: Long): TrackDecoder {
        val k = points.indexAtFrame(target)
        if (k == 0) {
            // From the very start: a fresh decoder, exactly like the first pass.
            closeDecoder()
            val d = openDecoder()
            decPos = 0
            return d
        }
        val d = dec ?: openDecoder()
        d.seekTo(points.ptsUs[k - 1])                   // one point early (a quarter second of warm-up)
        curOff = 0; curN = 0
        val wantPts = points.ptsUs[k]
        while (true) {
            val n = d.next()
            if (n < 0) throw IOException("Seek point not found")
            if (d.ptsUs < wantPts) continue
            curN = n
            decPos = if (d.ptsUs == wantPts) points.frames[k] else {
                // The file's timestamps after a seek don't match the first pass (some VBR MP3s).
                // Estimate from the timestamp (within a few milliseconds), never past the target.
                (points.frames[k] + (d.ptsUs - wantPts) * srcRate / 1_000_000).coerceAtMost(target)
            }
            return d
        }
    }

    private fun openDecoder(): TrackDecoder {
        val ex = openExtractor()
        val d = TrackDecoder(ex, audioTrackIndex(ex))
        dec = d
        curOff = 0; curN = 0
        DecoderPool.opened(this)
        return d
    }

    private fun closeDecoder() {
        dec?.let { d ->
            dec = null
            curOff = 0; curN = 0
            try { d.close() } catch (_: Exception) {}
            DecoderPool.closed(this)
        }
    }

    /** Frees the decoder (it reopens when needed) unless another thread is using it right now. */
    fun closeIfIdle() {
        if (decodeLock.tryLock()) try { closeDecoder() } finally { decodeLock.unlock() }
    }

    companion object {
        const val BLOCK_SHIFT = 14                       // 16384 frames (about 1/3 s) per block
        const val BLOCK_FRAMES = 1L shl BLOCK_SHIFT
        private const val BLOCK_MASK = BLOCK_FRAMES - 1
        private const val CACHED_BLOCKS = 24             // about 3 MB per source
        private const val FORWARD_LIMIT = 4 * BLOCK_FRAMES   // decode forward rather than seek for short jumps

        /** The first pass: decodes the whole track once for its length, seek points and waveform. */
        fun scan(ex: MediaExtractor, track: Int): ScanResult = TrackDecoder(ex, track).use { d ->
            var frames = 0L
            val pts = ArrayList<Long>()
            val at = ArrayList<Long>()
            var peaks: PeakBuilder? = null
            var rate = 0
            while (true) {
                val n = d.next()
                if (n < 0) break
                if (peaks == null) { rate = d.rate; peaks = PeakBuilder(rate) }
                if (at.isEmpty() || (frames - at.last() >= rate / 4 && d.ptsUs > pts.last())) {
                    pts += d.ptsUs
                    at += frames
                }
                val o = d.out
                for (f in 0 until n) peaks.push(o[f * 2], o[f * 2 + 1])
                frames += n
            }
            if (peaks == null) throw IOException("The decoder produced no audio")
            ScanResult(rate, frames, SeekPoints(pts.toLongArray(), at.toLongArray()), peaks.finish(framesAt48k(frames, rate)))
        }
    }
}

/**
 * Keeps the number of open decoders down (phones only allow a limited number). When too many are open the
 * least recently used is closed, and decoders unused for a while are closed too; they reopen when needed.
 */
internal object DecoderPool {
    const val MAX_OPEN = 8
    private const val IDLE_MS = 30_000L
    private val open = LinkedHashMap<DecodedPcm, Long>()   // least recently used first, with last use time

    /** One background thread, for reading ahead and closing idle decoders. */
    val background: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "audio-decode").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
    }.apply {
        scheduleWithFixedDelay({ closeIdle() }, 10, 10, TimeUnit.SECONDS)
    }

    fun opened(p: DecodedPcm) {
        val victims = synchronized(this) {
            open.remove(p); open[p] = System.currentTimeMillis()
            open.keys.take((open.size - MAX_OPEN).coerceAtLeast(0))
        }
        victims.forEach { it.closeIfIdle() }   // outside our lock: never wait on two locks at once
    }

    fun touch(p: DecodedPcm) = synchronized(this) {
        if (open.remove(p) != null) open[p] = System.currentTimeMillis()
    }

    fun closed(p: DecodedPcm) = synchronized(this) { open.remove(p) }

    private fun closeIdle() {
        val cutoff = System.currentTimeMillis() - IDLE_MS
        val idle = synchronized(this) { open.filterValues { it < cutoff }.keys.toList() }
        idle.forEach { it.closeIfIdle() }
    }

    val openCount: Int get() = synchronized(this) { open.size }
}
