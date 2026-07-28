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
import android.os.ParcelFileDescriptor
import java.io.Closeable

enum class AutocorrectPluginUiSurface {
    APP,
    KEYBOARD,
    BOTH;

    fun supports(surface: AutocorrectPluginUiSurface) = this == BOTH || this == surface
}

enum class AutocorrectPluginUiItemKind {
    NAVIGATION,
    SWITCH,
    SLIDER,
    CHOICE,
    TEXT,
    ACTION,
    DOCUMENT_IMPORT,
    DOCUMENT_EXPORT,
    INFO,
    PROGRESS,
    EXTERNAL_LINK,
}

enum class AutocorrectPluginUiIcon {
    NONE,
    SETTINGS,
    MODEL,
    DICTIONARY,
    TUNE,
    DOWNLOAD,
    UPLOAD,
    DELETE,
    REFRESH,
    INFO,
    ADD,
}

/**
 * A setting whose behavior is owned by the keyboard host. Providers supply its label and
 * placement, while the host supplies the authoritative value and applies the behavior.
 */
enum class AutocorrectPluginHostSetting {
    NONE,
    GLIDE_ENABLED,
    GLIDE_SENSITIVE,
}

data class AutocorrectPluginUiOption(
    val value: String,
    val label: String,
)

/**
 * One host-rendered provider control. [value] is encoded as text so future engines can introduce
 * settings without changing the wire format. Hosts interpret booleans and numbers according to
 * [kind]. [target] is a page ID for [AutocorrectPluginUiItemKind.NAVIGATION] and an absolute HTTPS
 * URL for [AutocorrectPluginUiItemKind.EXTERNAL_LINK].
 */
data class AutocorrectPluginUiItem(
    val id: String,
    val kind: AutocorrectPluginUiItemKind,
    val title: String,
    val summary: String? = null,
    val value: String? = null,
    val options: List<AutocorrectPluginUiOption> = emptyList(),
    val minimum: Double = 0.0,
    val maximum: Double = 1.0,
    val step: Double = 0.0,
    val target: String? = null,
    val icon: AutocorrectPluginUiIcon = AutocorrectPluginUiIcon.NONE,
    val enabled: Boolean = true,
    val confirmation: String? = null,
    val documentMimeTypes: List<String> = emptyList(),
    val documentSuggestedName: String? = null,
    val hostSetting: AutocorrectPluginHostSetting = AutocorrectPluginHostSetting.NONE,
)

data class AutocorrectPluginUiPage(
    val id: String,
    val title: String,
    val summary: String? = null,
    val surface: AutocorrectPluginUiSurface = AutocorrectPluginUiSurface.APP,
    val items: List<AutocorrectPluginUiItem>,
)

/** A bounded declarative UI owned by the provider and rendered by the keyboard host. */
data class AutocorrectPluginUi(
    val appRootPageId: String?,
    val keyboardRootPageId: String?,
    val pages: List<AutocorrectPluginUiPage>,
) {
    fun page(id: String?, surface: AutocorrectPluginUiSurface) =
        pages.firstOrNull { it.id == id && it.surface.supports(surface) }
}

data class AutocorrectPluginUiResult(
    val requestId: Long,
    val successful: Boolean,
    val ui: AutocorrectPluginUi?,
)

data class AutocorrectPluginDocument(
    val itemId: String,
    val displayName: String?,
    val mimeType: String?,
    val write: Boolean,
    val fileDescriptor: ParcelFileDescriptor,
) : Closeable {
    override fun close() = fileDescriptor.close()
}

fun pluginUiRequestBundle(requestId: Long, languageTags: List<String> = emptyList()) = Bundle().apply {
    putLong(UiKeys.REQUEST_ID, requestId)
    putStringArrayList(
        UiKeys.LANGUAGE_TAGS,
        ArrayList(
            languageTags
                .map { it.takeWireChars(UiLimits.LANGUAGE_TAG_CHARS) }
                .distinct()
                .take(UiLimits.LANGUAGES),
        ),
    )
}

fun pluginUiMutationBundle(requestId: Long, itemId: String, value: String? = null) = Bundle().apply {
    putLong(UiKeys.REQUEST_ID, requestId)
    putString(UiKeys.ITEM_ID, itemId.takeWireChars(UiLimits.ID_CHARS))
    putString(UiKeys.VALUE, value?.takeWireChars(UiLimits.VALUE_CHARS))
}

fun pluginUiDocumentBundle(
    requestId: Long,
    itemId: String,
    displayName: String?,
    mimeType: String?,
    write: Boolean,
    fileDescriptor: ParcelFileDescriptor,
) = Bundle().apply {
    putLong(UiKeys.REQUEST_ID, requestId)
    putString(UiKeys.ITEM_ID, itemId.takeWireChars(UiLimits.ID_CHARS))
    putString(UiKeys.DISPLAY_NAME, displayName?.takeWireChars(UiLimits.FILE_NAME_CHARS))
    putString(UiKeys.MIME_TYPE, mimeType?.takeWireChars(UiLimits.MIME_TYPE_CHARS))
    putBoolean(UiKeys.WRITE, write)
    putParcelable(UiKeys.FILE_DESCRIPTOR, fileDescriptor)
}

internal fun pluginUiResultBundle(
    requestId: Long,
    successful: Boolean,
    ui: AutocorrectPluginUi?,
) = Bundle().apply {
    putLong(UiKeys.REQUEST_ID, requestId)
    putBoolean(UiKeys.SUCCESSFUL, successful)
    putBundle(UiKeys.UI, ui?.toBundle())
}

fun pluginUiResultFromBundle(bundle: Bundle) = AutocorrectPluginUiResult(
    requestId = bundle.getLong(UiKeys.REQUEST_ID),
    successful = bundle.getBoolean(UiKeys.SUCCESSFUL),
    ui = bundle.getBundle(UiKeys.UI)?.toPluginUi(),
)

internal fun Bundle.pluginUiRequestId() = getLong(UiKeys.REQUEST_ID)

internal fun Bundle.pluginUiLanguageTags() =
    getStringArrayList(UiKeys.LANGUAGE_TAGS)
        .orEmpty()
        .map { it.takeWireChars(UiLimits.LANGUAGE_TAG_CHARS) }
        .distinct()
        .take(UiLimits.LANGUAGES)

internal fun Bundle.pluginUiItemId() =
    getString(UiKeys.ITEM_ID).orEmpty().takeWireChars(UiLimits.ID_CHARS)

internal fun Bundle.pluginUiValue() =
    getString(UiKeys.VALUE)?.takeWireChars(UiLimits.VALUE_CHARS)

@Suppress("DEPRECATION")
internal fun Bundle.pluginUiDocument(): AutocorrectPluginDocument? {
    val fileDescriptor = getParcelable<ParcelFileDescriptor>(UiKeys.FILE_DESCRIPTOR) ?: return null
    val itemId = pluginUiItemId().takeIf(String::isNotBlank) ?: run {
        runCatching(fileDescriptor::close)
        return null
    }
    return AutocorrectPluginDocument(
        itemId = itemId,
        displayName = getString(UiKeys.DISPLAY_NAME)?.takeWireChars(UiLimits.FILE_NAME_CHARS),
        mimeType = getString(UiKeys.MIME_TYPE)?.takeWireChars(UiLimits.MIME_TYPE_CHARS),
        write = getBoolean(UiKeys.WRITE),
        fileDescriptor = fileDescriptor,
    )
}

private fun AutocorrectPluginUi.toBundle() = Bundle().apply {
    val budget = UiBudget()
    putString(UiKeys.APP_ROOT, appRootPageId?.takeWireChars(UiLimits.ID_CHARS))
    putString(UiKeys.KEYBOARD_ROOT, keyboardRootPageId?.takeWireChars(UiLimits.ID_CHARS))
    putParcelableArrayList(
        UiKeys.PAGES,
        ArrayList(pages.take(UiLimits.PAGES).map { it.toBundle(budget) }),
    )
}

private fun AutocorrectPluginUiPage.toBundle(budget: UiBudget) = Bundle().apply {
    val pageItems = items.take(minOf(UiLimits.ITEMS_PER_PAGE, budget.items))
    budget.items -= pageItems.size
    putString(UiKeys.ID, id.takeWireChars(UiLimits.ID_CHARS))
    putString(UiKeys.TITLE, title.takeWireChars(UiLimits.TEXT_CHARS))
    putString(UiKeys.SUMMARY, summary?.takeWireChars(UiLimits.TEXT_CHARS))
    putString(UiKeys.SURFACE, surface.name)
    putParcelableArrayList(
        UiKeys.ITEMS,
        ArrayList(pageItems.map { it.toBundle(budget) }),
    )
}

private fun AutocorrectPluginUiItem.toBundle(budget: UiBudget) = Bundle().apply {
    val itemOptions = options.take(minOf(UiLimits.OPTIONS_PER_ITEM, budget.options))
    budget.options -= itemOptions.size
    putString(UiKeys.ID, id.takeWireChars(UiLimits.ID_CHARS))
    putString(UiKeys.KIND, kind.name)
    putString(UiKeys.TITLE, title.takeWireChars(UiLimits.TEXT_CHARS))
    putString(UiKeys.SUMMARY, summary?.takeWireChars(UiLimits.TEXT_CHARS))
    putString(UiKeys.VALUE, value?.takeWireChars(UiLimits.VALUE_CHARS))
    putParcelableArrayList(
        UiKeys.OPTIONS,
        ArrayList(itemOptions.map(AutocorrectPluginUiOption::toBundle)),
    )
    putDouble(UiKeys.MINIMUM, minimum)
    putDouble(UiKeys.MAXIMUM, maximum)
    putDouble(UiKeys.STEP, step)
    putString(UiKeys.TARGET, target.boundedTarget(kind))
    putString(UiKeys.ICON, icon.name)
    putBoolean(UiKeys.ENABLED, enabled)
    putString(UiKeys.CONFIRMATION, confirmation?.takeWireChars(UiLimits.TEXT_CHARS))
    putStringArrayList(
        UiKeys.DOCUMENT_MIME_TYPES,
        ArrayList(
            documentMimeTypes
                .map { it.takeWireChars(UiLimits.MIME_TYPE_CHARS) }
                .distinct()
                .take(UiLimits.MIME_TYPES),
        ),
    )
    putString(
        UiKeys.DOCUMENT_SUGGESTED_NAME,
        documentSuggestedName?.takeWireChars(UiLimits.FILE_NAME_CHARS),
    )
    putString(UiKeys.HOST_SETTING, hostSetting.name)
}

private fun AutocorrectPluginUiOption.toBundle() = Bundle().apply {
    putString(UiKeys.VALUE, value.takeWireChars(UiLimits.VALUE_CHARS))
    putString(UiKeys.TITLE, label.takeWireChars(UiLimits.TEXT_CHARS))
}

@Suppress("DEPRECATION")
private fun Bundle.toPluginUi(): AutocorrectPluginUi {
    val budget = UiBudget()
    val pages = getParcelableArrayList<Bundle>(UiKeys.PAGES)
        .orEmpty()
        .take(UiLimits.PAGES)
        .mapNotNull { it.toPluginUiPage(budget) }
        .distinctBy(AutocorrectPluginUiPage::id)
    return AutocorrectPluginUi(
        appRootPageId = getString(UiKeys.APP_ROOT)?.takeWireChars(UiLimits.ID_CHARS),
        keyboardRootPageId = getString(UiKeys.KEYBOARD_ROOT)
            ?.takeWireChars(UiLimits.ID_CHARS),
        pages = pages,
    )
}

@Suppress("DEPRECATION")
private fun Bundle.toPluginUiPage(budget: UiBudget): AutocorrectPluginUiPage? {
    val id = getString(UiKeys.ID)
        ?.takeWireChars(UiLimits.ID_CHARS)
        ?.takeIf(String::isNotBlank)
        ?: return null
    val title = getString(UiKeys.TITLE)
        ?.takeWireChars(UiLimits.TEXT_CHARS)
        ?.takeIf(String::isNotBlank)
        ?: return null
    val itemBundles = getParcelableArrayList<Bundle>(UiKeys.ITEMS)
        .orEmpty()
        .take(minOf(UiLimits.ITEMS_PER_PAGE, budget.items))
    budget.items -= itemBundles.size
    val pageItems = itemBundles
        .mapNotNull { it.toPluginUiItem(budget) }
        .distinctBy(AutocorrectPluginUiItem::id)
    return AutocorrectPluginUiPage(
        id = id,
        title = title,
        summary = getString(UiKeys.SUMMARY)?.takeWireChars(UiLimits.TEXT_CHARS),
        surface = enumValueOrDefault(
            getString(UiKeys.SURFACE),
            AutocorrectPluginUiSurface.APP,
        ),
        items = pageItems,
    )
}

@Suppress("DEPRECATION")
private fun Bundle.toPluginUiItem(budget: UiBudget): AutocorrectPluginUiItem? {
    val id = getString(UiKeys.ID)
        ?.takeWireChars(UiLimits.ID_CHARS)
        ?.takeIf(String::isNotBlank)
        ?: return null
    val title = getString(UiKeys.TITLE)
        ?.takeWireChars(UiLimits.TEXT_CHARS)
        ?.takeIf(String::isNotBlank)
        ?: return null
    val rawMinimum = getDouble(UiKeys.MINIMUM)
    val rawMaximum = getDouble(UiKeys.MAXIMUM, 1.0)
    val validRange = rawMinimum.isFloatRepresentable() &&
        rawMaximum.isFloatRepresentable() &&
        rawMaximum >= rawMinimum &&
        (rawMaximum.toFloat() - rawMinimum.toFloat()).isFinite()
    val minimum = if (validRange) rawMinimum else 0.0
    val maximum = if (validRange) rawMaximum else 1.0
    val floatSpan = maximum.toFloat() - minimum.toFloat()
    val optionBundles = getParcelableArrayList<Bundle>(UiKeys.OPTIONS)
        .orEmpty()
        .take(minOf(UiLimits.OPTIONS_PER_ITEM, budget.options))
    budget.options -= optionBundles.size
    val itemOptions = optionBundles
        .map {
            AutocorrectPluginUiOption(
                value = it.getString(UiKeys.VALUE).orEmpty()
                    .takeWireChars(UiLimits.VALUE_CHARS),
                label = it.getString(UiKeys.TITLE).orEmpty()
                    .takeWireChars(UiLimits.TEXT_CHARS),
            )
        }
        .distinctBy(AutocorrectPluginUiOption::value)
    val kind = enumValueOrDefault(
        getString(UiKeys.KIND),
        AutocorrectPluginUiItemKind.INFO,
    )
    return AutocorrectPluginUiItem(
        id = id,
        kind = kind,
        title = title,
        summary = getString(UiKeys.SUMMARY)?.takeWireChars(UiLimits.TEXT_CHARS),
        value = getString(UiKeys.VALUE)?.takeWireChars(UiLimits.VALUE_CHARS),
        options = itemOptions,
        minimum = minimum,
        maximum = maximum,
        step = getDouble(UiKeys.STEP).takeIf {
            it > 0.0 &&
                it.isFloatRepresentable() &&
                (floatSpan / it.toFloat()).isFinite()
        } ?: 0.0,
        target = getString(UiKeys.TARGET).boundedTarget(kind),
        icon = enumValueOrDefault(getString(UiKeys.ICON), AutocorrectPluginUiIcon.NONE),
        enabled = getBoolean(UiKeys.ENABLED, true),
        confirmation = getString(UiKeys.CONFIRMATION)?.takeWireChars(UiLimits.TEXT_CHARS),
        documentMimeTypes = getStringArrayList(UiKeys.DOCUMENT_MIME_TYPES)
            .orEmpty()
            .map { it.takeWireChars(UiLimits.MIME_TYPE_CHARS) }
            .distinct()
            .take(UiLimits.MIME_TYPES),
        documentSuggestedName = getString(UiKeys.DOCUMENT_SUGGESTED_NAME)
            ?.takeWireChars(UiLimits.FILE_NAME_CHARS),
        hostSetting = if (kind == AutocorrectPluginUiItemKind.SWITCH) {
            enumValueOrDefault(
                getString(UiKeys.HOST_SETTING),
                AutocorrectPluginHostSetting.NONE,
            )
        } else {
            AutocorrectPluginHostSetting.NONE
        },
    )
}

private fun Double.isFloatRepresentable() = isFinite() && toFloat().isFinite()

private fun String?.boundedTarget(kind: AutocorrectPluginUiItemKind) =
    takeIf {
        kind != AutocorrectPluginUiItemKind.EXTERNAL_LINK ||
            it.orEmpty().length <= UiLimits.TARGET_CHARS
    }
        ?.takeWireChars(UiLimits.TARGET_CHARS)

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T {
    return enumValues<T>().firstOrNull { it.name == value } ?: default
}

private object UiLimits {
    const val PAGES = 16
    const val ITEMS = 96
    const val ITEMS_PER_PAGE = 32
    const val OPTIONS = 256
    const val OPTIONS_PER_ITEM = 64
    const val ID_CHARS = 96
    const val TEXT_CHARS = 256
    const val VALUE_CHARS = 512
    const val TARGET_CHARS = 256
    const val LANGUAGES = 32
    const val LANGUAGE_TAG_CHARS = 64
    const val MIME_TYPES = 8
    const val MIME_TYPE_CHARS = 128
    const val FILE_NAME_CHARS = 192
}

private data class UiBudget(
    var items: Int = UiLimits.ITEMS,
    var options: Int = UiLimits.OPTIONS,
)

private object UiKeys {
    const val REQUEST_ID = "requestId"
    const val LANGUAGE_TAGS = "languageTags"
    const val SUCCESSFUL = "successful"
    const val UI = "ui"
    const val APP_ROOT = "appRoot"
    const val KEYBOARD_ROOT = "keyboardRoot"
    const val PAGES = "pages"
    const val ID = "id"
    const val KIND = "kind"
    const val TITLE = "title"
    const val SUMMARY = "summary"
    const val SURFACE = "surface"
    const val ITEMS = "items"
    const val ITEM_ID = "itemId"
    const val VALUE = "value"
    const val OPTIONS = "options"
    const val MINIMUM = "minimum"
    const val MAXIMUM = "maximum"
    const val STEP = "step"
    const val TARGET = "target"
    const val ICON = "icon"
    const val ENABLED = "enabled"
    const val CONFIRMATION = "confirmation"
    const val DOCUMENT_MIME_TYPES = "documentMimeTypes"
    const val DOCUMENT_SUGGESTED_NAME = "documentSuggestedName"
    const val HOST_SETTING = "hostSetting"
    const val DISPLAY_NAME = "displayName"
    const val MIME_TYPE = "mimeType"
    const val WRITE = "write"
    const val FILE_DESCRIPTOR = "fileDescriptor"
}
