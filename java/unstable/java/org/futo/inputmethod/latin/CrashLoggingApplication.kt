/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin

import android.app.Application
import androidx.datastore.preferences.core.Preferences

/**
 * Minimal application used by the service-only Floris provider flavor.
 *
 * The full stable application owns crash-report and settings UI integration; neither belongs in a
 * provider process which lives only while FlorisBoard is bound to it.
 */
class CrashLoggingApplication : Application() {
    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun logPreferences(preferences: Preferences) = Unit

        fun CopyLogsOption() = Unit
    }
}
