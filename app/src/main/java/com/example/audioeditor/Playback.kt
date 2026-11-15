package com.example.audioeditor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build

/**
 * Mixes all audio clips (with their fades) into stereo audio. Used for playback, export and sync.
 * Video clips are silent here; their sound comes from their linked audio clips. Muted tracks are skipped.
 */
class Mixer(private val sources: Map<Long, AudioSource>, clips: List<Clip>, mutedTracks: Set<Int> = emptySet()) {
    private val sorted = clips.filter { !it.video && it.track !in mutedTracks }.sortedBy { it.start }
    val totalFrames: Long = clips.maxOfOrNull { it.end } ?: 0L

    /** Fills [out] with [frames] stereo frames starting at timeline position [start]. */
    fun render(start: Long, frames: Int, out: FloatArray) {
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        val end = start + frames
        for (c in sorted) {
            if (c.end <= start || c.start >= end) continue
            val pcm = sources[c.sourceId]?.pcm ?: continue
            val from = maxOf(start, c.start)
            val stop = minOf(end, c.end, c.start - c.sourceIn + pcm.frames)
            val fadeInEnd = c.start + c.fadeIn.frames
            val fadeOutStart = c.end - c.fadeOut.frames
            var t = from
            while (t < stop) {
                val rel = t - c.start
                val srcFrame = c.sourceIn + rel
                val g = if (t < fadeInEnd || t >= fadeOutStart) c.gainAt(rel) else 1f
                val o = ((t - start) * 2).toInt()
                out[o] += pcm.left(srcFrame) * g
                out[o + 1] += pcm.right(srcFrame) * g
                t++
            }
        }
        for (i in 0 until frames * 2) out[i] = out[i].coerceIn(-1f, 1f)
    }
}

/**
 * Streams the mix to the speaker/headphones on a background thread.
 *
 * To start quickly, the audio output is opened once and kept between plays (opening it takes a noticeable
 * moment), and each play fills the buffer with sound before starting the output, so nothing waits on an
 * empty buffer.
 */
class Player {
    @Volatile private var mixer: Mixer? = null
    @Volatile private var running = false
    private val lock = Any()
    @Volatile private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var startFrame = 0L
    @Volatile private var headBase = 0L   // the track's position counter when this play started

    /** Lets edits made during playback be heard immediately. */
    fun updateMixer(m: Mixer) { mixer = m }

    /** Opens the audio output ahead of time, so the first play doesn't wait for it. Safe on any thread. */
    fun prepare() { synchronized(lock) { openTrack() } }

    /** The open audio output, opening it if needed (call holding [lock]). */
    private fun openTrack(): AudioTrack {
        track?.let { if (it.state == AudioTrack.STATE_INITIALIZED) return it; it.release() }
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
            // About 170 ms of buffer: room for the occasional wait while compressed audio is decoded.
            .setBufferSizeInBytes(maxOf(minBuf, 8192 * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Start as soon as a little audio is ready instead of waiting for the whole buffer (Android 12+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { t.setStartThresholdInFrames(START_FRAMES) } catch (_: Exception) {}
        }
        track = t
        return t
    }

    fun play(from: Long, m: Mixer) {
        stop()
        val t = synchronized(lock) {
            mixer = m
            startFrame = from
            val t = openTrack()
            headBase = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            running = true
            t
        }
        thread = Thread({
            val n = 1024
            val buf = FloatArray(n * 2)
            var pos = from
            var started = false
            fun startOutput() {
                synchronized(lock) {
                    if (running && !started) {
                        try { t.play() } catch (_: IllegalStateException) { running = false }
                        started = true
                    }
                }
            }
            while (running) {
                val mx = mixer ?: break
                if (pos >= mx.totalFrames) break
                mx.render(pos, n, buf)
                var off = 0
                while (off < buf.size && running) {
                    // Until the output starts, fill the buffer without waiting; once it's full, start it.
                    val mode = if (started) AudioTrack.WRITE_BLOCKING else AudioTrack.WRITE_NON_BLOCKING
                    val w = t.write(buf, off, buf.size - off, mode)
                    if (w < 0) {
                        // The output broke (e.g. the audio device went away): open a fresh one next time.
                        synchronized(lock) { running = false; if (track === t) { t.release(); track = null } }
                        break
                    }
                    off += w   // a 0-byte write just retries, so no audio is skipped
                    if (!started && off < buf.size) startOutput()
                }
                pos += n
            }
            // Less audio than a full buffer before the end: play what was written.
            if (!started) startOutput()
        }, "playback").apply { start() }
    }

    /** Current playback position on the timeline, in frames. */
    fun position(): Long {
        val t = track ?: return startFrame
        val raw = try { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL } catch (_: Exception) { return startFrame }
        // Some phones only reset the counter after the flush has gone through: count from zero then.
        if (raw < headBase) headBase = 0L
        return startFrame + (raw - headBase)
    }

    /** Stops playback; the audio output stays open, ready for the next play. */
    fun stop() {
        val t = synchronized(lock) {
            running = false
            track?.also { try { it.pause(); it.flush() } catch (_: IllegalStateException) {} }
        }
        thread?.join(1000)
        thread = null
        // The playback thread may have written a little more before it noticed the stop.
        synchronized(lock) { if (track === t) try { t?.flush() } catch (_: IllegalStateException) {} }
    }

    /** Closes the audio output for good. */
    fun release() {
        stop()
        synchronized(lock) { track?.release(); track = null }
    }

    private companion object {
        const val START_FRAMES = 2048
    }
}
