/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect

import android.content.Context
import android.util.Log
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.florisboard.autocorrect.api.AutocorrectAcceptanceKind
import org.florisboard.autocorrect.api.AutocorrectCandidate
import org.florisboard.autocorrect.api.AutocorrectCandidateKind
import org.florisboard.autocorrect.api.AutocorrectCapsMode
import org.florisboard.autocorrect.api.AutocorrectGesturePoint
import org.florisboard.autocorrect.api.AutocorrectInputMode
import org.florisboard.autocorrect.api.AutocorrectPluginContract
import org.florisboard.autocorrect.api.AutocorrectRequest
import org.florisboard.autocorrect.api.AutocorrectSession
import org.florisboard.autocorrect.api.AutocorrectSuggestionResult
import org.florisboard.autocorrect.api.AutocorrectTextEvent
import org.florisboard.autocorrect.api.AutocorrectTextEventKind
import org.florisboard.autocorrect.api.AutocorrectTouchPoint
import org.florisboard.autocorrect.api.AutocorrectUserDictionaryEntry
import org.florisboard.autocorrect.api.AutocorrectUserDictionaryPage
import org.florisboard.autocorrect.api.AutocorrectUserDictionaryReader
import org.florisboard.autocorrect.api.AutocorrectUserDictionaryStatus
import org.futo.inputmethod.event.Event
import org.futo.inputmethod.keyboard.Keyboard
import org.futo.inputmethod.latin.BinaryDictionary
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.Dictionary
import org.futo.inputmethod.latin.DictionaryFacilitator
import org.futo.inputmethod.latin.DictionaryFacilitatorProvider
import org.futo.inputmethod.latin.EmojiSuggestionIndex
import org.futo.inputmethod.latin.InputAttributes
import org.futo.inputmethod.latin.NgramContext
import org.futo.inputmethod.latin.Suggest
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.SuggestionBlacklist
import org.futo.inputmethod.latin.UserBinaryDictionary
import org.futo.inputmethod.latin.WordComposer
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.inputmethod.latin.common.ResizableIntArray
import org.futo.inputmethod.latin.personalization.PersonalizationHelper
import org.futo.inputmethod.latin.personalization.UserHistoryDictionary
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.settings.SettingsValuesForSuggestion
import org.futo.inputmethod.latin.settings.ProviderSessionLanguages
import org.futo.inputmethod.latin.uix.EmojiTracker.useEmoji
import org.futo.inputmethod.latin.uix.PreferenceUtils
import org.futo.inputmethod.latin.uix.SHOW_EMOJI_SUGGESTIONS
import org.futo.inputmethod.latin.uix.SUGGESTION_BLACKLIST
import org.futo.inputmethod.latin.uix.actions.PersistentEmojiState
import org.futo.inputmethod.latin.uix.classicSuggestions
import org.futo.inputmethod.latin.uix.dataStore
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.makeSuggestionLayout
import org.futo.inputmethod.latin.uix.setSettingAndAwaitCache
import org.futo.inputmethod.latin.utils.NgramContextUtils
import org.futo.inputmethod.latin.xlm.AllowTransformerOnNonQWERTYLayouts
import org.futo.inputmethod.latin.xlm.AutocorrectThresholdSetting
import org.futo.inputmethod.latin.xlm.BinaryDictTransformerWeightSetting
import org.futo.inputmethod.latin.xlm.ModelInfoLoader
import org.futo.inputmethod.latin.xlm.ModelLoadingException
import org.futo.inputmethod.latin.xlm.ModelPaths
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

internal class FutoAutocorrectEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hostUserDictionary: AutocorrectUserDictionaryReader,
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
        SuggestionBlacklist(settings, context, scope).also { it.init() }
    private val operationGuard = Mutex()
    private val stateGuard = Mutex()
    private val candidates = LinkedHashMap<String, CandidateRecord>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closeStarted = AtomicBoolean()

    private var session: AutocorrectSession? = null
    private var lastRequest: AutocorrectRequest? = null
    private var lastKeyboard: Keyboard? = null
    private var keyboardSignature = 0
    private var modelPreparation: Job? = null
    private var historyFlushJob: Job? = null
    @Volatile private var preparedModels: Map<String, ModelInfoLoader>? = null
    private var transformerDisabled = false
    private var appliedUserDictionary = emptyList<AutocorrectUserDictionaryEntry>()
    private var transformerUserDictionaryWords = emptyList<String>()
    private val modelUpdates = scope.launch(Dispatchers.Default) {
        ModelPaths.modelOptionsUpdated.collect {
            operationGuard.withLock {
                modelPreparation?.cancelAndJoin()
                modelPreparation = null
                preparedModels = null
                FutoTransformerModelCache.evict()
            }
        }
    }

    init {
        UserBinaryDictionary.setExternalSource(emptyList())
    }

    suspend fun startSession(newSession: AutocorrectSession) = operationGuard.withLock {
        startSessionLocked(newSession, refreshHostUserDictionary = true)
    }

    private suspend fun startSessionLocked(
        newSession: AutocorrectSession,
        forceReloadDictionaries: Boolean = false,
        refreshHostUserDictionary: Boolean = false,
    ) {
        FutoTransformerModelCache.clearContext()
        stateGuard.withLock {
            session = newSession
            lastRequest = null
            candidates.clear()
            transformerDisabled = false
            lastKeyboard = null
            keyboardSignature = 0
        }
        if (BuildConfig.FLAVOR == PROVIDER_FLAVOR) {
            EmojiSuggestionIndex.setPreferredSkinToneModifier(
                newSession.preferredEmojiSkinToneModifier,
            )
        }
        val locales = buildList {
            add(Locale.forLanguageTag(newSession.primaryLanguageTag))
            newSession.secondaryLanguageTags.mapTo(this) { Locale.forLanguageTag(it) }
        }.filter { it.language.isNotBlank() }.distinctBy(Locale::toLanguageTag)
        val primaryLocale = locales.firstOrNull() ?: Locale.ENGLISH
        ProviderSessionLanguages.replace(locales.drop(1))
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
            InputAttributes(editorInfo, false, context.packageName, newSession.editorFlags),
        )
        if (refreshHostUserDictionary) {
            refreshUserDictionary(locales.ifEmpty { listOf(primaryLocale) })
        }
        val activeModelLanguages = locales
            .ifEmpty { listOf(primaryLocale) }
            .mapTo(mutableSetOf()) { it.language }
        if (!settings.current.mTransformerPredictionEnabled) {
            FutoTransformerModelCache.evict()
        } else {
            FutoTransformerModelCache.evictUnless(activeModelLanguages)
        }
        val emojiSuggestionsEnabled =
            context.dataStore.data.first()[SHOW_EMOJI_SUGGESTIONS.key]
                ?: SHOW_EMOJI_SUGGESTIONS.default
        if (emojiSuggestionsEnabled) {
            locales.ifEmpty { listOf(primaryLocale) }.forEach {
                if (BuildConfig.FLAVOR == PROVIDER_FLAVOR) {
                    EmojiSuggestionIndex.loadForLanguage(context, it)
                } else {
                    PersistentEmojiState.loadTranslationsForLanguage(context, it)
                }
            }
        }
        val personalization = personalizationPolicy(
            settings.current.mUsePersonalizedDicts,
            newSession.allowPersonalizedLearning,
            !settings.current.mInputAttributes.mNoLearning,
        )
        dictionary.resetDictionaries(
            context,
            locales.ifEmpty { listOf(primaryLocale) },
            false,
            personalization.allowReads,
            forceReloadDictionaries,
            null,
            "",
            null,
        )
        dictionary.onStartInput()
        runInterruptible(Dispatchers.IO) {
            dictionary.waitForLoadingMainDictionaries()
        }
        if (
            settings.current.mTransformerPredictionEnabled &&
            preparedModels == null &&
            modelPreparation?.isActive != true
        ) {
            modelPreparation = scope.launch(Dispatchers.IO) {
                preparedModels = try {
                    ModelPaths.getModelOptions(context)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    null
                }
            }
        }
    }

    suspend fun suggest(request: AutocorrectRequest): AutocorrectSuggestionResult =
        operationGuard.withLock { suggestLocked(request) }

    private suspend fun suggestLocked(
        request: AutocorrectRequest,
    ): AutocorrectSuggestionResult {
        val activeSession = stateGuard.withLock {
            if (session?.sessionId != request.sessionId) {
                return AutocorrectSuggestionResult.Unhandled
            }
            lastRequest = request
            session
        } ?: return AutocorrectSuggestionResult.Unhandled
        if (!settings.current.needsToLookupSuggestions()) {
            return AutocorrectSuggestionResult.Empty
        }
        val prepared = prepareInput(activeSession, request)
            ?: return AutocorrectSuggestionResult.Unhandled
        if (
            !prepared.isGesture &&
            prepared.typedWord.isBlank() &&
            !settings.current.mBigramPredictionEnabled
        ) {
            return AutocorrectSuggestionResult.Empty
        }
        val blockPotentiallyOffensive =
            settings.current.mBlockPotentiallyOffensive || !request.allowPossiblyOffensive
        val settingsForSuggestion = SettingsValuesForSuggestion(
            blockPotentiallyOffensive,
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
            request.sessionId.toInt(),
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
            val model = getLanguageModelInfo(modelLocale)
            if (model == null) {
                null
            } else {
                val result = try {
                    FutoTransformerModelCache.withModel(context, model, modelLocale) {
                        it.getSuggestions(
                            prepared.composer.composedDataSnapshot,
                            prepared.ngramContext,
                            prepared.keyboard.proximityInfo.nativeProximityInfo,
                            context.getSetting(AutocorrectThresholdSetting),
                            transformerUserDictionaryWords,
                            context.getSetting(SUGGESTION_BLACKLIST).toTypedArray(),
                        )
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (error is ModelLoadingException) transformerDisabled = true
                    null
                }
                if (transformerDisabled) FutoTransformerModelCache.evict()
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
            allowCandidateRemoval = currentPersonalizationPolicy(activeSession).allowWrites,
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

    suspend fun reloadSettings(
        modelsChanged: Boolean = false,
        resourcesChanged: Boolean = false,
        userDictionaryChanged: Boolean = false,
    ) {
        operationGuard.withLock {
            if (resourcesChanged) flushHistory()
            if (modelsChanged) {
                modelPreparation?.cancelAndJoin()
                modelPreparation = null
                FutoTransformerModelCache.evict()
                preparedModels = null
            }
            evictDisabledTransformer()
            stateGuard.withLock { session }?.let {
                startSessionLocked(
                    it,
                    forceReloadDictionaries = resourcesChanged,
                    refreshHostUserDictionary = userDictionaryChanged,
                )
            }
        }
    }

    suspend fun replaceBlacklist(words: Set<String>) = operationGuard.withLock {
        replaceBlacklistLocked(words)
    }

    suspend fun accepted(
        sessionId: Long,
        candidateId: String,
        @Suppress("UNUSED_PARAMETER") acceptanceKind: AutocorrectAcceptanceKind,
    ) = operationGuard.withLock accepted@ {
        val record = stateGuard.withLock {
            if (
                session?.sessionId != sessionId ||
                !isLearningAllowed()
            ) {
                null
            } else {
                candidates[candidateId]
            }
        } ?: return@accepted
        if (record.isEmoji) {
            if (BuildConfig.FLAVOR != PROVIDER_FLAVOR) {
                context.useEmoji(record.word)
            }
        } else {
            learn(record.word, record.ngramContext, record.blockPotentiallyOffensive)
        }
    }

    suspend fun reverted(
        sessionId: Long,
        candidateId: String,
    ) = operationGuard.withLock reverted@ {
        val record = stateGuard.withLock {
            if (
                session?.sessionId != sessionId ||
                !isLearningAllowed()
            ) {
                null
            } else {
                candidates[candidateId]
            }
        } ?: return@reverted
        if (record.isEmoji) return@reverted
        dictionary.unlearnFromUserHistory(
            record.word,
            record.ngramContext,
            nowSeconds(),
            Constants.EVENT_REVERT,
        )
        scheduleHistoryFlush()
    }

    suspend fun remove(
        sessionId: Long,
        candidateId: String,
    ): Boolean = operationGuard.withLock remove@ {
        val record = stateGuard.withLock {
            if (
                session?.sessionId != sessionId ||
                !isLearningAllowed()
            ) {
                null
            } else {
                candidates[candidateId]
            }
        } ?: return@remove false
        replaceBlacklistLocked(context.getSetting(SUGGESTION_BLACKLIST) + record.word)
        if (!record.isEmoji) {
            dictionary.unlearnFromUserHistory(
                record.word,
                record.ngramContext,
                nowSeconds(),
                Constants.EVENT_REJECTION,
            )
            scheduleHistoryFlush()
        }
        true
    }

    private suspend fun replaceBlacklistLocked(words: Set<String>) {
        val snapshot = words.toSet()
        context.setSettingAndAwaitCache(SUGGESTION_BLACKLIST, snapshot)
        suggestionBlacklist.awaitRefresh(snapshot)
    }

    suspend fun textEvent(event: AutocorrectTextEvent) = operationGuard.withLock textEvent@ {
        val request = stateGuard.withLock {
            if (session?.sessionId != event.sessionId ||
                !isLearningAllowed()
            ) {
                null
            } else {
                lastRequest
            }
        } ?: return@textEvent
        val ngramContext = ngramContext(request)
        when (event.kind) {
            AutocorrectTextEventKind.COMMIT_TYPED,
            AutocorrectTextEventKind.COMMIT_GESTURE -> {
                learn(
                    event.text,
                    ngramContext,
                    settings.current.mBlockPotentiallyOffensive ||
                        !request.allowPossiblyOffensive,
                )
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
                    scheduleHistoryFlush()
                }
            }
        }
    }

    suspend fun finishSession(
        sessionId: Long,
        finalRequest: AutocorrectRequest?,
    ) = operationGuard.withLock finish@ {
        var committedEmail: String? = null
        val finished = stateGuard.withLock {
            if (session?.sessionId != sessionId) {
                false
            } else {
                if (
                    isLearningAllowed() &&
                    settings.current.mInputAttributes.mIsEmailField
                ) {
                    committedEmail = committedEmailForFinish(
                        sessionId,
                        finalRequest,
                        lastRequest,
                    )
                }
                clearSessionStateLocked()
                true
            }
        }
        if (!finished) return@finish
        try {
            committedEmail?.let(dictionary::onEmailTyped)
        } finally {
            finishSessionLifecycle(hadSession = true)
        }
    }

    suspend fun unbindHost() = operationGuard.withLock {
        val hadSession = stateGuard.withLock {
            val active = session != null
            clearSessionStateLocked()
            active
        }
        finishSessionLifecycle(hadSession)
    }

    private fun clearSessionStateLocked() {
        session = null
        lastRequest = null
        candidates.clear()
        lastKeyboard = null
        keyboardSignature = 0
        transformerDisabled = false
        if (BuildConfig.FLAVOR == PROVIDER_FLAVOR) {
            EmojiSuggestionIndex.clearPreferredSkinToneModifier()
        }
    }

    private suspend fun finishSessionLifecycle(hadSession: Boolean) {
        try {
            ProviderSessionLanguages.replace(emptyList())
        } finally {
            try {
                dictionary.clearSuggestionSessions()
            } finally {
                try {
                    if (hadSession) dictionary.onFinishInput(context)
                } finally {
                    try {
                        FutoTransformerModelCache.clearContext()
                    } finally {
                        if (hadSession || historyFlushJob != null) flushHistory()
                    }
                }
            }
        }
    }

    suspend fun clearHistory(): Boolean = operationGuard.withLock {
        cancelHistoryFlush()
        val cleared = withContext(Dispatchers.IO) {
            PersonalizationHelper.removeAllUserHistoryDictionaries(context)
            val emailCleared = dictionary.clearUserHistoryDictionaryAndWait(context)
            emailCleared && (context.filesDir.listFiles()?.none {
                it.name.startsWith(UserHistoryDictionary::class.java.simpleName)
            } == true)
        }
        stateGuard.withLock { session }?.let {
            startSessionLocked(it, forceReloadDictionaries = true)
        }
        cleared
    }

    fun closeAsync() {
        if (!closeStarted.compareAndSet(false, true)) return
        modelUpdates.cancel()
        modelPreparation?.cancel()
        cleanupScope.launch {
            try {
                operationGuard.withLock {
                    cancelHistoryFlush()
                    runCatching { modelPreparation?.cancelAndJoin() }.onFailure {
                        Log.w(TAG, "Failed to stop model preparation", it)
                    }
                    modelPreparation = null
                    runCatching { FutoTransformerModelCache.clearContext() }.onFailure {
                        Log.w(TAG, "Failed to clear the language model context", it)
                    }
                    runCatching(dictionary::closeDictionaries).onFailure {
                        Log.w(TAG, "Failed to close dictionaries", it)
                    }
                }
            } finally {
                cleanupScope.cancel()
            }
        }
    }

    private suspend fun refreshUserDictionary(locales: List<Locale>) {
        val result = hostUserDictionary.queryAllUserDictionary(
            locales.map(Locale::toLanguageTag),
        )
        val entries = when (result.status) {
            AutocorrectUserDictionaryStatus.OK -> result.entries.sortedBy { it.id }
            AutocorrectUserDictionaryStatus.DENIED -> emptyList()
            AutocorrectUserDictionaryStatus.UNAVAILABLE,
            AutocorrectUserDictionaryStatus.INVALID -> return
        }
        if (entries == appliedUserDictionary) return
        appliedUserDictionary = entries
        val transformerEntries = entries
            .asSequence()
            .filter { it.word.length < 64 }
            .sortedByDescending { it.frequency }
            .distinctBy { it.word }
            .toList()
        transformerUserDictionaryWords = buildList {
            var approximateTokens = 0
            for (entry in transformerEntries) {
                approximateTokens += (4 + entry.word.length) / 4
                if (approximateTokens > 200) break
                add(entry.word)
            }
        }
        UserBinaryDictionary.setExternalSource(
            entries.map {
                UserBinaryDictionary.ExternalEntry(
                    it.word,
                    it.frequency,
                    it.languageTag,
                    it.shortcut,
                )
            },
        )
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
        val composer = WordComposer().apply {
            setCapitalizedModeAtStartComposingTime(request.capsMode.toWordComposerCapsMode())
        }
        val typedWord = currentWord(request)
        val isGesture = request.inputTrace.mode == AutocorrectInputMode.GESTURE
        if (isGesture) {
            val points = request.inputTrace.gesturePoints
            val elapsedTimes = monotonicGestureTimes(points)
            val x = ResizableIntArray(points.size)
            val y = ResizableIntArray(points.size)
            val time = ResizableIntArray(points.size)
            points.forEachIndexed { index, point ->
                x.add(normalizedCoordinate(point.x, keyboard.mBaseWidth))
                y.add(normalizedCoordinate(point.y, keyboard.mBaseHeight))
                time.add(elapsedTimes[index])
            }
            val pointers = InputPointers(points.size).apply {
                onPointerDown(0)
                append(0, time, x, y, 0, points.size)
                onPointerUp(0)
            }
            composer.setBatchInputPointers(pointers)
            composer.setBatchInputWord("")
        } else {
            val points = alignedTouchPoints(typedWord, request.inputTrace.points, locale)
            typedWord.codePoints().toArray().forEachIndexed { index, codePoint ->
                val point = points[index]
                val x = point?.let { normalizedCoordinate(it.x, keyboard.mBaseWidth) }
                    ?: keyboard.getKey(codePoint)?.let { it.x + it.width / 2 }
                    ?: -1
                val y = point?.let { normalizedCoordinate(it.y, keyboard.mBaseHeight) }
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

    private suspend fun getLanguageModelInfo(locale: Locale): ModelInfoLoader? {
        if (modelPreparation?.isActive == true) return null
        val models = preparedModels ?: try {
            ModelPaths.getModelOptions(context).also { preparedModels = it }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            transformerDisabled = true
            null
        } ?: run {
            FutoTransformerModelCache.evict()
            return null
        }
        return models[locale.language] ?: run {
            FutoTransformerModelCache.evict()
            return null
        }
    }

    private suspend fun evictDisabledTransformer() {
        val preferences = PreferenceUtils.getDefaultSharedPreferences(context)
        if (!preferences.getBoolean(Settings.PREF_KEY_USE_TRANSFORMER_LM, true)) {
            FutoTransformerModelCache.evict()
        }
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
                Character.toLowerCase(it) == Character.toLowerCase(codePoint) ||
                    Character.toUpperCase(it) == Character.toUpperCase(codePoint)
            } ?: codePoint
        }
    }

    private suspend fun toCandidates(
        request: AutocorrectRequest,
        prepared: PreparedInput,
        dictionaryWords: SuggestedWords,
        transformerWords: List<SuggestedWordInfo>,
        allowCandidateRemoval: Boolean,
    ): List<AutocorrectCandidate> {
        val blockPotentiallyOffensive =
            settings.current.mBlockPotentiallyOffensive || !request.allowPossiblyOffensive
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
        val typedSuggestion = if (!prepared.isGesture && prepared.typedWord.isNotBlank()) {
            typedInfo ?: SuggestedWordInfo(
                prepared.typedWord,
                "",
                0,
                SuggestedWordInfo.KIND_TYPED,
                null,
                0,
                0,
            )
        } else {
            null
        }
        val eligibleRanked = rankedWords.map(RankedWord::info).filter { info ->
            info.word == prepared.typedWord ||
                (suggestionBlacklist.isSuggestedWordOk(info) &&
                    (!blockPotentiallyOffensive || !info.isPossiblyOffensive))
        }
        val autoInfo = typedSuggestion?.let {
            eligibleRanked.firstOrNull { info -> info.word == autoWord }
        }
        val eligible = listOfNotNull(typedSuggestion) + eligibleRanked
        val layoutWords = buildList {
            typedSuggestion?.let(::add)
            autoInfo?.let(::add)
            eligible.filterTo(this) { it !== typedSuggestion && it !== autoInfo }
        }
        val layout = makeSuggestionLayout(
            SuggestedWords(
                ArrayList(layoutWords),
                null,
                typedSuggestion,
                dictionaryWords.mTypedWordValid,
                autoInfo != null,
                false,
                if (prepared.isGesture) {
                    SuggestedWords.INPUT_STYLE_UPDATE_BATCH
                } else {
                    dictionaryWords.mInputStyle
                },
                SuggestedWords.NOT_A_SEQUENCE_NUMBER,
            ),
            null,
            false,
        )
        val ordered = orderProviderSuggestions(
            layout.classicSuggestions(),
            eligible,
            request.maxCandidateCount,
        )
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
                autoCommit = info.word == autoInfo?.word,
                removable = allowCandidateRemoval && kind != AutocorrectCandidateKind.TYPED,
                visible = settings.current.isSuggestionsEnabledPerUserSettings,
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
        if (settings.current.mInputAttributes.mIsEmailField) return
        dictionary.addToUserHistory(
            word,
            false,
            ngramContext,
            nowSeconds(),
            blockOffensive,
        )
        scheduleHistoryFlush()
    }

    private fun scheduleHistoryFlush() {
        if (historyFlushJob?.isActive == true) return
        historyFlushJob = scope.launch {
            delay(HISTORY_FLUSH_DELAY_MS)
            operationGuard.withLock {
                dictionary.flushUserHistoryDictionaries()
                historyFlushJob = null
            }
        }
    }

    private fun flushHistory() {
        cancelHistoryFlush()
        dictionary.flushUserHistoryDictionaries()
    }

    private fun cancelHistoryFlush() {
        historyFlushJob?.cancel()
        historyFlushJob = null
    }

    private fun currentPersonalizationPolicy(activeSession: AutocorrectSession? = session) =
        personalizationPolicy(
            settings.current.mUsePersonalizedDicts,
            activeSession?.allowPersonalizedLearning == true,
            !settings.current.mInputAttributes.mNoLearning,
        )

    private fun isLearningAllowed() = currentPersonalizationPolicy().allowWrites

    private fun nowSeconds() = System.currentTimeMillis() / 1_000L

    companion object {
        private const val TAG = "FutoAutocorrectEngine"
        private const val PROVIDER_FLAVOR = "provider"
        private const val HISTORY_FLUSH_DELAY_MS = 5_000L
    }
}

internal fun orderProviderSuggestions(
    classic: List<SuggestedWordInfo?>,
    eligible: List<SuggestedWordInfo>,
    maxCandidateCount: Int,
) = (classic.asSequence().filterNotNull() + eligible.asSequence())
    .distinctBy(SuggestedWordInfo::getWord)
    .take(maxCandidateCount)
    .toList()

@JvmOverloads
internal fun alignedTouchPoints(
    typedWord: String,
    points: List<AutocorrectTouchPoint>,
    locale: Locale = Locale.ROOT,
): List<AutocorrectTouchPoint?> {
    val expanded = buildList {
        points.forEach { point ->
            alignmentCodePoints(point.text, locale).forEach { codePoint ->
                add(codePoint to point)
            }
        }
    }
    var pointIndex = 0
    return typedWord.codePoints().toArray().map { codePoint ->
        val expected = alignmentCodePoints(String(Character.toChars(codePoint)), locale)
        var matchIndex = pointIndex
        while (
            matchIndex + expected.size <= expanded.size &&
            expected.indices.any { expanded[matchIndex + it].first != expected[it] }
        ) {
            matchIndex++
        }
        if (matchIndex + expected.size > expanded.size) {
            null
        } else {
            expanded[matchIndex].second.also {
                pointIndex = matchIndex + expected.size
            }
        }
    }
}

private fun alignmentCodePoints(text: String, locale: Locale): IntArray =
    Normalizer.normalize(text.uppercase(locale), Normalizer.Form.NFD)
        .codePoints()
        .toArray()

internal fun monotonicGestureTimes(points: List<AutocorrectGesturePoint>): IntArray {
    var previous = 0
    return IntArray(points.size) { index ->
        points[index].elapsedTimeMillis.coerceAtLeast(previous).also {
            previous = it
        }
    }
}

internal fun normalizedCoordinate(value: Float, size: Int): Int {
    if (size <= 0) return 0
    return (value * size).toInt().coerceIn(0, size - 1)
}

internal data class PersonalizationPolicy(
    val allowReads: Boolean,
    val allowWrites: Boolean,
)

internal fun personalizationPolicy(
    userEnabled: Boolean,
    sessionAllowsLearning: Boolean,
    editorAllowsLearning: Boolean,
) = PersonalizationPolicy(
    allowReads = userEnabled,
    allowWrites = userEnabled && sessionAllowsLearning && editorAllowsLearning,
)

internal fun committedEmailForFinish(
    sessionId: Long,
    finalRequest: AutocorrectRequest?,
    lastRequest: AutocorrectRequest?,
): String? {
    fun AutocorrectRequest.isValid() =
        this.sessionId == sessionId &&
            selectionStart == selectionEnd &&
            selectionStart in 0..text.length
    val request = if (finalRequest != null) {
        finalRequest.takeIf { it.isValid() }
    } else {
        lastRequest?.takeIf { it.isValid() }
    }
    return request?.let { committedEmailBeforeCursor(it.text, it.selectionStart) }
}

internal fun committedEmailBeforeCursor(text: String, selectionStart: Int): String? {
    if (selectionStart !in 0..text.length) return null
    val email = text
        .substring(0, selectionStart)
        .takeLast(BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH)
    if (
        email.length >= BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH ||
        ' ' in email
    ) {
        return null
    }
    val at = email.indexOf('@')
    if (at <= 0 || at != email.lastIndexOf('@') || at == email.lastIndex) return null
    val domain = email.substring(at + 1)
    return email.takeIf {
        '.' in domain && !domain.startsWith('.') && !domain.endsWith('.')
    }
}

internal suspend fun AutocorrectUserDictionaryReader.queryAllUserDictionary(
    languageTags: List<String>,
): AutocorrectUserDictionaryPage {
    val entries = mutableListOf<AutocorrectUserDictionaryEntry>()
    var afterId = 0L
    while (true) {
        val page = try {
            queryUserDictionary(
                languageTags = languageTags,
                afterId = afterId,
                limit = AutocorrectPluginContract.MAX_USER_DICTIONARY_PAGE_SIZE,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return AutocorrectUserDictionaryPage(AutocorrectUserDictionaryStatus.UNAVAILABLE)
        }
        if (!page.successful) {
            return AutocorrectUserDictionaryPage(page.status)
        }
        if (
            page.entries.any { it.id <= afterId } ||
            page.entries.zipWithNext().any { (first, second) -> second.id <= first.id }
        ) {
            return AutocorrectUserDictionaryPage(AutocorrectUserDictionaryStatus.INVALID)
        }
        entries += page.entries
        val nextAfterId = page.nextAfterId
            ?: return AutocorrectUserDictionaryPage(
                status = AutocorrectUserDictionaryStatus.OK,
                entries = entries,
            )
        if (nextAfterId <= afterId) {
            return AutocorrectUserDictionaryPage(AutocorrectUserDictionaryStatus.INVALID)
        }
        afterId = nextAfterId
    }
}

private fun AutocorrectCapsMode.toWordComposerCapsMode() = when (this) {
    AutocorrectCapsMode.UNSPECIFIED,
    AutocorrectCapsMode.UNSHIFTED -> WordComposer.CAPS_MODE_OFF
    AutocorrectCapsMode.SHIFTED_MANUAL -> WordComposer.CAPS_MODE_MANUAL_SHIFTED
    AutocorrectCapsMode.SHIFTED_AUTOMATIC -> WordComposer.CAPS_MODE_AUTO_SHIFTED
    AutocorrectCapsMode.CAPS_LOCK -> WordComposer.CAPS_MODE_MANUAL_SHIFT_LOCKED
}
