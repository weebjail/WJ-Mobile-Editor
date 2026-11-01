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

    fun save(context: Context, data: ProjectData) {
        val json = JSONObject().apply {
            put("version", 2)
            put("name", data.name)
            put("trackCount", data.trackCount)
            put("videoTrackCount", data.videoTrackCount)
            put("mutedTracks", JSONArray(data.mutedTracks.sorted()))
            put("playhead", data.playhead)

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
        val muted = HashSet<Int>()
        json.optJSONArray("mutedTracks")?.let { a -> for (i in 0 until a.length()) muted += a.getInt(i) }

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
            )
        }
        return ProjectData(name, trackCount, playhead, sourcesList, clipsList, videoTrackCount, muted)
    }

    fun delete(context: Context, projectName: String) {
        file(context, projectName).delete()
    }
}
