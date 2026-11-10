package com.example.audioeditor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class WavPcmTest {
    private val files = mutableListOf<File>()

    @After fun cleanup() { files.forEach { it.delete() } }

    // ------------------------------------------------------------ WAV writing helpers

    private fun le16(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
    private fun le32(o: ByteArrayOutputStream, v: Long) { for (i in 0 until 4) o.write(((v shr (8 * i)) and 0xFF).toInt()) }
    private fun le64(o: ByteArrayOutputStream, v: Long) { for (i in 0 until 8) o.write(((v shr (8 * i)) and 0xFF).toInt()) }
    private fun id(o: ByteArrayOutputStream, s: String) = o.write(s.toByteArray(Charsets.US_ASCII))

    /** Encodes [samples] (interleaved, -1..1) the way the given WAV format stores them, rounding them to what it can hold. */
    private fun encode(samples: DoubleArray, format: Int, bits: Int): ByteArray {
        if (format != 1 && format != 3) return ByteArray(samples.size * bits / 8)   // e.g. ADPCM: content irrelevant
        val b = ByteBuffer.allocate(samples.size * bits / 8).order(ByteOrder.LITTLE_ENDIAN)
        for ((i, s) in samples.withIndex()) when {
            format == 3 && bits == 32 -> { b.putFloat(s.toFloat()); samples[i] = s.toFloat().toDouble() }
            format == 3 -> b.putDouble(s)
            bits == 8 -> { val v = (s * 127).toInt(); b.put((v + 128).toByte()); samples[i] = v / 128.0 }
            bits == 16 -> { val v = (s * 32767).toInt(); b.putShort(v.toShort()); samples[i] = v / 32768.0 }
            bits == 24 -> {
                val v = (s * 8388607).toInt()
                b.put(v.toByte()); b.put((v shr 8).toByte()); b.put((v shr 16).toByte())
                samples[i] = v / 8388608.0
            }
            else -> { val v = (s * 2147483647.0).toInt(); b.putInt(v); samples[i] = v / 2147483648.0 }
        }
        return b.array()
    }

    private class Wav(val bytes: ByteArray, val samples: DoubleArray)

    private fun wav(
        frames: Int, channels: Int = 2, rate: Int = 48000, format: Int = 1, bits: Int = 16,
        extensible: Boolean = false, junkBeforeData: Boolean = false, rf64: Boolean = false,
        dataSizeField: Long? = null, trailing: ByteArray? = null, seed: Int = 1, timeReference: Long? = null,
    ): Wav {
        val r = Random(seed)
        val samples = DoubleArray(frames * channels) { (r.nextDouble() * 2 - 1) * 0.9 }
        val data = encode(samples, format, bits)
        val o = ByteArrayOutputStream()
        id(o, if (rf64) "RF64" else "RIFF"); le32(o, if (rf64) 0xFFFFFFFFL else 0L); id(o, "WAVE")
        if (rf64) { id(o, "ds64"); le32(o, 28); le64(o, 0); le64(o, data.size.toLong()); le64(o, frames.toLong()); le32(o, 0) }
        // Broadcast WAV chunk laid out like a Zoom recorder's: 858 bytes, TimeReference at byte 338.
        if (timeReference != null) { id(o, "bext"); le32(o, 858); val b = ByteArray(858); for (i in 0 until 8) b[338 + i] = (timeReference shr (8 * i)).toByte(); o.write(b) }
        if (junkBeforeData) { id(o, "LIST"); le32(o, 5); o.write(byteArrayOf(1, 2, 3, 4, 5)); o.write(0) } // odd size + pad byte
        id(o, "fmt ")
        if (extensible) {
            le32(o, 40); le16(o, 0xFFFE); le16(o, channels); le32(o, rate.toLong())
            le32(o, rate.toLong() * channels * bits / 8); le16(o, channels * bits / 8); le16(o, bits)
            le16(o, 22); le16(o, bits); le32(o, 3); le16(o, format)   // cbSize, valid bits, mask, subformat GUID start
            o.write(ByteArray(14))
        } else {
            le32(o, 16); le16(o, format); le16(o, channels); le32(o, rate.toLong())
            le32(o, rate.toLong() * channels * bits / 8); le16(o, channels * bits / 8); le16(o, bits)
        }
        id(o, "data"); le32(o, dataSizeField ?: if (rf64) 0xFFFFFFFFL else data.size.toLong())
        o.write(data)
        if (trailing != null) o.write(trailing)
        return Wav(o.toByteArray(), samples)
    }

    private fun open(w: Wav, maxChunkBytes: Long = 1L shl 30): WavPcm {
        val f = File.createTempFile("wavtest", ".wav").also { files += it }
        f.writeBytes(w.bytes)
        return RandomAccessFile(f, "r").use { WavPcm.open(it.channel, maxChunkBytes) }
    }

    /** Decoded values must match the stored ones (up to float precision). */
    private val EXACT = 1e-7

    private fun assertMatches(w: Wav, p: PcmData, channels: Int, t: Double) {
        val frames = w.samples.size / channels
        assertEquals(frames.toLong(), p.frames)
        for (f in 0 until frames) {
            val l = w.samples[f * channels]
            val r = if (channels > 1) w.samples[f * channels + 1] else l
            assertEquals("left $f", l, p.left(f.toLong()).toDouble(), t)
            assertEquals("right $f", r, p.right(f.toLong()).toDouble(), t)
        }
    }

    // ------------------------------------------------------------ Sample formats

    @Test fun allSampleFormatsReadExactly() {
        for ((format, bits) in listOf(1 to 8, 1 to 16, 1 to 24, 1 to 32, 3 to 32, 3 to 64)) {
            for (ch in listOf(1, 2, 6)) {
                val w = wav(3000, channels = ch, format = format, bits = bits)
                assertMatches(w, open(w), ch, EXACT)
            }
        }
    }

    @Test fun extensibleHeaderAndExtraChunks() {
        val w = wav(2000, format = 1, bits = 24, extensible = true, junkBeforeData = true)
        assertMatches(w, open(w), 2, EXACT)
    }

    @Test fun readsBroadcastWavTimecode() {
        val tr = 13L * 3600 * 96000 + 12345   // 13:00 at 96 kHz: needs more than 32 bits
        val w = wav(1500, bits = 24, timeReference = tr)
        val p = open(w)
        assertEquals(tr, p.layout.timeReference)
        assertMatches(w, p, 2, EXACT)                         // the extra chunk doesn't disturb the audio
        assertEquals(-1L, open(wav(10)).layout.timeReference)  // plain WAV: none
    }

    @Test fun timecodeLabels() {
        assertEquals("00:00:00:00", Exporters.timecode(0, 30))
        assertEquals("01:02:03:04", Exporters.timecode(((3600 + 120 + 3) * 25 + 4).toLong(), 25))
        assertEquals("13:31:30:29", Exporters.timecode((13L * 3600 + 31 * 60 + 30) * 30 + 29, 30))
    }

    @Test fun rf64UsesDs64SizeAndIgnoresTrailingChunks() {
        val trailing = ByteArray(1000) { 0x7F }   // e.g. an iXML chunk after the audio
        val w = wav(2500, rf64 = true, trailing = trailing)
        assertMatches(w, open(w), 2, EXACT)
    }

    @Test fun normalWavIgnoresTrailingChunks() {
        val w = wav(2500, trailing = ByteArray(999) { 0x55 })
        assertMatches(w, open(w), 2, EXACT)
    }

    @Test fun unknownDataSizeRunsToEndOfFile() {
        val w = wav(1234, dataSizeField = 0L)
        assertMatches(w, open(w), 2, EXACT)
    }

    @Test fun truncatedFileStopsAtLastWholeFrame() {
        val full = wav(1000)
        val cut = Wav(full.bytes.copyOf(full.bytes.size - 3), full.samples.copyOf((1000 - 1) * 2))  // 3 bytes = part of a frame
        assertMatches(cut, open(cut), 2, EXACT)
    }

    @Test fun framesSplitAcrossChunksReadCorrectly() {
        // 24-bit stereo = 6 bytes per frame; 1000-byte chunks force many chunk boundaries.
        val w = wav(5000, bits = 24)
        val p = open(w, maxChunkBytes = 1000)
        assertMatches(w, p, 2, EXACT)
        assertEquals(0f, p.left(5000))      // past the end is silent, not a crash
        assertEquals(0f, p.left(-1))
    }

    @Test fun rejectsFilesThatAreNotPlainWav() {
        for (bytes in listOf(ByteArray(0), "ID3\u0003hello".toByteArray(), wav(100, format = 2, bits = 4).bytes)) {
            val f = File.createTempFile("wavtest", ".bin").also { files += it }
            f.writeBytes(bytes)
            try {
                RandomAccessFile(f, "r").use { WavPcm.open(it.channel) }
                fail("should not be read as WAV")
            } catch (_: NotHandledAsWav) {
            }
        }
    }

    // ------------------------------------------------------------ Sample-rate conversion

    @Test fun converts44100To48000ByLinearInterpolation() {
        val w = wav(44100, rate = 44100, format = 3, bits = 32)   // 1 second
        val p = open(w)
        // Source frames 0..44099 cover output frames n with n * 44100/48000 <= 44099, i.e. 0..47998.
        assertEquals(47999L, p.frames)
        assertEquals(framesAt48k(44100, 44100), p.frames)
        val step = 44100.0 / 48000
        for (n in 0 until p.frames) {
            val pos = n * step
            val i = pos.toInt()
            val f = pos - i
            val a = w.samples[i * 2]
            val b = if (i + 1 < 44100) w.samples[(i + 1) * 2] else a
            assertEquals("frame $n", a + (b - a) * f, p.left(n).toDouble(), 1e-5)
        }
    }

    @Test fun frameCountsForCommonRates() {
        assertEquals(48000L, framesAt48k(48000, 48000))
        assertEquals(48000L, framesAt48k(96000, 96000))   // (96000-1)/2 + 1
        assertEquals(0L, framesAt48k(0, 44100))
        assertEquals(1L, framesAt48k(1, 44100))
        // A 3-hour recording at 44.1 kHz: no overflow, length within one frame of the exact value.
        val n = 3L * 3600 * 44100
        assertTrue(abs(framesAt48k(n, 44100) - 3L * 3600 * 48000) <= 1)
    }

    // ------------------------------------------------------------ Waveform overview

    @Test fun peaksAtNativeRate() {
        val w = wav(256 * 10 + 7, format = 3, bits = 32)
        val p = open(w)
        val peaks = p.scanPeaks()
        assertEquals(11, peaks.size)
        for (b in peaks.indices) {
            var m = 0.0
            for (f in b * 256 until minOf((b + 1) * 256, 256 * 10 + 7)) m = maxOf(m, abs(w.samples[f * 2]), abs(w.samples[f * 2 + 1]))
            assertEquals(m, peaks[b].toDouble(), 1e-6)
        }
    }

    @Test fun peaksMatchLengthAndShapeAt44100() {
        // A loud second half: the overview must show the jump at the right place.
        val frames = 44100
        val o = ByteArrayOutputStream()
        val samples = DoubleArray(frames * 2) { i -> val f = i / 2; (if (f < frames / 2) 0.1 else 0.8) * sin(f * 0.05) }
        val base = wav(frames, rate = 44100, format = 3, bits = 32)
        val data = encode(samples, 3, 32)
        o.write(base.bytes, 0, base.bytes.size - data.size); o.write(data)
        val p = open(Wav(o.toByteArray(), samples))
        val peaks = p.scanPeaks()
        assertEquals(((p.frames + 255) / 256).toInt(), peaks.size)
        val jump = peaks.indexOfFirst { it > 0.5f }
        assertEquals(24000.0 / 256, jump.toDouble(), 1.5)    // half-way at 48 kHz
    }
}
