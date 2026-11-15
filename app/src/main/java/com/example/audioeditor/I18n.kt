package com.example.audioeditor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** The languages the app's text is available in. [label] is always written in that language. */
enum class Language(val label: String) { ENGLISH("English"), JAPANESE("日本語") }

/**
 * The app's language, chosen in Settings (first run: Japanese if the phone is set to Japanese, otherwise English).
 * It's Compose state, so every screen redraws in the new language as soon as it changes.
 */
object I18n {
    private const val PREFS = "settings"
    private const val KEY = "language"

    var language by mutableStateOf(Language.ENGLISH)
        private set

    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        language = Language.entries.find { it.name == saved }
            ?: if (Locale.getDefault().language == "ja") Language.JAPANESE else Language.ENGLISH
    }

    fun set(context: Context, l: Language) {
        language = l
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, l.name).apply()
    }
}

/** Picks the text for the current language. Every piece of text shown to the user goes through this. */
fun tr(en: String, ja: String): String = if (I18n.language == Language.JAPANESE) ja else en
