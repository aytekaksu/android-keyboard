/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.futo.inputmethod.latin.uix.DataStoreHelper
import org.futo.inputmethod.latin.uix.SettingsKey
import java.io.FileNotFoundException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Prediction-only emoji aliases. The provider build generates these compact assets from FUTO's
 * browser/search data, avoiding UI code, fonts, and metadata which suggestion lookup never uses.
 */
object EmojiSuggestionIndex {
    private const val ASSET_DIRECTORY = "emoji-shortcuts"
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loads = ConcurrentHashMap<String, Deferred<Unit>>()
    private val shortcuts = ConcurrentHashMap<String, Map<String, String>>()
    @Volatile private var people = emptySet<String>()

    suspend fun loadForLanguage(context: Context, locale: Locale) {
        val applicationContext = context.applicationContext
        load("people") {
            people = applicationContext.assets.open("$ASSET_DIRECTORY/people.txt")
                .bufferedReader()
                .useLines { lines -> lines.filter(String::isNotBlank).toSet() }
        }.await()
        val language = locale.language
        load("language:$language") {
            shortcuts[language] = try {
                applicationContext.assets.open("$ASSET_DIRECTORY/$language.tsv")
                    .bufferedReader()
                    .useLines { lines ->
                        lines.mapNotNull { line ->
                            val separator = line.indexOf('\t')
                            if (separator <= 0 || separator == line.lastIndex) {
                                null
                            } else {
                                line.substring(0, separator) to line.substring(separator + 1)
                            }
                        }.toMap()
                    }
            } catch (_: FileNotFoundException) {
                emptyMap()
            }
        }.await()
    }

    fun getShortcut(locale: Locale, text: String): String? =
        shortcuts[locale.language]?.get(text)

    fun transformToLastSkinTone(emoji: String): String {
        val modifier = DataStoreHelper.getSetting(LAST_USED_SKIN_TONE)
        if (modifier.isEmpty()) return emoji
        return emoji.split("\u200D").joinToString("\u200D") { part ->
            if (part in people) part + modifier else part
        }
    }

    private fun load(key: String, block: suspend () -> Unit): Deferred<Unit> {
        loads[key]?.let { return it }
        val created = loadScope.async(start = CoroutineStart.LAZY) { block() }
        val existing = loads.putIfAbsent(key, created)
        if (existing != null) {
            created.cancel()
            return existing
        }
        created.invokeOnCompletion { error ->
            if (error != null) loads.remove(key, created)
        }
        created.start()
        return created
    }

    private val LAST_USED_SKIN_TONE = SettingsKey(
        stringPreferencesKey("last_used_skin_tone"),
        "",
    )
}
