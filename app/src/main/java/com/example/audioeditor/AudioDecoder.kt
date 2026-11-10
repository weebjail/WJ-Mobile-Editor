package com.example.audioeditor

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Opens media files for editing in place, like an NLE: audio is always read from the original file on
 * the phone and nothing is copied into the app.
 *
 * WAV files are memory-mapped and read directly. Everything else (MP3, AAC/M4A, FLAC, OGG, Opus, and
 * the audio of MP4/MOV video, or a WAV in a location that can't be mapped) is decoded by Android's
 * decoders on demand, see [DecodedPcm].
 *
 * The first time a file is opened it's read through once to draw its waveform (and, for compressed
 * files, to find its exact length and seek points). That small summary is cached by file identity
 * (URI + size + modified time), so reopening a project is quick.
 */
object AudioDecoder {

    private const val META_VERSION = 5
    private const val CACHE_LIMIT_BYTES = 256L shl 20   // waveform summaries only

    private const val KIND_NONE = 0      // a video without sound
    private const val KIND_WAV = 1
    private const val KIND_DECODED = 2

    /** The cached summary of one file. [audioFrames] and [mediaFrames] are 48 kHz frames. */
    private class Meta(
        val kind: Int, val audioFrames: Long, val mediaFrames: Long,
        val sampleRate: Int, val channels: Int, val bitDepth: Int,   // as shown to the user
        val peaks: FloatArray, val video: VideoInfo?,
        val srcRate: Int = 0, val srcFrames: Long = 0,                // KIND_DECODED only
        val points: SeekPoints = SeekPoints(LongArray(0), LongArray(0)),
    )

    private class ProbedVideo(val info: VideoInfo, val frames: Long)

    private class FileIdentity(val name: String, val size: Long, val modified: Long)

    private fun cacheDir(context: Context) = File(context.cacheDir, "media").apply { mkdirs() }

    fun decode(context: Context, uri: Uri, id: Long): AudioSource {
        val ident = identify(context, uri)
        val key = sha1("$uri|${ident.size}|${ident.modified}")
        val metaFile = File(cacheDir(context), "$key.meta")
        val cached = readMeta(metaFile)

        val meta: Meta
        val pcm: PcmData?
        val wav = openWavInPlace(context, uri)
        if (wav != null) {
            if (wav.frames <= 0) throw IOException("${ident.name} contains no audio")
            meta = cached?.takeIf { it.kind == KIND_WAV && it.audioFrames == wav.frames }
                ?: Meta(
                    KIND_WAV, wav.frames, wav.frames, wav.layout.sampleRate, wav.layout.channels, wav.layout.bits,
                    wav.scanPeaks(), null,
                ).also { writeMeta(metaFile, it) }
            pcm = wav
        } else {
            meta = cached?.takeIf { it.kind != KIND_WAV } ?: scanCompressed(context, uri, ident.name).also { writeMeta(metaFile, it) }
            pcm = if (meta.kind == KIND_DECODED) {
                val app = context.applicationContext
                DecodedPcm({ openExtractor(app, uri) }, meta.srcRate, meta.srcFrames, meta.points)
            } else null
        }
        metaFile.setLastModified(System.currentTimeMillis())

        return AudioSource(
            id, uri, ident.name, meta.mediaFrames, meta.sampleRate, meta.channels, meta.bitDepth,
            pcm, meta.peaks, meta.video,
        )
    }

    /**
     * Keeps the summary cache under its size limit, removing the least recently used first.
     * Also removes the full decoded copies (.f32) that older versions of the app made.
     */
    fun trimCache(context: Context) {
        val files = cacheDir(context).listFiles() ?: return
        val stale = System.currentTimeMillis() - 3_600_000   // a .tmp this old isn't being written any more
        files.filter { it.name.endsWith(".f32") || (it.name.endsWith(".tmp") && it.lastModified() < stale) }
            .forEach { it.delete() }
        var total = 0L
        for (f in files.filter { it.exists() }.sortedByDescending { it.lastModified() }) {
            total += f.length()
            if (total > CACHE_LIMIT_BYTES) f.delete()
        }
    }

    /**
     * Opens a WAV file for reading in place. Returns null when it isn't a WAV we read ourselves
     * (another format or an unusual WAV encoding), or can't be memory-mapped (some cloud storage apps).
     */
    private fun openWavInPlace(context: Context, uri: Uri): WavPcm? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { WavPcm.open(it.channel) }
        }
    } catch (_: Exception) {
        null
    }

    private fun openExtractor(context: Context, uri: Uri): MediaExtractor {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
        } catch (e: Exception) {
            ex.release()
            throw IOException("Couldn't open the file", e)
        }
        return ex
    }

    /** First pass over a compressed file (or a video): length, seek points and waveform. */
    private fun scanCompressed(context: Context, uri: Uri, name: String): Meta {
        val video = probeVideo(context, uri)
        val ex = openExtractor(context, uri)
        val track = audioTrackIndex(ex)
        if (track < 0) {
            ex.release()
            if (video == null || video.frames <= 0) throw IOException("This file has no audio the phone can read")
            return Meta(KIND_NONE, 0, video.frames, 0, 0, 0, FloatArray(0), video.info)
        }
        val format = ex.getTrackFormat(track)
        val scan = DecodedPcm.scan(ex, track)   // takes ownership of the extractor
        val audioFrames = framesAt48k(scan.srcFrames, scan.srcRate)
        if (audioFrames <= 0) throw IOException("$name contains no audio")
        return Meta(
            KIND_DECODED, audioFrames, maxOf(audioFrames, video?.frames ?: 0L),
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), 16,
            scan.peaks, video?.info, scan.srcRate, scan.srcFrames, scan.points,
        )
    }

    private fun identify(context: Context, uri: Uri): FileIdentity {
        var name: String? = null
        var size = -1L
        var modified = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        .takeIf { it >= 0 && !c.isNull(it) }?.let { modified = c.getLong(it) }
                }
            }
        } catch (_: Exception) {
        }
        if (uri.scheme == "file") uri.path?.let { File(it) }?.let { f -> size = f.length(); modified = f.lastModified() }
        return FileIdentity(name ?: uri.lastPathSegment ?: "media", size, modified)
    }

    fun displayName(context: Context, uri: Uri): String = identify(context, uri).name

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun writeMeta(file: File, m: Meta) {
        // Written to a temporary file first, so an interrupted write never leaves a broken summary.
        // The name is unique, as two imports of the same file can run at the same time.
        val tmp = File(file.parentFile, "${file.name}.${Thread.currentThread().id}.${System.nanoTime()}.tmp")
        DataOutputStream(FileOutputStream(tmp).buffered()).use { o ->
            o.writeInt(META_VERSION)
            o.writeInt(m.kind)
            o.writeLong(m.audioFrames); o.writeLong(m.mediaFrames)
            o.writeInt(m.sampleRate); o.writeInt(m.channels); o.writeInt(m.bitDepth)
            o.writeBoolean(m.video != null)
            m.video?.let { o.writeInt(it.width); o.writeInt(it.height); o.writeDouble(it.fps) }
            o.writeInt(m.peaks.size)
            for (p in m.peaks) o.writeFloat(p)
            o.writeInt(m.srcRate); o.writeLong(m.srcFrames)
            val pts = m.points
            o.writeInt(pts.size)
            for (i in 0 until pts.size) { o.writeLong(pts.ptsUs[i]); o.writeLong(pts.frames[i]) }
        }
        if (!tmp.renameTo(file)) { tmp.delete(); throw IOException("Couldn't save the waveform") }
    }

    private fun readMeta(file: File): Meta? {
        if (!file.exists()) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { i ->
                if (i.readInt() != META_VERSION) return null   // older versions: scanned again
                val kind = i.readInt()
                val audioFrames = i.readLong()
                val mediaFrames = i.readLong()
                val rate = i.readInt(); val ch = i.readInt(); val bits = i.readInt()
                val video = if (i.readBoolean()) VideoInfo(i.readInt(), i.readInt(), i.readDouble()) else null
                val peaks = FloatArray(i.readInt()) { i.readFloat() }
                val srcRate = i.readInt()
                val srcFrames = i.readLong()
                val n = i.readInt()
                val pts = LongArray(n)
                val at = LongArray(n)
                for (k in 0 until n) { pts[k] = i.readLong(); at[k] = i.readLong() }
                if (kind == KIND_DECODED && (n == 0 || srcRate <= 0)) return null
                Meta(kind, audioFrames, mediaFrames, rate, ch, bits, peaks, video, srcRate, srcFrames, SeekPoints(pts, at))
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- Video metadata

    private fun probeVideo(context: Context, uri: Uri): ProbedVideo? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") != true) continue
                var w = f.getInteger(MediaFormat.KEY_WIDTH)
                var h = f.getInteger(MediaFormat.KEY_HEIGHT)
                val rotation = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
                if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
                val fps = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    try { f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                    catch (_: ClassCastException) { f.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }
                } else 30.0
                val durUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                return ProbedVideo(VideoInfo(w, h, fps), durUs * SAMPLE_RATE / 1_000_000)
            }
            return null
        } catch (_: Exception) {
            return null
        } finally {
            ex.release()
        }
    }
}
