/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect

import android.content.Context
import android.view.inputmethod.EditorInfo
import org.florisboard.autocorrect.api.AutocorrectKeyGeometry
import org.florisboard.autocorrect.api.AutocorrectInputTrace
import org.futo.inputmethod.keyboard.Key
import org.futo.inputmethod.keyboard.Keyboard
import org.futo.inputmethod.keyboard.KeyboardId
import org.futo.inputmethod.keyboard.internal.KeyboardLayoutElement
import org.futo.inputmethod.keyboard.internal.KeyboardParams
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.settings.LongPressKeySettings
import org.futo.inputmethod.v2keyboard.KeyVisualStyle
import org.futo.inputmethod.v2keyboard.getKeyboardMode
import java.util.Locale
import kotlin.math.roundToInt

internal object FlorisVirtualKeyboard {
    private const val WIDTH = 1_000
    private const val HEIGHT = 400

    fun create(
        context: Context,
        locale: Locale,
        inputType: Int,
        trace: AutocorrectInputTrace,
    ): Keyboard? {
        if (trace.keys.isEmpty()) return null
        val editorInfo = EditorInfo().apply {
            this.inputType = inputType
            packageName = context.packageName
        }
        val element = KeyboardLayoutElement.fromElementId(KeyboardId.ELEMENT_ALPHABET)
        val params = KeyboardParams().apply {
            mId = KeyboardId(
                "floris",
                locale,
                WIDTH,
                HEIGHT,
                getKeyboardMode(editorInfo),
                KeyboardId.ELEMENT_ALPHABET,
                editorInfo,
                false,
                false,
                -1,
                "",
                false,
                false,
                false,
                0,
                false,
                false,
                false,
                LongPressKeySettings.forTest(),
                element,
            )
            mOccupiedWidth = WIDTH
            mOccupiedHeight = HEIGHT
            mBaseWidth = WIDTH
            mBaseHeight = HEIGHT
            mDefaultKeyWidth = WIDTH / 10
            mDefaultRowHeight = HEIGHT / 4
            GRID_WIDTH = context.resources.getInteger(R.integer.config_keyboard_grid_width)
            GRID_HEIGHT = context.resources.getInteger(R.integer.config_keyboard_grid_height)
            mProximityCharsCorrectionEnabled = true
        }
        trace.keys.forEachIndexed { index, geometry ->
            val code = geometry.text.codePointAt(0)
            val left = (geometry.left * WIDTH).roundToInt().coerceIn(0, WIDTH - 1)
            val top = (geometry.top * HEIGHT).roundToInt().coerceIn(0, HEIGHT - 1)
            val right = (geometry.right * WIDTH).roundToInt().coerceIn(left + 1, WIDTH)
            val bottom = (geometry.bottom * HEIGHT).roundToInt().coerceIn(top + 1, HEIGHT)
            params.onAddKey(
                Key(
                    code = code,
                    label = geometry.text,
                    labelFlags = 0,
                    width = right - left,
                    height = bottom - top,
                    horizontalGap = 0,
                    verticalGap = 0,
                    x = left,
                    y = top,
                    visualStyle = KeyVisualStyle.Normal,
                    actionFlags = 0,
                    isFastLongPress = false,
                    row = index,
                    column = index,
                ),
            )
        }
        return Keyboard(params)
    }

    fun createFallback(context: Context, locale: Locale, inputType: Int): Keyboard? {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val keys = rows.flatMapIndexed { rowIndex, row ->
            val keyWidth = 1f / row.length
            val inset = when (rowIndex) {
                1 -> 0.05f
                2 -> 0.15f
                else -> 0f
            }
            row.mapIndexed { column, character ->
                AutocorrectKeyGeometry(
                    text = character.toString(),
                    left = inset + column * keyWidth * (1f - inset * 2f),
                    top = rowIndex / 3f,
                    right = inset + (column + 1) * keyWidth * (1f - inset * 2f),
                    bottom = (rowIndex + 1) / 3f,
                )
            }
        }
        return create(
            context,
            locale,
            inputType,
            AutocorrectInputTrace(keys = keys, points = emptyList()),
        )
    }
}
