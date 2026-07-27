/*
 * Copyright (C) 2024 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.uix

import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo.KIND_EMOJI_SUGGESTION
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo.KIND_TYPED
import org.futo.inputmethod.latin.SuggestionBlacklist

data class SuggestionLayout(
    val autocorrectMatch: SuggestedWordInfo?,
    val sortedMatches: List<SuggestedWordInfo>,
    val emojiMatches: List<SuggestedWordInfo>,
    val verbatimWord: SuggestedWordInfo?,
    val areSuggestionsClueless: Boolean,
    val isGestureBatch: Boolean,
    val swipePrimaryElement: SuggestedWordInfo?,
    val presentableSuggestions: List<SuggestedWordInfo>,
)

fun SuggestedWords.getInfoOrNull(idx: Int): SuggestedWordInfo? = try {
    getInfo(idx)
} catch (_: IndexOutOfBoundsException) {
    null
}

fun makeSuggestionLayout(
    words: SuggestedWords,
    blacklist: SuggestionBlacklist?,
    swipeTailRemovePrimarySuggestion: Boolean,
): SuggestionLayout {
    val isGestureBatch = words.mInputStyle == SuggestedWords.INPUT_STYLE_UPDATE_BATCH
    val isSwipeTail = words.mInputStyle == SuggestedWords.INPUT_STYLE_TAIL_BATCH
    val typedWord = words.getInfoOrNull(SuggestedWords.INDEX_OF_TYPED_WORD)
        ?.takeIf { it.kind == KIND_TYPED && blacklist?.isSuggestedWordOk(it) != false }
    val autocorrectMatch = words.getInfoOrNull(SuggestedWords.INDEX_OF_AUTO_CORRECTION)
        ?.takeIf { words.mWillAutoCorrect }
    val emojiMatches = words.mSuggestedWordInfoList.filter { it.kind == KIND_EMOJI_SUGGESTION }
    val sortedMatches = words.mSuggestedWordInfoList.filter {
        it != typedWord && it.kind != KIND_TYPED && it != autocorrectMatch &&
            it !in emojiMatches &&
            (isGestureBatch || autocorrectMatch == null || typedWord == null ||
                it.mWord != typedWord.mWord)
    }.toMutableList()

    var swipePrimaryElement: SuggestedWordInfo? = null
    if (isSwipeTail && sortedMatches.size > 1) {
        if (swipeTailRemovePrimarySuggestion) {
            sortedMatches.removeAt(0)
        } else {
            swipePrimaryElement = sortedMatches[0]
        }
    }
    val areSuggestionsClueless = (autocorrectMatch ?: sortedMatches.getOrNull(0))?.let {
        it.mOriginatesFromTransformerLM && it.mScore < -50
    } ?: false
    return SuggestionLayout(
        autocorrectMatch = autocorrectMatch,
        sortedMatches = sortedMatches,
        emojiMatches = emojiMatches,
        verbatimWord = typedWord,
        areSuggestionsClueless = areSuggestionsClueless,
        isGestureBatch = isGestureBatch,
        swipePrimaryElement = swipePrimaryElement,
        presentableSuggestions = (listOf(typedWord, autocorrectMatch) + sortedMatches)
            .filterNotNull(),
    )
}

/** The same fixed slots used by FUTO's classic action bar. */
fun SuggestionLayout.classicSuggestions(): List<SuggestedWordInfo?> {
    if (isGestureBatch || (emojiMatches.isEmpty() && presentableSuggestions.size <= 1)) {
        return listOf(presentableSuggestions.firstOrNull())
    }
    if (autocorrectMatch != null) {
        val first = emojiMatches.firstOrNull() ?: sortedMatches.getOrNull(0)
        val third = verbatimWord?.takeIf { it.mWord != autocorrectMatch.mWord }
            ?: sortedMatches.getOrNull(if (emojiMatches.isEmpty()) 1 else 0)
        return listOf(first, autocorrectMatch, third)
    }
    val offset = if (emojiMatches.isEmpty()) 1 else 0
    return listOf(
        emojiMatches.firstOrNull() ?: sortedMatches.getOrNull(1),
        sortedMatches.getOrNull(0),
        sortedMatches.getOrNull(1 + offset),
    )
}
