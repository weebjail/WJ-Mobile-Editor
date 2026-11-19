package com.example.audioeditor

import android.net.Uri
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** Everything inside the editor runs at 48 kHz stereo. Imported files are converted on import. */
const val SAMPLE_RATE = 48000

/** One waveform peak value is stored for every PEAK_BLOCK frames of audio. */
const val PEAK_BLOCK = 256

const val MAX_AUDIO_TRACKS = 8
const val MAX_VIDEO_TRACKS = 4

/** Longest fade or crossfade the sliders set (Resolve-style transitions rarely need more). */
const val MAX_FADE_SECONDS = 5L

/** Timeline format for new projects (and older projects saved before it was stored). */
val DEFAULT_FRAME_RATE = FrameRate.F29_97
const val DEFAULT_WIDTH = 1920
const val DEFAULT_HEIGHT = 1080

/** Allowed timeline resolution (each side, in pixels). */
const val MIN_RESOLUTION = 16
const val MAX_RESOLUTION = 8192

/**
 * Fade shapes, matching DaVinci Resolve's audio transitions:
 * LINEAR = "Cross Fade (0 dB)", CURVED = "Cross Fade (+3 dB)" (equal power).
 * The curved fade rises quickly and eases in at the top, so a crossfade between two clips
 * keeps a constant loudness instead of dipping in the middle.
 */
enum class FadeCurve(val label: String) { LINEAR("Lin"), CURVED("Exp") }

data class Fade(val frames: Long = 0L, val curve: FadeCurve = FadeCurve.CURVED)

/** An imported media file. Its audio (if any) is read as 48 kHz stereo straight from the file itself. */
class AudioSource(
    val id: Long,
    val uri: Uri,
    val name: String,
    val frames: Long,              // length of the media, in 48 kHz frames
    val originalSampleRate: Int,
    val originalChannels: Int,
    val originalBitDepth: Int,
    val pcm: PcmData?,             // decoded audio; null when the file has no audio
    val peaks: FloatArray,         // waveform overview
    val video: VideoInfo? = null,  // set for video files
    val startSeconds: Double? = null,  // time of day the recording started (Broadcast WAV timecode), if known
) {
    val hasAudio: Boolean get() = pcm != null
}

class VideoInfo(val width: Int, val height: Int, val fps: Double)

/**
 * A piece of a source placed on the timeline. All positions are in frames at 48 kHz.
 * [video] clips live on video tracks, others on audio tracks; [track] indexes within that kind.
 * Clips sharing a non-null [linkId] (a video clip and its camera audio) move and edit together,
 * like linked clips in Resolve.
 */
data class Clip(
    val id: Long,
    val sourceId: Long,
    val track: Int,
    val start: Long,     // where the clip sits on the timeline
    val sourceIn: Long,  // where in the source file the clip begins
    val length: Long,
    val fadeIn: Fade = Fade(),
    val fadeOut: Fade = Fade(),
    val video: Boolean = false,
    val linkId: Long? = null,
    val grade: Grade = Grade(),   // colour (video clips only)
    /** Clip markers, like Resolve's: [Marker.frame] is a position in the source media, so they move with the clip. */
    val markers: List<Marker> = emptyList(),
    /** Clip volume as a plain multiplier (1 = 0 dB), like Resolve's clip volume line. Audio clips only. */
    val volume: Float = 1f,
) {
    val end: Long get() = start + length

    /** Volume multiplier at a position measured from the clip's start: the clip volume times its fades. */
    fun gainAt(pos: Long): Float = volume * fadeGainAt(pos)

    /** The fades alone (0..1) at a position measured from the clip's start. */
    fun fadeGainAt(pos: Long): Float {
        var g = 1f
        if (pos < fadeIn.frames) {
            g *= fadeInGain(pos.toFloat() / fadeIn.frames, fadeIn.curve)
        }
        val fromEnd = length - pos
        if (fromEnd < fadeOut.frames) {
            g *= fadeInGain(fromEnd.toFloat() / fadeOut.frames, fadeOut.curve)
        }
        return g
    }

    fun sameLane(o: Clip) = video == o.video && track == o.track

    /** The clip's markers that fall inside its trimmed part, with their timeline positions. */
    fun visibleMarkers(): List<Pair<Marker, Long>> =
        markers.filter { it.frame >= sourceIn && it.frame < sourceIn + length }.map { it to start + (it.frame - sourceIn) }
}

/**
 * Gain for a fade-in at progress t (0 = silent, 1 = full volume). A fade-out uses the same curve mirrored.
 * CURVED is the equal-power sine curve Resolve uses for its +3 dB crossfade.
 */
fun fadeInGain(t: Float, curve: FadeCurve): Float {
    val x = t.coerceIn(0f, 1f)
    return when (curve) {
        FadeCurve.LINEAR -> x
        FadeCurve.CURVED -> sin(x * (PI / 2).toFloat())
    }
}

/** Keeps fades inside the clip and stops fade-in and fade-out from overlapping. */
fun Clip.withValidFades(): Clip {
    val fi = fadeIn.frames.coerceIn(0L, length)
    val fo = fadeOut.frames.coerceIn(0L, length - fi)
    return copy(fadeIn = fadeIn.copy(frames = fi), fadeOut = fadeOut.copy(frames = fo))
}

/**
 * Marker colours. [resolve] is the name Resolve uses for the nearest of its own marker colours
 * (Resolve has no orange or magenta, so those come in as Sand and Fuchsia); [otio] is the OTIO colour.
 */
enum class MarkerColor(val argb: Long, val en: String, val ja: String, val otio: String, val resolve: String) {
    RED(0xFFE5483B, "Red", "レッド", "RED", "Red"),
    ORANGE(0xFFF08A24, "Orange", "オレンジ", "ORANGE", "Sand"),
    YELLOW(0xFFF2C94C, "Yellow", "イエロー", "YELLOW", "Yellow"),
    GREEN(0xFF3BB54A, "Green", "グリーン", "GREEN", "Green"),
    BLUE(0xFF2D8CEB, "Blue", "ブルー", "BLUE", "Blue"),
    PURPLE(0xFF9B59D0, "Purple", "パープル", "PURPLE", "Purple"),
    MAGENTA(0xFFE1005F, "Magenta", "マゼンタ", "MAGENTA", "Fuchsia");

    val label: String get() = tr(en, ja)

    companion object {
        /** Reads a saved colour name, including the older Resolve-style colours, falling back to blue. */
        fun fromName(name: String?): MarkerColor = when (name) {
            "CYAN", "SKY" -> BLUE
            "PINK", "ROSE", "FUCHSIA" -> MAGENTA
            "LAVENDER" -> PURPLE
            "MINT" -> GREEN
            "LEMON", "CREAM" -> YELLOW
            "SAND", "COCOA" -> ORANGE
            else -> entries.find { it.name == name } ?: BLUE
        }
    }
}

/** A timeline marker, like Resolve's: a coloured flag at one point, with an optional name and note. */
data class Marker(
    val id: Long,
    val frame: Long,
    val color: MarkerColor = MarkerColor.BLUE,
    val name: String = "",
    val note: String = "",
)

/** Loudest clip volume: +12 dB, the top of the clip volume line. */
const val MAX_CLIP_VOLUME = 3.981072f

fun volumeToDb(v: Float): Float = if (v <= 0f) Float.NEGATIVE_INFINITY else (20.0 * log10(v.toDouble())).toFloat()

fun dbToVolume(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

/** "+3.0 dB", "-6.0 dB", "-∞ dB". */
fun formatDb(v: Float): String {
    val db = volumeToDb(v)
    return if (db < -60f) "-∞ dB" else "%+.1f dB".format(if (abs(db) < 0.05f) 0f else db)
}

/**
 * Where the clip volume line sits, as a fraction of the clip's height from the top. The top is +12 dB and
 * 0 dB is a third of the way down; below that the line falls in step with the volume, to silence at the bottom.
 */
fun volumeToLineFraction(v: Float): Float =
    if (v >= 1f) (1f - volumeToDb(v) / 12f) / 3f else 1f - v.coerceAtLeast(0f) * (2f / 3f)

fun lineFractionToVolume(f: Float): Float {
    val x = f.coerceIn(0f, 1f)
    return if (x <= 1f / 3f) dbToVolume(12f * (1f - 3f * x)) else ((1f - x) * 1.5f).coerceIn(0f, 1f)
}

fun formatTime(frames: Long): String {
    val totalCs = frames * 100 / SAMPLE_RATE
    val m = totalCs / 6000
    val s = (totalCs / 100) % 60
    val cs = totalCs % 100
    return "%d:%02d.%02d".format(m, s, cs)
}
