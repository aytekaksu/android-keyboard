/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.florisboard.autocorrect.api

import android.os.Bundle
import java.util.Locale

enum class AutocorrectUserDictionaryStatus {
    OK,
    UNAVAILABLE,
    DENIED,
    INVALID,
}

enum class AutocorrectUserDictionaryOperation {
    QUERY,
    UPSERT,
    DELETE,
}

/**
 * One row in the Android system user dictionary. An [id] of zero requests an insert; a positive
 * ID addresses an existing row. Frequency zero is valid because some engines use it for
 * structured entries such as Japanese readings. [languageTag] is a canonical BCP-47 tag; `null`
 * is the only language-neutral representation.
 */
data class AutocorrectUserDictionaryEntry(
    val id: Long = 0L,
    val word: String,
    val frequency: Int,
    val languageTag: String? = null,
    val shortcut: String? = null,
)

data class AutocorrectUserDictionaryPage(
    val status: AutocorrectUserDictionaryStatus,
    val entries: List<AutocorrectUserDictionaryEntry> = emptyList(),
    val nextAfterId: Long? = null,
) {
    val successful: Boolean
        get() = status == AutocorrectUserDictionaryStatus.OK
}

data class AutocorrectUserDictionaryMutationResult(
    val status: AutocorrectUserDictionaryStatus,
    val entry: AutocorrectUserDictionaryEntry? = null,
) {
    val successful: Boolean
        get() = status == AutocorrectUserDictionaryStatus.OK
}

interface AutocorrectUserDictionaryReader {
    /**
     * Returns rows after [afterId], ordered by their stable host ID. Empty [languageTags] requests
     * every language while provider settings are visible; during typing the host restricts this to
     * the active session languages and language-neutral rows (`languageTag = null`).
     */
    suspend fun queryUserDictionary(
        languageTags: List<String> = emptyList(),
        afterId: Long = 0L,
        limit: Int = AutocorrectPluginContract.MAX_USER_DICTIONARY_PAGE_SIZE,
    ): AutocorrectUserDictionaryPage
}

/**
 * An editor scoped to one explicit, still-pending action in the host-rendered provider UI.
 * Providers cannot retain it to mutate the dictionary after that action finishes.
 */
interface AutocorrectUserDictionaryEditor : AutocorrectUserDictionaryReader {
    suspend fun upsertUserDictionaryEntry(
        entry: AutocorrectUserDictionaryEntry,
    ): AutocorrectUserDictionaryMutationResult

    suspend fun deleteUserDictionaryEntry(id: Long): AutocorrectUserDictionaryMutationResult
}

data class AutocorrectUserDictionaryRequest(
    val requestId: Long,
    val operation: AutocorrectUserDictionaryOperation,
    val originUiRequestId: Long,
    val languageTags: List<String>,
    val afterId: Long,
    val limit: Int,
    val entryId: Long,
    val entry: AutocorrectUserDictionaryEntry?,
)

fun userDictionaryQueryBundle(
    requestId: Long,
    languageTags: List<String>,
    afterId: Long,
    limit: Int,
) = userDictionaryRequestBundle(
    requestId = requestId,
    operation = AutocorrectUserDictionaryOperation.QUERY,
    originUiRequestId = 0L,
    languageTags = languageTags,
    afterId = afterId,
    limit = limit,
)

fun userDictionaryUpsertBundle(
    requestId: Long,
    originUiRequestId: Long,
    entry: AutocorrectUserDictionaryEntry,
) = userDictionaryRequestBundle(
    requestId = requestId,
    operation = AutocorrectUserDictionaryOperation.UPSERT,
    originUiRequestId = originUiRequestId,
    entry = entry,
)

fun userDictionaryDeleteBundle(
    requestId: Long,
    originUiRequestId: Long,
    id: Long,
) = userDictionaryRequestBundle(
    requestId = requestId,
    operation = AutocorrectUserDictionaryOperation.DELETE,
    originUiRequestId = originUiRequestId,
    entryId = id,
)

private fun userDictionaryRequestBundle(
    requestId: Long,
    operation: AutocorrectUserDictionaryOperation,
    originUiRequestId: Long,
    languageTags: List<String> = emptyList(),
    afterId: Long = 0L,
    limit: Int = AutocorrectPluginContract.MAX_USER_DICTIONARY_PAGE_SIZE,
    entryId: Long = 0L,
    entry: AutocorrectUserDictionaryEntry? = null,
) = Bundle().apply {
    putLong(DictionaryKeys.REQUEST_ID, requestId)
    putString(DictionaryKeys.OPERATION, operation.name)
    putLong(DictionaryKeys.ORIGIN_UI_REQUEST_ID, originUiRequestId)
    putStringArrayList(
        DictionaryKeys.LANGUAGE_TAGS,
        ArrayList(
            languageTags
                .map { it.canonicalLanguageTag() ?: INVALID_LANGUAGE_TAG }
                .distinct()
                .take(AutocorrectPluginContract.MAX_USER_DICTIONARY_LANGUAGE_TAGS)
        ),
    )
    putLong(DictionaryKeys.AFTER_ID, afterId.coerceAtLeast(0L))
    putInt(
        DictionaryKeys.LIMIT,
        limit.coerceIn(1, AutocorrectPluginContract.MAX_USER_DICTIONARY_PAGE_SIZE),
    )
    putLong(DictionaryKeys.ID, entryId)
    putBundle(DictionaryKeys.ENTRY, entry?.toDictionaryBundle())
}

fun userDictionaryRequestFromBundle(bundle: Bundle): AutocorrectUserDictionaryRequest? {
    val requestId = (bundle.raw(DictionaryKeys.REQUEST_ID) as? Long)
        ?.takeIf { it > 0L } ?: return null
    val operation = enumValues<AutocorrectUserDictionaryOperation>().firstOrNull {
        it.name == bundle.raw(DictionaryKeys.OPERATION)
    } ?: return null
    val rawLanguageTags = (bundle.raw(DictionaryKeys.LANGUAGE_TAGS) as? ArrayList<*>)
        ?.map { it as? String ?: return null }
        ?: return null
    if (rawLanguageTags.size > AutocorrectPluginContract.MAX_USER_DICTIONARY_LANGUAGE_TAGS) {
        return null
    }
    val languageTags = rawLanguageTags.map { it.canonicalLanguageTag() ?: return null }.distinct()
    val originUiRequestId = (bundle.raw(DictionaryKeys.ORIGIN_UI_REQUEST_ID) as? Long)
        ?.coerceAtLeast(0L) ?: return null
    val entryId = (bundle.raw(DictionaryKeys.ID) as? Long)
        ?.coerceAtLeast(0L) ?: return null
    val rawEntry = bundle.raw(DictionaryKeys.ENTRY)
    if (rawEntry != null && rawEntry !is Bundle) return null
    val entry = rawEntry?.toDictionaryEntry()
    val afterId = (bundle.raw(DictionaryKeys.AFTER_ID) as? Long)
        ?.coerceAtLeast(0L) ?: return null
    val limit = (bundle.raw(DictionaryKeys.LIMIT) as? Int)
        ?.coerceIn(1, AutocorrectPluginContract.MAX_USER_DICTIONARY_PAGE_SIZE)
        ?: return null
    if (
        (operation == AutocorrectUserDictionaryOperation.UPSERT &&
            (originUiRequestId == 0L || entry == null)) ||
        (operation == AutocorrectUserDictionaryOperation.DELETE &&
            (originUiRequestId == 0L || entryId == 0L))
    ) {
        return null
    }
    return AutocorrectUserDictionaryRequest(
        requestId = requestId,
        operation = operation,
        originUiRequestId = originUiRequestId,
        languageTags = languageTags,
        afterId = afterId,
        limit = limit,
        entryId = entryId,
        entry = entry,
    )
}

fun userDictionaryResultBundle(
    requestId: Long,
    status: AutocorrectUserDictionaryStatus,
    entries: List<AutocorrectUserDictionaryEntry> = emptyList(),
    nextAfterId: Long? = null,
) = Bundle().apply {
    putLong(DictionaryKeys.REQUEST_ID, requestId)
    putString(DictionaryKeys.STATUS, status.name)
    putParcelableArrayList(
        DictionaryKeys.ENTRIES,
        ArrayList(
            entries
                .take(AutocorrectPluginContract.MAX_USER_DICTIONARY_PAGE_SIZE)
                .mapNotNull { entry ->
                    entry.validated()?.takeIf { it.id > 0L }?.toDictionaryBundle()
                },
        ),
    )
    putLong(DictionaryKeys.NEXT_AFTER_ID, nextAfterId?.takeIf { it > 0L } ?: 0L)
}

fun userDictionaryResultFromBundle(
    bundle: Bundle,
): Pair<Long, AutocorrectUserDictionaryPage> {
    val requestId = bundle.raw(DictionaryKeys.REQUEST_ID) as? Long
    var status = enumValues<AutocorrectUserDictionaryStatus>().firstOrNull {
        it.name == bundle.raw(DictionaryKeys.STATUS)
    } ?: AutocorrectUserDictionaryStatus.INVALID
    val rawEntries = bundle.raw(DictionaryKeys.ENTRIES) as? ArrayList<*>
    val entryBundles = rawEntries?.filterIsInstance<Bundle>().orEmpty()
    val entries = entryBundles
        .take(AutocorrectPluginContract.MAX_USER_DICTIONARY_PAGE_SIZE)
        .mapNotNull { it.toDictionaryEntry()?.takeIf { entry -> entry.id > 0L } }
    val rawNextAfterId = bundle.raw(DictionaryKeys.NEXT_AFTER_ID) as? Long
    if (
        requestId == null ||
        requestId <= 0L ||
        rawEntries == null ||
        rawNextAfterId == null ||
        rawNextAfterId < 0L ||
        rawEntries.size > AutocorrectPluginContract.MAX_USER_DICTIONARY_PAGE_SIZE ||
        entryBundles.size != rawEntries.size ||
        entries.size != entryBundles.size
    ) {
        status = AutocorrectUserDictionaryStatus.INVALID
    }
    return (requestId ?: 0L) to AutocorrectUserDictionaryPage(
        status = status,
        entries = entries,
        nextAfterId = rawNextAfterId?.takeIf { it > 0L },
    )
}

private fun AutocorrectUserDictionaryEntry.toDictionaryBundle() = Bundle().apply {
    putLong(DictionaryKeys.ID, id)
    putString(DictionaryKeys.WORD, word)
    putInt(DictionaryKeys.FREQUENCY, frequency)
    putString(DictionaryKeys.LANGUAGE_TAG, languageTag)
    putString(DictionaryKeys.SHORTCUT, shortcut)
}

private fun Bundle.toDictionaryEntry(): AutocorrectUserDictionaryEntry? {
    val id = raw(DictionaryKeys.ID) as? Long ?: return null
    val word = raw(DictionaryKeys.WORD) as? String ?: return null
    val frequency = raw(DictionaryKeys.FREQUENCY) as? Int ?: return null
    val rawLanguageTag = raw(DictionaryKeys.LANGUAGE_TAG)
    if (rawLanguageTag != null && rawLanguageTag !is String) return null
    val rawShortcut = raw(DictionaryKeys.SHORTCUT)
    if (rawShortcut != null && rawShortcut !is String) return null
    return AutocorrectUserDictionaryEntry(
        id = id,
        word = word,
        frequency = frequency,
        languageTag = rawLanguageTag,
        shortcut = rawShortcut,
    ).validated()
}

private fun AutocorrectUserDictionaryEntry.validated(): AutocorrectUserDictionaryEntry? {
    val validWord = word.takeIf {
        it.isNotBlank() &&
            it.length <= AutocorrectPluginContract.MAX_USER_DICTIONARY_TEXT_CHARS &&
            '\u0000' !in it
    } ?: return null
    if (id < 0L || frequency !in DictionaryLimits.FREQUENCY) return null
    val validLanguageTag = languageTag?.canonicalLanguageTag()
    if (languageTag != null && validLanguageTag == null) return null
    val validShortcut = shortcut?.takeIf {
        it.length <= AutocorrectPluginContract.MAX_USER_DICTIONARY_TEXT_CHARS &&
            '\u0000' !in it
    }
    if (shortcut != null && validShortcut == null) return null
    return copy(
        word = validWord,
        languageTag = validLanguageTag,
        shortcut = validShortcut?.takeIf(String::isNotEmpty),
    )
}

private fun String.canonicalLanguageTag(): String? {
    if (
        isBlank() ||
        length > AutocorrectPluginContract.MAX_USER_DICTIONARY_LANGUAGE_TAG_CHARS ||
        '\u0000' in this
    ) {
        return null
    }
    return runCatching {
        Locale.Builder().setLanguageTag(this).build()
    }.getOrNull()?.takeIf {
        it.language.isNotEmpty()
    }?.toLanguageTag()
}

private const val INVALID_LANGUAGE_TAG = "!"

@Suppress("DEPRECATION")
private fun Bundle.raw(key: String): Any? = get(key)

private object DictionaryKeys {
    const val REQUEST_ID = "udRequestId"
    const val OPERATION = "udOperation"
    const val ORIGIN_UI_REQUEST_ID = "udOriginUiRequestId"
    const val LANGUAGE_TAGS = "udLanguageTags"
    const val AFTER_ID = "udAfterId"
    const val LIMIT = "udLimit"
    const val ENTRY = "udEntry"
    const val STATUS = "udStatus"
    const val ENTRIES = "udEntries"
    const val NEXT_AFTER_ID = "udNextAfterId"
    const val ID = "udId"
    const val WORD = "udWord"
    const val FREQUENCY = "udFrequency"
    const val LANGUAGE_TAG = "udLanguageTag"
    const val SHORTCUT = "udShortcut"
}

private object DictionaryLimits {
    val FREQUENCY = 0..255
}
