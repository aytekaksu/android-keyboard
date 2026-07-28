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

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutocorrectInputTraceTest {
    @Test
    fun traceTextLimitKeepsTheV4EightCharAsciiBehavior() {
        val input = "12345678tail"
        val trace = AutocorrectInputTrace(
            keys = listOf(AutocorrectKeyGeometry(input, 0f, 0f, 1f, 1f)),
            points = listOf(AutocorrectTouchPoint(input, 0.5f, 0.5f)),
        )

        val decoded = trace.toBundle().toAutocorrectInputTrace()

        assertEquals("12345678", decoded.keys.single().text)
        assertEquals("12345678", decoded.points.single().text)
    }

    @Test
    fun traceTextLimitKeepsTheV4Utf16BudgetWithoutSplittingPairs() {
        val input = "1234567\uD83D\uDE00tail"
        val trace = AutocorrectInputTrace(
            keys = listOf(AutocorrectKeyGeometry(input, 0f, 0f, 1f, 1f)),
            points = listOf(AutocorrectTouchPoint(input, 0.5f, 0.5f)),
        )

        val decoded = trace.toBundle().toAutocorrectInputTrace()

        assertEquals("1234567", decoded.keys.single().text)
        assertEquals("1234567", decoded.points.single().text)
    }

    @Test
    fun traceRoundTripKeepsFourSupplementaryCodePoints() {
        val text = "\uD83D\uDE00".repeat(4)
        val trace = AutocorrectInputTrace(
            keys = listOf(AutocorrectKeyGeometry("$text-extra", 0f, 0f, 1f, 1f)),
            points = listOf(AutocorrectTouchPoint("$text-extra", 0.5f, 0.5f)),
        )

        val decoded = trace.toBundle().toAutocorrectInputTrace()

        assertEquals(text, decoded.keys.single().text)
        assertEquals(text, decoded.points.single().text)
    }
}
