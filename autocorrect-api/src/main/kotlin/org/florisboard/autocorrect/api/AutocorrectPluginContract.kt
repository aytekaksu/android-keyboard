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

/**
 * Stable wire contract between a keyboard host and an independently installed autocorrect provider.
 *
 * Providers are ordinary bound Android services. They must never call startService() for a typing
 * session; the host controls their lifetime by binding only while input is active.
 */
object AutocorrectPluginContract {
    const val ACTION_BIND_PROVIDER = "org.florisboard.autocorrect.api.action.BIND_PROVIDER"
    const val META_PROTOCOL_VERSION = "org.florisboard.autocorrect.api.PROTOCOL_VERSION"
    const val PROTOCOL_VERSION = 4

    const val MSG_START_SESSION = 1
    const val MSG_SUGGEST = 2
    const val MSG_ACCEPTED = 3
    const val MSG_REVERTED = 4
    const val MSG_REMOVE = 5
    const val MSG_FINISH_SESSION = 6
    const val MSG_CANCEL = 7
    const val MSG_GET_PLUGIN_UI = 8
    const val MSG_SET_PLUGIN_UI_VALUE = 9
    const val MSG_INVOKE_PLUGIN_UI_ACTION = 10
    const val MSG_TEXT_EVENT = 11
    const val MSG_PLUGIN_UI_CLOSED = 12
    const val MSG_PLUGIN_UI_DOCUMENT = 13
    const val MSG_HOST_USER_DICTIONARY_RESULT = 14

    const val MSG_SUGGESTIONS = 101
    const val MSG_REMOVE_RESULT = 102
    const val MSG_PLUGIN_UI_RESULT = 103
    const val MSG_FINISH_SESSION_RESULT = 104
    const val MSG_HOST_USER_DICTIONARY_REQUEST = 105

    const val MAX_CONTEXT_CHARS = 512
    const val MAX_CANDIDATES = 16
    const val MAX_CANDIDATE_ID_CHARS = 256
    const val MAX_CANDIDATE_TEXT_CHARS = 256
    const val MAX_SECONDARY_TEXT_CHARS = 128
    const val MAX_TRACE_KEY_COUNT = 64
    const val MAX_TRACE_POINT_COUNT = 48
    const val MAX_GESTURE_POINT_COUNT = 128
    const val MAX_BOOSTED_CODE_POINT_COUNT = 64
    const val MAX_USER_DICTIONARY_PAGE_SIZE = 128
    const val MAX_USER_DICTIONARY_TEXT_CHARS = 256
    const val MAX_USER_DICTIONARY_LANGUAGE_TAG_CHARS = 64
    const val MAX_USER_DICTIONARY_LANGUAGE_TAGS = 16
}

internal fun String.takeWireChars(maxChars: Int): String {
    require(maxChars >= 0)
    val end = minOf(length, maxChars)
    if (end == 0 || !Character.isHighSurrogate(this[end - 1])) return substring(0, end)
    return substring(0, end - 1)
}

enum class AutocorrectCandidateKind {
    TYPED,
    CORRECTION,
    COMPLETION,
    NEXT_WORD,
    EMOJI,
}

enum class AutocorrectSeparatorBehavior {
    DEFAULT,
    INSERT,
    OMIT,
}

enum class AutocorrectAcceptanceKind {
    MANUAL,
    AUTO_CORRECTION,
    GESTURE,
}

/** Host keyboard shift state at the time a suggestion request is made. */
enum class AutocorrectCapsMode {
    UNSPECIFIED,
    UNSHIFTED,
    SHIFTED_MANUAL,
    SHIFTED_AUTOMATIC,
    CAPS_LOCK,
}

/** Normalized editor behavior traits. These values never identify the target application. */
object AutocorrectEditorFlags {
    const val CODE_LIKE = 1 shl 0
    const val WEB_FIELD = 1 shl 1

    internal const val ALL = CODE_LIKE or WEB_FIELD
}

data class AutocorrectSession(
    val sessionId: Long,
    val primaryLanguageTag: String,
    val secondaryLanguageTags: List<String>,
    val inputType: Int,
    val capsMode: Int,
    val allowPersonalizedLearning: Boolean = false,
    val editorFlags: Int = 0,
    /** Preferred Unicode emoji skin-tone modifier, or `0` for the provider default. */
    val preferredEmojiSkinToneModifier: Int = 0,
) {
    fun toBundle() = Bundle().apply {
        putLong(Keys.SESSION_ID, sessionId)
        putString(Keys.PRIMARY_LANGUAGE_TAG, primaryLanguageTag)
        putStringArrayList(Keys.SECONDARY_LANGUAGE_TAGS, ArrayList(secondaryLanguageTags))
        putInt(Keys.INPUT_TYPE, inputType)
        putInt(Keys.CAPS_MODE, capsMode)
        putBoolean(Keys.ALLOW_PERSONALIZED_LEARNING, allowPersonalizedLearning)
        putInt(Keys.EDITOR_FLAGS, editorFlags and AutocorrectEditorFlags.ALL)
        putInt(
            Keys.PREFERRED_EMOJI_SKIN_TONE_MODIFIER,
            preferredEmojiSkinToneModifier.normalizedEmojiSkinToneModifier(),
        )
    }

    companion object {
        internal fun fromBundle(bundle: Bundle) = AutocorrectSession(
            sessionId = bundle.getLong(Keys.SESSION_ID),
            primaryLanguageTag = bundle.getString(Keys.PRIMARY_LANGUAGE_TAG).orEmpty(),
            secondaryLanguageTags = bundle.getStringArrayList(Keys.SECONDARY_LANGUAGE_TAGS).orEmpty(),
            inputType = bundle.getInt(Keys.INPUT_TYPE),
            capsMode = bundle.getInt(Keys.CAPS_MODE),
            allowPersonalizedLearning = bundle.getBoolean(Keys.ALLOW_PERSONALIZED_LEARNING),
            editorFlags = bundle.getInt(Keys.EDITOR_FLAGS) and AutocorrectEditorFlags.ALL,
            preferredEmojiSkinToneModifier = bundle
                .getInt(Keys.PREFERRED_EMOJI_SKIN_TONE_MODIFIER)
                .normalizedEmojiSkinToneModifier(),
        )
    }
}

private fun Int.normalizedEmojiSkinToneModifier() =
    takeIf { it in 0x1F3FB..0x1F3FF } ?: 0

data class AutocorrectRequest(
    val sessionId: Long,
    val requestId: Long,
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val composingStart: Int,
    val composingEnd: Int,
    val currentWordStart: Int,
    val currentWordEnd: Int,
    val maxCandidateCount: Int,
    val allowPossiblyOffensive: Boolean,
    val inputTrace: AutocorrectInputTrace = AutocorrectInputTrace.Empty,
    val capsMode: AutocorrectCapsMode = AutocorrectCapsMode.UNSPECIFIED,
) {
    init {
        require(text.length <= AutocorrectPluginContract.MAX_CONTEXT_CHARS)
    }

    fun toBundle() = Bundle().apply {
        putLong(Keys.SESSION_ID, sessionId)
        putLong(Keys.REQUEST_ID, requestId)
        putString(Keys.TEXT, text)
        putInt(Keys.SELECTION_START, selectionStart)
        putInt(Keys.SELECTION_END, selectionEnd)
        putInt(Keys.COMPOSING_START, composingStart)
        putInt(Keys.COMPOSING_END, composingEnd)
        putInt(Keys.CURRENT_WORD_START, currentWordStart)
        putInt(Keys.CURRENT_WORD_END, currentWordEnd)
        putInt(Keys.MAX_CANDIDATE_COUNT, maxCandidateCount.coerceIn(1, AutocorrectPluginContract.MAX_CANDIDATES))
        putBoolean(Keys.ALLOW_POSSIBLY_OFFENSIVE, allowPossiblyOffensive)
        putBundle(Keys.INPUT_TRACE, inputTrace.toBundle())
        putString(Keys.REQUEST_CAPS_MODE, capsMode.name)
    }

    companion object {
        internal fun fromBundle(bundle: Bundle): AutocorrectRequest {
            val text = bundle.getString(Keys.TEXT).orEmpty()
                .takeWireChars(AutocorrectPluginContract.MAX_CONTEXT_CHARS)
            fun boundedOffset(key: String) = bundle.getInt(key, -1)
                .takeIf { it in 0..text.length }
                ?: -1
            fun boundedRange(startKey: String, endKey: String): Pair<Int, Int> {
                val start = boundedOffset(startKey)
                val end = boundedOffset(endKey)
                return if (start >= 0 && end >= start) start to end else -1 to -1
            }
            val selection = boundedRange(Keys.SELECTION_START, Keys.SELECTION_END)
            val composing = boundedRange(Keys.COMPOSING_START, Keys.COMPOSING_END)
            val currentWord = boundedRange(Keys.CURRENT_WORD_START, Keys.CURRENT_WORD_END)
            return AutocorrectRequest(
                sessionId = bundle.getLong(Keys.SESSION_ID),
                requestId = bundle.getLong(Keys.REQUEST_ID),
                text = text,
                selectionStart = selection.first,
                selectionEnd = selection.second,
                composingStart = composing.first,
                composingEnd = composing.second,
                currentWordStart = currentWord.first,
                currentWordEnd = currentWord.second,
                maxCandidateCount = bundle.getInt(Keys.MAX_CANDIDATE_COUNT, 3)
                    .coerceIn(1, AutocorrectPluginContract.MAX_CANDIDATES),
                allowPossiblyOffensive = bundle.getBoolean(Keys.ALLOW_POSSIBLY_OFFENSIVE),
                inputTrace = bundle.getBundle(Keys.INPUT_TRACE)?.toAutocorrectInputTrace()
                    ?: AutocorrectInputTrace.Empty,
                capsMode = bundle.enumValueOrDefault(
                    Keys.REQUEST_CAPS_MODE,
                    AutocorrectCapsMode.UNSPECIFIED,
                ),
            )
        }
    }
}

data class AutocorrectCandidate(
    val id: String,
    val text: String,
    val secondaryText: String? = null,
    val confidence: Double = 0.0,
    val kind: AutocorrectCandidateKind = AutocorrectCandidateKind.COMPLETION,
    val autoCommit: Boolean = false,
    val removable: Boolean = false,
    val visible: Boolean = true,
    val replacementStart: Int = -1,
    val replacementEnd: Int = -1,
    val separatorBehavior: AutocorrectSeparatorBehavior = AutocorrectSeparatorBehavior.DEFAULT,
) {
    internal fun toBundle() = Bundle().apply {
        putString(Keys.ID, id.takeWireChars(AutocorrectPluginContract.MAX_CANDIDATE_ID_CHARS))
        putString(Keys.TEXT, text.takeWireChars(AutocorrectPluginContract.MAX_CANDIDATE_TEXT_CHARS))
        putString(
            Keys.SECONDARY_TEXT,
            secondaryText?.takeWireChars(AutocorrectPluginContract.MAX_SECONDARY_TEXT_CHARS),
        )
        putDouble(Keys.CONFIDENCE, confidence.normalizedConfidence())
        putString(Keys.KIND, kind.name)
        putBoolean(Keys.AUTO_COMMIT, autoCommit)
        putBoolean(Keys.REMOVABLE, removable)
        putBoolean(Keys.VISIBLE, visible)
        putInt(Keys.REPLACEMENT_START, replacementStart)
        putInt(Keys.REPLACEMENT_END, replacementEnd)
        putString(Keys.SEPARATOR_BEHAVIOR, separatorBehavior.name)
    }

    companion object {
        internal fun fromBundle(bundle: Bundle): AutocorrectCandidate? {
            val text = bundle.getString(Keys.TEXT)
                ?.takeWireChars(AutocorrectPluginContract.MAX_CANDIDATE_TEXT_CHARS)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val rawReplacementStart = bundle.getInt(Keys.REPLACEMENT_START, -1)
            val rawReplacementEnd = bundle.getInt(Keys.REPLACEMENT_END, -1)
            val hasReplacement = rawReplacementStart != -1 || rawReplacementEnd != -1
            if (
                hasReplacement &&
                (rawReplacementStart < 0 || rawReplacementEnd < rawReplacementStart)
            ) {
                return null
            }
            return AutocorrectCandidate(
                id = bundle.getString(Keys.ID).orEmpty()
                    .takeWireChars(AutocorrectPluginContract.MAX_CANDIDATE_ID_CHARS),
                text = text,
                secondaryText = bundle.getString(Keys.SECONDARY_TEXT)
                    ?.takeWireChars(AutocorrectPluginContract.MAX_SECONDARY_TEXT_CHARS),
                confidence = bundle.getDouble(Keys.CONFIDENCE).normalizedConfidence(),
                kind = bundle.enumValueOrDefault(Keys.KIND, AutocorrectCandidateKind.COMPLETION),
                autoCommit = bundle.getBoolean(Keys.AUTO_COMMIT),
                removable = bundle.getBoolean(Keys.REMOVABLE),
                visible = bundle.getBoolean(Keys.VISIBLE, true),
                replacementStart = rawReplacementStart,
                replacementEnd = rawReplacementEnd,
                separatorBehavior = bundle.enumValueOrDefault(
                    Keys.SEPARATOR_BEHAVIOR,
                    AutocorrectSeparatorBehavior.DEFAULT,
                ),
            )
        }
    }
}

/**
 * Suggestions returned for the current composing state.
 *
 * [boostedCodePoints] contains Unicode code points which are likely next input for this locale and
 * editor. The host may use them for predictive hit testing; they do not redefine text boundaries.
 */
data class AutocorrectSuggestionResult(
    val candidates: List<AutocorrectCandidate>,
    val boostedCodePoints: Set<Int> = emptySet(),
    val handled: Boolean = true,
) {
    companion object {
        val Empty = AutocorrectSuggestionResult(emptyList())
        val Unhandled = AutocorrectSuggestionResult(emptyList(), handled = false)
    }
}

private fun Double.normalizedConfidence() = takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0

internal fun suggestionResultToBundle(
    requestId: Long,
    result: AutocorrectSuggestionResult,
) = Bundle().apply {
    putLong(Keys.REQUEST_ID, requestId)
    putParcelableArrayList(
        Keys.CANDIDATES,
        ArrayList(
            result.candidates
                .take(AutocorrectPluginContract.MAX_CANDIDATES)
                .map { it.toBundle() },
        ),
    )
    putIntArray(
        Keys.BOOSTED_CODE_POINTS,
        result.boostedCodePoints
            .asSequence()
            .filter(Character::isValidCodePoint)
            .take(AutocorrectPluginContract.MAX_BOOSTED_CODE_POINT_COUNT)
            .toList()
            .toIntArray(),
    )
    putBoolean(Keys.HANDLED, result.handled)
}

@Suppress("DEPRECATION")
fun suggestionResultFromBundle(bundle: Bundle): Pair<Long, AutocorrectSuggestionResult> {
    val candidates = bundle.getParcelableArrayList<Bundle>(Keys.CANDIDATES)
        .orEmpty()
        .take(AutocorrectPluginContract.MAX_CANDIDATES)
        .mapNotNull(AutocorrectCandidate::fromBundle)
    val boostedCodePoints = (bundle.getIntArray(Keys.BOOSTED_CODE_POINTS) ?: intArrayOf())
        .asSequence()
        .take(AutocorrectPluginContract.MAX_BOOSTED_CODE_POINT_COUNT)
        .filter(Character::isValidCodePoint)
        .toSet()
    return bundle.getLong(Keys.REQUEST_ID) to AutocorrectSuggestionResult(
        candidates = candidates,
        boostedCodePoints = boostedCodePoints,
        handled = bundle.getBoolean(Keys.HANDLED, true),
    )
}

@Deprecated("Use suggestionResultFromBundle to retain optional provider hints")
fun candidatesFromBundle(bundle: Bundle): Pair<Long, List<AutocorrectCandidate>> {
    val (requestId, result) = suggestionResultFromBundle(bundle)
    return requestId to result.candidates
}

fun candidateEventBundle(
    sessionId: Long,
    candidateId: String,
    acceptanceKind: AutocorrectAcceptanceKind? = null,
) = Bundle().apply {
    putLong(Keys.SESSION_ID, sessionId)
    putString(Keys.ID, candidateId.takeWireChars(AutocorrectPluginContract.MAX_CANDIDATE_ID_CHARS))
    acceptanceKind?.let { putString(Keys.ACCEPTANCE_KIND, it.name) }
}

fun removalRequestBundle(sessionId: Long, requestId: Long, candidateId: String) =
    candidateEventBundle(sessionId, candidateId).apply {
        putLong(Keys.REQUEST_ID, requestId)
    }

/** Legacy form for hosts which cannot supply an authoritative final editor snapshot. */
fun finishSessionBundle(sessionId: Long) = Bundle().apply {
    putLong(Keys.SESSION_ID, sessionId)
}

/** Finish form whose snapshot remains authoritative even when it sanitizes to empty text. */
fun finishSessionBundle(
    sessionId: Long,
    finalRequest: AutocorrectRequest,
) = Bundle().apply {
    val request = finalRequest.sanitizedForSessionFinish(sessionId)
        ?: emptyFinalRequest(sessionId)
    putLong(Keys.SESSION_ID, sessionId)
    putBoolean(Keys.HAS_FINAL_REQUEST, true)
    putBundle(Keys.FINAL_REQUEST, request.toBundle())
}

fun finishSessionResultFromBundle(bundle: Bundle) = bundle.getLong(Keys.SESSION_ID)

internal fun finalRequestFromFinishSessionBundle(
    bundle: Bundle,
    sessionId: Long,
): AutocorrectRequest? {
    if (!bundle.getBoolean(Keys.HAS_FINAL_REQUEST)) return null
    return bundle.getBundle(Keys.FINAL_REQUEST)
        ?.let(AutocorrectRequest::fromBundle)
        ?.sanitizedForSessionFinish(sessionId)
        ?: emptyFinalRequest(sessionId)
}

private fun emptyFinalRequest(sessionId: Long) = AutocorrectRequest(
    sessionId = sessionId,
    requestId = 0L,
    text = "",
    selectionStart = 0,
    selectionEnd = 0,
    composingStart = -1,
    composingEnd = -1,
    currentWordStart = -1,
    currentWordEnd = -1,
    maxCandidateCount = 1,
    allowPossiblyOffensive = false,
)

private fun AutocorrectRequest.sanitizedForSessionFinish(
    expectedSessionId: Long,
): AutocorrectRequest? {
    if (
        sessionId != expectedSessionId ||
        selectionStart != selectionEnd ||
        selectionStart !in 0..text.length
    ) {
        return null
    }
    fun validRange(start: Int, end: Int) =
        start >= 0 && end in start..text.length
    val validComposing = validRange(composingStart, composingEnd)
    val validCurrentWord = validRange(currentWordStart, currentWordEnd)
    return copy(
        composingStart = if (validComposing) composingStart else -1,
        composingEnd = if (validComposing) composingEnd else -1,
        currentWordStart = if (validCurrentWord) currentWordStart else -1,
        currentWordEnd = if (validCurrentWord) currentWordEnd else -1,
        inputTrace = AutocorrectInputTrace.Empty,
    )
}

fun removalResultFromBundle(bundle: Bundle): Pair<Long, Boolean> {
    return bundle.getLong(Keys.REQUEST_ID) to bundle.getBoolean(Keys.REMOVED)
}

internal object Keys {
    const val SESSION_ID = "sessionId"
    const val REQUEST_ID = "requestId"
    const val PRIMARY_LANGUAGE_TAG = "primaryLanguageTag"
    const val SECONDARY_LANGUAGE_TAGS = "secondaryLanguageTags"
    const val INPUT_TYPE = "inputType"
    const val CAPS_MODE = "capsMode"
    const val ALLOW_PERSONALIZED_LEARNING = "allowPersonalizedLearning"
    const val EDITOR_FLAGS = "editorFlags"
    const val PREFERRED_EMOJI_SKIN_TONE_MODIFIER = "preferredEmojiSkinToneModifier"
    const val TEXT = "text"
    const val SELECTION_START = "selectionStart"
    const val SELECTION_END = "selectionEnd"
    const val COMPOSING_START = "composingStart"
    const val COMPOSING_END = "composingEnd"
    const val CURRENT_WORD_START = "currentWordStart"
    const val CURRENT_WORD_END = "currentWordEnd"
    const val MAX_CANDIDATE_COUNT = "maxCandidateCount"
    const val ALLOW_POSSIBLY_OFFENSIVE = "allowPossiblyOffensive"
    const val INPUT_TRACE = "inputTrace"
    const val REQUEST_CAPS_MODE = "requestCapsMode"
    const val ID = "id"
    const val ACCEPTANCE_KIND = "acceptanceKind"
    const val SECONDARY_TEXT = "secondaryText"
    const val CONFIDENCE = "confidence"
    const val KIND = "kind"
    const val AUTO_COMMIT = "autoCommit"
    const val REMOVABLE = "removable"
    const val VISIBLE = "visible"
    const val REPLACEMENT_START = "replacementStart"
    const val REPLACEMENT_END = "replacementEnd"
    const val SEPARATOR_BEHAVIOR = "separatorBehavior"
    const val CANDIDATES = "candidates"
    const val BOOSTED_CODE_POINTS = "boostedCodePoints"
    const val HANDLED = "handled"
    const val REMOVED = "removed"
    const val HAS_FINAL_REQUEST = "hasFinalRequest"
    const val FINAL_REQUEST = "finalRequest"
}

private inline fun <reified T : Enum<T>> Bundle.enumValueOrDefault(key: String, default: T): T {
    return getString(key)?.let { value ->
        enumValues<T>().firstOrNull { it.name == value }
    } ?: default
}
