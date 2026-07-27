package org.futo.inputmethod.latin.settings

import java.util.Locale

object ProviderSessionLanguages {
    @Volatile
    private var secondary = emptyList<Locale>()

    @JvmStatic
    fun replace(locales: List<Locale>) {
        secondary = locales.toList()
    }

    @JvmStatic
    fun secondary(): List<Locale> = secondary
}
