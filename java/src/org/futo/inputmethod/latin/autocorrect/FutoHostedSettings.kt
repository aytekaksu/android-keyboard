/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect

import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.provider.UserDictionary
import android.system.Os
import androidx.core.content.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.florisboard.autocorrect.api.AutocorrectPluginDocument
import org.florisboard.autocorrect.api.AutocorrectPluginHostSetting
import org.florisboard.autocorrect.api.AutocorrectPluginUi
import org.florisboard.autocorrect.api.AutocorrectPluginUiIcon
import org.florisboard.autocorrect.api.AutocorrectPluginUiItem
import org.florisboard.autocorrect.api.AutocorrectPluginUiItemKind
import org.florisboard.autocorrect.api.AutocorrectPluginUiOption
import org.florisboard.autocorrect.api.AutocorrectPluginUiPage
import org.florisboard.autocorrect.api.AutocorrectPluginUiSurface
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.LegacySwipeSetting
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.localeFromString
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.FileKind
import org.futo.inputmethod.latin.uix.PersonalWord
import org.futo.inputmethod.latin.uix.PreferenceUtils
import org.futo.inputmethod.latin.uix.SHOW_EMOJI_SUGGESTIONS
import org.futo.inputmethod.latin.uix.SUGGESTION_BLACKLIST
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.UserDictionaryIO
import org.futo.inputmethod.latin.uix.dataStore
import org.futo.inputmethod.latin.uix.determineFileKind
import org.futo.inputmethod.latin.uix.namePreferenceKeyFor
import org.futo.inputmethod.latin.uix.preferenceKeyFor
import org.futo.inputmethod.latin.uix.setSettingAndAwaitCache
import org.futo.inputmethod.latin.uix.settings.pages.pdict.JapanesePersonalWord
import org.futo.inputmethod.latin.uix.settings.pages.pdict.PosTypes
import org.futo.inputmethod.latin.uix.settings.pages.pdict.decodeJapanesePersonalWord
import org.futo.inputmethod.latin.uix.settings.pages.pdict.encode
import org.futo.inputmethod.latin.xlm.AllowTransformerOnNonQWERTYLayouts
import org.futo.inputmethod.latin.xlm.AutocorrectThresholdSetting
import org.futo.inputmethod.latin.xlm.BASE_MODEL_NAME
import org.futo.inputmethod.latin.xlm.BinaryDictTransformerWeightSetting
import org.futo.inputmethod.latin.xlm.MODEL_OPTION_KEY
import org.futo.inputmethod.latin.xlm.ModelInfo
import org.futo.inputmethod.latin.xlm.ModelInfoLoader
import org.futo.inputmethod.latin.xlm.ModelPaths
import java.io.File
import java.util.Locale

/**
 * Provider-owned settings state and content. FlorisBoard owns every rendered control and system
 * picker; this class only supplies bounded declarative pages and applies their mutations.
 */
internal class FutoHostedSettings(
    private val context: Context,
    private val engine: FutoAutocorrectEngine,
) {
    private var languages = listOf(Locale.ENGLISH.toLanguageTag())
    private var modelLanguage = languages.first()
    private var dictionaryLanguage = languages.first()
    private var selectedModelName: String? = null
    private var modelPage = 0
    private var modelDetailsCacheKey: String? = null
    private var modelDetailsCache: ModelInfo? = null
    private var selectedPersonalWord: PersonalWord? = null
    private var personalWord = ""
    private var personalShortcut = ""
    private var personalReading = ""
    private var personalPos = PosTypes.NO_POS
    private var personalLocale = languages.first()
    private var personalLanguages = languages
    private var personalPage = 0
    private var blacklistWord = ""
    private var selectedBlacklistWord: String? = null
    private var blacklistPage = 0
    private var paymentReminderDays = "30"
    private var documentStatus: String? = null
    private var documentSuccessStatus: String? = null

    suspend fun ui(languageTags: List<String>): AutocorrectPluginUi {
        updateLanguages(languageTags)
        val preferences = PreferenceUtils.getDefaultSharedPreferences(context)
        val transformerEnabled = preferences.getBoolean(
            Settings.PREF_KEY_USE_TRANSFORMER_LM,
            true,
        )
        val models = ModelPaths.getModels(context).sortedBy { it.name.lowercase(Locale.ROOT) }
        val personalWords = UserDictionaryIO(context).get()
            .sortedWith(compareBy({ it.locale.orEmpty() }, { it.word.lowercase(Locale.ROOT) }))
        val blacklist = setting(SUGGESTION_BLACKLIST).sortedWith(String.CASE_INSENSITIVE_ORDER)
        val modelOptions = setting(MODEL_OPTION_KEY)
        val hasPaidFuto = setting(FUTO_ALREADY_PAID)
        val paymentReminderTime = setting(FUTO_PAYMENT_REMINDER_TIME)
        val paymentReminderDue = !hasPaidFuto && isPaymentReminderDue(paymentReminderTime)
        val paymentLicense = setting(FUTO_PAYMENT_LICENSE)

        selectedModelName = selectedModelName
            ?.takeIf { selected -> models.any { it.path.name == selected } }
            ?: modelOptions
                .firstOrNull { it.substringBefore(':') == locale(modelLanguage).language }
                ?.substringAfter(':')
                ?.let { "$it.gguf" }
                ?.takeIf { selected -> models.any { it.path.name == selected } }
            ?: models.firstOrNull()?.path?.name
        modelPage = models.indexOfFirst { it.path.name == selectedModelName }
            .takeIf { it >= 0 }
            ?.div(PAGE_SIZE)
            ?: 0
        selectedPersonalWord = selectedPersonalWord?.takeIf(personalWords::contains)
        updatePersonalLanguages(personalWords)
        selectedBlacklistWord = selectedBlacklistWord?.takeIf(blacklist::contains)
        personalPage = personalPage.coerceIn(0, lastPage(personalWords.size))
        blacklistPage = blacklistPage.coerceIn(0, lastPage(blacklist.size))

        return AutocorrectPluginUi(
            appRootPageId = PAGE_OVERVIEW,
            keyboardRootPageId = PAGE_QUICK,
            pages = listOf(
                AutocorrectPluginUiPage(
                    id = PAGE_OVERVIEW,
                    title = "FUTO predictive text",
                    summary = "On-device prediction supplied by the FUTO engine and rendered by FlorisBoard.",
                    items = commonSettings(preferences, transformerEnabled) + listOf(
                        navigation(
                            id = "openAdvanced",
                            title = "Advanced prediction",
                            target = PAGE_ADVANCED,
                            icon = AutocorrectPluginUiIcon.TUNE,
                        ),
                        navigation(
                            id = "openResources",
                            title = "Models and dictionaries",
                            summary = "${models.size} model file(s), ${personalWords.size} personal word(s)",
                            target = PAGE_RESOURCES,
                            icon = AutocorrectPluginUiIcon.MODEL,
                        ),
                        navigation(
                            id = "openBlacklist",
                            title = "Suggestion filtering",
                            summary = "${blacklist.size} manually blocked word(s)",
                            target = PAGE_BLACKLIST,
                            icon = AutocorrectPluginUiIcon.DELETE,
                        ),
                        navigation(
                            id = "openFutoSupport",
                            title = "Support FUTO",
                            summary = if (hasPaidFuto) {
                                "Marked as paid — thank you for supporting the upstream engine"
                            } else if (paymentReminderDue) {
                                "Support reminder due — review FUTO's payment options"
                            } else {
                                "Payment and support options for the FUTO engine"
                            },
                            target = PAGE_FUTO_SUPPORT,
                            icon = AutocorrectPluginUiIcon.INFO,
                        ),
                    ),
                ),
                AutocorrectPluginUiPage(
                    id = PAGE_ADVANCED,
                    title = "Advanced prediction",
                    items = advancedSettings(transformerEnabled),
                ),
                AutocorrectPluginUiPage(
                    id = PAGE_RESOURCES,
                    title = "Models and dictionaries",
                    items = listOfNotNull(documentStatusItem()) + listOf(
                        navigation(
                            "openModels",
                            "Language models",
                            PAGE_MODELS,
                            AutocorrectPluginUiIcon.MODEL,
                            "${models.size} installed",
                        ),
                        navigation(
                            "openDictionaries",
                            "Language dictionaries",
                            PAGE_DICTIONARIES,
                            AutocorrectPluginUiIcon.DICTIONARY,
                            "Configured for FlorisBoard languages",
                        ),
                        navigation(
                            "openPersonal",
                            "Personal dictionary",
                            PAGE_PERSONAL,
                            AutocorrectPluginUiIcon.DICTIONARY,
                            "${personalWords.size} word(s)",
                        ),
                        action(
                            id = "clearHistory",
                            title = "Clear learned history",
                            confirmation = "Delete all words learned from typing?",
                        ),
                    ),
                ),
                modelPage(models, modelOptions, transformerEnabled),
                dictionaryPage(),
                personalDictionaryPage(personalWords),
                blacklistPage(preferences, blacklist),
                AutocorrectPluginUiPage(
                    id = PAGE_FUTO_SUPPORT,
                    title = "Support FUTO",
                    summary = "This provider is a modified, non-commercial derivative of FUTO Keyboard.",
                    items = buildList {
                        add(
                            info(
                                id = "futoPaymentAbout",
                                title = "FUTO develops the upstream engine",
                                summary = "The provider remains free to use. FUTO asks users who find its work useful to support continued private, on-device keyboard development.",
                                icon = AutocorrectPluginUiIcon.INFO,
                            ),
                        )
                        add(
                            info(
                                id = "futoSwipeAttribution",
                                title = "Powered by FUTO Swipe technology.",
                                summary = "Neural glide model weights are provided by FUTO.",
                                icon = AutocorrectPluginUiIcon.INFO,
                            ),
                        )
                        if (hasPaidFuto) {
                            add(
                                info(
                                    id = "futoPaymentStatus",
                                    title = "Thank you for supporting FUTO",
                                    summary = if (paymentLicense.isBlank()) {
                                        "This installation is marked as already paid."
                                    } else {
                                        "Payment was recorded for this installation."
                                    },
                                    icon = AutocorrectPluginUiIcon.INFO,
                                ),
                            )
                        } else {
                            if (paymentReminderDue) {
                                add(
                                    info(
                                        id = "futoPaymentReminderDue",
                                        title = "FUTO support reminder due",
                                        summary = "You asked to be reminded about supporting the upstream engine.",
                                        icon = AutocorrectPluginUiIcon.INFO,
                                    ),
                                )
                            }
                            add(
                                link(
                                    id = "futoPay",
                                    title = "Pay ${BuildConfig.FUTOPAY_PRICE} via FUTO",
                                    summary = "Open FUTO's secure checkout in your browser.",
                                    target = BuildConfig.FUTOPAY_URL,
                                ),
                            )
                            add(
                                action(
                                    id = "futoAlreadyPaid",
                                    title = "I already paid",
                                    summary = "Record this locally; no account or network request is made.",
                                    confirmation = "Mark this installation as already paid?",
                                    icon = AutocorrectPluginUiIcon.INFO,
                                ),
                            )
                        }
                        add(
                            choice(
                                id = "futoPaymentReminderDays",
                                title = "Remind me later",
                                value = paymentReminderDays,
                                options = listOf(
                                    AutocorrectPluginUiOption("7", "In 7 days"),
                                    AutocorrectPluginUiOption("30", "In 1 month"),
                                    AutocorrectPluginUiOption("182", "In 6 months"),
                                    AutocorrectPluginUiOption("36500", "Next century"),
                                ),
                                summary = paymentReminderSummary(paymentReminderTime),
                                enabled = !hasPaidFuto,
                            ),
                        )
                        add(
                            action(
                                id = "futoSetPaymentReminder",
                                title = "Set support reminder",
                                summary = "The date stays on-device and appears in provider settings when due.",
                                enabled = !hasPaidFuto,
                                icon = AutocorrectPluginUiIcon.REFRESH,
                            ),
                        )
                        add(
                            link(
                                id = "futoHelp",
                                title = "FUTO Keyboard project page",
                                summary = "Learn more about FUTO Keyboard and its funding model.",
                                target = FUTO_KEYBOARD_URL,
                            ),
                        )
                    },
                ),
                AutocorrectPluginUiPage(
                    id = PAGE_QUICK,
                    title = "FUTO predictive text",
                    surface = AutocorrectPluginUiSurface.KEYBOARD,
                    items = commonSettings(preferences, transformerEnabled) + buildList {
                        if (paymentReminderDue) {
                            add(
                                info(
                                    id = "futoPaymentReminderDueQuick",
                                    title = "FUTO support reminder due",
                                    summary = "Payment options are available in Better FlorisBoard settings.",
                                    icon = AutocorrectPluginUiIcon.INFO,
                                ),
                            )
                        }
                        add(
                            navigation(
                                id = "quickAdvanced",
                                title = "Prediction strength",
                                target = PAGE_QUICK_ADVANCED,
                                icon = AutocorrectPluginUiIcon.TUNE,
                            ),
                        )
                    },
                ),
                AutocorrectPluginUiPage(
                    id = PAGE_QUICK_ADVANCED,
                    title = "Prediction strength",
                    surface = AutocorrectPluginUiSurface.KEYBOARD,
                    items = strengthSettings(transformerEnabled),
                ),
            ),
        )
    }

    suspend fun setValue(itemId: String, value: String): Boolean {
        val preferences = PreferenceUtils.getDefaultSharedPreferences(context)
        val changed = when (itemId) {
            "autoCorrection" -> preferences.setBoolean(Settings.PREF_AUTO_CORRECTION, value)
            "transformer" -> preferences.setBoolean(Settings.PREF_KEY_USE_TRANSFORMER_LM, value)
            "personalized" -> preferences.setBoolean(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, value)
            "smartKeyHit" -> preferences.setBoolean(Settings.PREF_USE_DICT_KEY_BOOSTING, value)
            "showSuggestions" -> preferences.setBoolean(Settings.PREF_SHOW_SUGGESTIONS, value)
            "gesture" -> preferences.setBoolean(Settings.PREF_GESTURE_INPUT, value)
            "sensitiveGesture" ->
                preferences.setBoolean(Settings.PREF_GESTURE_INPUT_SENSITIVITY, value)
            "nextWord" -> preferences.setBoolean(Settings.PREF_BIGRAM_PREDICTIONS, value)
            "blockOffensive" ->
                preferences.setBoolean(Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE_2, value)
            "blockSlurs" -> preferences.setBoolean(Settings.PREF_BLOCK_SLURS, value)
            "emojiSuggestions" -> setBoolean(SHOW_EMOJI_SUGGESTIONS, value)
            "allowNonQwerty" -> setBoolean(AllowTransformerOnNonQWERTYLayouts, value)
            "swipeEngine" -> when (value) {
                "neural" -> context.setSettingAndAwaitCache(LegacySwipeSetting, false).let { true }
                "legacy" -> context.setSettingAndAwaitCache(LegacySwipeSetting, true).let { true }
                else -> false
            }
            "autocorrectThreshold" -> value.toFloatOrNull()
                ?.takeIf { it.isFinite() && it in 0f..25f }
                ?.let {
                    context.setSettingAndAwaitCache(AutocorrectThresholdSetting, it)
                    true
                }
                ?: false
            "transformerWeight" -> value.toFloatOrNull()
                ?.takeIf { it.isFinite() && it in 0f..100f }
                ?.let {
                    context.setSettingAndAwaitCache(
                        BinaryDictTransformerWeightSetting,
                        when {
                            it < 0.0001f -> Float.NEGATIVE_INFINITY
                            it > 99.9f -> Float.POSITIVE_INFINITY
                            else -> it
                        },
                    )
                    true
                } ?: false
            "modelLanguage" -> selectLanguage(value) { modelLanguage = it }
            "selectedModel" -> ModelPaths.getModels(context)
                .firstOrNull { it.path.name == value }
                ?.let {
                    selectedModelName = it.path.name
                    true
                } ?: false
            "dictionaryLanguage" -> selectLanguage(value) { dictionaryLanguage = it }
            "personalSelection" -> selectPersonalWord(value)
            "personalWord" -> value.take(MAX_WORD_CHARS).let {
                personalWord = it
                true
            }
            "personalShortcut" -> value.take(MAX_WORD_CHARS).let {
                personalShortcut = it
                true
            }
            "personalReading" -> value.take(MAX_WORD_CHARS).let {
                personalReading = it
                true
            }
            "personalPos" -> value.toIntOrNull()
                ?.let(PosTypes.entries::getOrNull)
                ?.let {
                    personalPos = it
                    true
                } ?: false
            "personalLocale" -> setPersonalLocale(value)
            "blacklistWord" -> value.take(MAX_WORD_CHARS).let {
                blacklistWord = it
                true
            }
            "blacklistSelection" -> setting(SUGGESTION_BLACKLIST)
                .firstOrNull { it == value }
                ?.let {
                    selectedBlacklistWord = it
                    true
                } ?: false
            "futoPaymentReminderDays" -> value
                .takeIf { it in PAYMENT_REMINDER_OPTIONS }
                ?.let {
                    paymentReminderDays = it
                    true
                } ?: false
            else -> false
        }
        if (changed && itemId in LIVE_SETTING_IDS) {
            reload(resourcesChanged = itemId == "personalized")
        }
        return changed
    }

    suspend fun invoke(itemId: String): Boolean = when (itemId) {
        "clearHistory" -> engine.clearHistory()
        "modelDefault" -> setSelectedModelDefault()
        "modelDelete" -> deleteSelectedModel()
        "modelPrevious" -> selectModelPage(modelPage - 1)
        "modelNext" -> selectModelPage(modelPage + 1)
        "dictionaryRemove" -> removeDictionary()
        "personalNew" -> {
            clearPersonalEditor()
            true
        }
        "personalSave" -> savePersonalWord()
        "personalDelete" -> deletePersonalWord()
        "personalPrevious" -> {
            personalPage = (personalPage - 1).coerceAtLeast(0)
            true
        }
        "personalNext" -> {
            personalPage++
            true
        }
        "blacklistAdd" -> addBlacklistWord()
        "blacklistRemove" -> removeBlacklistWord()
        "clearBlacklist" -> {
            engine.replaceBlacklist(emptySet())
            selectedBlacklistWord = null
            blacklistPage = 0
            true
        }
        "blacklistPrevious" -> {
            blacklistPage = (blacklistPage - 1).coerceAtLeast(0)
            true
        }
        "blacklistNext" -> {
            blacklistPage++
            true
        }
        "futoAlreadyPaid" -> {
            context.setSettingAndAwaitCache(FUTO_ALREADY_PAID, true)
            true
        }
        "futoSetPaymentReminder" -> paymentReminderDays.toLongOrNull()?.let { days ->
            val reminderTime = System.currentTimeMillis() / 1000L +
                days.coerceAtMost(MAX_PAYMENT_REMINDER_DAYS) * SECONDS_PER_DAY
            context.setSettingAndAwaitCache(FUTO_PAYMENT_REMINDER_TIME, reminderTime)
            true
        } ?: false
        else -> false
    }

    suspend fun document(document: AutocorrectPluginDocument): Boolean {
        documentSuccessStatus = null
        val operation = when (document.itemId) {
            "modelImport" -> "Model import"
            "modelExport" -> "Model export"
            "dictionaryImport" -> "Dictionary import"
            else -> "File operation"
        }
        return try {
            val successful = when (document.itemId) {
                "modelImport" -> !document.write && importModel(document)
                "modelExport" -> document.write && exportModel(document)
                "dictionaryImport" -> !document.write && importDictionary(document)
                else -> false
            }
            documentStatus = if (successful) {
                documentSuccessStatus ?: when (document.itemId) {
                    "modelImport" -> "Imported ${selectedModelName.orEmpty()} for ${languageLabel(modelLanguage)}."
                    "modelExport" -> "Exported ${selectedModelName.orEmpty()}."
                    "dictionaryImport" -> "Imported a custom dictionary for ${languageLabel(dictionaryLanguage)}."
                    else -> "$operation completed."
                }
            } else {
                "$operation was rejected."
            }.take(MAX_STATUS_CHARS)
            successful
        } catch (error: DocumentFailure) {
            documentStatus = "$operation failed: ${error.message.orEmpty()}".take(MAX_STATUS_CHARS)
            false
        } catch (_: Exception) {
            documentStatus = "$operation failed because the file could not be read or written."
            false
        }
    }

    private suspend fun modelPage(
        models: List<ModelInfoLoader>,
        modelOptions: Set<String>,
        transformerEnabled: Boolean,
    ): AutocorrectPluginUiPage {
        val selected = models.firstOrNull { it.path.name == selectedModelName }
        val selectedDetails = selected?.let(::modelDetails)
        val selectedSupported = selectedDetails?.isProviderSupported() == true
        val pageModels = models.drop(modelPage * PAGE_SIZE).take(PAGE_SIZE)
        val languageCode = locale(modelLanguage).language
        val selectedSupportsLanguage = selectedSupported && selectedDetails!!.languages.any {
            locale(it).language == languageCode
        }
        val defaultName = modelOptions.firstOrNull {
            it.substringBefore(':') == languageCode
        }?.substringAfter(':')
        val selectedIsDefault = selected?.path?.nameWithoutExtension == defaultName
        val detailItems = when {
            selected == null -> listOf(
                info(
                    "modelDetails",
                    "Model details",
                    "No model is installed.",
                    AutocorrectPluginUiIcon.INFO,
                ),
            )
            selectedDetails == null -> listOf(
                info(
                    "modelDetails",
                    "Model details",
                    "${selected.path.name} • ${formatBytes(selected.path.length())} • " +
                        "Metadata could not be read, so this model cannot be activated.",
                    AutocorrectPluginUiIcon.INFO,
                ),
            )
            else -> listOf(
                info(
                    "modelDetails",
                    selectedDetails.name.ifBlank { selected.name },
                    listOfNotNull(
                        selectedDetails.description.takeIf(String::isNotBlank),
                        formatBytes(selected.path.length()),
                        if (selectedDetails.isProviderSupported()) {
                            "Compatible keyboard model"
                        } else {
                            "Unsupported keyboard model; it can only be exported or deleted"
                        },
                    ).joinToString(" • "),
                    AutocorrectPluginUiIcon.INFO,
                ),
                info(
                    "modelAttribution",
                    "Author and license",
                    "Author: ${selectedDetails.author.ifBlank { "Not declared" }} • " +
                        "License: ${selectedDetails.license.ifBlank { "Not declared" }}",
                    AutocorrectPluginUiIcon.INFO,
                ),
                info(
                    "modelCompatibility",
                    "Languages and tokenizer",
                    "Languages: ${selectedDetails.languages.ifEmpty { listOf("Not declared") }.joinToString()} • " +
                        "Tokenizer: ${selectedDetails.tokenizer_type.ifBlank { "Not declared" }}",
                    AutocorrectPluginUiIcon.INFO,
                ),
                info(
                    "modelFeatures",
                    "Model features",
                    buildString {
                        append(selectedDetails.features.ifEmpty { listOf("None declared") }.joinToString())
                        if (selectedDetails.finetune_count > 0) {
                            append(" • ${selectedDetails.finetune_count} fine-tuning run(s)")
                        }
                    },
                    AutocorrectPluginUiIcon.INFO,
                ),
            )
        }
        return AutocorrectPluginUiPage(
            id = PAGE_MODELS,
            title = "Language models",
            summary = if (transformerEnabled) {
                "Models run locally. The selected language comes from FlorisBoard's configured subtypes."
            } else {
                "Models can be managed while the transformer is off, but cannot be activated until it is enabled."
            },
            items = listOfNotNull(documentStatusItem()) + listOf(
                choice(
                    id = "modelLanguage",
                    title = "Language",
                    value = modelLanguage,
                    options = languageOptions(),
                ),
                choice(
                    id = "selectedModel",
                    title = "Installed model",
                    value = selected?.path?.name.orEmpty(),
                    summary = pageSummary(modelPage, models.size),
                    options = pageModels.map {
                        AutocorrectPluginUiOption(it.path.name, it.name)
                    },
                    enabled = models.isNotEmpty(),
                ),
            ) + detailItems + listOf(
                action(
                    id = "modelDefault",
                    title = if (selectedIsDefault) {
                        "Default model for ${languageLabel(modelLanguage)}"
                    } else {
                        "Use for ${languageLabel(modelLanguage)}"
                    },
                    summary = if (transformerEnabled) {
                        null
                    } else {
                        "Enable the transformer language model before changing its active model."
                    },
                    enabled = transformerEnabled && selectedSupportsLanguage && !selectedIsDefault,
                    icon = AutocorrectPluginUiIcon.REFRESH,
                ),
                documentImport(
                    id = "modelImport",
                    title = "Import model",
                    summary = "Select a GGUF keyboard language model.",
                    icon = AutocorrectPluginUiIcon.DOWNLOAD,
                ),
                documentExport(
                    id = "modelExport",
                    title = "Export selected model",
                    name = selected?.path?.name ?: "keyboard-model.gguf",
                    enabled = selected != null,
                    confirmation = selectedDetails?.takeIf { it.finetune_count > 0 }?.let {
                        "This fine-tuned model may contain information learned from your typing. Export it only for private backup or transfer."
                    },
                ),
                action(
                    id = "modelDelete",
                    title = "Delete selected model",
                    confirmation = "Delete this model file? This cannot be undone.",
                    enabled = selected != null && selected.path.nameWithoutExtension != BASE_MODEL_NAME,
                ),
                pagingAction("modelPrevious", "Previous models", modelPage > 0),
                pagingAction(
                    "modelNext",
                    "Next models",
                    (modelPage + 1) * PAGE_SIZE < models.size,
                ),
            ),
        )
    }

    private suspend fun dictionaryPage(): AutocorrectPluginUiPage {
        val current = dictionaryResource(dictionaryLanguage)
        return AutocorrectPluginUiPage(
            id = PAGE_DICTIONARIES,
            title = "Language dictionaries",
            summary = "A custom binary dictionary replaces the built-in dictionary for the selected FlorisBoard language.",
            items = listOfNotNull(documentStatusItem()) + listOf(
                choice(
                    id = "dictionaryLanguage",
                    title = "Language",
                    value = dictionaryLanguage,
                    options = languageOptions(),
                ),
                info(
                    id = "dictionaryStatus",
                    title = "Current dictionary",
                    summary = current?.second ?: "Built-in dictionary",
                    icon = AutocorrectPluginUiIcon.DICTIONARY,
                ),
                documentImport(
                    id = "dictionaryImport",
                    title = "Import custom dictionary",
                    summary = "Select a FUTO-compatible binary .dict file.",
                    icon = AutocorrectPluginUiIcon.DOWNLOAD,
                ),
                action(
                    id = "dictionaryRemove",
                    title = "Revert to built-in dictionary",
                    confirmation = "Remove the custom dictionary for this language?",
                    enabled = current != null,
                ),
            ),
        )
    }

    private fun personalDictionaryPage(
        words: List<PersonalWord>,
    ): AutocorrectPluginUiPage {
        val pageWords = words.page(personalPage)
        val japanese = personalLocaleLanguage() == Locale.JAPANESE.language
        val chinese = personalLocaleLanguage() == Locale.CHINESE.language
        val editable = !chinese && selectedPersonalWord?.isChinese() != true
        val validJapaneseReading = !japanese || personalReading.isValidJapaneseReading()
        return AutocorrectPluginUiPage(
            id = PAGE_PERSONAL,
            title = "Personal dictionary",
            summary = "Personal words and shortcuts are stored in Android's user dictionary.",
            items = listOf(
                choice(
                    id = "personalSelection",
                    title = "Saved words",
                    summary = pageSummary(personalPage, words.size),
                    value = selectedPersonalWord
                        ?.let(words::indexOf)
                        ?.takeIf { it >= 0 }
                        ?.toString()
                        .orEmpty(),
                    options = pageWords.map { (index, word) ->
                        AutocorrectPluginUiOption(
                            index.toString(),
                            buildString {
                                append(word.word)
                                word.displayShortcut()
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { append(" ← $it") }
                                append(" (${word.locale?.let(::languageLabel) ?: "all languages"})")
                            },
                        )
                    },
                    enabled = pageWords.isNotEmpty(),
                ),
                text(
                    id = "personalWord",
                    title = "Word",
                    value = personalWord,
                    summary = "The word to add or edit.",
                    enabled = editable,
                ),
            ) + if (japanese) {
                listOf(
                    text(
                        id = "personalReading",
                        title = "Reading",
                        value = personalReading,
                        summary = if (validJapaneseReading) {
                            "Japanese reading (よみ)."
                        } else {
                            "Use only hiragana and Japanese punctuation."
                        },
                        enabled = editable,
                    ),
                    choice(
                        id = "personalPos",
                        title = "Part of speech",
                        value = PosTypes.entries.indexOf(personalPos).toString(),
                        options = PosTypes.entries.mapIndexed { index, pos ->
                            AutocorrectPluginUiOption(index.toString(), pos.text)
                        },
                        enabled = editable,
                    ),
                )
            } else {
                listOf(
                    text(
                        id = "personalShortcut",
                        title = "Shortcut",
                        value = personalShortcut,
                        summary = "Optional text which expands to the word.",
                        enabled = editable,
                    ),
                )
            } + listOf(
                choice(
                    id = "personalLocale",
                    title = "Language",
                    value = personalLocale,
                    options = listOf(
                        AutocorrectPluginUiOption(ALL_LANGUAGES, "All languages"),
                    ) + personalLanguageOptions(
                        includeChinese = selectedPersonalWord?.isChinese() == true,
                    ),
                    enabled = editable,
                ),
            ) + listOfNotNull(
                if (chinese || selectedPersonalWord?.isChinese() == true) {
                    info(
                        id = "personalChineseReadOnly",
                        title = "Chinese entries are read-only",
                        summary = "FUTO does not support editing Chinese personal dictionary entries.",
                        icon = AutocorrectPluginUiIcon.INFO,
                    )
                } else {
                    null
                },
            ) + listOf(
                action(
                    id = "personalSave",
                    title = if (selectedPersonalWord == null) "Add word" else "Save word",
                    enabled = editable && validJapaneseReading && personalWord.isNotBlank(),
                    icon = AutocorrectPluginUiIcon.ADD,
                ),
                action(
                    id = "personalNew",
                    title = "Create another word",
                    enabled = selectedPersonalWord != null ||
                        personalWord.isNotEmpty() ||
                        personalShortcut.isNotEmpty() ||
                        personalReading.isNotEmpty() ||
                        personalPos != PosTypes.NO_POS ||
                        personalLocale != defaultPersonalLocale(),
                    icon = AutocorrectPluginUiIcon.ADD,
                ),
                action(
                    id = "personalDelete",
                    title = "Delete selected word",
                    confirmation = "Delete this personal dictionary entry?",
                    enabled = editable && selectedPersonalWord != null,
                ),
                pagingAction("personalPrevious", "Previous words", personalPage > 0),
                pagingAction(
                    "personalNext",
                    "Next words",
                    (personalPage + 1) * PAGE_SIZE < words.size,
                ),
            ),
        )
    }

    private fun blacklistPage(
        preferences: SharedPreferences,
        words: List<String>,
    ): AutocorrectPluginUiPage {
        val pageWords = words.page(blacklistPage)
        val blockOffensive = Settings.readBlockPotentiallyOffensive(preferences, context.resources)
        return AutocorrectPluginUiPage(
            id = PAGE_BLACKLIST,
            title = "Suggestion filtering",
            items = listOf(
                switch(
                    id = "blockOffensive",
                    title = "Block potentially offensive words",
                    value = blockOffensive,
                ),
                switch(
                    id = "blockSlurs",
                    title = "Block slurs",
                    value = Settings.readBlockSlurs(preferences, context.resources),
                    summary = if (blockOffensive) {
                        "Potentially offensive filtering already includes slurs."
                    } else {
                        null
                    },
                    enabled = !blockOffensive,
                ),
                text(
                    id = "blacklistWord",
                    title = "Word to block",
                    value = blacklistWord,
                    summary = "Exact suggestion text.",
                ),
                action(
                    id = "blacklistAdd",
                    title = "Add to blacklist",
                    enabled = blacklistWord.isNotBlank(),
                    icon = AutocorrectPluginUiIcon.ADD,
                ),
                choice(
                    id = "blacklistSelection",
                    title = "Blocked words",
                    summary = pageSummary(blacklistPage, words.size),
                    value = selectedBlacklistWord.orEmpty(),
                    options = pageWords.map { (_, word) ->
                        AutocorrectPluginUiOption(word, word)
                    },
                    enabled = pageWords.isNotEmpty(),
                ),
                action(
                    id = "blacklistRemove",
                    title = "Allow selected word",
                    enabled = selectedBlacklistWord != null,
                ),
                action(
                    id = "clearBlacklist",
                    title = "Clear suggestion blacklist",
                    confirmation = "Allow every manually blacklisted suggestion again?",
                    enabled = words.isNotEmpty(),
                ),
                pagingAction("blacklistPrevious", "Previous blocked words", blacklistPage > 0),
                pagingAction(
                    "blacklistNext",
                    "Next blocked words",
                    (blacklistPage + 1) * PAGE_SIZE < words.size,
                ),
            ),
        )
    }

    private fun commonSettings(
        preferences: SharedPreferences,
        transformerEnabled: Boolean,
    ) = listOf(
        switch(
            "autoCorrection",
            "Autocorrection",
            preferences.getBoolean(Settings.PREF_AUTO_CORRECTION, true),
        ),
        switch(
            "transformer",
            "Transformer language model",
            preferences.getBoolean(Settings.PREF_KEY_USE_TRANSFORMER_LM, true),
        ),
        switch(
            "personalized",
            "Personalized suggestions",
            preferences.getBoolean(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, true),
        ),
        switch(
            "smartKeyHit",
            "Smart key-hit detection",
            preferences.getBoolean(Settings.PREF_USE_DICT_KEY_BOOSTING, true),
        ),
        switch(
            "showSuggestions",
            "Show suggestions",
            preferences.getBoolean(Settings.PREF_SHOW_SUGGESTIONS, true),
        ),
        switch(
            "gesture",
            "Swipe typing",
            Settings.readGestureInputEnabled(preferences, context.resources),
            hostSetting = AutocorrectPluginHostSetting.GLIDE_ENABLED,
        ),
        switch(
            "sensitiveGesture",
            "Sensitive swipe detection",
            preferences.getBoolean(Settings.PREF_GESTURE_INPUT_SENSITIVITY, false),
            summary = "Start swipe typing with a shorter movement.",
            hostSetting = AutocorrectPluginHostSetting.GLIDE_SENSITIVE,
        ),
        switch(
            "nextWord",
            "Next-word predictions",
            transformerEnabled || preferences.getBoolean(
                Settings.PREF_BIGRAM_PREDICTIONS,
                context.resources.getBoolean(R.bool.config_default_next_word_prediction),
            ),
            summary = if (transformerEnabled) {
                "Always enabled while the transformer language model is on."
            } else {
                null
            },
            enabled = !transformerEnabled,
        ),
    )

    private suspend fun advancedSettings(transformerEnabled: Boolean) =
        strengthSettings(transformerEnabled) + listOf(
            switch(
                id = "emojiSuggestions",
                title = "Emoji suggestions",
                value = setting(SHOW_EMOJI_SUGGESTIONS),
            ),
            switch(
                id = "allowNonQwerty",
                title = "Transformer on non-QWERTY layouts",
                value = setting(AllowTransformerOnNonQWERTYLayouts),
                summary = if (transformerEnabled) {
                    "Allow experimental transformer predictions on other alphabet layouts."
                } else {
                    "Enable the transformer language model to use this option."
                },
                enabled = transformerEnabled,
            ),
            choice(
                id = "swipeEngine",
                title = "Swipe decoder",
                value = if (setting(LegacySwipeSetting)) "legacy" else "neural",
                options = listOf(
                    AutocorrectPluginUiOption("neural", "Neural decoder"),
                    AutocorrectPluginUiOption("legacy", "Legacy decoder"),
                ),
            ),
            info(
                id = "offensiveLocation",
                title = "Offensive-word and slur controls",
                summary = "These controls are available under Suggestion filtering.",
                icon = AutocorrectPluginUiIcon.INFO,
            ),
            info(
                id = "finetuningStatus",
                title = "On-device fine-tuning is unavailable",
                summary = "The current upstream FUTO build ships its training worker disabled.",
                icon = AutocorrectPluginUiIcon.INFO,
            ),
        )

    private suspend fun strengthSettings(transformerEnabled: Boolean) = listOf(
        AutocorrectPluginUiItem(
            id = "transformerWeight",
            kind = AutocorrectPluginUiItemKind.SLIDER,
            title = "Transformer strength",
            summary = if (transformerEnabled) {
                "Balances transformer and binary-dictionary ranking."
            } else {
                "Enable the transformer language model to adjust prediction strength."
            },
            value = when (val weight = setting(BinaryDictTransformerWeightSetting)) {
                Float.NEGATIVE_INFINITY -> "0.0"
                Float.POSITIVE_INFINITY -> "100.0"
                else -> weight.toString()
            },
            minimum = 0.0,
            maximum = 100.0,
            step = 0.1,
            icon = AutocorrectPluginUiIcon.TUNE,
            enabled = transformerEnabled,
        ),
        AutocorrectPluginUiItem(
            id = "autocorrectThreshold",
            kind = AutocorrectPluginUiItemKind.SLIDER,
            title = "Autocorrect threshold",
            summary = if (transformerEnabled) {
                "Higher values require more model confidence."
            } else {
                "Enable the transformer language model to adjust its autocorrect threshold."
            },
            value = setting(AutocorrectThresholdSetting).toString(),
            minimum = 0.0,
            maximum = 25.0,
            step = 0.1,
            icon = AutocorrectPluginUiIcon.TUNE,
            enabled = transformerEnabled,
        ),
    )

    private suspend fun importModel(document: AutocorrectPluginDocument): Boolean {
        val targetLanguage = locale(modelLanguage).language
        val imported = withContext(Dispatchers.IO) {
            val safeName = safeFileName(document.displayName, "keyboard-model.gguf", ".gguf")
            val directory = ModelPaths.getModelDirectory(context)
            val destination = File(directory, safeName)
            if (destination.exists()) {
                throw DocumentFailure("A model named $safeName is already installed.")
            }
            val staging = File.createTempFile(".floris-model-", ".tmp", directory)
            try {
                copyDocument(document, staging, MAX_MODEL_BYTES, "The model")
                val magic = staging.inputStream().use { input ->
                    val bytes = ByteArray(4)
                    input.read(bytes) == bytes.size && bytes.contentEquals(GGUF_MAGIC)
                }
                if (!magic) {
                    throw DocumentFailure("The file is not a valid GGUF file.")
                }
                val details = runCatching {
                    ModelInfoLoader(staging, destination.nameWithoutExtension)
                        .loadDetails()
                }.getOrNull()?.takeIf { it.isProviderSupported() }
                if (details == null) {
                    throw DocumentFailure(
                        "The file is not a supported GGUF keyboard language model.",
                    )
                }
                if (destination.exists()) {
                    throw DocumentFailure("A model named $safeName is already installed.")
                }
                Os.rename(staging.absolutePath, destination.absolutePath)
                destination to details
            } finally {
                staging.delete()
            }
        }
        val file = imported.first
        val details = imported.second
        val supportsSelectedLanguage = details.languages.any {
            locale(it).language == targetLanguage
        }
        selectedModelName = file.name
        if (supportsSelectedLanguage) {
            ModelPaths.updateModelOption(context, targetLanguage, file)
            documentSuccessStatus =
                "Imported ${file.name} and selected it for ${languageLabel(modelLanguage)}."
        } else {
            ModelPaths.signalReloadModels()
            documentSuccessStatus = "Imported ${file.name}. Its declared languages " +
                "(${details.languages.joinToString().ifBlank { "none" }}) do not include " +
                "${languageLabel(modelLanguage)}, so the current default was kept."
        }
        reload(modelsChanged = true)
        return true
    }

    private suspend fun exportModel(document: AutocorrectPluginDocument): Boolean {
        val selected = ModelPaths.getModels(context)
            .firstOrNull { it.path.name == selectedModelName }
            ?: throw DocumentFailure("No installed model is selected.")
        withContext(Dispatchers.IO) {
            ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.dup(document.fileDescriptor.fileDescriptor),
            ).use { output ->
                selected.path.inputStream().use { it.copyTo(output) }
            }
        }
        return true
    }

    private suspend fun importDictionary(document: AutocorrectPluginDocument): Boolean {
        val targetLocale = locale(dictionaryLanguage)
        val imported = withContext(Dispatchers.IO) {
            val localeName = targetLocale.toString().replace(UNSAFE_FILE_CHARS, "_")
            val destinationName = "dictionary_$localeName.dict"
            val directory = context.getExternalFilesDir(null)
                ?: throw DocumentFailure("Provider storage is unavailable.")
            val destination = File(directory, destinationName)
            val staging = File.createTempFile(".floris-dictionary-", ".tmp", directory)
            try {
                copyDocument(document, staging, MAX_DICTIONARY_BYTES, "The dictionary")
                val detected = runCatching {
                    staging.inputStream().buffered().use(::determineFileKind)
                }.getOrNull()?.takeIf { it.kind == FileKind.Dictionary }
                    ?: throw DocumentFailure(
                        "The file is not a supported FUTO binary dictionary.",
                    )
                detected.locale?.let { declaredLocale ->
                    val declaredLanguage = locale(declaredLocale).language
                    if (declaredLanguage.isNotBlank() && declaredLanguage != "und") {
                        if (declaredLanguage != targetLocale.language) {
                            throw DocumentFailure(
                                "This dictionary is for ${languageLabel(declaredLocale)}, not " +
                                    "${languageLabel(dictionaryLanguage)}.",
                            )
                        }
                    }
                }
                Os.rename(staging.absolutePath, destination.absolutePath)
                Triple(destinationName, detected.name, document.displayName)
            } finally {
                staging.delete()
            }
        }
        val localeKey = targetLocale.toString()
        context.setSettingAndAwaitCache(
            FileKind.Dictionary.preferenceKeyFor(localeKey),
            imported.first,
        )
        context.setSettingAndAwaitCache(
            FileKind.Dictionary.namePreferenceKeyFor(localeKey),
            imported.second ?: imported.third ?: imported.first,
        )
        reload(resourcesChanged = true)
        return true
    }

    private suspend fun setSelectedModelDefault(): Boolean {
        val model = ModelPaths.getModels(context)
            .firstOrNull { it.path.name == selectedModelName }
            ?: return false
        val language = locale(modelLanguage).language
        val supported = runCatching {
            modelDetails(model)
                ?.takeIf { it.isProviderSupported() }
                ?.languages
                ?.any { locale(it).language == language } == true
        }.getOrDefault(false)
        if (!supported) return false
        ModelPaths.updateModelOption(context, language, model.path)
        reload(modelsChanged = true)
        return true
    }

    private fun selectModelPage(page: Int): Boolean {
        val models = ModelPaths.getModels(context).sortedBy { it.name.lowercase(Locale.ROOT) }
        val targetPage = page.coerceIn(0, lastPage(models.size))
        val first = models.getOrNull(targetPage * PAGE_SIZE) ?: return false
        modelPage = targetPage
        selectedModelName = first.path.name
        return true
    }

    private suspend fun deleteSelectedModel(): Boolean {
        val file = ModelPaths.getModels(context)
            .firstOrNull { it.path.name == selectedModelName }
            ?.path
            ?.takeIf { it.nameWithoutExtension != BASE_MODEL_NAME }
            ?: return false
        val selectedBaseName = file.nameWithoutExtension
        if (!withContext(Dispatchers.IO) { file.delete() }) return false
        ModelPaths.ensureDefaultModelExists(context)
        val modelOptions = setting(MODEL_OPTION_KEY).mapNotNullTo(mutableSetOf()) { option ->
            val language = option.substringBefore(':')
            when {
                option.substringAfter(':') != selectedBaseName -> option
                language == Locale.ENGLISH.language -> "$language:$BASE_MODEL_NAME"
                else -> null
            }
        }
        context.setSettingAndAwaitCache(MODEL_OPTION_KEY, modelOptions)
        ModelPaths.signalReloadModels()
        selectedModelName = "$BASE_MODEL_NAME.gguf"
        reload(modelsChanged = true)
        return true
    }

    private suspend fun removeDictionary(): Boolean {
        val current = dictionaryResource(dictionaryLanguage) ?: return false
        val externalDir = context.getExternalFilesDir(null)?.canonicalFile ?: return false
        val file = File(externalDir, current.first).canonicalFile
        if (file.parentFile == externalDir) {
            withContext(Dispatchers.IO) { file.delete() }
        }
        val key = current.third
        context.setSettingAndAwaitCache(FileKind.Dictionary.preferenceKeyFor(key), "")
        context.setSettingAndAwaitCache(FileKind.Dictionary.namePreferenceKeyFor(key), "")
        reload(resourcesChanged = true)
        return true
    }

    private suspend fun savePersonalWord(): Boolean {
        val word = personalWord.trim().take(MAX_WORD_CHARS)
        if (
            word.isEmpty() ||
            personalLocaleLanguage() == Locale.CHINESE.language ||
            selectedPersonalWord?.isChinese() == true ||
            (personalLocaleLanguage() == Locale.JAPANESE.language &&
                !personalReading.isValidJapaneseReading())
        ) {
            return false
        }
        val dictionary = UserDictionaryIO(context)
        val targetLocale = personalLocale.takeUnless { it == ALL_LANGUAGES }?.let(::locale)
        val entry = if (targetLocale?.language == Locale.JAPANESE.language) {
            JapanesePersonalWord(
                furigana = personalReading.trim().take(MAX_WORD_CHARS),
                output = word,
                pos = personalPos,
            ).encode(targetLocale)
        } else {
            PersonalWord(
                word = word,
                frequency = 250,
                locale = targetLocale?.toString(),
                appId = 0,
                shortcut = personalShortcut.trim().take(MAX_WORD_CHARS).ifEmpty { null },
            )
        }
        val previous = selectedPersonalWord
        if (entry != previous) {
            val existingWords = dictionary.get()
            val alreadyPresent = entry in existingWords
            dictionary.put(listOf(entry))
            if (entry !in dictionary.get()) return false
            if (
                previous != null &&
                !removePersonalWordExact(previous) &&
                previous in dictionary.get()
            ) {
                if (!alreadyPresent) removePersonalWordExact(entry)
                return false
            }
        }
        populatePersonalEditor(entry)
        reload()
        return true
    }

    private suspend fun deletePersonalWord(): Boolean {
        val selected = selectedPersonalWord ?: return false
        if (selected.isChinese()) return false
        if (!removePersonalWordExact(selected) && selected in UserDictionaryIO(context).get()) {
            return false
        }
        clearPersonalEditor()
        reload()
        return true
    }

    private suspend fun addBlacklistWord(): Boolean {
        val word = blacklistWord.trim().take(MAX_WORD_CHARS)
        if (word.isEmpty()) return false
        engine.replaceBlacklist(setting(SUGGESTION_BLACKLIST) + word)
        selectedBlacklistWord = word
        blacklistWord = ""
        return true
    }

    private suspend fun removeBlacklistWord(): Boolean {
        val word = selectedBlacklistWord ?: return false
        engine.replaceBlacklist(setting(SUGGESTION_BLACKLIST) - word)
        selectedBlacklistWord = null
        return true
    }

    private suspend fun dictionaryResource(
        languageTag: String,
    ): Triple<String, String, String>? {
        val target = locale(languageTag)
        val candidates = listOf(
            target.toString(),
            target.language,
            "${target.language}_${target.country.ifEmpty { target.language }}",
            "${target.language.lowercase()}_${target.country.ifEmpty { target.language }.uppercase()}",
        ).distinct()
        val values = context.dataStore.data.first()
        val key = candidates.firstOrNull {
            !values[FileKind.Dictionary.preferenceKeyFor(it)].isNullOrBlank()
        } ?: return null
        val file = values[FileKind.Dictionary.preferenceKeyFor(key)].orEmpty()
        val name = values[FileKind.Dictionary.namePreferenceKeyFor(key)]
            ?.takeIf(String::isNotBlank)
            ?: file
        return Triple(file, name, key)
    }

    private suspend fun selectPersonalWord(value: String): Boolean {
        val words = UserDictionaryIO(context).get()
            .sortedWith(compareBy({ it.locale.orEmpty() }, { it.word.lowercase(Locale.ROOT) }))
        val selected = value.toIntOrNull()?.let(words::getOrNull) ?: return false
        populatePersonalEditor(selected)
        return true
    }

    private fun clearPersonalEditor() {
        selectedPersonalWord = null
        personalWord = ""
        personalShortcut = ""
        personalReading = ""
        personalPos = PosTypes.NO_POS
        personalLocale = defaultPersonalLocale()
    }

    private suspend fun reload(
        modelsChanged: Boolean = false,
        resourcesChanged: Boolean = false,
    ) {
        engine.reloadSettings(modelsChanged, resourcesChanged)
    }

    private fun updateLanguages(languageTags: List<String>) {
        languages = languageTags
            .map(::locale)
            .filter { it.language.isNotBlank() && it.language != "und" }
            .distinctBy(Locale::toLanguageTag)
            .map(Locale::toLanguageTag)
            .take(MAX_LANGUAGES)
            .ifEmpty { listOf(Locale.getDefault().toLanguageTag()) }
        if (modelLanguage !in languages) modelLanguage = languages.first()
        if (dictionaryLanguage !in languages) dictionaryLanguage = languages.first()
    }

    private fun updatePersonalLanguages(words: List<PersonalWord>) {
        val available = (languages + words.mapNotNull { it.locale?.toPersonalLanguageTag() })
            .distinct()
            .take(MAX_PERSONAL_LANGUAGES)
            .toMutableList()
        selectedPersonalWord
            ?.locale
            ?.toPersonalLanguageTag()
            ?.let { selected ->
                if (selected !in available) {
                    if (available.size == MAX_PERSONAL_LANGUAGES) {
                        available.removeAt(available.lastIndex)
                    }
                    available.add(selected)
                }
            }
        personalLanguages = available.ifEmpty { languages }
        if (
            (personalLocale != ALL_LANGUAGES && personalLocale !in personalLanguages) ||
            (selectedPersonalWord == null &&
                personalLocaleLanguage() == Locale.CHINESE.language)
        ) {
            personalLocale = defaultPersonalLocale()
        }
    }

    private fun populatePersonalEditor(entry: PersonalWord) {
        selectedPersonalWord = entry
        personalLocale = entry.locale?.toPersonalLanguageTag() ?: ALL_LANGUAGES
        if (personalLocale != ALL_LANGUAGES && personalLocale !in personalLanguages) {
            personalLanguages = (personalLanguages + personalLocale).distinct()
        }
        val japanese = entry.locale
            ?.let(::localeFromString)
            ?.language == Locale.JAPANESE.language
        val decoded = if (japanese) {
            runCatching { decodeJapanesePersonalWord(entry) }.getOrNull()
        } else {
            null
        }
        personalWord = decoded?.output ?: entry.word
        personalShortcut = if (japanese) "" else entry.shortcut.orEmpty()
        personalReading = decoded?.furigana
            ?: entry.shortcut.orEmpty().substringBefore('\t').takeIf { japanese }
            .orEmpty()
        personalPos = decoded?.pos ?: PosTypes.NO_POS
    }

    private fun setPersonalLocale(value: String): Boolean {
        if (value != ALL_LANGUAGES && value !in personalLanguages) return false
        val wasJapanese = personalLocaleLanguage() == Locale.JAPANESE.language
        personalLocale = value
        val isJapanese = personalLocaleLanguage() == Locale.JAPANESE.language
        if (isJapanese && !wasJapanese) {
            personalReading = personalShortcut
            personalPos = PosTypes.NO_POS
        } else if (!isJapanese && wasJapanese) {
            personalShortcut = personalReading
        }
        return true
    }

    private fun personalLocaleLanguage(): String? =
        personalLocale.takeUnless { it == ALL_LANGUAGES }?.let(::locale)?.language

    private fun PersonalWord.isChinese() =
        locale?.let(::localeFromString)?.language == Locale.CHINESE.language

    private fun PersonalWord.displayShortcut(): String? {
        val japanese = locale?.let(::localeFromString)?.language == Locale.JAPANESE.language
        return if (japanese) {
            runCatching { decodeJapanesePersonalWord(this)?.furigana }.getOrNull()
                ?: shortcut
        } else {
            shortcut
        }
    }

    private fun String.toPersonalLanguageTag(): String? = localeFromString(this)
        .takeIf { it.language.isNotBlank() && it.language != "und" }
        ?.toLanguageTag()

    private fun String.isValidJapaneseReading() = all { character ->
        Character.UnicodeBlock.of(character) == Character.UnicodeBlock.HIRAGANA ||
            character in JAPANESE_READING_PUNCTUATION
    }

    private fun removePersonalWordExact(word: PersonalWord): Boolean {
        val clauses = mutableListOf(
            "${UserDictionary.Words.WORD} = ?",
            "${UserDictionary.Words.FREQUENCY} = ?",
            "${UserDictionary.Words.APP_ID} = ?",
        )
        val arguments = mutableListOf(
            word.word,
            word.frequency.toString(),
            word.appId.toString(),
        )
        if (word.locale == null) {
            clauses += "${UserDictionary.Words.LOCALE} IS NULL"
        } else {
            clauses += "${UserDictionary.Words.LOCALE} = ?"
            arguments += word.locale
        }
        if (word.shortcut == null) {
            clauses += "${UserDictionary.Words.SHORTCUT} IS NULL"
        } else {
            clauses += "${UserDictionary.Words.SHORTCUT} = ?"
            arguments += word.shortcut
        }
        return context.contentResolver.delete(
            UserDictionary.Words.CONTENT_URI,
            clauses.joinToString(" AND "),
            arguments.toTypedArray(),
        ) > 0
    }

    private fun selectLanguage(value: String, select: (String) -> Unit): Boolean {
        if (value !in languages) return false
        select(value)
        return true
    }

    private fun languageOptions() = languages.map {
        AutocorrectPluginUiOption(it, languageLabel(it))
    }

    private fun personalLanguageOptions(includeChinese: Boolean) = personalLanguages
        .filter { includeChinese || locale(it).language != Locale.CHINESE.language }
        .map { AutocorrectPluginUiOption(it, languageLabel(it)) }

    private fun defaultPersonalLocale() = languages.firstOrNull {
        locale(it).language != Locale.CHINESE.language
    } ?: ALL_LANGUAGES

    private fun languageLabel(languageTag: String): String {
        if (languageTag == ALL_LANGUAGES) return "All languages"
        val locale = locale(languageTag)
        return locale.getDisplayName(locale).ifBlank { languageTag }
    }

    private fun locale(languageTag: String) = localeFromString(languageTag)

    private suspend fun <T> setting(setting: SettingsKey<T>): T =
        context.dataStore.data.first()[setting.key] ?: setting.default

    private suspend fun setBoolean(setting: SettingsKey<Boolean>, value: String): Boolean =
        value.toBooleanStrictOrNull()?.let {
            context.setSettingAndAwaitCache(setting, it)
            true
        } ?: false

    private fun SharedPreferences.setBoolean(key: String, value: String): Boolean =
        value.toBooleanStrictOrNull()?.let {
            edit { putBoolean(key, it) }
            true
        } ?: false

    private fun <T> List<T>.page(page: Int) =
        withIndex().drop(page * PAGE_SIZE).take(PAGE_SIZE)

    private fun lastPage(size: Int) = ((size - 1).coerceAtLeast(0)) / PAGE_SIZE

    private fun pageSummary(page: Int, size: Int): String {
        if (size == 0) return "No entries"
        val first = page * PAGE_SIZE + 1
        val last = minOf((page + 1) * PAGE_SIZE, size)
        return "$first–$last of $size"
    }

    private fun paymentReminderSummary(reminderTime: Long): String {
        if (reminderTime <= 0L) {
            return "No future reminder is set"
        }
        if (isPaymentReminderDue(reminderTime)) return "Support reminder is due"
        val days = ((reminderTime - System.currentTimeMillis() / 1000L) / SECONDS_PER_DAY)
            .coerceAtLeast(1L)
        return "Reminder due in about $days day(s)"
    }

    private fun isPaymentReminderDue(reminderTime: Long) =
        reminderTime > 0L && reminderTime <= System.currentTimeMillis() / 1000L

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.1f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun safeFileName(name: String?, fallback: String, extension: String): String {
        val sanitized = name
            ?.substringAfterLast('/')
            ?.replace(UNSAFE_FILE_CHARS, "_")
            ?.trim('.', '_')
            ?.take(MAX_FILE_NAME_CHARS)
            ?.takeIf(String::isNotBlank)
            ?: fallback
        return if (sanitized.endsWith(extension, ignoreCase = true)) {
            sanitized.dropLast(extension.length) + extension
        } else {
            "$sanitized$extension"
        }
    }

    private fun copyDocument(
        document: AutocorrectPluginDocument,
        destination: File,
        maximumBytes: Long,
        label: String,
    ) {
        val declaredSize = document.fileDescriptor.statSize
        if (declaredSize > maximumBytes) {
            throw DocumentFailure("$label exceeds the supported size limit.")
        }
        ParcelFileDescriptor.AutoCloseInputStream(
            ParcelFileDescriptor.dup(document.fileDescriptor.fileDescriptor),
        ).use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > maximumBytes) {
                        throw DocumentFailure("$label exceeds the supported size limit.")
                    }
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private fun modelDetails(model: ModelInfoLoader): ModelInfo? {
        val file = model.path
        val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        if (key != modelDetailsCacheKey) {
            modelDetailsCacheKey = key
            modelDetailsCache = runCatching { model.loadDetails() }.getOrNull()
        }
        return modelDetailsCache
    }

    private fun ModelInfo.isProviderSupported(): Boolean =
        !isUnsupported() && features.all {
            it in SUPPORTED_MODEL_FEATURES || it.startsWith("opt_") || it.startsWith("_")
        }

    private fun switch(
        id: String,
        title: String,
        value: Boolean,
        summary: String? = null,
        enabled: Boolean = true,
        hostSetting: AutocorrectPluginHostSetting = AutocorrectPluginHostSetting.NONE,
    ) = AutocorrectPluginUiItem(
        id = id,
        kind = AutocorrectPluginUiItemKind.SWITCH,
        title = title,
        summary = summary,
        value = value.toString(),
        icon = AutocorrectPluginUiIcon.SETTINGS,
        enabled = enabled,
        hostSetting = hostSetting,
    )

    private fun choice(
        id: String,
        title: String,
        value: String,
        options: List<AutocorrectPluginUiOption>,
        summary: String? = null,
        enabled: Boolean = true,
    ) = AutocorrectPluginUiItem(
        id = id,
        kind = AutocorrectPluginUiItemKind.CHOICE,
        title = title,
        summary = summary,
        value = value,
        options = options,
        icon = AutocorrectPluginUiIcon.SETTINGS,
        enabled = enabled,
    )

    private fun text(
        id: String,
        title: String,
        value: String,
        summary: String,
        enabled: Boolean = true,
    ) =
        AutocorrectPluginUiItem(
            id = id,
            kind = AutocorrectPluginUiItemKind.TEXT,
            title = title,
            summary = summary,
            value = value,
            icon = AutocorrectPluginUiIcon.TUNE,
            enabled = enabled,
        )

    private fun navigation(
        id: String,
        title: String,
        target: String,
        icon: AutocorrectPluginUiIcon,
        summary: String? = null,
    ) = AutocorrectPluginUiItem(
        id = id,
        kind = AutocorrectPluginUiItemKind.NAVIGATION,
        title = title,
        summary = summary,
        target = target,
        icon = icon,
    )

    private fun action(
        id: String,
        title: String,
        summary: String? = null,
        confirmation: String? = null,
        enabled: Boolean = true,
        icon: AutocorrectPluginUiIcon = AutocorrectPluginUiIcon.DELETE,
    ) = AutocorrectPluginUiItem(
        id = id,
        kind = AutocorrectPluginUiItemKind.ACTION,
        title = title,
        summary = summary,
        confirmation = confirmation,
        icon = icon,
        enabled = enabled,
    )

    private fun link(
        id: String,
        title: String,
        summary: String,
        target: String,
    ) = AutocorrectPluginUiItem(
        id = id,
        kind = AutocorrectPluginUiItemKind.EXTERNAL_LINK,
        title = title,
        summary = summary,
        target = target,
        icon = AutocorrectPluginUiIcon.INFO,
    )

    private fun pagingAction(id: String, title: String, enabled: Boolean) =
        action(id, title, enabled = enabled, icon = AutocorrectPluginUiIcon.REFRESH)

    private fun documentStatusItem() = documentStatus?.let {
        info(
            id = "documentStatus",
            title = if ("failed" in it.lowercase(Locale.ROOT)) {
                "Last file operation failed"
            } else {
                "Last file operation"
            },
            summary = it,
            icon = AutocorrectPluginUiIcon.INFO,
        )
    }

    private fun info(
        id: String,
        title: String,
        summary: String,
        icon: AutocorrectPluginUiIcon,
    ) = AutocorrectPluginUiItem(
        id = id,
        kind = AutocorrectPluginUiItemKind.INFO,
        title = title,
        summary = summary,
        icon = icon,
    )

    private fun documentImport(
        id: String,
        title: String,
        summary: String,
        icon: AutocorrectPluginUiIcon,
    ) = AutocorrectPluginUiItem(
        id = id,
        kind = AutocorrectPluginUiItemKind.DOCUMENT_IMPORT,
        title = title,
        summary = summary,
        icon = icon,
        documentMimeTypes = listOf(BINARY_MIME),
    )

    private fun documentExport(
        id: String,
        title: String,
        name: String,
        enabled: Boolean,
        confirmation: String?,
    ) = AutocorrectPluginUiItem(
        id = id,
        kind = AutocorrectPluginUiItemKind.DOCUMENT_EXPORT,
        title = title,
        icon = AutocorrectPluginUiIcon.UPLOAD,
        enabled = enabled,
        confirmation = confirmation,
        documentMimeTypes = listOf(BINARY_MIME),
        documentSuggestedName = name,
    )

    private companion object {
        const val PAGE_OVERVIEW = "overview"
        const val PAGE_ADVANCED = "advanced"
        const val PAGE_RESOURCES = "resources"
        const val PAGE_MODELS = "models"
        const val PAGE_DICTIONARIES = "dictionaries"
        const val PAGE_PERSONAL = "personal"
        const val PAGE_BLACKLIST = "blacklist"
        const val PAGE_FUTO_SUPPORT = "futoSupport"
        const val PAGE_QUICK = "quick"
        const val PAGE_QUICK_ADVANCED = "quickAdvanced"
        const val ALL_LANGUAGES = "*"
        const val PAGE_SIZE = 20
        const val MAX_LANGUAGES = 16
        const val MAX_PERSONAL_LANGUAGES = 48
        const val MAX_WORD_CHARS = 64
        const val MAX_FILE_NAME_CHARS = 80
        const val MAX_STATUS_CHARS = 220
        const val MAX_MODEL_BYTES = 2L * 1024L * 1024L * 1024L
        const val MAX_DICTIONARY_BYTES = 512L * 1024L * 1024L
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val BINARY_MIME = "application/octet-stream"
        const val FUTO_KEYBOARD_URL = "https://keyboard.futo.tech/"
        const val SECONDS_PER_DAY = 24L * 60L * 60L
        const val MAX_PAYMENT_REMINDER_DAYS = 36500L
        val PAYMENT_REMINDER_OPTIONS = setOf("7", "30", "182", "36500")
        val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")
        val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
        val JAPANESE_READING_PUNCTUATION = "？！…。〜ー、".toSet()
        val SUPPORTED_MODEL_FEATURES = setOf(
            "base_v1",
            "inverted_space",
            "xbu_char_autocorrect_v1",
            "lora_finetunable_v1",
            "xc0_swipe_typing_v1",
            "char_embed_mixing_v1",
            "experiment_linear_208_209_210",
        )
        val LIVE_SETTING_IDS = setOf(
            "autoCorrection",
            "transformer",
            "personalized",
            "smartKeyHit",
            "showSuggestions",
            "nextWord",
            "blockOffensive",
            "blockSlurs",
            "emojiSuggestions",
            "allowNonQwerty",
            "swipeEngine",
            "autocorrectThreshold",
            "transformerWeight",
        )
    }
}

private val FUTO_ALREADY_PAID = SettingsKey(
    booleanPreferencesKey("already_paid"),
    false,
)
private val FUTO_PAYMENT_REMINDER_TIME = SettingsKey(
    longPreferencesKey("notice_reminder_time"),
    0L,
)
private val FUTO_PAYMENT_LICENSE = SettingsKey(
    stringPreferencesKey("license_key"),
    "",
)

private class DocumentFailure(message: String) : Exception(message)
