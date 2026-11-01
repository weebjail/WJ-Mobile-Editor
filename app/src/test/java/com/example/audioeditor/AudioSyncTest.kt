package com.example.audioeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class AudioSyncTest {
    /** A loudness curve with random bursts (speech-like), at the envelope rate. */
    private fun bursts(n: Int, seed: Int): FloatArray {
        val r = Random(seed)
        val x = FloatArray(n) { 0.01f }
        var i = 0
        while (i < n) {
            val len = 20 + r.nextInt(100)
            val level = 0.1f + r.nextFloat() * 0.8f
            for (j in i until minOf(n, i + len)) x[j] = level * (1f - (j - i).toFloat() / len)
            i += len + r.nextInt(150)
        }
        return x
    }

    @Test fun findsCameraInsideLongRecording() {
        val rate = AudioSync.ENV_RATE
        val rec = bursts(600 * rate, 1)                      // 10 minute recorder file
        val offset = 123 * rate + 37                         // camera started 123.148 s in
        val rnd = Random(7)
        // Camera: same sound, quieter, with noise (different mic and auto-gain).
        val cam = FloatArray(40 * rate) { rec[offset + it] * 0.3f + rnd.nextFloat() * 0.02f }
        val m = AudioSync.findOffset(AudioSync.onsets(cam), AudioSync.onsets(rec))
        assertNotNull(m)
        val expected = offset.toLong() * AudioSync.ENV_BLOCK
        assertEquals(0.0, abs(m!!.lagFrames - expected).toDouble(), AudioSync.ENV_BLOCK.toDouble())
    }

    @Test fun rejectsUnrelatedAudio() {
        val rate = AudioSync.ENV_RATE
        val m = AudioSync.findOffset(
            AudioSync.onsets(bursts(30 * rate, 3)),
            AudioSync.onsets(bursts(300 * rate, 4)),
        )
        assertNull(m)
    }
}
