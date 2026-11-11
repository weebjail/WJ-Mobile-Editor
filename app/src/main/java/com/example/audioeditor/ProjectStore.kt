package com.example.audioeditor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedClip(
    val id: Long,
    val sourceId: Long,
    val track: Int,
    val start: Long,
    val sourceIn: Long,
    val length: Long,
    val fadeInFrames: Long,
    val fadeInCurve: String,
    val fadeOutFrames: Long,
    val fadeOutCurve: String,
    val video: Boolean = false,
    val linkId: Long? = null,
    val grade: Grade = Grade(),
)

data class SavedSource(
    val id: Long,
    val uriString: String,
    val name: String,
)

data class ProjectData(
    val name: String,
    val trackCount: Int,
    val playhead: Long,
    val sources: List<SavedSource>,
    val clips: List<SavedClip>,
    val videoTrackCount: Int = 1,
    val mutedTracks: Set<Int> = emptySet(),
    val lockedAudio: Set<Int> = emptySet(),
    val lockedVideo: Set<Int> = emptySet(),
    // Timeline format; null in projects saved before it was stored.
    val frameRate: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

object ProjectStore {
    private fun dir(context: Context): File = File(context.filesDir, "projects").apply { mkdirs() }

    /** Makes a user-typed project name safe to use as a file name (no slashes, colons, etc.). */
    fun safeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(80)
            .takeUnless { s -> s.all { it == '_' || it == '.' || it.isWhitespace() } } ?: "Untitled"

    private fun file(context: Context, name: String) = File(dir(context), "${safeName(name)}.json")

    fun exists(context: Context, name: String): Boolean = file(context, name).exists()

    fun listProjects(context: Context): List<String> {
        val files = dir(context).listFiles() ?: return emptyList()
        return files.filter { it.extension == "json" }.map { it.nameWithoutExtension }.sortedBy { it.lowercase() }
    }

    // ---------------------------------------------------------------- Project list details

    /**
     * A saved project as shown in the Open dialog. [mediaBytes] is the total size of the media files it uses
     * (they stay where they are on the phone); [missingMedia] counts the ones that can't be found.
     */
    data class Info(val name: String, val lastOpened: Long, val mediaBytes: Long, val missingMedia: Int)

    private fun openedTimes(context: Context) = context.getSharedPreferences("project_opened", Context.MODE_PRIVATE)

    /** Records that a project was opened (or saved) now. */
    fun markOpened(context: Context, name: String) {
        openedTimes(context).edit().putLong(safeName(name), System.currentTimeMillis()).apply()
    }

    /** All saved projects, most recently opened first. Reads every project and media file size: call off the main thread. */
    fun listInfo(context: Context): List<Info> {
        val times = openedTimes(context)
        val sizes = HashMap<String, Long?>()   // media shared by several projects is only looked up once
        return listProjects(context).map { name ->
            val file = file(context, name)
            val sources = try { load(context, name)?.sources.orEmpty() } catch (_: Exception) { emptyList() }
            var bytes = 0L
            var missing = 0
            for (uri in sources.map { it.uriString }.distinct()) {
                val size = sizes.getOrPut(uri) { mediaSize(context, uri) }
                if (size == null) missing++ else bytes += size
            }
            // Projects not opened since this was added show when they were last saved.
            Info(name, times.getLong(name, file.lastModified()), bytes, missing)
        }.sortedByDescending { it.lastOpened }
    }

    /** Size of a media file in bytes, or null if it can't be found. */
    private fun mediaSize(context: Context, uriString: String): Long? = try {
        val uri = android.net.Uri.parse(uriString)
        if (uri.scheme == "file") {
            uri.path?.let { File(it) }?.takeIf { it.exists() }?.length()
        } else {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }
    } catch (_: Exception) {
        null   // deleted, moved, or permission lost
    }

    fun save(context: Context, data: ProjectData) {
        val json = JSONObject().apply {
            put("version", 2)
            put("name", data.name)
            put("trackCount", data.trackCount)
            put("videoTrackCount", data.videoTrackCount)
            put("mutedTracks", JSONArray(data.mutedTracks.sorted()))
            put("lockedAudio", JSONArray(data.lockedAudio.sorted()))
            put("lockedVideo", JSONArray(data.lockedVideo.sorted()))
            put("playhead", data.playhead)
            data.frameRate?.let { put("frameRate", it) }
            data.width?.let { put("width", it) }
            data.height?.let { put("height", it) }

            val srcsArray = JSONArray()
            for (s in data.sources) {
                srcsArray.put(JSONObject().apply {
                    put("id", s.id)
                    put("uri", s.uriString)
                    put("name", s.name)
                })
            }
            put("sources", srcsArray)

            val clipsArray = JSONArray()
            for (c in data.clips) {
                clipsArray.put(JSONObject().apply {
                    put("id", c.id)
                    put("sourceId", c.sourceId)
                    put("track", c.track)
                    put("start", c.start)
                    put("sourceIn", c.sourceIn)
                    put("length", c.length)
                    put("fadeInFrames", c.fadeInFrames)
                    put("fadeInCurve", c.fadeInCurve)
                    put("fadeOutFrames", c.fadeOutFrames)
                    put("fadeOutCurve", c.fadeOutCurve)
                    put("video", c.video)
                    c.linkId?.let { put("linkId", it) }
                    if (!c.grade.isNeutral) put("grade", JSONObject().apply {
                        c.grade.lut?.let { put("lut", it) }
                        put("exposure", c.grade.exposure.toDouble())
                        put("highlights", c.grade.highlights.toDouble())
                        put("shadows", c.grade.shadows.toDouble())
                    })
                })
            }
            put("clips", clipsArray)
        }
        // Write to a temp file first so a crash mid-save can't destroy the previous version.
        val target = file(context, data.name)
        val tmp = File(target.path + ".tmp")
        tmp.writeText(json.toString(2))
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("Couldn't save the project")
        }
    }

    fun load(context: Context, projectName: String): ProjectData? {
        val file = file(context, projectName)
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val name = json.optString("name", projectName)
        val trackCount = json.optInt("trackCount", 2).coerceIn(1, MAX_AUDIO_TRACKS)
        val videoTrackCount = json.optInt("videoTrackCount", 1).coerceIn(1, MAX_VIDEO_TRACKS)
        val playhead = json.optLong("playhead", 0L).coerceAtLeast(0L)
        fun intSet(key: String): Set<Int> {
            val out = HashSet<Int>()
            json.optJSONArray(key)?.let { a -> for (i in 0 until a.length()) out += a.getInt(i) }
            return out
        }
        val muted = intSet("mutedTracks")

        val sourcesList = mutableListOf<SavedSource>()
        val srcsArray = json.optJSONArray("sources") ?: JSONArray()
        for (i in 0 until srcsArray.length()) {
            val o = srcsArray.getJSONObject(i)
            sourcesList += SavedSource(
                id = o.getLong("id"),
                uriString = o.getString("uri"),
                name = o.optString("name", "Audio"),
            )
        }

        val clipsList = mutableListOf<SavedClip>()
        val clipsArray = json.optJSONArray("clips") ?: JSONArray()
        for (i in 0 until clipsArray.length()) {
            val o = clipsArray.getJSONObject(i)
            clipsList += SavedClip(
                id = o.getLong("id"),
                sourceId = o.getLong("sourceId"),
                track = o.getInt("track"),
                start = o.getLong("start"),
                sourceIn = o.getLong("sourceIn"),
                length = o.getLong("length"),
                fadeInFrames = o.optLong("fadeInFrames", 0L),
                fadeInCurve = o.optString("fadeInCurve", "LINEAR"),
                fadeOutFrames = o.optLong("fadeOutFrames", 0L),
                fadeOutCurve = o.optString("fadeOutCurve", "LINEAR"),
                video = o.optBoolean("video", false),
                linkId = if (o.has("linkId")) o.getLong("linkId") else null,
                grade = o.optJSONObject("grade")?.let { g ->
                    Grade(
                        lut = if (g.has("lut")) g.getString("lut") else null,
                        exposure = g.optDouble("exposure", 0.0).toFloat(),
                        highlights = g.optDouble("highlights", 0.0).toFloat(),
                        shadows = g.optDouble("shadows", 0.0).toFloat(),
                    )
                } ?: Grade(),
            )
        }
        return ProjectData(
            name, trackCount, playhead, sourcesList, clipsList, videoTrackCount, muted,
            lockedAudio = intSet("lockedAudio"),
            lockedVideo = intSet("lockedVideo"),
            frameRate = if (json.has("frameRate")) json.getString("frameRate") else null,
            width = if (json.has("width")) json.getInt("width").coerceIn(MIN_RESOLUTION, MAX_RESOLUTION) else null,
            height = if (json.has("height")) json.getInt("height").coerceIn(MIN_RESOLUTION, MAX_RESOLUTION) else null,
        )
    }

    fun delete(context: Context, projectName: String) {
        file(context, projectName).delete()
        openedTimes(context).edit().remove(safeName(projectName)).apply()
    }
}
