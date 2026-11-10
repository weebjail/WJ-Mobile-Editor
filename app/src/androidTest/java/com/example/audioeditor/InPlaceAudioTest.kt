package com.example.audioeditor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Runs on a phone or emulator: checks that media is read in place (nothing copied into the app) and that
 * on-demand decoding of compressed audio is sample-accurate however it's accessed.
 */
@RunWith(AndroidJUnit4::class)
class InPlaceAudioTest {

    companion object {
        private lateinit var ctx: Context
        private lateinit var dir: File
        private lateinit var aac48: File
        private lateinit var aac44: File
        private lateinit var aacMono: File
        private lateinit var signal48: ShortArray

        private const val SECONDS = 40

        @BeforeClass @JvmStatic fun makeFiles() {
            ctx = InstrumentationRegistry.getInstrumentation().targetContext
            dir = File(ctx.filesDir, "inplace-test").apply { deleteRecursively(); mkdirs() }
            File(ctx.cacheDir, "media").deleteRecursively()
            signal48 = signal(48000, 2, SECONDS, seed = 1)
            aac48 = File(dir, "a48.m4a").also { encodeAac(signal48, 48000, 2, it) }
            aac44 = File(dir, "a44.m4a").also { encodeAac(signal(44100, 2, SECONDS, seed = 2), 44100, 2, it) }
            aacMono = File(dir, "mono.m4a").also { encodeAac(signal(48000, 1, 10, seed = 3), 48000, 1, it) }
        }

        @AfterClass @JvmStatic fun cleanup() { dir.deleteRecursively() }

        /** Speech-like test audio: tones that change every 50 ms plus noise bursts, so every position is distinct. */
        private fun signal(rate: Int, channels: Int, seconds: Int, seed: Int): ShortArray {
            val r = Random(seed)
            val frames = rate * seconds
            val out = ShortArray(frames * channels)
            var freq = 300.0
            var phase = 0.0
            for (f in 0 until frames) {
                if (f % (rate / 20) == 0) freq = 150.0 + r.nextDouble() * 3000
                phase += 2 * PI * freq / rate
                val burst = if ((f / (rate / 8)) % 3 == 0) (r.nextDouble() - 0.5) * 0.3 else 0.0
                for (c in 0 until channels) {
                    val v = 0.4 * sin(phase + c) + burst
                    out[f * channels + c] = (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            return out
        }

        private fun encodeAac(pcm: ShortArray, rate: Int, channels: Int, out: File) {
            val mime = MediaFormat.MIMETYPE_AUDIO_AAC
            val fmt = MediaFormat.createAudioFormat(mime, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val enc = MediaCodec.createEncoderByType(mime)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var inPos = 0
            var inputDone = false
            var frames = 0L
            while (true) {
                if (!inputDone) {
                    val i = enc.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = enc.getInputBuffer(i)!!
                        buf.clear()
                        val n = minOf(buf.capacity() / 2 / channels * channels, pcm.size - inPos)
                        if (n == 0) {
                            enc.queueInputBuffer(i, 0, 0, frames * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, inPos, n)
                            enc.queueInputBuffer(i, 0, n * 2, frames * 1_000_000 / rate, 0)
                            inPos += n
                            frames += n / channels
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(enc.outputFormat)
                    mux.start()
                } else if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) mux.writeSampleData(track, enc.getOutputBuffer(o)!!, info)
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            enc.stop(); enc.release()
            mux.stop(); mux.release()
        }

        /** The whole track decoded straight through from the start, at its own rate: the reference. */
        private fun referenceDecode(file: File): FloatArray {
            val ex = MediaExtractor().apply { setDataSource(file.path) }
            return TrackDecoder(ex, audioTrackIndex(ex)).use { d ->
                var data = FloatArray(1 shl 20)
                var n = 0
                while (true) {
                    val k = d.next()
                    if (k < 0) break
                    if (n + k * 2 > data.size) data = data.copyOf(maxOf(data.size * 2, n + k * 2))
                    System.arraycopy(d.out, 0, data, n, k * 2)
                    n += k * 2
                }
                data.copyOf(n)
            }
        }

        private fun open(file: File, id: Long = 1): AudioSource = AudioDecoder.decode(ctx, Uri.fromFile(file), id)
    }

    // ------------------------------------------------------------ Compressed audio

    /** Reads [len] source frames at [start] and checks them against [ref], bit for bit. */
    private fun check(p: DecodedPcm, ref: FloatArray, start: Long, len: Int, what: String) {
        for (f in start until minOf(start + len, p.srcFrames)) {
            val i = (f * 2).toInt()
            if (p.sourceSample(f, 0) != ref[i] || p.sourceSample(f, 1) != ref[i + 1]) {
                throw AssertionError("$what: frame $f differs (got ${p.sourceSample(f, 0)}, expected ${ref[i]})")
            }
        }
    }

    @Test fun lengthMatchesAFullDecode() {
        for (file in listOf(aac48, aac44, aacMono)) {
            val ref = referenceDecode(file)
            val src = open(file)
            val p = src.pcm as DecodedPcm
            assertEquals(file.name, (ref.size / 2).toLong(), p.srcFrames)
            assertEquals(framesAt48k(p.srcFrames, p.srcRate), src.pcm!!.frames)
            assertEquals(((src.pcm!!.frames + PEAK_BLOCK - 1) / PEAK_BLOCK).toInt(), src.peaks.size)
        }
    }

    @Test fun randomJumpsGiveIdenticalSamples() {
        for (file in listOf(aac48, aac44, aacMono)) {
            val ref = referenceDecode(file)
            val p = open(file).pcm as DecodedPcm
            val r = Random(42)
            val n = p.srcFrames
            // Random places, backwards jumps, block edges, the very start and the very end.
            val starts = List(150) { r.nextLong(n) } +
                listOf(0L, 1L, n - 1, n - 5000, DecodedPcm.BLOCK_FRAMES - 3, 7 * DecodedPcm.BLOCK_FRAMES - 1) +
                List(20) { (n - it * 48000L).coerceAtLeast(0) }
            for (s in starts) check(p, ref, s, 2500, "${file.name} @ $s")
        }
    }

    @Test fun readingStraightThroughMatches() {
        val ref = referenceDecode(aac44)
        val p = open(aac44).pcm as DecodedPcm
        check(p, ref, 0, Int.MAX_VALUE, "sequential")
    }

    @Test fun matchesTheOriginalSound() {
        // AAC is lossy, so compare with the original via correlation (allowing for the encoder delay).
        val pcm = open(aac48).pcm!!
        val start = 10 * 48000
        val len = 48000
        var best = 0.0
        for (lag in 0..3000) {
            var xy = 0.0; var xx = 0.0; var yy = 0.0
            for (i in 0 until len step 4) {
                val x = signal48[(start + i) * 2] / 32768.0
                val y = pcm.left((start + i + lag).toLong()).toDouble()
                xy += x * y; xx += x * x; yy += y * y
            }
            best = maxOf(best, xy / sqrt(xx * yy))
        }
        assertTrue("correlation $best", best > 0.95)
    }

    @Test fun severalThreadsAtOnce() {
        val ref = referenceDecode(aac48)
        val p = open(aac48).pcm as DecodedPcm
        val error = AtomicReference<Throwable?>()
        val threads = List(4) { t ->
            Thread {
                try {
                    val r = Random(t)
                    repeat(60) { check(p, ref, r.nextLong(p.srcFrames), 3000, "thread $t") }
                } catch (e: Throwable) { error.compareAndSet(null, e) }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        error.get()?.let { throw it }
    }

    @Test fun manySourcesShareALimitedNumberOfDecoders() {
        val ref = referenceDecode(aac48)
        val sources = (1..12).map { open(aac48, it.toLong()).pcm as DecodedPcm }
        val r = Random(5)
        repeat(80) {
            val p = sources[r.nextInt(sources.size)]
            check(p, ref, r.nextLong(p.srcFrames), 2000, "source")
            // One more may stay open for a moment while it is busy reading ahead in the background.
            assertTrue("open decoders ${DecoderPool.openCount}", DecoderPool.openCount <= DecoderPool.MAX_OPEN + 1)
        }
    }

    @Test fun reopeningUsesTheSavedSummary() {
        open(aacMono)
        val t0 = System.nanoTime()
        val again = open(aacMono, 2)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("reopen took $ms ms", ms < 1000)
        val ref = referenceDecode(aacMono)
        check(again.pcm as DecodedPcm, ref, 12345, 5000, "reopened")
    }

    @Test fun firstReadMidFileAndAfterTheDecoderWasClosed() {
        val ref = referenceDecode(aac48)
        repeat(5) { i ->
            val p = open(aac48, 100L + i).pcm as DecodedPcm
            val at = p.srcFrames / 7 * (i + 1)
            check(p, ref, at, 3000, "fresh decoder, first read at $at")
            p.closeIfIdle()                                    // as the pool does when idle
            check(p, ref, at / 2, 3000, "reopened decoder")
        }
    }

    @Test fun aMissingFileIsSilentAndReportedNotACrash() {
        val f = File(dir, "gone.m4a")
        aacMono.copyTo(f, overwrite = true)
        val p = open(f).pcm as DecodedPcm
        f.delete()
        assertEquals(0f, p.left(p.frames / 2))
        assertTrue(p.readErrors > 0)
    }

    @Test fun sameFileImportedSeveralTimesAtOnce() {
        File(ctx.cacheDir, "media").deleteRecursively()
        val results = arrayOfNulls<Result<AudioSource>>(4)
        List(4) { i -> Thread { results[i] = runCatching { open(aac44, 200L + i) } }.apply { start() } }.forEach { it.join() }
        val ref = referenceDecode(aac44)
        for (r in results) {
            val src = r!!.getOrThrow()
            check(src.pcm as DecodedPcm, ref, 54321, 2000, "parallel import")
        }
    }

    // ------------------------------------------------------------ WAV

    private fun writeWav(file: File, pcm: ShortArray, rate: Int, channels: Int) {
        val b = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size * 2); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(channels.toShort())
        b.putInt(rate); b.putInt(rate * channels * 2); b.putShort((channels * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(pcm.size * 2)
        for (s in pcm) b.putShort(s)
        file.writeBytes(b.array())
    }

    @Test fun wavIsReadInPlaceExactly() {
        val pcm = signal(48000, 2, 20, seed = 9)
        val f = File(dir, "rec.wav").also { writeWav(it, pcm, 48000, 2) }
        val src = open(f)
        assertTrue(src.pcm is WavPcm)
        assertEquals((pcm.size / 2).toLong(), src.frames)
        val r = Random(3)
        repeat(5000) {
            val n = r.nextInt(pcm.size / 2)
            assertEquals(pcm[n * 2] / 32768f, src.pcm!!.left(n.toLong()))
            assertEquals(pcm[n * 2 + 1] / 32768f, src.pcm!!.right(n.toLong()))
        }
    }

    @Test fun nothingIsCopiedIntoTheApp() {
        val wav = File(dir, "big.wav").also { writeWav(it, signal(48000, 2, 30, seed = 4), 48000, 2) }
        open(wav)
        open(aac48)
        val stored = File(ctx.cacheDir, "media").walk().filter { it.isFile }.sumOf { it.length() }
        val media = wav.length() + aac48.length()
        // Only the waveform summaries are kept: about 1/64 of the 48 kHz float audio, far less than the files.
        assertTrue("app stores $stored bytes for $media bytes of media", stored < media / 20)
        assertTrue(File(ctx.cacheDir, "media").listFiles()!!.all { it.name.endsWith(".meta") })
    }
}
