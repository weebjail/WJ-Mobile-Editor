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
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * Shows the video under the playhead. Decoding is done by ExoPlayer with the phone's hardware
 * H.264 / H.265 decoders; the audio mixer's playhead is the master clock and the video follows it.
 */
class VideoPreview(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply { volume = 0f }

    /** False when no video clip is under the playhead (the preview shows black). */
    var hasFrame by mutableStateOf(false); private set

    private var currentUri: Uri? = null
    private var currentClipId: Long? = null
    private var lastSeekMs = Long.MIN_VALUE
    private var lastSeekAt = 0L

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
            val now = SystemClock.uptimeMillis()
            val drifted = abs(player.currentPosition - targetMs) > MAX_DRIFT_MS && now - lastSeekAt > SEEK_SETTLE_MS
            if (!player.playWhenReady || clipChanged || drifted) {
                player.seekTo(targetMs)
                lastSeekAt = now
            }
            player.playWhenReady = true
        } else {
            player.playWhenReady = false
            if (targetMs != lastSeekMs) {
                player.seekTo(targetMs)
                lastSeekMs = targetMs
            }
        }
    }

    fun release() = player.release()

    private companion object {
        const val MAX_DRIFT_MS = 120L
        const val SEEK_SETTLE_MS = 600L
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
