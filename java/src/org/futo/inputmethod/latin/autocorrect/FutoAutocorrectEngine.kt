/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.florisboard.autocorrect.api.AutocorrectAcceptanceKind
import org.florisboard.autocorrect.api.AutocorrectCandidate
import org.florisboard.autocorrect.api.AutocorrectCandidateKind
import org.florisboard.autocorrect.api.AutocorrectInputMode
import org.florisboard.autocorrect.api.AutocorrectRequest
import org.florisboard.autocorrect.api.AutocorrectSession
import org.florisboard.autocorrect.api.AutocorrectSuggestionResult
import org.florisboard.autocorrect.api.AutocorrectTextEvent
import org.florisboard.autocorrect.api.AutocorrectTextEventKind
import org.futo.inputmethod.event.Event
import org.futo.inputmethod.keyboard.Keyboard
import org.futo.inputmethod.latin.BinaryDictionary
import org.futo.inputmethod.latin.Dictionary
import org.futo.inputmethod.latin.DictionaryFacilitator
import org.futo.inputmethod.latin.DictionaryFacilitatorProvider
import org.futo.inputmethod.latin.InputAttributes
import org.futo.inputmethod.latin.NgramContext
import org.futo.inputmethod.latin.Suggest
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.SuggestionBlacklist
import org.futo.inputmethod.latin.WordComposer
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.inputmethod.latin.common.ResizableIntArray
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.settings.SettingsValuesForSuggestion
import org.futo.inputmethod.latin.uix.EmojiTracker.useEmoji
import org.futo.inputmethod.latin.uix.SUGGESTION_BLACKLIST
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.utils.NgramContextUtils
import org.futo.inputmethod.latin.xlm.AllowTransformerOnNonQWERTYLayouts
import org.futo.inputmethod.latin.xlm.AutocorrectThresholdSetting
import org.futo.inputmethod.latin.xlm.BinaryDictTransformerWeightSetting
import org.futo.inputmethod.latin.xlm.LanguageModel
import org.futo.inputmethod.latin.xlm.ModelInfoLoader
import org.futo.inputmethod.latin.xlm.ModelLoadingException
import org.futo.inputmethod.latin.xlm.ModelPaths
import org.futo.inputmethod.latin.xlm.UserDictionaryObserver
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

internal class FutoAutocorrectEngine(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
) {
    private data class CandidateRecord(
        val word: String,
        val ngramContext: NgramContext,
        val blockPotentiallyOffensive: Boolean,
        val isEmoji: Boolean,
    )

    private data class PreparedInput(
        val composer: WordComposer,
        val ngramContext: NgramContext,
        val keyboard: Keyboard,
        val typedWord: String,
        val isGesture: Boolean,
    )

    private data class RankedWord(
        val info: SuggestedWordInfo,
        var score: Double,
    )

    private val settings = Settings.getInstance()
    private val dictionary: DictionaryFacilitator =
        DictionaryFacilitatorProvider.getDictionaryFacilitator(false)
    private val suggest = Suggest(dictionary)
    private val suggestionBlacklist =
        SuggestionBlacklist(settings, context, lifecycleScope).also { it.init() }
    private val userDictionary = UserDictionaryObserver(context)
    private val stateGuard = Mutex()
    private val candidates = LinkedHashMap<String, CandidateRecord>()

    private var session: AutocorrectSession? = null
    private var lastRequest: AutocorrectRequest? = null
    private var lastKeyboard: Keyboard? = null
    private var keyboardSignature = 0
    private var languageModel: LanguageModel? = null
    private var languageModelKey: String? = null
    private var modelPreparation: Job? = null
    @Volatile private var preparedModels: Map<String, ModelInfoLoader>? = null
    private var transformerTimeouts = 0
    private var transformerDisabled = false
    @Volatile private var modelsInvalidated = false
    private val modelUpdates = lifecycleScope.launch(Dispatchers.Default) {
        ModelPaths.modelOptionsUpdated.collect {
            modelsInvalidated = true
            preparedModels = null
        }
    }

    suspend fun startSession(newSession: AutocorrectSession) {
        stateGuard.withLock {
            session = newSession
            lastRequest = null
            candidates.clear()
            transformerTimeouts = 0
            transformerDisabled = false
            lastKeyboard = null
            keyboardSignature = 0
        }
        val locales = buildList {
            add(Locale.forLanguageTag(newSession.primaryLanguageTag))
            newSession.secondaryLanguageTags.mapTo(this) { Locale.forLanguageTag(it) }
        }.filter { it.language.isNotBlank() }.distinctBy(Locale::toLanguageTag)
        val primaryLocale = locales.firstOrNull() ?: Locale.ENGLISH
        val editorInfo = EditorInfo().apply {
            inputType = newSession.inputType
            packageName = context.packageName
            if (!newSession.allowPersonalizedLearning) {
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
        }
        settings.loadSettings(
            context,
            primaryLocale,
            InputAttributes(editorInfo, false, context.packageName),
        )
        val usePersonalized = settings.current.mUsePersonalizedDicts &&
            newSession.allowPersonalizedLearning
        dictionary.resetDictionaries(
            context,
            locales.ifEmpty { listOf(primaryLocale) },
            false,
            usePersonalized,
            false,
            null,
            "",
            null,
        )
        dictionary.onStartInput()
        withContext(Dispatchers.IO) {
            runCatching {
                dictionary.waitForLoadingMainDictionaries(250, TimeUnit.MILLISECONDS)
            }
        }
        if (
            settings.current.mTransformerPredictionEnabled &&
            preparedModels == null &&
            modelPreparation?.isActive != true
        ) {
            modelPreparation = lifecycleScope.launch(Dispatchers.IO) {
                preparedModels = try {
                    ModelPaths.getModelOptions(context)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    null
                }
            }
        }
    }

    suspend fun suggest(request: AutocorrectRequest): AutocorrectSuggestionResult {
        val activeSession = stateGuard.withLock {
            if (session?.sessionId != request.sessionId) {
                return AutocorrectSuggestionResult.Empty
            }
            lastRequest = request
            session
        } ?: return AutocorrectSuggestionResult.Empty
        if (!settings.current.isSuggestionsEnabledPerUserSettings) {
            return AutocorrectSuggestionResult.Empty
        }
        if (
            request.inputTrace.mode == AutocorrectInputMode.GESTURE &&
            !settings.current.mGestureInputEnabled
        ) {
            return AutocorrectSuggestionResult.Empty
        }
        val prepared = prepareInput(activeSession, request)
            ?: return AutocorrectSuggestionResult.Empty
        val settingsForSuggestion = SettingsValuesForSuggestion(
            !request.allowPossiblyOffensive,
            settings.current.mTransformerPredictionEnabled,
        )
        suggest.setAutoCorrectionThreshold(settings.current.mAutoCorrectionThreshold)
        suggest.setPlausibilityThreshold(settings.current.mPlausibilityThreshold)
        var dictionaryWords: SuggestedWords? = null
        suggest.getSuggestedWords(
            prepared.composer,
            prepared.ngramContext,
            prepared.keyboard,
            settingsForSuggestion,
            settings.current.mAutoCorrectionEnabledPerUserSettings,
            if (prepared.isGesture) {
                SuggestedWords.INPUT_STYLE_TAIL_BATCH
            } else {
                SuggestedWords.INPUT_STYLE_TYPING
            },
            request.requestId.toInt(),
        ) { dictionaryWords = it }
        val filteredDictionaryWords = dictionaryWords?.let {
            suggestionBlacklist.filterBlacklistedSuggestions(it)
        } ?: SuggestedWords.getEmptyInstance()
        val transformerWords = if (
            !prepared.isGesture &&
            settings.current.mTransformerPredictionEnabled &&
            !transformerDisabled &&
            !settings.current.mInputAttributes.mIsEmailField &&
            prepared.typedWord.length < BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH &&
            isTransformerLayoutSupported(request)
        ) {
            val modelLocale = dictionary.mostConfidentLocale.takeIf {
                it.language.isNotBlank()
            } ?: dictionary.primaryLocale
            val model = getLanguageModel(modelLocale)
            if (model == null) {
                null
            } else {
                val result = try {
                    withTimeoutOrNull(325L) {
                        model.getSuggestions(
                            prepared.composer.composedDataSnapshot,
                            prepared.ngramContext,
                            prepared.keyboard.proximityInfo.nativeProximityInfo,
                            context.getSetting(AutocorrectThresholdSetting),
                            userDictionary.getWords(dictionary.locales).map { it.word },
                            context.getSetting(SUGGESTION_BLACKLIST).toTypedArray(),
                        )
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (error is ModelLoadingException) transformerDisabled = true
                    null
                }
                if (result == null) {
                    transformerTimeouts++
                    transformerDisabled = transformerTimeouts > 5
                } else {
                    transformerTimeouts = 0
                }
                result
            }
        } else {
            null
        }
        val candidates = toCandidates(
            request = request,
            prepared = prepared,
            dictionaryWords = filteredDictionaryWords,
            transformerWords = transformerWords.orEmpty(),
        )
        val boostedCodePoints = if (
            !prepared.isGesture &&
            prepared.typedWord.isNotBlank() &&
            settings.current.mUseDictionaryKeyBoosting
        ) {
            mapBoostsToLayout(
                suggest.getValidNextCodePoints(prepared.composer),
                request,
            )
        } else {
            emptySet()
        }
        return AutocorrectSuggestionResult(candidates, boostedCodePoints)
    }

    suspend fun accepted(
        sessionId: Long,
        candidateId: String,
        @Suppress("UNUSED_PARAMETER") acceptanceKind: AutocorrectAcceptanceKind,
    ) {
        val record = stateGuard.withLock {
            if (session?.sessionId != sessionId || session?.allowPersonalizedLearning != true) {
                return
            }
            candidates[candidateId]
        } ?: return
        if (record.isEmoji) {
            context.useEmoji(record.word)
        } else {
            learn(record.word, record.ngramContext, record.blockPotentiallyOffensive)
        }
    }

    suspend fun reverted(sessionId: Long, candidateId: String) {
        val record = stateGuard.withLock {
            if (session?.sessionId != sessionId || session?.allowPersonalizedLearning != true) {
                return
            }
            candidates[candidateId]
        } ?: return
        if (record.isEmoji) return
        dictionary.unlearnFromUserHistory(
            record.word,
            record.ngramContext,
            nowSeconds(),
            Constants.EVENT_REVERT,
        )
    }

    suspend fun remove(sessionId: Long, candidateId: String): Boolean {
        val record = stateGuard.withLock {
            if (session?.sessionId != sessionId) return false
            candidates[candidateId]
        } ?: return false
        context.setSetting(
            SUGGESTION_BLACKLIST,
            context.getSetting(SUGGESTION_BLACKLIST) + record.word,
        )
        if (session?.allowPersonalizedLearning == true && !record.isEmoji) {
            dictionary.unlearnFromUserHistory(
                record.word,
                record.ngramContext,
                nowSeconds(),
                Constants.EVENT_REJECTION,
            )
        }
        return true
    }

    suspend fun textEvent(event: AutocorrectTextEvent) {
        val request = stateGuard.withLock {
            if (session?.sessionId != event.sessionId ||
                session?.allowPersonalizedLearning != true
            ) {
                return
            }
            lastRequest
        } ?: return
        val ngramContext = ngramContext(request)
        when (event.kind) {
            AutocorrectTextEventKind.COMMIT_TYPED,
            AutocorrectTextEventKind.COMMIT_GESTURE -> {
                learn(event.text, ngramContext, !request.allowPossiblyOffensive)
            }
            AutocorrectTextEventKind.DELETE_BACKWARD,
            AutocorrectTextEventKind.DELETE_FORWARD -> {
                if (event.text.isNotBlank()) {
                    dictionary.unlearnFromUserHistory(
                        event.text,
                        ngramContext,
                        nowSeconds(),
                        Constants.EVENT_BACKSPACE,
                    )
                }
            }
        }
    }

    suspend fun finishSession(sessionId: Long) {
        stateGuard.withLock {
            if (session?.sessionId != sessionId) return
            session = null
            lastRequest = null
            candidates.clear()
        }
        dictionary.onFinishInput(context)
        withContext(Dispatchers.IO) {
            dictionary.flushUserHistoryDictionaries()
        }
    }

    suspend fun clearHistory(): Boolean = withContext(Dispatchers.IO) {
        dictionary.clearUserHistoryDictionary(context)
    }

    suspend fun close() {
        modelUpdates.cancel()
        modelPreparation?.cancel()
        languageModel?.closeInternalLocked()
        languageModel = null
        languageModelKey = null
        dictionary.closeDictionaries()
        userDictionary.unregister()
    }

    private suspend fun prepareInput(
        session: AutocorrectSession,
        request: AutocorrectRequest,
    ): PreparedInput? {
        val locale = dictionary.primaryLocale.takeIf { it.language.isNotBlank() }
            ?: Locale.forLanguageTag(session.primaryLanguageTag)
        val signature = request.inputTrace.keys.hashCode()
        if (request.inputTrace.keys.isNotEmpty() &&
            (lastKeyboard == null || signature != keyboardSignature)
        ) {
            lastKeyboard = FlorisVirtualKeyboard.create(
                context,
                locale,
                session.inputType,
                request.inputTrace,
            )
            keyboardSignature = signature
        }
        val keyboard = lastKeyboard ?: FlorisVirtualKeyboard.createFallback(
            context,
            locale,
            session.inputType,
        ).also { lastKeyboard = it } ?: return null
        val composer = WordComposer()
        val typedWord = currentWord(request)
        val isGesture = request.inputTrace.mode == AutocorrectInputMode.GESTURE
        if (isGesture) {
            val points = request.inputTrace.gesturePoints
            val x = ResizableIntArray(points.size)
            val y = ResizableIntArray(points.size)
            val time = ResizableIntArray(points.size)
            points.forEach { point ->
                x.add((point.x * keyboard.mBaseWidth).toInt())
                y.add((point.y * keyboard.mBaseHeight).toInt())
                time.add(point.elapsedTimeMillis)
            }
            val pointers = InputPointers(points.size).apply {
                onPointerDown(0)
                append(0, time, x, y, 0, points.size)
                onPointerUp(0)
            }
            composer.setBatchInputPointers(pointers)
            composer.setBatchInputWord("")
        } else {
            val points = request.inputTrace.points
            typedWord.codePoints().toArray().forEachIndexed { index, codePoint ->
                val point = points.getOrNull(index)
                val x = point?.let { (it.x * keyboard.mBaseWidth).toInt() }
                    ?: keyboard.getKey(codePoint)?.let { it.x + it.width / 2 }
                    ?: -1
                val y = point?.let { (it.y * keyboard.mBaseHeight).toInt() }
                    ?: keyboard.getKey(codePoint)?.let { it.y + it.height / 2 }
                    ?: -1
                val event = Event.createEventForCodePointFromAlreadyTypedText(codePoint, x, y)
                composer.applyProcessedEvent(composer.processEvent(event))
            }
        }
        return PreparedInput(
            composer = composer,
            ngramContext = ngramContext(request),
            keyboard = keyboard,
            typedWord = typedWord,
            isGesture = isGesture,
        )
    }

    private fun ngramContext(request: AutocorrectRequest): NgramContext {
        val selection = request.selectionStart.coerceIn(0, request.text.length)
        val wordStart = request.currentWordStart.takeIf { it in 0..selection } ?: selection
        val prefix = request.text.substring(0, wordStart)
        return NgramContextUtils.getNgramContextFromNthPreviousWord(
            prefix,
            settings.current.mSpacingAndPunctuations,
            1,
        ).apply {
            fullContext = request.text.substring(0, selection)
        }
    }

    private fun currentWord(request: AutocorrectRequest): String {
        val start = request.currentWordStart
        val end = request.currentWordEnd
        return if (start >= 0 && end in start..request.text.length) {
            request.text.substring(start, end)
        } else {
            ""
        }
    }

    private suspend fun getLanguageModel(locale: Locale): LanguageModel? {
        if (modelPreparation?.isActive == true) return null
        if (modelsInvalidated) {
            languageModel?.closeInternalLocked()
            languageModel = null
            languageModelKey = null
            modelsInvalidated = false
        }
        val models = preparedModels ?: try {
            ModelPaths.getModelOptions(context).also { preparedModels = it }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            transformerDisabled = true
            null
        } ?: return null
        val model = models[locale.language] ?: return null
        val key = "${locale.language}:${model.path.absolutePath}"
        if (languageModelKey != key) {
            languageModel?.closeInternalLocked()
            languageModel = LanguageModel(context, lifecycleScope, model, locale)
            languageModelKey = key
        }
        return languageModel
    }

    private fun isTransformerLayoutSupported(request: AutocorrectRequest): Boolean {
        if (context.getSetting(AllowTransformerOnNonQWERTYLayouts)) return true
        if (request.inputTrace.keys.isEmpty()) return true
        val letters = request.inputTrace.keys.asSequence()
            .filter { key ->
                key.text.codePointCount(0, key.text.length) == 1 &&
                    Character.isLetter(key.text.codePointAt(0))
            }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .joinToString(separator = "") { it.text.lowercase(Locale.ROOT) }
        return letters == "qwertyuiopasdfghjklzxcvbnm"
    }

    private fun mapBoostsToLayout(
        codePoints: Set<Int>,
        request: AutocorrectRequest,
    ): Set<Int> {
        val layoutCodePoints = request.inputTrace.keys.mapNotNull { key ->
            key.text.takeIf(String::isNotEmpty)?.codePointAt(0)
        }
        return codePoints.mapTo(linkedSetOf()) { codePoint ->
            layoutCodePoints.firstOrNull {
                Character.toLowerCase(it) == Character.toLowerCase(codePoint)
            } ?: codePoint
        }
    }

    private suspend fun toCandidates(
        request: AutocorrectRequest,
        prepared: PreparedInput,
        dictionaryWords: SuggestedWords,
        transformerWords: List<SuggestedWordInfo>,
    ): List<AutocorrectCandidate> {
        val typedInfo = dictionaryWords.typedWordInfo
        val dictionaryCandidates = dictionaryWords.mSuggestedWordInfoList
            .filter { it !== typedInfo }
        var transformerWeight = context.getSetting(BinaryDictTransformerWeightSetting)
        if (dictionary.locales.size > 1) transformerWeight = 1.0f
        if (transformerWeight <= 0f && dictionaryCandidates.isEmpty()) {
            transformerWeight = 1.0f
        }
        val transformerCandidates = transformerWords.mapIndexedNotNull { index, info ->
            if (transformerWeight == Float.NEGATIVE_INFINITY) {
                null
            } else {
                val weightedScore = if (transformerWeight == Float.POSITIVE_INFINITY) {
                    info.mScore.toDouble() * 1_000.0
                } else {
                    info.mScore.toDouble() * transformerWeight - index
                }
                RankedWord(info, weightedScore)
            }
        }
        val dictionaryScores = dictionaryCandidates.associate {
            it.word to it.mScore.toDouble()
        }.toMutableMap()
        var dictionaryBest = dictionaryCandidates
            .filterNot { it.isKindOf(SuggestedWordInfo.KIND_EMOJI_SUGGESTION) }
            .maxByOrNull { it.mScore }
        val dictionaryAlternative = dictionaryCandidates
            .filterNot {
                it.isKindOf(SuggestedWordInfo.KIND_EMOJI_SUGGESTION) ||
                    (it.isKindOf(SuggestedWordInfo.KIND_WHITELIST) &&
                        it.mSourceDict?.mDictType == Dictionary.TYPE_MAIN)
            }
            .maxByOrNull { it.mScore }
        val transformerBest = transformerCandidates.maxByOrNull { it.score }?.info
        if (
            dictionaryBest != null &&
            dictionaryAlternative != null &&
            dictionaryAlternative !== dictionaryBest &&
            dictionaryAlternative.word == transformerBest?.word
        ) {
            dictionaryScores[dictionaryBest.word] = minOf(
                dictionaryScores.getValue(dictionaryBest.word),
                dictionaryAlternative.mScore.toDouble() - 1.0,
            )
            dictionaryBest = dictionaryAlternative
        }
        val spaceCandidate = dictionaryBest?.takeIf { it.word.count { char -> char == ' ' } == 1 }
        if (spaceCandidate != null) {
            transformerCandidates.filter { transformer ->
                spaceCandidate.mScore > transformer.score / 3.0 ||
                    '-' in transformer.info.word ||
                    prepared.typedWord.length > ceil(transformer.info.word.length * 1.5)
            }.maxOfOrNull { it.score + 1.0 }?.let { score ->
                dictionaryScores[spaceCandidate.word] = maxOf(
                    dictionaryScores.getValue(spaceCandidate.word),
                    score,
                )
            }
        }
        val historyCandidate = dictionaryBest?.takeIf {
            it.mSourceDict?.mDictType == Dictionary.TYPE_USER_HISTORY &&
                it.mScore > 100 &&
                (it.word == prepared.typedWord || it.word.length > 1)
        }
        if (
            historyCandidate != null &&
            transformerBest != null
        ) {
            dictionaryScores[historyCandidate.word] = maxOf(
                dictionaryScores.getValue(historyCandidate.word),
                transformerCandidates.maxOfOrNull { it.score }?.plus(1.0) ?: 0.0,
            )
        }
        val ranked = linkedMapOf<String, RankedWord>()
        if (transformerWeight != Float.POSITIVE_INFINITY) {
            dictionaryCandidates.forEach { info ->
                ranked[info.word] = RankedWord(info, dictionaryScores.getValue(info.word))
            }
        }
        transformerCandidates.forEach { transformer ->
            ranked[transformer.info.word]?.let {
                it.score = it.score.coerceAtLeast(0.0) + transformer.score.coerceAtLeast(0.0)
            } ?: run {
                ranked[transformer.info.word] = transformer
            }
        }
        val sameWord = dictionaryBest?.takeIf { it.word == transformerBest?.word }
        val sameWordLowercase = dictionaryBest?.takeIf {
            it.word == transformerBest?.word?.lowercase(Locale.ROOT)
        }
        (sameWord ?: sameWordLowercase)?.let { agreement ->
            val transformerScore = transformerCandidates
                .firstOrNull { it.info === transformerBest }
                ?.score
                ?: 0.0
            val score = if (sameWord != null) {
                dictionaryScores.getValue(agreement.word).coerceAtLeast(0.0) +
                    transformerScore.coerceAtLeast(0.0)
            } else {
                maxOf(dictionaryScores.getValue(agreement.word), transformerScore + 1.0)
            }
            ranked[agreement.word] = RankedWord(agreement, score)
        }
        val autoWord = when {
            prepared.isGesture -> null
            !settings.current.mAutoCorrectionEnabledPerUserSettings -> null
            sameWord != null -> sameWord.word
            sameWordLowercase != null -> sameWordLowercase.word
            dictionaryWords.mWillAutoCorrect -> dictionaryWords.autoCorrectCandidate?.word
            transformerBest?.isAprapreateForAutoCorrection == true &&
                prepared.typedWord.length > 1 -> transformerBest.word
            else -> null
        }
        val rankedWords = ranked.values.sortedByDescending(RankedWord::score).toMutableList()
        autoWord?.let { word ->
            val index = rankedWords.indexOfFirst { it.info.word == word }
            if (index > 0) rankedWords.add(0, rankedWords.removeAt(index))
        }
        val ordered = buildList {
            if (!prepared.isGesture && prepared.typedWord.isNotBlank()) {
                add(
                    typedInfo ?: SuggestedWordInfo(
                        prepared.typedWord,
                        "",
                        0,
                        SuggestedWordInfo.KIND_TYPED,
                        null,
                        0,
                        0,
                    ),
                )
            }
            addAll(rankedWords.map(RankedWord::info))
        }.filter { info ->
            info === typedInfo ||
                info.word == prepared.typedWord ||
                (suggestionBlacklist.isSuggestedWordOk(info) &&
                    (request.allowPossiblyOffensive || !info.isPossiblyOffensive))
        }.distinctBy(SuggestedWordInfo::getWord)
            .take(request.maxCandidateCount)
        val blockPotentiallyOffensive = !request.allowPossiblyOffensive
        val replacementStart = request.currentWordStart.takeIf {
            !prepared.isGesture && it >= 0
        } ?: -1
        val replacementEnd = request.currentWordEnd.takeIf {
            replacementStart >= 0 && it in replacementStart..request.text.length
        } ?: -1
        val output = ordered.mapIndexed { index, info ->
            val kind = when {
                info.isKindOf(SuggestedWordInfo.KIND_EMOJI_SUGGESTION) ->
                    AutocorrectCandidateKind.EMOJI
                prepared.isGesture -> AutocorrectCandidateKind.CORRECTION
                !prepared.isGesture && info.word == prepared.typedWord ->
                    AutocorrectCandidateKind.TYPED
                prepared.typedWord.isBlank() -> AutocorrectCandidateKind.NEXT_WORD
                info.word.startsWith(prepared.typedWord, ignoreCase = true) ->
                    AutocorrectCandidateKind.COMPLETION
                else -> AutocorrectCandidateKind.CORRECTION
            }
            val id = "${request.requestId}:$index:${info.word.hashCode()}"
            id to AutocorrectCandidate(
                id = id,
                text = info.word,
                confidence = 1.0 - index.toDouble() / (ordered.size + 1.0),
                kind = kind,
                autoCommit = info.word == autoWord,
                removable = kind != AutocorrectCandidateKind.TYPED,
                replacementStart = replacementStart,
                replacementEnd = replacementEnd,
            )
        }
        stateGuard.withLock {
            output.forEach { (id, candidate) ->
                candidates[id] = CandidateRecord(
                    word = candidate.text,
                    ngramContext = prepared.ngramContext,
                    blockPotentiallyOffensive = blockPotentiallyOffensive,
                    isEmoji = candidate.kind == AutocorrectCandidateKind.EMOJI,
                )
            }
            while (candidates.size > 128) {
                candidates.remove(candidates.keys.first())
            }
        }
        return output.map(Pair<String, AutocorrectCandidate>::second)
    }

    private fun learn(word: String, ngramContext: NgramContext, blockOffensive: Boolean) {
        if (word.isBlank()) return
        dictionary.onWordCommitted(word)
        dictionary.addToUserHistory(
            word,
            false,
            ngramContext,
            nowSeconds(),
            blockOffensive,
        )
    }

    private fun nowSeconds() = System.currentTimeMillis() / 1_000L
}
