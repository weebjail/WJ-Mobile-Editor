package com.example.audioeditor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Waveform sync, like Resolve's "Auto Sync Audio Based on Waveform": finds where a camera's scratch audio
 * lines up inside a separate recorder's audio.
 *
 * Both signals are reduced to an "onset" envelope at [ENV_RATE] Hz (how suddenly the sound gets louder),
 * which ignores differences in microphone, level and camera auto-gain. The envelopes are then
 * cross-correlated with an FFT, which stays fast even for hours of recording.
 */
object AudioSync {
    const val ENV_RATE = 250
    const val ENV_BLOCK = SAMPLE_RATE / ENV_RATE   // 192 audio frames per envelope sample (4 ms)

    /** Minimum normalised correlation to accept a match. Unrelated audio typically scores below 0.15. */
    const val MIN_SCORE = 0.25

    /** [lagFrames]: where the camera clip's first frame falls, measured from the recorder clip's first frame. */
    data class Match(val lagFrames: Long, val score: Double)

    /** Onset envelope of [frames] frames of audio starting at [from]. */
    fun envelope(pcm: PcmData, from: Long, frames: Long): FloatArray {
        val avail = (pcm.frames - from).coerceAtLeast(0L)
        val n = (minOf(frames, avail) / ENV_BLOCK).toInt()
        val loud = FloatArray(n)
        var f = from
        for (i in 0 until n) {
            var s = 0f
            for (j in 0 until ENV_BLOCK) {
                s += abs(pcm.left(f)) + abs(pcm.right(f))
                f++
            }
            loud[i] = s / (2 * ENV_BLOCK)
        }
        return onsets(loud)
    }

    /** Turns a loudness curve into rises in log-loudness, with the mean removed. */
    fun onsets(loud: FloatArray): FloatArray {
        val n = loud.size
        val out = FloatArray(n)
        var prev = 0.0
        for (i in 0 until n) {
            val l = ln(1.0 + 1000.0 * loud[i])
            out[i] = if (i == 0) 0f else maxOf(0.0, l - prev).toFloat()
            prev = l
        }
        val mean = out.average().toFloat()
        for (i in 0 until n) out[i] -= mean
        return out
    }

    /**
     * Finds the offset of [cam] inside [rec] (both envelopes). Lags where the clips overlap by less than half
     * of the shorter one (and at least 2 s) are ignored, so a match can't come from a tiny sliver of audio.
     */
    fun findOffset(cam: FloatArray, rec: FloatArray): Match? {
        val nc = cam.size
        val nr = rec.size
        if (nc < ENV_RATE || nr < ENV_RATE) return null
        var size = 1
        while (size < nc + nr) size = size shl 1

        val corr = crossCorrelate(cam, rec, size)
        val camSq = prefixSquares(cam)
        val recSq = prefixSquares(rec)
        val minOverlap = maxOf(minOf(nc, nr) / 2, 2 * ENV_RATE)

        var bestLag = 0
        var bestScore = -1.0
        for (lag in -(nc - 1) until nr) {
            val lo = maxOf(0, -lag)
            val hi = minOf(nc, nr - lag)
            if (hi - lo < minOverlap) continue
            val c = corr[Math.floorMod(lag, size)]
            val e = (camSq[hi] - camSq[lo]) * (recSq[hi + lag] - recSq[lo + lag])
            if (e <= 0) continue
            val score = c / sqrt(e)
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        if (bestScore < MIN_SCORE) return null

        // Parabolic interpolation around the peak for sub-sample accuracy.
        var frac = 0.0
        val ym = corr[Math.floorMod(bestLag - 1, size)]
        val y0 = corr[Math.floorMod(bestLag, size)]
        val yp = corr[Math.floorMod(bestLag + 1, size)]
        val denom = ym - 2 * y0 + yp
        if (denom < 0) frac = (0.5 * (ym - yp) / denom).coerceIn(-0.5, 0.5)
        return Match(Math.round((bestLag + frac) * ENV_BLOCK), bestScore)
    }

    private fun prefixSquares(x: FloatArray): DoubleArray {
        val p = DoubleArray(x.size + 1)
        for (i in x.indices) p[i + 1] = p[i] + x[i].toDouble() * x[i]
        return p
    }

    /** r[k] = sum_i cam[i] * rec[i + k], with negative k stored at size + k. */
    private fun crossCorrelate(cam: FloatArray, rec: FloatArray, size: Int): DoubleArray {
        val ar = FloatArray(size); val ai = FloatArray(size)
        val br = FloatArray(size); val bi = FloatArray(size)
        rec.copyInto(ar)
        cam.copyInto(br)
        fft(ar, ai, false)
        fft(br, bi, false)
        for (i in 0 until size) {   // A * conj(B)
            val re = ar[i] * br[i] + ai[i] * bi[i]
            val im = ai[i] * br[i] - ar[i] * bi[i]
            ar[i] = re; ai[i] = im
        }
        fft(ar, ai, true)
        return DoubleArray(size) { ar[it].toDouble() / size }
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = (re[a] - xr).toFloat(); im[b] = (im[a] - xi).toFloat()
                    re[a] = (re[a] + xr).toFloat(); im[a] = (im[a] + xi).toFloat()
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
