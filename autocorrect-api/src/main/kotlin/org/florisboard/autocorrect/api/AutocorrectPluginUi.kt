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
    ACTIVITY,
    INFO,
    PROGRESS,
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

data class AutocorrectPluginUiOption(
    val value: String,
    val label: String,
)

/**
 * One host-rendered provider control. [value] is encoded as text so future engines can introduce
 * settings without changing the wire format. Hosts interpret booleans and numbers according to
 * [kind].
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
)

data class AutocorrectPluginUiPage(
    val id: String,
    val title: String,
    val summary: String? = null,
    val surface: AutocorrectPluginUiSurface = AutocorrectPluginUiSurface.APP,
    val items: List<AutocorrectPluginUiItem>,
)

/**
 * A bounded declarative UI owned by the provider and rendered by the keyboard host.
 *
 * Complex provider-owned workflows can use an [AutocorrectPluginUiItemKind.ACTIVITY] item. Its
 * target must name an exported activity in the same package as the provider service.
 */
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

fun pluginUiRequestBundle(requestId: Long) = Bundle().apply {
    putLong(UiKeys.REQUEST_ID, requestId)
}

fun pluginUiMutationBundle(requestId: Long, itemId: String, value: String? = null) = Bundle().apply {
    putLong(UiKeys.REQUEST_ID, requestId)
    putString(UiKeys.ITEM_ID, itemId.take(UiLimits.ID_CHARS))
    putString(UiKeys.VALUE, value?.take(UiLimits.VALUE_CHARS))
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

internal fun Bundle.pluginUiItemId() =
    getString(UiKeys.ITEM_ID).orEmpty().take(UiLimits.ID_CHARS)

internal fun Bundle.pluginUiValue() =
    getString(UiKeys.VALUE)?.take(UiLimits.VALUE_CHARS)

private fun AutocorrectPluginUi.toBundle() = Bundle().apply {
    val budget = UiBudget()
    putString(UiKeys.APP_ROOT, appRootPageId?.take(UiLimits.ID_CHARS))
    putString(UiKeys.KEYBOARD_ROOT, keyboardRootPageId?.take(UiLimits.ID_CHARS))
    putParcelableArrayList(
        UiKeys.PAGES,
        ArrayList(pages.take(UiLimits.PAGES).map { it.toBundle(budget) }),
    )
}

private fun AutocorrectPluginUiPage.toBundle(budget: UiBudget) = Bundle().apply {
    val pageItems = items.take(minOf(UiLimits.ITEMS_PER_PAGE, budget.items))
    budget.items -= pageItems.size
    putString(UiKeys.ID, id.take(UiLimits.ID_CHARS))
    putString(UiKeys.TITLE, title.take(UiLimits.TEXT_CHARS))
    putString(UiKeys.SUMMARY, summary?.take(UiLimits.TEXT_CHARS))
    putString(UiKeys.SURFACE, surface.name)
    putParcelableArrayList(
        UiKeys.ITEMS,
        ArrayList(pageItems.map { it.toBundle(budget) }),
    )
}

private fun AutocorrectPluginUiItem.toBundle(budget: UiBudget) = Bundle().apply {
    val itemOptions = options.take(minOf(UiLimits.OPTIONS_PER_ITEM, budget.options))
    budget.options -= itemOptions.size
    putString(UiKeys.ID, id.take(UiLimits.ID_CHARS))
    putString(UiKeys.KIND, kind.name)
    putString(UiKeys.TITLE, title.take(UiLimits.TEXT_CHARS))
    putString(UiKeys.SUMMARY, summary?.take(UiLimits.TEXT_CHARS))
    putString(UiKeys.VALUE, value?.take(UiLimits.VALUE_CHARS))
    putParcelableArrayList(
        UiKeys.OPTIONS,
        ArrayList(itemOptions.map(AutocorrectPluginUiOption::toBundle)),
    )
    putDouble(UiKeys.MINIMUM, minimum)
    putDouble(UiKeys.MAXIMUM, maximum)
    putDouble(UiKeys.STEP, step)
    putString(UiKeys.TARGET, target?.take(UiLimits.TARGET_CHARS))
    putString(UiKeys.ICON, icon.name)
    putBoolean(UiKeys.ENABLED, enabled)
    putString(UiKeys.CONFIRMATION, confirmation?.take(UiLimits.TEXT_CHARS))
}

private fun AutocorrectPluginUiOption.toBundle() = Bundle().apply {
    putString(UiKeys.VALUE, value.take(UiLimits.VALUE_CHARS))
    putString(UiKeys.TITLE, label.take(UiLimits.TEXT_CHARS))
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
        appRootPageId = getString(UiKeys.APP_ROOT)?.take(UiLimits.ID_CHARS),
        keyboardRootPageId = getString(UiKeys.KEYBOARD_ROOT)?.take(UiLimits.ID_CHARS),
        pages = pages,
    )
}

@Suppress("DEPRECATION")
private fun Bundle.toPluginUiPage(budget: UiBudget): AutocorrectPluginUiPage? {
    val id = getString(UiKeys.ID)?.take(UiLimits.ID_CHARS)?.takeIf(String::isNotBlank)
        ?: return null
    val title = getString(UiKeys.TITLE)?.take(UiLimits.TEXT_CHARS)?.takeIf(String::isNotBlank)
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
        summary = getString(UiKeys.SUMMARY)?.take(UiLimits.TEXT_CHARS),
        surface = enumValueOrDefault(
            getString(UiKeys.SURFACE),
            AutocorrectPluginUiSurface.APP,
        ),
        items = pageItems,
    )
}

@Suppress("DEPRECATION")
private fun Bundle.toPluginUiItem(budget: UiBudget): AutocorrectPluginUiItem? {
    val id = getString(UiKeys.ID)?.take(UiLimits.ID_CHARS)?.takeIf(String::isNotBlank)
        ?: return null
    val title = getString(UiKeys.TITLE)?.take(UiLimits.TEXT_CHARS)?.takeIf(String::isNotBlank)
        ?: return null
    val minimum = getDouble(UiKeys.MINIMUM).takeIf(Double::isFinite) ?: 0.0
    val maximum = getDouble(UiKeys.MAXIMUM, 1.0).takeIf(Double::isFinite)
        ?.coerceAtLeast(minimum) ?: 1.0.coerceAtLeast(minimum)
    val optionBundles = getParcelableArrayList<Bundle>(UiKeys.OPTIONS)
        .orEmpty()
        .take(minOf(UiLimits.OPTIONS_PER_ITEM, budget.options))
    budget.options -= optionBundles.size
    val itemOptions = optionBundles
        .map {
            AutocorrectPluginUiOption(
                value = it.getString(UiKeys.VALUE).orEmpty().take(UiLimits.VALUE_CHARS),
                label = it.getString(UiKeys.TITLE).orEmpty().take(UiLimits.TEXT_CHARS),
            )
        }
        .distinctBy(AutocorrectPluginUiOption::value)
    return AutocorrectPluginUiItem(
        id = id,
        kind = enumValueOrDefault(
            getString(UiKeys.KIND),
            AutocorrectPluginUiItemKind.INFO,
        ),
        title = title,
        summary = getString(UiKeys.SUMMARY)?.take(UiLimits.TEXT_CHARS),
        value = getString(UiKeys.VALUE)?.take(UiLimits.VALUE_CHARS),
        options = itemOptions,
        minimum = minimum,
        maximum = maximum,
        step = getDouble(UiKeys.STEP).takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
        target = getString(UiKeys.TARGET)?.take(UiLimits.TARGET_CHARS),
        icon = enumValueOrDefault(getString(UiKeys.ICON), AutocorrectPluginUiIcon.NONE),
        enabled = getBoolean(UiKeys.ENABLED, true),
        confirmation = getString(UiKeys.CONFIRMATION)?.take(UiLimits.TEXT_CHARS),
    )
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T {
    return enumValues<T>().firstOrNull { it.name == value } ?: default
}

private object UiLimits {
    const val PAGES = 16
    const val ITEMS = 96
    const val ITEMS_PER_PAGE = 32
    const val OPTIONS = 128
    const val OPTIONS_PER_ITEM = 32
    const val ID_CHARS = 96
    const val TEXT_CHARS = 256
    const val VALUE_CHARS = 512
    const val TARGET_CHARS = 256
}

private data class UiBudget(
    var items: Int = UiLimits.ITEMS,
    var options: Int = UiLimits.OPTIONS,
)

private object UiKeys {
    const val REQUEST_ID = "requestId"
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
}
