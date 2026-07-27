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

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

private const val AUTOCORRECT_PLUGIN_TAG = "AutocorrectPlugin"

/**
 * Base service for an external autocorrect provider.
 *
 * Suggestion work is cancelled when a newer request arrives or the host unbinds. Implementations
 * should cooperate with coroutine cancellation and must not start a background service, acquire a
 * wake lock, or schedule recurring work for an active typing session.
 */
abstract class AutocorrectPluginService : Service() {
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            Log.e(AUTOCORRECT_PLUGIN_TAG, "Provider operation failed", error)
        },
    )
    private var sessionJob: Job? = null
    private var suggestionJob: Job? = null
    private var activeSessionId: Long? = null
    private var uiLanguageTags = emptyList<String>()
    @Volatile private var uiClient: Messenger? = null
    private val predictionGuard = Mutex()
    private val uiMutationGuard = Mutex()
    private val messenger = Messenger(IncomingHandler())

    final override fun onBind(intent: Intent?): IBinder? {
        return messenger.binder.takeIf {
            intent?.action == AutocorrectPluginContract.ACTION_BIND_PROVIDER
        }
    }

    final override fun onDestroy() {
        uiClient = null
        serviceScope.cancel()
        try {
            onServiceDestroyed()
        } finally {
            super.onDestroy()
        }
    }

    /** Releases provider-owned resources after outstanding provider operations are cancelled. */
    protected open fun onServiceDestroyed() = Unit

    protected open suspend fun onStartSession(session: AutocorrectSession) = Unit

    protected open suspend fun onSuggest(request: AutocorrectRequest): List<AutocorrectCandidate> =
        emptyList()

    /**
     * Extended suggestion hook for providers which also supply optional next-key hit-test hints.
     * Existing candidate-only providers can continue overriding [onSuggest].
     */
    protected open suspend fun onSuggestResult(
        request: AutocorrectRequest,
    ): AutocorrectSuggestionResult = AutocorrectSuggestionResult(onSuggest(request))

    /**
     * Compatibility hook for protocol v1 providers. New providers should override the overload
     * which also receives [AutocorrectAcceptanceKind].
     */
    protected open suspend fun onSuggestionAccepted(sessionId: Long, candidateId: String) = Unit

    protected open suspend fun onSuggestionAccepted(
        sessionId: Long,
        candidateId: String,
        acceptanceKind: AutocorrectAcceptanceKind,
    ) = onSuggestionAccepted(sessionId, candidateId)

    protected open suspend fun onSuggestionReverted(sessionId: Long, candidateId: String) = Unit

    protected open suspend fun onRemoveSuggestion(sessionId: Long, candidateId: String): Boolean = false

    protected open suspend fun onTextEvent(event: AutocorrectTextEvent) = Unit

    protected open suspend fun onFinishSession(sessionId: Long) = Unit

    /** Returns declarative pages which FlorisBoard can render in its app and keyboard UIs. */
    protected open suspend fun onGetPluginUi(languageTags: List<String>): AutocorrectPluginUi? = null

    /** Persists one host-rendered setting. Return false if the item or value is invalid. */
    protected open suspend fun onSetPluginUiValue(itemId: String, value: String): Boolean = false

    /** Runs one explicit user action from a host-rendered provider page. */
    protected open suspend fun onInvokePluginUiAction(itemId: String): Boolean = false

    /** Reads or writes a document selected through the host's system document picker. */
    protected open suspend fun onPluginUiDocument(document: AutocorrectPluginDocument): Boolean = false

    /** Called when the last host-rendered provider page closes. */
    protected open suspend fun onPluginUiClosed() = Unit

    /**
     * Allows providers which retain sensitive personalization data to restrict compatible hosts.
     * The default accepts every host implementing the public protocol.
     */
    protected open fun isHostAuthorized(packageNames: Set<String>): Boolean = true

    /**
     * Pushes updated status or progress while a provider page is visible. This does not start or
     * keep the service alive; it is delivered only to an already-bound host.
     */
    protected fun publishPluginUi(ui: AutocorrectPluginUi) {
        uiClient.sendSafely(
            AutocorrectPluginContract.MSG_PLUGIN_UI_RESULT,
            pluginUiResultBundle(0L, true, ui),
        )
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (!isAuthorized(message)) {
                if (message.what == AutocorrectPluginContract.MSG_PLUGIN_UI_DOCUMENT) {
                    runCatching { message.data.pluginUiDocument()?.close() }
                }
                return
            }
            when (message.what) {
                AutocorrectPluginContract.MSG_START_SESSION -> {
                    val session = AutocorrectSession.fromBundle(message.data)
                    suggestionJob?.cancel()
                    activeSessionId = session.sessionId
                    enqueueSessionOperation {
                        onStartSession(session)
                    }
                }
                AutocorrectPluginContract.MSG_SUGGEST -> {
                    val request = AutocorrectRequest.fromBundle(message.data)
                    if (request.sessionId != activeSessionId) return
                    val replyTo = message.replyTo
                    suggestionJob?.cancel()
                    val sessionReady = sessionJob
                    suggestionJob = serviceScope.launch {
                        sessionReady?.join()
                        if (request.sessionId != activeSessionId) return@launch
                        val result = try {
                            predictionGuard.withLock { onSuggestResult(request) }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.e(AUTOCORRECT_PLUGIN_TAG, "Suggestion request failed", error)
                            AutocorrectSuggestionResult.Unhandled
                        }
                        replyTo.sendSafely(
                            AutocorrectPluginContract.MSG_SUGGESTIONS,
                            suggestionResultToBundle(request.requestId, result),
                        )
                    }
                }
                AutocorrectPluginContract.MSG_ACCEPTED -> {
                    val sessionId = message.data.getLong(Keys.SESSION_ID)
                    if (sessionId != activeSessionId) return
                    val candidateId = message.data.getString(Keys.ID).orEmpty()
                        .take(AutocorrectPluginContract.MAX_CANDIDATE_ID_CHARS)
                    val acceptanceKind = message.data.getString(Keys.ACCEPTANCE_KIND)?.let { value ->
                        enumValues<AutocorrectAcceptanceKind>().firstOrNull { it.name == value }
                    } ?: AutocorrectAcceptanceKind.MANUAL
                    enqueueSessionOperation {
                        onSuggestionAccepted(sessionId, candidateId, acceptanceKind)
                    }
                }
                AutocorrectPluginContract.MSG_REVERTED -> {
                    val sessionId = message.data.getLong(Keys.SESSION_ID)
                    if (sessionId != activeSessionId) return
                    val candidateId = message.data.getString(Keys.ID).orEmpty()
                        .take(AutocorrectPluginContract.MAX_CANDIDATE_ID_CHARS)
                    enqueueSessionOperation {
                        onSuggestionReverted(sessionId, candidateId)
                    }
                }
                AutocorrectPluginContract.MSG_REMOVE -> {
                    val sessionId = message.data.getLong(Keys.SESSION_ID)
                    if (sessionId != activeSessionId) return
                    val requestId = message.data.getLong(Keys.REQUEST_ID)
                    val candidateId = message.data.getString(Keys.ID).orEmpty()
                        .take(AutocorrectPluginContract.MAX_CANDIDATE_ID_CHARS)
                    val replyTo = message.replyTo
                    enqueueSessionOperation {
                        val removed = onRemoveSuggestion(sessionId, candidateId)
                        replyTo.sendSafely(
                            AutocorrectPluginContract.MSG_REMOVE_RESULT,
                            android.os.Bundle().apply {
                                putLong(Keys.REQUEST_ID, requestId)
                                putBoolean(Keys.REMOVED, removed)
                            },
                        )
                    }
                }
                AutocorrectPluginContract.MSG_FINISH_SESSION -> {
                    val sessionId = message.data.getLong(Keys.SESSION_ID)
                    if (sessionId != activeSessionId) return
                    suggestionJob?.cancel()
                    val replyTo = message.replyTo
                    activeSessionId = null
                    enqueueSessionOperation {
                        onFinishSession(sessionId)
                        replyTo.sendSafely(
                            AutocorrectPluginContract.MSG_FINISH_SESSION_RESULT,
                            finishSessionBundle(sessionId),
                        )
                    }
                }
                AutocorrectPluginContract.MSG_CANCEL -> {
                    suggestionJob?.cancel()
                }
                AutocorrectPluginContract.MSG_TEXT_EVENT -> {
                    val event = AutocorrectTextEvent.fromBundle(message.data) ?: return
                    if (event.sessionId != activeSessionId) return
                    enqueueSessionOperation {
                        onTextEvent(event)
                    }
                }
                AutocorrectPluginContract.MSG_GET_PLUGIN_UI -> {
                    uiLanguageTags = message.data.pluginUiLanguageTags()
                    replyWithPluginUi(message, successful = true)
                }
                AutocorrectPluginContract.MSG_SET_PLUGIN_UI_VALUE -> {
                    val itemId = message.data.pluginUiItemId()
                    val value = message.data.pluginUiValue() ?: return
                    replyWithPluginUi(message) {
                        onSetPluginUiValue(itemId, value)
                    }
                }
                AutocorrectPluginContract.MSG_INVOKE_PLUGIN_UI_ACTION -> {
                    val itemId = message.data.pluginUiItemId()
                    replyWithPluginUi(message) {
                        onInvokePluginUiAction(itemId)
                    }
                }
                AutocorrectPluginContract.MSG_PLUGIN_UI_DOCUMENT -> {
                    val document = message.data.pluginUiDocument() ?: return
                    replyWithPluginUi(
                        message = message,
                        cleanup = document::close,
                        operation = { onPluginUiDocument(document) },
                    )
                }
                AutocorrectPluginContract.MSG_PLUGIN_UI_CLOSED -> {
                    enqueuePluginUiOperation {
                        uiClient = null
                        onPluginUiClosed()
                    }
                }
                else -> super.handleMessage(message)
            }
        }

        private fun enqueueSessionOperation(operation: suspend () -> Unit) {
            val previous = sessionJob
            sessionJob = serviceScope.launch {
                previous?.join()
                predictionGuard.withLock { operation() }
            }
        }

        private fun enqueuePluginUiOperation(
            cleanup: () -> Unit = {},
            operation: suspend () -> Unit,
        ) {
            serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    uiMutationGuard.withLock {
                        yield()
                        predictionGuard.withLock {
                            operation()
                        }
                    }
                } finally {
                    runCatching(cleanup)
                }
            }
        }

        private fun isAuthorized(message: Message): Boolean {
            if (message.sendingUid == Process.myUid()) return true
            val packages = packageManager.getPackagesForUid(message.sendingUid)
                ?.toSet()
                .orEmpty()
            return packages.isNotEmpty() && isHostAuthorized(packages)
        }

        private fun replyWithPluginUi(
            message: Message,
            successful: Boolean? = null,
            cleanup: () -> Unit = {},
            operation: suspend () -> Boolean = { true },
        ) {
            val requestId = message.data.pluginUiRequestId()
            val languageTags = uiLanguageTags
            val replyTo = message.replyTo
            if (replyTo == null) {
                runCatching(cleanup)
                return
            }
            enqueuePluginUiOperation(cleanup) {
                uiClient = replyTo
                val (result, ui) = try {
                    val operationResult = successful ?: operation()
                    operationResult to onGetPluginUi(languageTags)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(AUTOCORRECT_PLUGIN_TAG, "Provider UI operation failed", error)
                    false to null
                }
                replyTo.sendSafely(
                    AutocorrectPluginContract.MSG_PLUGIN_UI_RESULT,
                    pluginUiResultBundle(
                        requestId,
                        result,
                        ui,
                    ),
                )
            }
        }
    }
}

private fun Messenger?.sendSafely(what: Int, data: android.os.Bundle) {
    if (this == null) return
    try {
        send(Message.obtain(null, what).apply { this.data = data })
    } catch (_: RemoteException) {
        // The host disappeared; Android will shortly unbind and destroy this service.
    }
}
