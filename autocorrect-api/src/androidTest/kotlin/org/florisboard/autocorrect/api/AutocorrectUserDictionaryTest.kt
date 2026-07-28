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
class AutocorrectUserDictionaryTest {
    @Test
    fun structuredEntrySurvivesResultRoundTrip() {
        val entry = AutocorrectUserDictionaryEntry(
            id = 42L,
            word = "変換",
            frequency = 0,
            languageTag = "ja-JP",
            shortcut = "へんかん\t名詞",
        )

        val (requestId, page) = userDictionaryResultFromBundle(
            userDictionaryResultBundle(
                requestId = 7L,
                status = AutocorrectUserDictionaryStatus.OK,
                entries = listOf(entry),
            ),
        )

        assertEquals(7L, requestId)
        assertEquals(listOf(entry), page.entries)
    }

    @Test
    fun queryRequestClampsBounds() {
        val request = userDictionaryRequestFromBundle(
            userDictionaryQueryBundle(
                requestId = 1L,
                languageTags = List(20) { "en-x-$it" },
                afterId = -1L,
                limit = Int.MAX_VALUE,
            ),
        )!!

        assertEquals(
            AutocorrectPluginContract.MAX_USER_DICTIONARY_LANGUAGE_TAGS,
            request.languageTags.size,
        )
        assertEquals(0L, request.afterId)
        assertEquals(AutocorrectPluginContract.MAX_USER_DICTIONARY_PAGE_SIZE, request.limit)
    }

    @Test
    fun invalidMutationEntryIsRejected() {
        val request = userDictionaryRequestFromBundle(
            userDictionaryUpsertBundle(
                requestId = 1L,
                originUiRequestId = 2L,
                entry = AutocorrectUserDictionaryEntry(
                    word = "x".repeat(
                        AutocorrectPluginContract.MAX_USER_DICTIONARY_TEXT_CHARS + 1,
                    ),
                    frequency = 100,
                ),
            ),
        )

        assertNull(request)
    }

    @Test
    fun deleteRequestPreservesStableId() {
        val request = userDictionaryRequestFromBundle(
            userDictionaryDeleteBundle(
                requestId = 1L,
                originUiRequestId = 2L,
                id = 42L,
            ),
        )!!

        assertEquals(42L, request.entryId)
        assertNull(request.entry)
    }

    @Test
    fun scriptAndRegionAreCanonicalizedWithoutLoss() {
        val request = userDictionaryRequestFromBundle(
            userDictionaryQueryBundle(
                requestId = 1L,
                languageTags = listOf("zh-hant-tw", "sr-Latn-RS"),
                afterId = 0L,
                limit = 1,
            ),
        )!!

        assertEquals(listOf("zh-Hant-TW", "sr-Latn-RS"), request.languageTags)
    }

    @Test
    fun malformedProtocolLocaleRejectsTheRequest() {
        assertNull(
            userDictionaryRequestFromBundle(
                userDictionaryQueryBundle(
                    requestId = 1L,
                    languageTags = listOf("en_US"),
                    afterId = 0L,
                    limit = 1,
                ),
            ),
        )
    }

    @Test
    fun nullIsTheOnlyLanguageNeutralEntryLocale() {
        listOf("und", "und-Latn", "x-private").forEach { languageTag ->
            assertNull(
                userDictionaryRequestFromBundle(
                    userDictionaryUpsertBundle(
                        requestId = 1L,
                        originUiRequestId = 2L,
                        entry = AutocorrectUserDictionaryEntry(
                            word = "word",
                            frequency = 128,
                            languageTag = languageTag,
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun wrongTypedDictionaryPrimitivesAreRejected() {
        val request = userDictionaryQueryBundle(
            requestId = 1L,
            languageTags = listOf("en"),
            afterId = 0L,
            limit = 1,
        ).apply {
            putString("udRequestId", "1")
        }
        assertNull(userDictionaryRequestFromBundle(request))

        val result = userDictionaryResultBundle(
            requestId = 1L,
            status = AutocorrectUserDictionaryStatus.OK,
            entries = listOf(
                AutocorrectUserDictionaryEntry(
                    id = 2L,
                    word = "word",
                    frequency = 0,
                ),
            ),
        )
        @Suppress("DEPRECATION")
        result.getParcelableArrayList<Bundle>("udEntries")!![0]
            .putString("udFrequency", "0")

        val (_, page) = userDictionaryResultFromBundle(result)
        assertEquals(AutocorrectUserDictionaryStatus.INVALID, page.status)
    }
}
