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
)

object ProjectStore {
    private fun dir(context: Context): File = File(context.filesDir, "projects").apply { mkdirs() }

    fun listProjects(context: Context): List<String> {
        val files = dir(context).listFiles() ?: return emptyList()
        return files.filter { it.extension == "json" }.map { it.nameWithoutExtension }.sorted()
    }

    fun save(context: Context, data: ProjectData) {
        val file = File(dir(context), "${data.name}.json")
        val json = JSONObject().apply {
            put("name", data.name)
            put("trackCount", data.trackCount)
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
                })
            }
            put("clips", clipsArray)
        }
        file.writeText(json.toString(2))
    }

    fun load(context: Context, projectName: String): ProjectData? {
        val file = File(dir(context), "$projectName.json")
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val name = json.optString("name", projectName)
        val trackCount = json.optInt("trackCount", 2)
        val playhead = json.optLong("playhead", 0L)

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
            )
        }
        return ProjectData(name, trackCount, playhead, sourcesList, clipsList)
    }

    fun delete(context: Context, projectName: String) {
        File(dir(context), "$projectName.json").delete()
    }
}
