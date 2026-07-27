package org.futo.inputmethod.latin

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.SUGGESTION_BLACKLIST
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.getSettingFlow
import org.futo.inputmethod.latin.uix.settings.BadWordMode
import org.futo.inputmethod.latin.uix.settings.shouldBlockWord

class SuggestionBlacklist(
    val settings: Settings,
    val context: Context,
    private val scope: CoroutineScope,
) {
    private val userBlacklistedWords =
        MutableStateFlow(context.getSetting(SUGGESTION_BLACKLIST).toSet())

    private val mode get() = BadWordMode(
        language = settings.current.mLocale.language.lowercase(),
        blockSlurs = settings.current.mBlockSlurs,
        blockOffensive = settings.current.mBlockPotentiallyOffensive
    )

    fun init() {
        scope.launch {
            context.getSettingFlow(SUGGESTION_BLACKLIST).collect { value ->
                userBlacklistedWords.value = value.toSet()
            }
        }
    }

    suspend fun awaitRefresh(expected: Set<String>) {
        userBlacklistedWords.first { it == expected }
    }

    private fun isWordOk(word: String): Boolean {
        if(word in userBlacklistedWords.value) return false
        if(shouldBlockWord(mode, word)) return false
        return true
    }

    fun isSuggestedWordOk(word: SuggestedWordInfo): Boolean {
        return word.isKindOf(SuggestedWordInfo.KIND_TYPED) || isWordOk(word.mWord)
    }

    fun filterBlacklistedSuggestions(suggestions: SuggestedWords): SuggestedWords {
        val typedWord = when(suggestions.mInputStyle) {
            SuggestedWords.INPUT_STYLE_UPDATE_BATCH,
            SuggestedWords.INPUT_STYLE_TAIL_BATCH -> null

            else -> suggestions.mTypedWordInfo
        }

        val filter: (SuggestedWordInfo) -> Boolean = { it -> isSuggestedWordOk(it) || (it == typedWord) }

        val shouldStillAutocorrect = suggestions.mWillAutoCorrect
                && (suggestions.size() > SuggestedWords.INDEX_OF_AUTO_CORRECTION)
                && filter(suggestions.getInfo(SuggestedWords.INDEX_OF_AUTO_CORRECTION))

        val filtered = suggestions.mSuggestedWordInfoList.filter(filter)

        return SuggestedWords(
            ArrayList(filtered),
            suggestions.mRawSuggestions?.filter {
                it == suggestions.mTypedWordInfo || isWordOk(it.mWord)
            }?.let { ArrayList(it) },
            suggestions.mTypedWordInfo,
            suggestions.mTypedWordValid,
            shouldStillAutocorrect,
            suggestions.mIsObsoleteSuggestions,
            suggestions.mInputStyle,
            suggestions.mSequenceNumber,
            suggestions.mHighlightedCandidate
        )
    }
}
