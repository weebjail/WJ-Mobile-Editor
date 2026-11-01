package com.example.audioeditor

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.math.abs

private class NotHandledAsWav(msg: String) : IOException(msg)

/**
 * Decoded 48 kHz stereo audio, memory-mapped from the cache file.
 * Mapped in 1 GB chunks because a single mapping can't exceed 2 GB (about 93 minutes of audio).
 */
class PcmData private constructor(private val chunks: Array<FloatBuffer>, val frames: Long) {
    fun left(frame: Long): Float = at(frame * 2)
    fun right(frame: Long): Float = at(frame * 2 + 1)

    private fun at(i: Long): Float = chunks[(i ushr CHUNK_SHIFT).toInt()].get((i and CHUNK_MASK).toInt())

    companion object {
        private const val CHUNK_SHIFT = 28                 // 2^28 floats = 1 GB per chunk
        private const val CHUNK_MASK = (1L shl CHUNK_SHIFT) - 1

        fun map(file: File, frames: Long): PcmData = RandomAccessFile(file, "r").use { raf ->
            val bytes = frames * 8
            val chunkBytes = 4L shl CHUNK_SHIFT
            val count = ((bytes + chunkBytes - 1) / chunkBytes).toInt()
            val chunks = Array(count) { i ->
                val offset = i * chunkBytes
                raf.channel.map(FileChannel.MapMode.READ_ONLY, offset, minOf(chunkBytes, bytes - offset))
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            }
            PcmData(chunks, frames)
        }
    }
}

/**
 * Turns any supported audio or video file into 48 kHz stereo float PCM stored in a cache file.
 * WAV files are read by our own parser (8/16/24/32-bit integer and 32/64-bit float, incl. RF64
 * and WAVE_FORMAT_EXTENSIBLE). Everything else (MP3, AAC/M4A, FLAC, OGG, Opus, and the audio of
 * MP4/MOV video) goes through Android's built-in decoders.
 *
 * Results are cached by file identity (URI + size + modified time), so reopening a project
 * doesn't decode everything again.
 */
object AudioDecoder {

    private const val META_VERSION = 2
    private const val CACHE_LIMIT_BYTES = 4L shl 30   // 4 GB

    private class DecodeResult(
        val frames: Long, val sampleRate: Int, val channels: Int, val bitDepth: Int, val peaks: FloatArray,
    )

    private class Meta(
        val audioFrames: Long, val mediaFrames: Long,
        val sampleRate: Int, val channels: Int, val bitDepth: Int,
        val peaks: FloatArray, val video: VideoInfo?,
    )

    private class ProbedVideo(val info: VideoInfo, val frames: Long)

    private class FileIdentity(val name: String, val size: Long, val modified: Long)

    private fun cacheDir(context: Context) = File(context.cacheDir, "media").apply { mkdirs() }

    fun decode(context: Context, uri: Uri, id: Long): AudioSource {
        val ident = identify(context, uri)
        val key = sha1("$uri|${ident.size}|${ident.modified}")
        val dir = cacheDir(context)
        val metaFile = File(dir, "$key.meta")
        val pcmFile = File(dir, "$key.f32")

        val meta = readMeta(metaFile)?.takeIf { it.audioFrames == 0L || pcmFile.length() == it.audioFrames * 8 }
            ?: decodeToCache(context, uri, ident.name, dir, key, metaFile, pcmFile)
        val now = System.currentTimeMillis()
        metaFile.setLastModified(now)
        pcmFile.setLastModified(now)

        val pcm = if (meta.audioFrames > 0) PcmData.map(pcmFile, meta.audioFrames) else null
        return AudioSource(
            id, uri, ident.name, meta.mediaFrames, meta.sampleRate, meta.channels, meta.bitDepth,
            pcm, meta.peaks, meta.video,
        )
    }

    /** Keeps the decode cache under its size limit, removing the least recently used files first. */
    fun trimCache(context: Context) {
        val files = cacheDir(context).listFiles() ?: return
        files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        val groups = files.filter { it.exists() }.groupBy { it.nameWithoutExtension }
            .values.sortedByDescending { g -> g.maxOf { it.lastModified() } }
        var total = 0L
        for (g in groups) {
            total += g.sumOf { it.length() }
            if (total > CACHE_LIMIT_BYTES) g.forEach { it.delete() }
        }
    }

    private fun decodeToCache(
        context: Context, uri: Uri, name: String, dir: File, key: String, metaFile: File, pcmFile: File,
    ): Meta {
        val video = probeVideo(context, uri)
        val tmp = File(dir, "$key.${System.nanoTime()}.tmp")
        try {
            val audio = try {
                context.contentResolver.openInputStream(uri)?.use { decodeWav(it, tmp) }
                    ?: throw IOException("Couldn't open $name")
            } catch (_: NotHandledAsWav) {
                decodeWithMediaCodec(context, uri, tmp, allowNoAudio = video != null)
            }
            val audioFrames = audio?.frames ?: 0L
            val mediaFrames = maxOf(audioFrames, video?.frames ?: 0L)
            if (mediaFrames <= 0) throw IOException("$name contains no audio")
            val meta = Meta(
                audioFrames, mediaFrames,
                audio?.sampleRate ?: 0, audio?.channels ?: 0, audio?.bitDepth ?: 0,
                audio?.peaks ?: FloatArray(0), video?.info,
            )
            if (audioFrames > 0) {
                pcmFile.delete()
                if (!tmp.renameTo(pcmFile)) throw IOException("Couldn't write the audio cache")
            }
            writeMeta(metaFile, meta)
            return meta
        } finally {
            tmp.delete()
        }
    }

    private fun identify(context: Context, uri: Uri): FileIdentity {
        var name: String? = null
        var size = -1L
        var modified = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        .takeIf { it >= 0 && !c.isNull(it) }?.let { modified = c.getLong(it) }
                }
            }
        } catch (_: Exception) {
        }
        return FileIdentity(name ?: uri.lastPathSegment ?: "media", size, modified)
    }

    fun displayName(context: Context, uri: Uri): String = identify(context, uri).name

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun writeMeta(file: File, m: Meta) {
        DataOutputStream(FileOutputStream(file).buffered()).use { o ->
            o.writeInt(META_VERSION)
            o.writeLong(m.audioFrames); o.writeLong(m.mediaFrames)
            o.writeInt(m.sampleRate); o.writeInt(m.channels); o.writeInt(m.bitDepth)
            o.writeBoolean(m.video != null)
            m.video?.let { o.writeInt(it.width); o.writeInt(it.height); o.writeDouble(it.fps) }
            o.writeInt(m.peaks.size)
            for (p in m.peaks) o.writeFloat(p)
        }
    }

    private fun readMeta(file: File): Meta? {
        if (!file.exists()) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { i ->
                if (i.readInt() != META_VERSION) return null
                val audioFrames = i.readLong()
                val mediaFrames = i.readLong()
                val rate = i.readInt(); val ch = i.readInt(); val bits = i.readInt()
                val video = if (i.readBoolean()) VideoInfo(i.readInt(), i.readInt(), i.readDouble()) else null
                val peaks = FloatArray(i.readInt()) { i.readFloat() }
                Meta(audioFrames, mediaFrames, rate, ch, bits, peaks, video)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- Video metadata

    private fun probeVideo(context: Context, uri: Uri): ProbedVideo? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") != true) continue
                var w = f.getInteger(MediaFormat.KEY_WIDTH)
                var h = f.getInteger(MediaFormat.KEY_HEIGHT)
                val rotation = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
                if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
                val fps = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    try { f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                    catch (_: ClassCastException) { f.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }
                } else 30.0
                val durUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                return ProbedVideo(VideoInfo(w, h, fps), durUs * SAMPLE_RATE / 1_000_000)
            }
            return null
        } catch (_: Exception) {
            return null
        } finally {
            ex.release()
        }
    }

    // ---------------------------------------------------------------- WAV

    private fun decodeWav(raw: InputStream, outFile: File): DecodeResult {
        val ins = raw.buffered(256 shl 10)
        val header = ByteArray(12)
        if (readFully(ins, header, 12) < 12) throw NotHandledAsWav("too short")
        val riff = String(header, 0, 4, Charsets.US_ASCII)
        val wave = String(header, 8, 4, Charsets.US_ASCII)
        if ((riff != "RIFF" && riff != "RF64") || wave != "WAVE") throw NotHandledAsWav("not a WAV")

        var format = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        val chunkHeader = ByteArray(8)
        while (true) {
            if (readFully(ins, chunkHeader, 8) < 8) throw NotHandledAsWav("no data chunk")
            val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val size = le32(chunkHeader, 4)
            when (chunkId) {
                "fmt " -> {
                    if (size < 16 || size > 4096) throw NotHandledAsWav("bad fmt chunk")
                    val fmt = ByteArray(size.toInt())
                    if (readFully(ins, fmt, fmt.size) < fmt.size) throw NotHandledAsWav("bad fmt chunk")
                    format = le16(fmt, 0)
                    channels = le16(fmt, 2)
                    sampleRate = le32(fmt, 4).toInt()
                    bits = le16(fmt, 14)
                    if (format == 0xFFFE && fmt.size >= 26) format = le16(fmt, 24) // extensible
                    if (size % 2 == 1L) skipFully(ins, 1)
                }
                "data" -> {
                    val supported = (format == 1 && bits in intArrayOf(8, 16, 24, 32)) ||
                        (format == 3 && (bits == 32 || bits == 64))
                    // Anything unusual (ADPCM, mu-law...) is handed to Android's decoder instead.
                    if (!supported || channels < 1 || sampleRate <= 0) throw NotHandledAsWav("unsupported WAV")
                    return readWavData(ins, outFile, size, format, bits, channels, sampleRate)
                }
                else -> skipFully(ins, size + (size and 1L))
            }
        }
    }

    private fun readWavData(
        ins: InputStream, outFile: File, size: Long, format: Int, bits: Int, channels: Int, sampleRate: Int,
    ): DecodeResult {
        val bytesPerSample = bits / 8
        val frameBytes = bytesPerSample * channels
        var remaining = if (size == 0xFFFFFFFFL || size == 0L) Long.MAX_VALUE else size
        val sink = StereoSink(sampleRate, outFile)
        try {
            val buf = ByteArray(frameBytes * 16384)
            while (remaining > 0) {
                var want = minOf(buf.size.toLong(), remaining).toInt()
                want -= want % frameBytes
                if (want == 0) break
                val n = readFully(ins, buf, want)
                val framesRead = n / frameBytes
                for (f in 0 until framesRead) {
                    val base = f * frameBytes
                    val l = sampleAt(buf, base, format, bits)
                    val r = if (channels > 1) sampleAt(buf, base + bytesPerSample, format, bits) else l
                    sink.push(l, r)
                }
                remaining -= n
                if (n < want) break
            }
            val frames = sink.finish()
            return DecodeResult(frames, sampleRate, channels, bits, sink.peaks())
        } catch (e: Exception) {
            sink.abort()
            throw e
        }
    }

    private fun sampleAt(b: ByteArray, o: Int, format: Int, bits: Int): Float = when {
        format == 3 && bits == 32 -> Float.fromBits(le32(b, o).toInt())
        format == 3 -> Double.fromBits(le64(b, o)).toFloat()
        bits == 8 -> ((b[o].toInt() and 0xFF) - 128) / 128f
        bits == 16 -> ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)) / 32768f
        bits == 24 -> ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            (b[o + 2].toInt() shl 16)) / 8388608f
        else -> le32(b, o).toInt() / 2147483648f
    }

    // ---------------------------------------------------------------- Other formats

    /** Returns null when the file has no audio track and [allowNoAudio] is set (a silent video). */
    private fun decodeWithMediaCodec(context: Context, uri: Uri, outFile: File, allowNoAudio: Boolean): DecodeResult? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var trackIndex = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; break
                }
            }
            if (trackIndex < 0) {
                if (allowNoAudio) return null
                throw IOException("This file has no audio the phone can read")
            }
            ex.selectTrack(trackIndex)
            val inFormat = ex.getTrackFormat(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val origRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val origChannels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var sampleRate = origRate
            var channels = origChannels
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            val codec = MediaCodec.createDecoderByType(mime)
            var sink: StereoSink? = null
            try {
                codec.configure(inFormat, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val b = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(b, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    } else if (o >= 0) {
                        val ob = codec.getOutputBuffer(o)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.nativeOrder())
                        val s = sink ?: StereoSink(sampleRate, outFile).also { sink = it }
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = ob.asFloatBuffer()
                            while (fb.remaining() >= channels) {
                                val l = fb.get()
                                val r = if (channels > 1) fb.get() else l
                                for (c in 2 until channels) fb.get()
                                s.push(l, r)
                            }
                        } else {
                            val sb = ob.asShortBuffer()
                            while (sb.remaining() >= channels) {
                                val l = sb.get() / 32768f
                                val r = if (channels > 1) sb.get() / 32768f else l
                                for (c in 2 until channels) sb.get()
                                s.push(l, r)
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
                val s = sink ?: throw IOException("The decoder produced no audio")
                val frames = s.finish()
                return DecodeResult(frames, origRate, origChannels, 16, s.peaks())
            } catch (e: Exception) {
                sink?.abort()
                throw e
            } finally {
                try { codec.stop() } catch (_: Exception) {}
                codec.release()
            }
        } finally {
            ex.release()
        }
    }

    // ---------------------------------------------------------------- Helpers

    private fun readFully(ins: InputStream, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = ins.read(buf, total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun skipFully(ins: InputStream, count: Long) {
        var rem = count
        while (rem > 0) {
            val s = ins.skip(rem)
            if (s > 0) rem -= s
            else if (ins.read() == -1) return
            else rem--
        }
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun le64(b: ByteArray, o: Int): Long = le32(b, o) or (le32(b, o + 4) shl 32)
}

/**
 * Receives stereo frames at the source's sample rate, converts them to 48 kHz,
 * writes them to the cache file and builds the waveform overview.
 */
private class StereoSink(srcRate: Int, file: File) {
    private val out = FileOutputStream(file).channel
    private val buf = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.LITTLE_ENDIAN)
    private val resample = srcRate != SAMPLE_RATE
    private val step = srcRate.toDouble() / SAMPLE_RATE
    private var pos = 0.0
    private var k = 0L
    private var prevL = 0f
    private var prevR = 0f
    private var frames = 0L
    private var peaks = FloatArray(1024)
    private var peakCount = 0
    private var blockMax = 0f
    private var inBlock = 0

    fun push(l: Float, r: Float) {
        if (!resample) { emit(l, r); return }
        // Linear-interpolation resampler. Fine for 44.1 kHz material; a windowed-sinc filter would
        // be cleaner when downsampling 88.2/96 kHz recordings.
        if (k == 0L) { prevL = l; prevR = r; k = 1; return }
        while (pos < k) {
            val f = (pos - (k - 1)).toFloat()
            emit(prevL + (l - prevL) * f, prevR + (r - prevR) * f)
            pos += step
        }
        prevL = l; prevR = r; k++
    }

    private fun emit(l: Float, r: Float) {
        if (buf.remaining() < 8) flush()
        buf.putFloat(l)
        buf.putFloat(r)
        frames++
        val m = maxOf(abs(l), abs(r))
        if (m > blockMax) blockMax = m
        if (++inBlock == PEAK_BLOCK) addPeak()
    }

    private fun addPeak() {
        if (peakCount == peaks.size) peaks = peaks.copyOf(peaks.size * 2)
        peaks[peakCount++] = blockMax
        blockMax = 0f
        inBlock = 0
    }

    private fun flush() {
        buf.flip()
        while (buf.hasRemaining()) out.write(buf)
        buf.clear()
    }

    fun finish(): Long {
        if (inBlock > 0) addPeak()
        flush()
        out.close()
        return frames
    }

    fun abort() {
        try { out.close() } catch (_: IOException) {}
    }

    fun peaks(): FloatArray = peaks.copyOf(peakCount)
}
