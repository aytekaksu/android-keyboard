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
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutocorrectWireStringTest {
    @Test
    fun candidateRoundTripKeepsPairsAndDropsOnlyDanglingHighSurrogates() {
        val exactText = "a".repeat(254) + "\uD83D\uDE00"
        val splitId = "i".repeat(255) + "\uD83D\uDE00"
        val candidate = AutocorrectCandidate(
            id = splitId,
            text = exactText,
            secondaryText = "note\uD83D",
        )

        val decoded = AutocorrectCandidate.fromBundle(candidate.toBundle())!!

        assertEquals("i".repeat(255), decoded.id)
        assertEquals(exactText, decoded.text)
        assertEquals("note", decoded.secondaryText)
        assertEquals("\uDE00", "\uDE00".takeWireChars(1))
    }

    @Test
    fun candidateReaderRejectsMalformedReplacementRangesAsPairs() {
        listOf(3 to 1, 0 to -1, -1 to 2).forEach { (start, end) ->
            val decoded = AutocorrectCandidate.fromBundle(Bundle().apply {
                putString(Keys.TEXT, "word")
                putInt(Keys.REPLACEMENT_START, start)
                putInt(Keys.REPLACEMENT_END, end)
            })

            assertNull(decoded)
        }
    }

    @Test
    fun suggestionReaderBoundsInputsBeforeDecodingEntries() {
        val candidateBundles = ArrayList<Bundle>().apply {
            repeat(AutocorrectPluginContract.MAX_CANDIDATES) { add(Bundle()) }
            add(AutocorrectCandidate(id = "late", text = "late").toBundle())
        }
        val (_, result) = suggestionResultFromBundle(Bundle().apply {
            putParcelableArrayList(Keys.CANDIDATES, candidateBundles)
            putIntArray(
                Keys.BOOSTED_CODE_POINTS,
                IntArray(AutocorrectPluginContract.MAX_BOOSTED_CODE_POINT_COUNT + 1) { index ->
                    if (index == AutocorrectPluginContract.MAX_BOOSTED_CODE_POINT_COUNT) {
                        'a'.code
                    } else {
                        -1
                    }
                },
            )
        })

        assertEquals(emptyList<AutocorrectCandidate>(), result.candidates)
        assertEquals(emptySet<Int>(), result.boostedCodePoints)
    }

    @Test
    fun requestReaderSafelyClampsLegacyBundlesAndKeepsDefaults() {
        val bundle = Bundle().apply {
            putString(Keys.TEXT, "x".repeat(511) + "\uD83D\uDE00tail")
            putInt(Keys.SELECTION_START, 511)
            putInt(Keys.SELECTION_END, 512)
            putInt(Keys.COMPOSING_START, 0)
            putInt(Keys.COMPOSING_END, Int.MAX_VALUE)
            putInt(Keys.CURRENT_WORD_START, -2)
            putInt(Keys.CURRENT_WORD_END, 511)
            putInt(Keys.MAX_CANDIDATE_COUNT, 0)
        }

        val decoded = AutocorrectRequest.fromBundle(bundle)

        assertEquals("x".repeat(511), decoded.text)
        assertEquals(1, decoded.maxCandidateCount)
        assertEquals(-1, decoded.selectionStart)
        assertEquals(-1, decoded.selectionEnd)
        assertEquals(-1, decoded.composingStart)
        assertEquals(-1, decoded.composingEnd)
        assertEquals(-1, decoded.currentWordStart)
        assertEquals(-1, decoded.currentWordEnd)
        assertEquals(AutocorrectCapsMode.UNSPECIFIED, decoded.capsMode)
        assertEquals(AutocorrectInputTrace.Empty, decoded.inputTrace)
    }

    @Test
    fun requestReaderRejectsReversedRangesAsPairs() {
        val decoded = AutocorrectRequest.fromBundle(Bundle().apply {
            putString(Keys.TEXT, "word")
            putInt(Keys.SELECTION_START, 3)
            putInt(Keys.SELECTION_END, 1)
            putInt(Keys.COMPOSING_START, 4)
            putInt(Keys.COMPOSING_END, 2)
            putInt(Keys.CURRENT_WORD_START, 2)
            putInt(Keys.CURRENT_WORD_END, 1)
        })

        assertEquals(-1, decoded.selectionStart)
        assertEquals(-1, decoded.selectionEnd)
        assertEquals(-1, decoded.composingStart)
        assertEquals(-1, decoded.composingEnd)
        assertEquals(-1, decoded.currentWordStart)
        assertEquals(-1, decoded.currentWordEnd)
    }

    @Test
    fun requestRoundTripKeepsPairAtExactUtf16Limit() {
        val text = "x".repeat(510) + "\uD83D\uDE00"
        val request = AutocorrectRequest(
            sessionId = 1L,
            requestId = 2L,
            text = text,
            selectionStart = text.length,
            selectionEnd = text.length,
            composingStart = 0,
            composingEnd = text.length,
            currentWordStart = 0,
            currentWordEnd = text.length,
            maxCandidateCount = 3,
            allowPossiblyOffensive = false,
        )

        assertEquals(request, AutocorrectRequest.fromBundle(request.toBundle()))
    }

    @Test
    fun textEventReaderClampsBeforeApplyingLegacyKindDefault() {
        val decoded = AutocorrectTextEvent.fromBundle(Bundle().apply {
            putLong(Keys.SESSION_ID, 7L)
            putString(Keys.TEXT, "w".repeat(255) + "\uD83D\uDE00tail")
        })

        assertEquals("w".repeat(255), decoded?.text)
        assertEquals(AutocorrectTextEventKind.COMMIT_TYPED, decoded?.kind)
        assertNull(AutocorrectTextEvent.fromBundle(Bundle().apply {
            putString(Keys.TEXT, "\uD83D")
        }))
    }
}
