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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutocorrectFinishSessionTest {
    @Test
    fun finalRequestRoundTripStripsTraceAndInvalidRanges() {
        val request = request().copy(
            composingStart = 0,
            composingEnd = 99,
            inputTrace = AutocorrectInputTrace(
                keys = emptyList(),
                points = listOf(AutocorrectTouchPoint("x", 0.5f, 0.5f)),
            ),
        )

        val bundle = finishSessionBundle(7L, request)
        val actual = finalRequestFromFinishSessionBundle(bundle, 7L)!!

        assertTrue(bundle.getBoolean("hasFinalRequest"))
        assertEquals(AutocorrectInputTrace.Empty, actual.inputTrace)
        assertEquals(-1, actual.composingStart)
        assertEquals(-1, actual.composingEnd)
        assertEquals(0, actual.currentWordStart)
        assertEquals(5, actual.currentWordEnd)
    }

    @Test
    fun legacyFinishBundleHasNoFinalRequest() {
        val bundle = Bundle().apply { putLong("sessionId", 7L) }

        assertFalse(bundle.getBoolean("hasFinalRequest"))
        assertNull(finalRequestFromFinishSessionBundle(bundle, 7L))
    }

    @Test
    fun emptyCursorSnapshotRemainsAuthoritative() {
        val bundle = finishSessionBundle(
            7L,
            request().copy(
                text = "",
                selectionStart = 0,
                selectionEnd = 0,
                currentWordStart = -1,
                currentWordEnd = -1,
            ),
        )

        assertTrue(bundle.getBoolean("hasFinalRequest"))
        assertEquals("", finalRequestFromFinishSessionBundle(bundle, 7L)?.text)
    }

    @Test
    fun staleOrNonCursorSnapshotsBecomeAuthoritativeEmpty() {
        val stale = finishSessionBundle(7L, request()).apply {
            getBundle("finalRequest")!!.putLong("sessionId", 8L)
        }
        val selected = finishSessionBundle(
            7L,
            request().copy(selectionStart = 0, selectionEnd = 5),
        )

        assertEquals("", finalRequestFromFinishSessionBundle(stale, 7L)?.text)
        assertTrue(selected.getBoolean("hasFinalRequest"))
        assertEquals("", finalRequestFromFinishSessionBundle(selected, 7L)?.text)
    }

    private fun request() = AutocorrectRequest(
        sessionId = 7L,
        requestId = 9L,
        text = "probe@example.test",
        selectionStart = 18,
        selectionEnd = 18,
        composingStart = -1,
        composingEnd = -1,
        currentWordStart = 0,
        currentWordEnd = 5,
        maxCandidateCount = 3,
        allowPossiblyOffensive = false,
    )
}
