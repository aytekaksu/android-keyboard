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
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutocorrectPluginServiceLifecycleTest {
    @Test
    fun authorizationIsCachedOnlyAfterHostClaimSucceeds() {
        val authorization = BindingHostAuthorization()
        var authorizationChecks = 0
        var claims = 0
        fun accepts(uid: Int, authorized: Boolean = true, claimed: Boolean = true) =
            authorization.accepts(
                uid,
                authorize = {
                    authorizationChecks++
                    authorized
                },
                claim = {
                    claims++
                    claimed
                },
            )

        assertFalse(accepts(uid = 10, claimed = false))
        assertTrue(accepts(uid = 20))
        assertTrue(accepts(uid = 20, authorized = false))
        assertFalse(accepts(uid = 21))

        assertEquals(2, authorizationChecks)
        assertEquals(3, claims)
    }

    @Test
    fun invalidatingUiLifetimeCancelsOldWorkAndAllowsFreshWork() {
        val parent = Job()
        val lifetime = PluginUiLifetime(parent)
        assertNull(lifetime.invalidate())
        val first = lifetime.open()
        val child = Job(first)

        assertSame(first, lifetime.invalidate())
        assertTrue(first.isCancelled)
        assertTrue(child.isCancelled)
        assertFalse(lifetime.isCurrent(first))
        assertNull(lifetime.invalidate())

        val second = lifetime.open()
        assertNotSame(first, second)
        assertTrue(lifetime.isCurrent(second))
        parent.cancel()
    }
}
