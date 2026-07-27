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
class AutocorrectSessionTest {
    @Test
    fun preferredEmojiSkinToneRoundTrips() {
        val session = session(preferredEmojiSkinToneModifier = 0x1F3FD)

        assertEquals(session, AutocorrectSession.fromBundle(session.toBundle()))
    }

    @Test
    fun invalidPreferredEmojiSkinToneNormalizesToDefault() {
        val session = session(preferredEmojiSkinToneModifier = 0x1F600)

        assertEquals(
            0,
            AutocorrectSession.fromBundle(session.toBundle()).preferredEmojiSkinToneModifier,
        )
    }

    private fun session(preferredEmojiSkinToneModifier: Int) = AutocorrectSession(
        sessionId = 7L,
        primaryLanguageTag = "en-US",
        secondaryLanguageTags = listOf("tr-TR"),
        inputType = 1,
        capsMode = 0,
        preferredEmojiSkinToneModifier = preferredEmojiSkinToneModifier,
    )
}
