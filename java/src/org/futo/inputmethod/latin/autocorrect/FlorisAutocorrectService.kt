/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect

import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.runBlocking
import org.florisboard.autocorrect.api.AutocorrectAcceptanceKind
import org.florisboard.autocorrect.api.AutocorrectPluginService
import org.florisboard.autocorrect.api.AutocorrectPluginUi
import org.florisboard.autocorrect.api.AutocorrectPluginUiIcon
import org.florisboard.autocorrect.api.AutocorrectPluginUiItem
import org.florisboard.autocorrect.api.AutocorrectPluginUiItemKind
import org.florisboard.autocorrect.api.AutocorrectPluginUiPage
import org.florisboard.autocorrect.api.AutocorrectPluginUiSurface
import org.florisboard.autocorrect.api.AutocorrectRequest
import org.florisboard.autocorrect.api.AutocorrectSession
import org.florisboard.autocorrect.api.AutocorrectSuggestionResult
import org.florisboard.autocorrect.api.AutocorrectTextEvent
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.DataStoreHelper
import org.futo.inputmethod.latin.uix.PreferenceUtils
import org.futo.inputmethod.latin.uix.SUGGESTION_BLACKLIST
import org.futo.inputmethod.latin.uix.forceUnlockDatastore
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.xlm.AutocorrectThresholdSetting
import org.futo.inputmethod.latin.xlm.BinaryDictTransformerWeightSetting
import org.futo.inputmethod.latin.xlm.ModelPaths

class FlorisAutocorrectService : AutocorrectPluginService(), LifecycleOwner {
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var engine: FutoAutocorrectEngine

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.INITIALIZED
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        forceUnlockDatastore(this)
        DataStoreHelper.init(this)
        Settings.init(this)
        engine = FutoAutocorrectEngine(this, lifecycleScope)
    }

    override fun onServiceDestroyed() {
        runBlocking { engine.close() }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    override suspend fun onStartSession(session: AutocorrectSession) {
        engine.startSession(session)
    }

    override fun isHostAuthorized(packageNames: Set<String>): Boolean {
        return packageNames.any(ALLOWED_FLORISBOARD_PACKAGES::contains)
    }

    override suspend fun onSuggestResult(
        request: AutocorrectRequest,
    ): AutocorrectSuggestionResult {
        return engine.suggest(request)
    }

    override suspend fun onSuggestionAccepted(
        sessionId: Long,
        candidateId: String,
        acceptanceKind: AutocorrectAcceptanceKind,
    ) {
        engine.accepted(sessionId, candidateId, acceptanceKind)
    }

    override suspend fun onSuggestionReverted(sessionId: Long, candidateId: String) {
        engine.reverted(sessionId, candidateId)
    }

    override suspend fun onRemoveSuggestion(sessionId: Long, candidateId: String): Boolean {
        return engine.remove(sessionId, candidateId)
    }

    override suspend fun onTextEvent(event: AutocorrectTextEvent) {
        engine.textEvent(event)
    }

    override suspend fun onFinishSession(sessionId: Long) {
        engine.finishSession(sessionId)
    }

    override suspend fun onGetPluginUi(): AutocorrectPluginUi {
        val preferences = PreferenceUtils.getDefaultSharedPreferences(this)
        val modelCount = ModelPaths.getModelDirectory(this).listFiles()?.size ?: 0
        val blacklistCount = getSetting(SUGGESTION_BLACKLIST).size
        return AutocorrectPluginUi(
            appRootPageId = "overview",
            keyboardRootPageId = "quick",
            pages = listOf(
                AutocorrectPluginUiPage(
                    id = "overview",
                    title = "FUTO predictive text",
                    summary = "FUTO's on-device dictionaries and language model, provided to FlorisBoard.",
                    surface = AutocorrectPluginUiSurface.APP,
                    items = commonSettings(preferences) + listOf(
                        AutocorrectPluginUiItem(
                            id = "advanced",
                            kind = AutocorrectPluginUiItemKind.NAVIGATION,
                            title = "Advanced prediction",
                            target = "advanced",
                            icon = AutocorrectPluginUiIcon.TUNE,
                        ),
                        AutocorrectPluginUiItem(
                            id = "resources",
                            kind = AutocorrectPluginUiItemKind.NAVIGATION,
                            title = "Models and dictionaries",
                            summary = "$modelCount model file(s), $blacklistCount blacklisted word(s)",
                            target = "resources",
                            icon = AutocorrectPluginUiIcon.MODEL,
                        ),
                        activityItem(
                            id = "allPredictiveSettings",
                            title = "Open all FUTO prediction settings",
                            target = FlorisAutocorrectSettingsActivity::class.java.name,
                            icon = AutocorrectPluginUiIcon.SETTINGS,
                        ),
                    ),
                ),
                AutocorrectPluginUiPage(
                    id = "advanced",
                    title = "Advanced prediction",
                    surface = AutocorrectPluginUiSurface.APP,
                    items = advancedSettings(),
                ),
                AutocorrectPluginUiPage(
                    id = "resources",
                    title = "Models and dictionaries",
                    surface = AutocorrectPluginUiSurface.APP,
                    items = resourceSettings(modelCount, blacklistCount),
                ),
                AutocorrectPluginUiPage(
                    id = "quick",
                    title = "FUTO predictive text",
                    surface = AutocorrectPluginUiSurface.KEYBOARD,
                    items = commonSettings(preferences) + listOf(
                        AutocorrectPluginUiItem(
                            id = "quickAdvanced",
                            kind = AutocorrectPluginUiItemKind.NAVIGATION,
                            title = "Prediction strength",
                            target = "quickAdvanced",
                            icon = AutocorrectPluginUiIcon.TUNE,
                        ),
                        activityItem(
                            id = "quickDictionary",
                            title = "Personal dictionary",
                            target = FlorisDictionarySettingsActivity::class.java.name,
                            icon = AutocorrectPluginUiIcon.DICTIONARY,
                        ),
                    ),
                ),
                AutocorrectPluginUiPage(
                    id = "quickAdvanced",
                    title = "Prediction strength",
                    surface = AutocorrectPluginUiSurface.KEYBOARD,
                    items = advancedSettings(),
                ),
            ),
        )
    }

    override suspend fun onSetPluginUiValue(itemId: String, value: String): Boolean {
        val preferences = PreferenceUtils.getDefaultSharedPreferences(this)
        return when (itemId) {
            "autoCorrection" -> value.toBooleanStrictOrNull()?.let {
                preferences.edit { putBoolean(Settings.PREF_AUTO_CORRECTION, it) }
                true
            } ?: false
            "transformer" -> value.toBooleanStrictOrNull()?.let {
                preferences.edit { putBoolean(Settings.PREF_KEY_USE_TRANSFORMER_LM, it) }
                true
            } ?: false
            "personalized" -> value.toBooleanStrictOrNull()?.let {
                preferences.edit { putBoolean(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, it) }
                true
            } ?: false
            "smartKeyHit" -> value.toBooleanStrictOrNull()?.let {
                preferences.edit { putBoolean(Settings.PREF_USE_DICT_KEY_BOOSTING, it) }
                true
            } ?: false
            "showSuggestions" -> value.toBooleanStrictOrNull()?.let {
                preferences.edit { putBoolean(Settings.PREF_SHOW_SUGGESTIONS, it) }
                true
            } ?: false
            "gesture" -> value.toBooleanStrictOrNull()?.let {
                preferences.edit { putBoolean(Settings.PREF_GESTURE_INPUT, it) }
                true
            } ?: false
            "nextWord" -> value.toBooleanStrictOrNull()?.let {
                preferences.edit { putBoolean(Settings.PREF_BIGRAM_PREDICTIONS, it) }
                true
            } ?: false
            "autocorrectThreshold" -> value.toFloatOrNull()
                ?.takeIf { it.isFinite() && it in 0f..25f }
                ?.let {
                    setSetting(AutocorrectThresholdSetting, it)
                    true
                } ?: false
            "transformerWeight" -> value.toFloatOrNull()
                ?.takeIf { it.isFinite() && it in 0f..100f }
                ?.let {
                    setSetting(
                        BinaryDictTransformerWeightSetting,
                        when {
                            it < 0.0001f -> Float.NEGATIVE_INFINITY
                            it > 99.9f -> Float.POSITIVE_INFINITY
                            else -> it
                        },
                    )
                    true
                } ?: false
            else -> false
        }
    }

    override suspend fun onInvokePluginUiAction(itemId: String): Boolean {
        return when (itemId) {
            "clearHistory" -> engine.clearHistory()
            "clearBlacklist" -> {
                setSetting(SUGGESTION_BLACKLIST, emptySet())
                true
            }
            else -> false
        }
    }

    private fun commonSettings(
        preferences: android.content.SharedPreferences,
    ) = listOf(
        switchItem(
            "autoCorrection",
            "Autocorrection",
            preferences.getBoolean(Settings.PREF_AUTO_CORRECTION, true),
        ),
        switchItem(
            "transformer",
            "Transformer language model",
            preferences.getBoolean(Settings.PREF_KEY_USE_TRANSFORMER_LM, true),
        ),
        switchItem(
            "personalized",
            "Personalized suggestions",
            preferences.getBoolean(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, true),
        ),
        switchItem(
            "smartKeyHit",
            "Smart key-hit detection",
            preferences.getBoolean(Settings.PREF_USE_DICT_KEY_BOOSTING, true),
        ),
        switchItem(
            "showSuggestions",
            "Show suggestions",
            preferences.getBoolean(Settings.PREF_SHOW_SUGGESTIONS, true),
        ),
        switchItem(
            "gesture",
            "FUTO swipe decoding",
            Settings.readGestureInputEnabled(preferences, resources),
        ),
        switchItem(
            "nextWord",
            "Next-word predictions",
            preferences.getBoolean(
                Settings.PREF_BIGRAM_PREDICTIONS,
                resources.getBoolean(org.futo.inputmethod.latin.R.bool.config_default_next_word_prediction),
            ),
        ),
    )

    private fun advancedSettings() = listOf(
        AutocorrectPluginUiItem(
            id = "transformerWeight",
            kind = AutocorrectPluginUiItemKind.SLIDER,
            title = "Transformer strength",
            summary = "Balances transformer and binary-dictionary ranking.",
            value = when (val weight = getSetting(BinaryDictTransformerWeightSetting)) {
                Float.NEGATIVE_INFINITY -> "0.0"
                Float.POSITIVE_INFINITY -> "100.0"
                else -> weight.toString()
            },
            minimum = 0.0,
            maximum = 100.0,
            step = 0.1,
            icon = AutocorrectPluginUiIcon.TUNE,
        ),
        AutocorrectPluginUiItem(
            id = "autocorrectThreshold",
            kind = AutocorrectPluginUiItemKind.SLIDER,
            title = "Autocorrect threshold",
            summary = "Higher values require more model confidence.",
            value = getSetting(AutocorrectThresholdSetting).toString(),
            minimum = 0.0,
            maximum = 25.0,
            step = 0.1,
            icon = AutocorrectPluginUiIcon.TUNE,
        ),
        AutocorrectPluginUiItem(
            id = "finetuningStatus",
            kind = AutocorrectPluginUiItemKind.INFO,
            title = "On-device fine-tuning is unavailable",
            summary = "The current upstream FUTO build ships its training worker disabled.",
            icon = AutocorrectPluginUiIcon.INFO,
        ),
    )

    private fun resourceSettings(modelCount: Int, blacklistCount: Int) = listOf(
        activityItem(
            id = "models",
            title = "Manage language models",
            summary = "$modelCount model file(s)",
            target = FlorisModelSettingsActivity::class.java.name,
            icon = AutocorrectPluginUiIcon.MODEL,
        ),
        activityItem(
            id = "dictionary",
            title = "Personal dictionary",
            target = FlorisDictionarySettingsActivity::class.java.name,
            icon = AutocorrectPluginUiIcon.DICTIONARY,
        ),
        activityItem(
            id = "blacklist",
            title = "Suggestion blacklist",
            summary = "$blacklistCount word(s)",
            target = FlorisBlacklistSettingsActivity::class.java.name,
            icon = AutocorrectPluginUiIcon.DELETE,
        ),
        AutocorrectPluginUiItem(
            id = "clearHistory",
            kind = AutocorrectPluginUiItemKind.ACTION,
            title = "Clear learned history",
            confirmation = "Delete all words learned from typing?",
            icon = AutocorrectPluginUiIcon.DELETE,
        ),
        AutocorrectPluginUiItem(
            id = "clearBlacklist",
            kind = AutocorrectPluginUiItemKind.ACTION,
            title = "Clear suggestion blacklist",
            confirmation = "Allow every blacklisted suggestion again?",
            enabled = blacklistCount > 0,
            icon = AutocorrectPluginUiIcon.DELETE,
        ),
    )

    private fun switchItem(id: String, title: String, value: Boolean) =
        AutocorrectPluginUiItem(
            id = id,
            kind = AutocorrectPluginUiItemKind.SWITCH,
            title = title,
            value = value.toString(),
            icon = AutocorrectPluginUiIcon.SETTINGS,
        )

    private fun activityItem(
        id: String,
        title: String,
        target: String,
        icon: AutocorrectPluginUiIcon,
        summary: String? = null,
    ) = AutocorrectPluginUiItem(
        id = id,
        kind = AutocorrectPluginUiItemKind.ACTIVITY,
        title = title,
        summary = summary,
        target = target,
        icon = icon,
    )

    private companion object {
        val ALLOWED_FLORISBOARD_PACKAGES = setOf(
            "dev.patrickgold.florisboard",
            "dev.patrickgold.florisboard.beta",
            "dev.patrickgold.florisboard.debug",
            "dev.patrickgold.florisboard.bench",
        )
    }
}
