/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect

import android.app.Activity
import android.os.Bundle
import org.futo.inputmethod.latin.uix.settings.SettingsActivity

abstract class FlorisSettingsRedirectActivity(
    private val destination: String,
) : Activity() {
    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsActivity.openToNavDest(this, destination, providerMode = true)
        finish()
    }
}

class FlorisAutocorrectSettingsActivity : FlorisSettingsRedirectActivity("predictiveText")

class FlorisModelSettingsActivity : FlorisSettingsRedirectActivity("models")

class FlorisDictionarySettingsActivity : FlorisSettingsRedirectActivity("pdict")

class FlorisBlacklistSettingsActivity : FlorisSettingsRedirectActivity("blacklist")
