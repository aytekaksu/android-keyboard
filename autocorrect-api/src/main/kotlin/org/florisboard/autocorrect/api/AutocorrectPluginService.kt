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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val AUTOCORRECT_PLUGIN_TAG = "AutocorrectPlugin"

/**
 * Base service for an external autocorrect provider.
 *
 * Suggestion work is cancelled when a newer request arrives or the host unbinds. Implementations
 * should cooperate with coroutine cancellation and must not start a background service, acquire a
 * wake lock, or schedule recurring work for an active typing session.
 */
abstract class AutocorrectPluginService : Service() {
    private val operationErrorHandler = CoroutineExceptionHandler { _, error ->
        Log.e(AUTOCORRECT_PLUGIN_TAG, "Provider operation failed", error)
    }
    private val lifecycleScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + operationErrorHandler,
    )
    private var serviceScope = newServiceScope()
    @Volatile private var bindingCleanup: Job? = null
    @Volatile private var bindingReady: Job? = null
    @Volatile private var activeBindingEpoch = 0L
    private var nextBindingEpoch = 0L
    private var sessionJob: Job? = null
    private var suggestionJob: Job? = null
    @Volatile private var activeSessionId: Long? = null
    private var uiLanguageTags = emptyList<String>()
    @Volatile private var uiClient: Messenger? = null
    private val predictionGuard = Mutex()
    private val uiMutationGuard = Mutex()
    private val callbackBindingEpoch = ThreadLocal<Long>()
    private val userDictionaryClient = HostUserDictionaryClient(callbackBindingEpoch)

    /**
     * Reads Android personal-dictionary rows during a provider callback or its structured child
     * coroutine. Calls outside the current binding's callback context return `UNAVAILABLE`.
     */
    protected val hostUserDictionary: AutocorrectUserDictionaryReader
        get() = userDictionaryClient

    final override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != AutocorrectPluginContract.ACTION_BIND_PROVIDER) return null
        if (!serviceScope.coroutineContext[Job]!!.isActive) {
            serviceScope = newServiceScope()
        }
        val epoch = ++nextBindingEpoch
        activeBindingEpoch = epoch
        val messenger = Messenger(IncomingHandler(epoch))
        userDictionaryClient.beginBinding(epoch, messenger)
        val previousCleanup = bindingCleanup
        bindingReady = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            previousCleanup?.join()
            if (activeBindingEpoch == epoch) {
                userDictionaryClient.activate(epoch)
            }
        }
        return messenger.binder
    }

    final override fun onUnbind(intent: Intent?): Boolean {
        val (epoch, oldBinding) = clearBinding()
        if (epoch != 0L) {
            scheduleBindingCleanup(oldBinding)
        }
        return super.onUnbind(intent)
    }

    final override fun onDestroy() {
        val (epoch, oldBinding) = clearBinding()
        if (epoch != 0L) {
            scheduleBindingCleanup(oldBinding)
        }
        val cleanup = bindingCleanup
        lifecycleScope.launch {
            cleanup?.join()
            oldBinding.join()
            uiMutationGuard.withLock {
                predictionGuard.withLock {
                    runCatching { onServiceDestroyed() }.onFailure { error ->
                        Log.e(AUTOCORRECT_PLUGIN_TAG, "Provider destruction cleanup failed", error)
                    }
                }
            }
        }.invokeOnCompletion {
            lifecycleScope.cancel()
        }
        super.onDestroy()
    }

    /** Releases provider-owned resources after outstanding provider operations are cancelled. */
    protected open fun onServiceDestroyed() = Unit

    /** Releases resources tied to the host which just unbound. */
    protected open fun onHostUnbound() = Unit

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

    /**
     * Runs an explicit action with a dictionary editor valid only for that visible host action.
     * Existing providers can keep overriding the single-argument overload.
     */
    protected open suspend fun onInvokePluginUiAction(
        itemId: String,
        userDictionary: AutocorrectUserDictionaryEditor,
    ): Boolean = onInvokePluginUiAction(itemId)

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

    private fun newServiceScope() = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + operationErrorHandler,
    )

    private fun clearBinding(): Pair<Long, Job> {
        val epoch = activeBindingEpoch
        activeBindingEpoch = 0L
        bindingReady?.cancel()
        bindingReady = null
        suggestionJob?.cancel()
        suggestionJob = null
        sessionJob?.cancel()
        sessionJob = null
        activeSessionId = null
        uiClient = null
        uiLanguageTags = emptyList()
        userDictionaryClient.detach()
        val bindingJob = serviceScope.coroutineContext[Job]!!
        serviceScope.cancel()
        return epoch to bindingJob
    }

    private fun scheduleBindingCleanup(bindingJob: Job) {
        val previousCleanup = bindingCleanup
        bindingCleanup = lifecycleScope.launch {
            previousCleanup?.join()
            bindingJob.join()
            uiMutationGuard.withLock {
                predictionGuard.withLock {
                    runCatching { onHostUnbound() }.onFailure { error ->
                        Log.e(AUTOCORRECT_PLUGIN_TAG, "Provider unbind cleanup failed", error)
                    }
                }
            }
        }
    }

    private inner class IncomingHandler(
        private val bindingEpoch: Long,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (
                bindingEpoch != activeBindingEpoch ||
                !isAuthorized(message) ||
                !userDictionaryClient.claimHost(message.sendingUid, message.replyTo)
            ) {
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
                    val ready = bindingReady
                    suggestionJob = serviceScope.launch(
                        callbackBindingEpoch.asContextElement(bindingEpoch),
                    ) {
                        ready?.join()
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
                    val requestId = message.data.pluginUiRequestId()
                    replyWithPluginUi(message) {
                        val editor = userDictionaryClient.editor(requestId)
                        try {
                            onInvokePluginUiAction(itemId, editor)
                        } finally {
                            editor.close()
                        }
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
                AutocorrectPluginContract.MSG_HOST_USER_DICTIONARY_RESULT -> {
                    userDictionaryClient.complete(message.sendingUid, message.data)
                }
                else -> super.handleMessage(message)
            }
        }

        private fun enqueueSessionOperation(operation: suspend () -> Unit) {
            val previous = sessionJob
            val ready = bindingReady
            sessionJob = serviceScope.launch(
                callbackBindingEpoch.asContextElement(bindingEpoch),
            ) {
                ready?.join()
                previous?.join()
                predictionGuard.withLock { operation() }
            }
        }

        private fun enqueuePluginUiOperation(
            cleanup: () -> Unit = {},
            operation: suspend () -> Unit,
        ) {
            val ready = bindingReady
            serviceScope.launch(
                context = callbackBindingEpoch.asContextElement(bindingEpoch),
                start = CoroutineStart.UNDISPATCHED,
            ) {
                try {
                    ready?.join()
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

private class HostUserDictionaryClient(
    private val callbackBindingEpoch: ThreadLocal<Long>,
) : AutocorrectUserDictionaryReader {
    companion object {
        private const val RESPONSE_TIMEOUT_MS = 2_000L
    }

    private val nextRequestId = AtomicLong(1L)
    private val guard = Any()
    private val pending = mutableMapOf<Long, CompletableDeferred<AutocorrectUserDictionaryPage>>()
    private var replyMessenger: Messenger? = null
    private var bindingEpoch = 0L
    private var activeBindingEpoch = 0L
    private var hostUid: Int? = null
    private var host: Messenger? = null

    fun beginBinding(epoch: Long, messenger: Messenger) {
        synchronized(guard) {
            bindingEpoch = epoch
            activeBindingEpoch = 0L
            replyMessenger = messenger
        }
    }

    fun activate(epoch: Long) {
        synchronized(guard) {
            if (bindingEpoch == epoch && replyMessenger != null) {
                activeBindingEpoch = epoch
            }
        }
    }

    fun claimHost(uid: Int, messenger: Messenger?): Boolean = synchronized(guard) {
        val currentUid = hostUid
        if (currentUid == null) {
            messenger ?: return@synchronized false
            hostUid = uid
            host = messenger
            true
        } else {
            currentUid == uid && (
                messenger == null ||
                    messenger.binder == host?.binder
            )
        }
    }

    fun detach() {
        val interrupted = synchronized(guard) {
            hostUid = null
            host = null
            replyMessenger = null
            bindingEpoch = 0L
            activeBindingEpoch = 0L
            pending.values.toList().also { pending.clear() }
        }
        interrupted.forEach {
            it.complete(
                AutocorrectUserDictionaryPage(AutocorrectUserDictionaryStatus.UNAVAILABLE),
            )
        }
    }

    fun editor(originUiRequestId: Long) = ScopedEditor(originUiRequestId)

    inner class ScopedEditor(
        private val originUiRequestId: Long,
    ) : AutocorrectUserDictionaryEditor {
        private val open = AtomicBoolean(true)

        fun close() {
            open.set(false)
        }

        override suspend fun queryUserDictionary(
            languageTags: List<String>,
            afterId: Long,
            limit: Int,
        ) = if (open.get()) {
            this@HostUserDictionaryClient.queryUserDictionary(languageTags, afterId, limit)
        } else {
            deniedPage()
        }

        override suspend fun upsertUserDictionaryEntry(
            entry: AutocorrectUserDictionaryEntry,
        ) = if (open.get()) {
            request(
                userDictionaryUpsertBundle(
                    requestId = nextRequestId.getAndIncrement(),
                    originUiRequestId = originUiRequestId,
                    entry = entry,
                ),
            ).toMutationResult()
        } else {
            deniedPage().toMutationResult()
        }

        override suspend fun deleteUserDictionaryEntry(
            id: Long,
        ) = if (!open.get()) {
            deniedPage().toMutationResult()
        } else if (id <= 0L) {
            AutocorrectUserDictionaryMutationResult(
                AutocorrectUserDictionaryStatus.INVALID,
            )
        } else {
            request(
                userDictionaryDeleteBundle(
                    requestId = nextRequestId.getAndIncrement(),
                    originUiRequestId = originUiRequestId,
                    id = id,
                ),
            ).toMutationResult()
        }
    }

    override suspend fun queryUserDictionary(
        languageTags: List<String>,
        afterId: Long,
        limit: Int,
    ) = request(
        userDictionaryQueryBundle(
            requestId = nextRequestId.getAndIncrement(),
            languageTags = languageTags,
            afterId = afterId,
            limit = limit,
        ),
    )

    fun complete(uid: Int, bundle: android.os.Bundle) {
        val (requestId, result) = runCatching {
            userDictionaryResultFromBundle(bundle)
        }.getOrNull() ?: return
        val deferred = synchronized(guard) {
            if (hostUid == uid) pending.remove(requestId) else null
        }
        deferred?.complete(result)
    }

    private suspend fun request(data: android.os.Bundle): AutocorrectUserDictionaryPage {
        val request = runCatching { userDictionaryRequestFromBundle(data) }.getOrNull()
            ?: return AutocorrectUserDictionaryPage(AutocorrectUserDictionaryStatus.INVALID)
        val requestId = request.requestId
        val deferred = CompletableDeferred<AutocorrectUserDictionaryPage>()
        val callerEpoch = callbackBindingEpoch.get()
        val sent = synchronized(guard) {
            if (callerEpoch == null || callerEpoch != activeBindingEpoch) {
                return@synchronized false
            }
            val current = host ?: return@synchronized false
            val replies = replyMessenger ?: return@synchronized false
            pending[requestId] = deferred
            try {
                current.send(
                    Message.obtain(
                        null,
                        AutocorrectPluginContract.MSG_HOST_USER_DICTIONARY_REQUEST,
                    ).apply {
                        this.data = data
                        replyTo = replies
                    },
                )
                true
            } catch (_: RemoteException) {
                pending.remove(requestId, deferred)
                false
            }
        }
        if (!sent) {
            return AutocorrectUserDictionaryPage(AutocorrectUserDictionaryStatus.UNAVAILABLE)
        }
        val result = try {
            withTimeoutOrNull(RESPONSE_TIMEOUT_MS) { deferred.await() }
                ?: AutocorrectUserDictionaryPage(AutocorrectUserDictionaryStatus.UNAVAILABLE)
        } finally {
            synchronized(guard) { pending.remove(requestId, deferred) }
        }
        return result.validatedFor(request)
    }

    private fun deniedPage() =
        AutocorrectUserDictionaryPage(AutocorrectUserDictionaryStatus.DENIED)

    private fun AutocorrectUserDictionaryPage.validatedFor(
        request: AutocorrectUserDictionaryRequest,
    ): AutocorrectUserDictionaryPage {
        if (!successful) return AutocorrectUserDictionaryPage(status)
        return when (request.operation) {
            AutocorrectUserDictionaryOperation.QUERY -> {
                val ordered = entries.size <= request.limit &&
                    entries.all { it.id > request.afterId } &&
                    entries.zipWithNext().all { (first, second) -> first.id < second.id }
                val cursorValid = nextAfterId == null || (
                    nextAfterId > request.afterId &&
                        nextAfterId >= (entries.lastOrNull()?.id ?: 0L)
                    )
                takeIf { ordered && cursorValid }
                    ?: AutocorrectUserDictionaryPage(AutocorrectUserDictionaryStatus.INVALID)
            }
            AutocorrectUserDictionaryOperation.UPSERT -> {
                val entry = entries.singleOrNull()
                takeIf {
                    nextAfterId == null &&
                        entry != null &&
                        entry.id > 0L &&
                        (request.entry?.id == 0L || request.entry?.id == entry.id)
                } ?: AutocorrectUserDictionaryPage(AutocorrectUserDictionaryStatus.INVALID)
            }
            AutocorrectUserDictionaryOperation.DELETE -> {
                takeIf { entries.isEmpty() && nextAfterId == null }
                    ?: AutocorrectUserDictionaryPage(AutocorrectUserDictionaryStatus.INVALID)
            }
        }
    }

    private fun AutocorrectUserDictionaryPage.toMutationResult() =
        AutocorrectUserDictionaryMutationResult(status, entries.firstOrNull())
}

private fun Messenger?.sendSafely(what: Int, data: android.os.Bundle) {
    if (this == null) return
    try {
        send(Message.obtain(null, what).apply { this.data = data })
    } catch (_: RemoteException) {
        // The host disappeared; Android will shortly unbind and destroy this service.
    }
}
