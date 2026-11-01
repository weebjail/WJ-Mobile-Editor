package com.example.audioeditor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Mixes all clips (with their fades) into stereo audio. Used for both playback and export. */
class Mixer(private val sources: Map<Long, AudioSource>, clips: List<Clip>) {
    private val sorted = clips.sortedBy { it.start }
    val totalFrames: Long = clips.maxOfOrNull { it.end } ?: 0L

    /** Fills [out] with [frames] stereo frames starting at timeline position [start]. */
    fun render(start: Long, frames: Int, out: FloatArray) {
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        val end = start + frames
        for (c in sorted) {
            if (c.end <= start || c.start >= end) continue
            val src = sources[c.sourceId] ?: continue
            var t = maxOf(start, c.start)
            val stop = minOf(end, c.end)
            while (t < stop) {
                val rel = t - c.start
                val srcFrame = c.sourceIn + rel
                if (srcFrame >= src.frames) break
                val g = c.gainAt(rel)
                val o = ((t - start) * 2).toInt()
                val si = (srcFrame * 2).toInt()
                out[o] += src.pcm.get(si) * g
                out[o + 1] += src.pcm.get(si + 1) * g
                t++
            }
        }
        for (i in 0 until frames * 2) out[i] = out[i].coerceIn(-1f, 1f)
    }
}

/** Streams the mix to the speaker/headphones on a background thread. */
class Player {
    @Volatile private var mixer: Mixer? = null
    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var startFrame = 0L

    /** Lets edits made during playback be heard immediately. */
    fun updateMixer(m: Mixer) { mixer = m }

    fun play(from: Long, m: Mixer) {
        stop()
        mixer = m
        startFrame = from
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 4096 * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        running = true
        t.play()
        thread = Thread({
            val n = 1024
            val buf = FloatArray(n * 2)
            var pos = from
            while (running) {
                val mx = mixer ?: break
                if (pos >= mx.totalFrames) break
                mx.render(pos, n, buf)
                var off = 0
                while (off < buf.size && running) {
                    val w = t.write(buf, off, buf.size - off, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) running = false
                    if (w <= 0) break
                    off += w
                }
                pos += n
            }
        }, "playback").apply { start() }
    }

    /** Current playback position on the timeline, in frames. */
    fun position(): Long {
        val t = track ?: return startFrame
        return startFrame + (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
    }

    fun stop() {
        running = false
        val t = track
        try { t?.pause(); t?.flush() } catch (_: IllegalStateException) {}
        thread?.join(1000)
        thread = null
        t?.release()
        track = null
    }
}
