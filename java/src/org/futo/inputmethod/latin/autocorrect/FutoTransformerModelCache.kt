/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.xlm.LanguageModel
import org.futo.inputmethod.latin.xlm.ModelInfoLoader
import java.util.Locale

/**
 * Keeps expensive native models warm only while the provider process itself remains alive.
 *
 * Session and editor ownership stay outside this cache; transient model context is cleared at
 * every session boundary.
 */
internal object FutoTransformerModelCache {
    private val transformerGuard = Mutex()
    private var transformer: LanguageModel? = null

    suspend fun <T> withModel(
        context: Context,
        loader: ModelInfoLoader,
        locale: Locale,
        block: suspend (LanguageModel) -> T,
    ): T = transformerGuard.withLock {
        val current = transformer
        if (
            current == null ||
            current.modelInfoLoader.path != loader.path ||
            current.locale.language != locale.language
        ) {
            evictLocked()
            transformer = LanguageModel(context.applicationContext, loader, locale)
        }
        block(checkNotNull(transformer))
    }

    suspend fun clearContext() = transformerGuard.withLock {
        transformer?.clearTransientContext()
    }

    suspend fun evictUnless(languages: Set<String>) =
        transformerGuard.withLock {
            if (transformer?.locale?.language !in languages) evictLocked()
        }

    suspend fun evict() = transformerGuard.withLock {
        evictLocked()
    }

    private suspend fun evictLocked() {
        val model = transformer ?: return
        withContext(NonCancellable) {
            model.closeInternalLocked()
            if (transformer === model) transformer = null
        }
    }
}
