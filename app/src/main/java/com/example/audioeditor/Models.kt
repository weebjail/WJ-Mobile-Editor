package com.example.audioeditor

import android.net.Uri
import java.nio.FloatBuffer

/** Everything inside the editor runs at 48 kHz stereo. Imported files are converted on import. */
const val SAMPLE_RATE = 48000

/** One waveform peak value is stored for every PEAK_BLOCK frames of audio. */
const val PEAK_BLOCK = 256

enum class FadeCurve(val label: String) { LINEAR("Lin"), EXPONENTIAL("Exp") }

data class Fade(val frames: Long = 0L, val curve: FadeCurve = FadeCurve.LINEAR)

/** An imported audio file, decoded to 48 kHz stereo float PCM in a cache file. */
class AudioSource(
    val id: Long,
    val uri: Uri,
    val name: String,
    val frames: Long,
    val originalSampleRate: Int,
    val originalChannels: Int,
    val originalBitDepth: Int,
    val pcm: FloatBuffer,      // interleaved L,R floats
    val peaks: FloatArray,     // waveform overview
)

/** A piece of a source placed on the timeline. All positions are in frames at 48 kHz. */
data class Clip(
    val id: Long,
    val sourceId: Long,
    val track: Int,
    val start: Long,     // where the clip sits on the timeline
    val sourceIn: Long,  // where in the source file the clip begins
    val length: Long,
    val fadeIn: Fade = Fade(),
    val fadeOut: Fade = Fade(),
) {
    val end: Long get() = start + length

    /** Volume multiplier (0..1) at a position measured from the clip's start. */
    fun gainAt(pos: Long): Float {
        var g = 1f
        if (fadeIn.frames > 0 && pos < fadeIn.frames) {
            g *= fadeInGain(pos.toFloat() / fadeIn.frames, fadeIn.curve)
        }
        val fromEnd = length - pos
        if (fadeOut.frames > 0 && fromEnd < fadeOut.frames) {
            g *= fadeInGain(fromEnd.toFloat() / fadeOut.frames, fadeOut.curve)
        }
        return g
    }
}

/**
 * Gain for a fade-in at progress t (0 = silent, 1 = full volume).
 * A fade-out uses the same curve mirrored.
 * Exponential = straight line in decibels from -60 dB to 0 dB, which sounds smooth and natural.
 */
fun fadeInGain(t: Float, curve: FadeCurve): Float {
    val x = t.coerceIn(0f, 1f)
    return when (curve) {
        FadeCurve.LINEAR -> x
        FadeCurve.EXPONENTIAL -> x * x
    }
}

/** Keeps fades inside the clip and stops fade-in and fade-out from overlapping. */
fun Clip.withValidFades(): Clip {
    val fi = fadeIn.frames.coerceIn(0L, length)
    val fo = fadeOut.frames.coerceIn(0L, length - fi)
    return copy(fadeIn = fadeIn.copy(frames = fi), fadeOut = fadeOut.copy(frames = fo))
}

fun formatTime(frames: Long): String {
    val totalCs = frames * 100 / SAMPLE_RATE
    val m = totalCs / 6000
    val s = (totalCs / 100) % 60
    val cs = totalCs % 100
    return "%d:%02d.%02d".format(m, s, cs)
}
