package com.example.audioeditor

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs

private class NotHandledAsWav(msg: String) : IOException(msg)

/**
 * Turns any supported audio file into 48 kHz stereo float PCM stored in a cache file.
 * WAV files are read by our own parser (8/16/24/32-bit integer and 32/64-bit float, incl. RF64
 * and WAVE_FORMAT_EXTENSIBLE). Everything else (MP3, AAC/M4A, FLAC, OGG, Opus, ...) goes through
 * Android's built-in decoders.
 */
object AudioDecoder {

    private class DecodeResult(
        val frames: Long, val sampleRate: Int, val channels: Int, val bitDepth: Int, val peaks: FloatArray,
    )

    fun decode(context: Context, uri: Uri, id: Long, cacheDir: File): AudioSource {
        val name = displayName(context, uri)
        val outFile = File(cacheDir, "$id.f32")
        val result = try {
            context.contentResolver.openInputStream(uri)?.use { decodeWav(it, outFile) }
                ?: throw IOException("Couldn't open $name")
        } catch (e: NotHandledAsWav) {
            decodeWithMediaCodec(context, uri, outFile)
        }
        if (result.frames <= 0) throw IOException("$name contains no audio")
        val mapped = RandomAccessFile(outFile, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }
        val pcm = mapped.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return AudioSource(
            id, uri, name, result.frames, result.sampleRate, result.channels,
            result.bitDepth, pcm, result.peaks,
        )
    }

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) c.getString(i)?.let { return it }
                    }
                }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "audio"
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

    private fun decodeWithMediaCodec(context: Context, uri: Uri, outFile: File): DecodeResult {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var trackIndex = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; break
                }
            }
            if (trackIndex < 0) throw IOException("This file has no audio the phone can read")
            ex.selectTrack(trackIndex)
            val inFormat = ex.getTrackFormat(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val origRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val origChannels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var sampleRate = origRate
            var channels = origChannels
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(inFormat, null, null, 0)
                codec.start()
                var sink: StereoSink? = null
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
        // Simple linear-interpolation resampler (see NOTES.md: worth upgrading later).
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

    fun peaks(): FloatArray = peaks.copyOf(peakCount)
}
