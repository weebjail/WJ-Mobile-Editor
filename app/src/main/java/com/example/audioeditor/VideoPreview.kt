package com.example.audioeditor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * Shows the video under the playhead. Decoding is done by ExoPlayer with the phone's hardware
 * H.264 / H.265 decoders; the audio mixer's playhead is the master clock and the video follows it.
 *
 * Keeping the video in step during playback: seeking is slow (the decoder has to restart from the previous
 * keyframe, often up to a second back), so small drift is corrected by nudging the video's playback speed
 * instead, which is invisible. Only big jumps (starting, a new clip, a large gap) seek, and they aim ahead
 * by however long seeks have been taking on this phone so the video lands in step.
 */
class VideoPreview(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        volume = 0f
        // The preview's sound comes from our own mixer; don't decode the video's audio at all. This also
        // makes the player run on its own clock instead of a silent audio track's.
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && seekPending) {
                    seekPending = false
                    // Remember how long seeks take here (smoothed), to aim the next one ahead by that much.
                    val took = (SystemClock.uptimeMillis() - lastSeekAt).coerceIn(0L, MAX_LEAD_MS)
                    seekLeadMs = (seekLeadMs * 3 + took) / 4
                }
            }
        })
    }

    /** False when no video clip is under the playhead (the preview shows black). */
    var hasFrame by mutableStateOf(false); private set

    private var currentUri: Uri? = null
    private var currentClipId: Long? = null
    private var lastSeekMs = Long.MIN_VALUE
    private var lastSeekAt = 0L
    private var seekPending = false
    private var seekLeadMs = 250L
    private var speed = 1f

    private fun setSpeed(s: Float) {
        if (abs(s - speed) < 0.01f) return
        speed = s
        player.setPlaybackSpeed(s)
    }

    private fun seekWhilePlaying(targetMs: Long, clipEndMs: Long) {
        lastSeekAt = SystemClock.uptimeMillis()
        seekPending = true
        setSpeed(1f)
        player.seekTo((targetMs + seekLeadMs).coerceAtMost(clipEndMs))
    }

    fun update(clips: List<Clip>, sources: Map<Long, AudioSource>, playhead: Long, playing: Boolean) {
        // The highest video track wins, as in Resolve (V2 covers V1).
        val clip = clips.filter { it.video && playhead >= it.start && playhead < it.end }.maxByOrNull { it.track }
        val src = clip?.let { sources[it.sourceId] }
        if (clip == null || src == null) {
            if (hasFrame) { player.playWhenReady = false; hasFrame = false }
            currentClipId = null
            return
        }
        hasFrame = true
        if (src.uri != currentUri) {
            player.setMediaItem(MediaItem.fromUri(src.uri))
            player.prepare()
            currentUri = src.uri
            lastSeekMs = Long.MIN_VALUE
        }
        val targetMs = (clip.sourceIn + playhead - clip.start) * 1000 / SAMPLE_RATE
        val clipChanged = clip.id != currentClipId
        currentClipId = clip.id
        if (playing) {
            val clipEndMs = (clip.sourceIn + clip.length) * 1000 / SAMPLE_RATE
            if (!player.playWhenReady || clipChanged) {
                seekWhilePlaying(targetMs, clipEndMs)
            } else if (!seekPending && player.playbackState == Player.STATE_READY) {
                val drift = targetMs - player.currentPosition          // > 0: video is behind the audio
                when {
                    // Far out (e.g. the phone stalled): jump, but not again until the last jump has settled.
                    abs(drift) > MAX_DRIFT_MS && SystemClock.uptimeMillis() - lastSeekAt > SEEK_SETTLE_MS ->
                        seekWhilePlaying(targetMs, clipEndMs)
                    // Close enough to see as in sync: normal speed.
                    abs(drift) < IN_SYNC_MS -> setSpeed(1f)
                    // Otherwise catch up / ease back smoothly: half a second of drift → 25% faster/slower.
                    else -> setSpeed(1f + (drift / 2000f).coerceIn(-MAX_SPEED_CHANGE, MAX_SPEED_CHANGE))
                }
            }
            player.playWhenReady = true
            lastSeekMs = Long.MIN_VALUE
        } else {
            player.playWhenReady = false
            seekPending = false
            setSpeed(1f)
            if (targetMs != lastSeekMs) {
                player.seekTo(targetMs)
                lastSeekMs = targetMs
            }
        }
    }

    fun release() = player.release()

    private companion object {
        const val IN_SYNC_MS = 25L           // about one video frame
        const val MAX_SPEED_CHANGE = 0.25f
        const val MAX_DRIFT_MS = 1000L
        const val SEEK_SETTLE_MS = 1500L
        const val MAX_LEAD_MS = 800L
    }
}

class Thumb(val frame: Long, val image: ImageBitmap)

object Thumbnails {
    private const val HEIGHT = 96
    private const val MAX_COUNT = 60

    /** Grabs evenly spaced keyframes for the timeline's video track. */
    fun generate(context: Context, uri: Uri, frames: Long, info: VideoInfo): List<Thumb> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val seconds = frames.toDouble() / SAMPLE_RATE
            val count = (seconds / 2).toInt().coerceIn(1, MAX_COUNT)
            val w = (HEIGHT * info.width / info.height.coerceAtLeast(1)).coerceAtLeast(1)
            val out = ArrayList<Thumb>(count)
            for (i in 0 until count) {
                val frame = frames * i / count
                val us = frame * 1_000_000 / SAMPLE_RATE
                val bmp = if (Build.VERSION.SDK_INT >= 27) {
                    r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, HEIGHT)
                } else {
                    r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.let { Bitmap.createScaledBitmap(it, w, HEIGHT, true) }
                } ?: continue
                out += Thumb(frame, bmp.asImageBitmap())
            }
            return out
        } catch (_: Exception) {
            return emptyList()
        } finally {
            r.release()
        }
    }
}
