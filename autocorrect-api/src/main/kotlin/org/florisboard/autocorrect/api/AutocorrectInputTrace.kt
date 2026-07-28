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

data class AutocorrectKeyGeometry(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class AutocorrectTouchPoint(
    val text: String,
    val x: Float,
    val y: Float,
)

data class AutocorrectGesturePoint(
    val x: Float,
    val y: Float,
    val elapsedTimeMillis: Int,
)

enum class AutocorrectInputMode {
    TYPING,
    GESTURE,
}

/**
 * Optional normalized keyboard geometry and pointer data for engines which perform key-proximity
 * scoring or gesture decoding. Coordinates are in the range 0..1 and contain no physical screen
 * location.
 */
data class AutocorrectInputTrace(
    val keys: List<AutocorrectKeyGeometry>,
    val points: List<AutocorrectTouchPoint>,
    val gesturePoints: List<AutocorrectGesturePoint> = emptyList(),
    val mode: AutocorrectInputMode = AutocorrectInputMode.TYPING,
) {
    companion object {
        val Empty = AutocorrectInputTrace(emptyList(), emptyList())
    }
}

internal fun AutocorrectInputTrace.toBundle() = Bundle().apply {
    putParcelableArrayList(
        TraceKeys.KEYS,
        ArrayList(keys.take(AutocorrectPluginContract.MAX_TRACE_KEY_COUNT).map { key ->
            Bundle().apply {
                putString(TraceKeys.TEXT, key.text.takeWireChars(TraceLimits.TEXT_CHARS))
                putFloat(TraceKeys.LEFT, key.left.normalized())
                putFloat(TraceKeys.TOP, key.top.normalized())
                putFloat(TraceKeys.RIGHT, key.right.normalized())
                putFloat(TraceKeys.BOTTOM, key.bottom.normalized())
            }
        }),
    )
    putParcelableArrayList(
        TraceKeys.POINTS,
        ArrayList(points.take(AutocorrectPluginContract.MAX_TRACE_POINT_COUNT).map { point ->
            Bundle().apply {
                putString(TraceKeys.TEXT, point.text.takeWireChars(TraceLimits.TEXT_CHARS))
                putFloat(TraceKeys.X, point.x.normalized())
                putFloat(TraceKeys.Y, point.y.normalized())
            }
        }),
    )
    putParcelableArrayList(
        TraceKeys.GESTURE_POINTS,
        ArrayList(
            gesturePoints.take(AutocorrectPluginContract.MAX_GESTURE_POINT_COUNT).map { point ->
                Bundle().apply {
                    putFloat(TraceKeys.X, point.x.normalized())
                    putFloat(TraceKeys.Y, point.y.normalized())
                    putInt(
                        TraceKeys.ELAPSED_TIME_MILLIS,
                        point.elapsedTimeMillis.coerceIn(0, TraceLimits.MAX_ELAPSED_TIME_MILLIS),
                    )
                }
            },
        ),
    )
    putString(TraceKeys.MODE, mode.name)
}

@Suppress("DEPRECATION")
internal fun Bundle.toAutocorrectInputTrace() = AutocorrectInputTrace(
    keys = getParcelableArrayList<Bundle>(TraceKeys.KEYS)
        .orEmpty()
        .take(AutocorrectPluginContract.MAX_TRACE_KEY_COUNT)
        .mapNotNull { key ->
            val text = key.getString(TraceKeys.TEXT)
                ?.takeWireChars(TraceLimits.TEXT_CHARS)
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            AutocorrectKeyGeometry(
                text = text,
                left = key.getFloat(TraceKeys.LEFT).normalized(),
                top = key.getFloat(TraceKeys.TOP).normalized(),
                right = key.getFloat(TraceKeys.RIGHT).normalized(),
                bottom = key.getFloat(TraceKeys.BOTTOM).normalized(),
            )
        },
    points = getParcelableArrayList<Bundle>(TraceKeys.POINTS)
        .orEmpty()
        .take(AutocorrectPluginContract.MAX_TRACE_POINT_COUNT)
        .mapNotNull { point ->
            val text = point.getString(TraceKeys.TEXT)
                ?.takeWireChars(TraceLimits.TEXT_CHARS)
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            AutocorrectTouchPoint(
                text = text,
                x = point.getFloat(TraceKeys.X).normalized(),
                y = point.getFloat(TraceKeys.Y).normalized(),
            )
        },
    gesturePoints = getParcelableArrayList<Bundle>(TraceKeys.GESTURE_POINTS)
        .orEmpty()
        .take(AutocorrectPluginContract.MAX_GESTURE_POINT_COUNT)
        .map { point ->
            AutocorrectGesturePoint(
                x = point.getFloat(TraceKeys.X).normalized(),
                y = point.getFloat(TraceKeys.Y).normalized(),
                elapsedTimeMillis = point.getInt(TraceKeys.ELAPSED_TIME_MILLIS)
                    .coerceIn(0, TraceLimits.MAX_ELAPSED_TIME_MILLIS),
            )
        },
    mode = getString(TraceKeys.MODE)?.let { value ->
        enumValues<AutocorrectInputMode>().firstOrNull { it.name == value }
    } ?: AutocorrectInputMode.TYPING,
)

private fun Float.normalized() = takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f

private object TraceLimits {
    const val TEXT_CHARS = 8
    const val MAX_ELAPSED_TIME_MILLIS = 60_000
}

private object TraceKeys {
    const val KEYS = "keys"
    const val POINTS = "points"
    const val GESTURE_POINTS = "gesturePoints"
    const val MODE = "mode"
    const val TEXT = "text"
    const val LEFT = "left"
    const val TOP = "top"
    const val RIGHT = "right"
    const val BOTTOM = "bottom"
    const val X = "x"
    const val Y = "y"
    const val ELAPSED_TIME_MILLIS = "elapsedTimeMillis"
}
