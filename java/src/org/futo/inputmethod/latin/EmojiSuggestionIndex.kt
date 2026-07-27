/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
    @Volatile private var preferredSkinToneModifier = 0

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

    fun setPreferredSkinToneModifier(modifier: Int) {
        preferredSkinToneModifier = normalizedSkinToneModifier(modifier)
    }

    fun clearPreferredSkinToneModifier() {
        preferredSkinToneModifier = 0
    }

    fun transformToPreferredSkinTone(emoji: String): String =
        applyPreferredSkinTone(emoji, preferredSkinToneModifier, people)

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
}

internal fun normalizedSkinToneModifier(modifier: Int) =
    modifier.takeIf { it in 0x1F3FB..0x1F3FF } ?: 0

private val skinToneModifiers =
    (0x1F3FB..0x1F3FF).map { String(Character.toChars(it)) }

internal fun applyPreferredSkinTone(
    emoji: String,
    modifier: Int,
    toneableEmoji: Set<String>,
): String {
    val normalized = normalizedSkinToneModifier(modifier)
    if (normalized == 0) return emoji
    val tone = String(Character.toChars(normalized))
    return emoji.split("\u200D").joinToString("\u200D") { part ->
        val untoned = skinToneModifiers.fold(part) { value, existing ->
            value.replace(existing, "")
        }
        if (untoned in toneableEmoji) untoned + tone else part
    }
}
