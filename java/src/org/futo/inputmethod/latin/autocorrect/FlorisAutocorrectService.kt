/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.florisboard.autocorrect.api.AutocorrectAcceptanceKind
import org.florisboard.autocorrect.api.AutocorrectPluginDocument
import org.florisboard.autocorrect.api.AutocorrectPluginService
import org.florisboard.autocorrect.api.AutocorrectPluginUi
import org.florisboard.autocorrect.api.AutocorrectRequest
import org.florisboard.autocorrect.api.AutocorrectSession
import org.florisboard.autocorrect.api.AutocorrectSuggestionResult
import org.florisboard.autocorrect.api.AutocorrectTextEvent
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.DataStoreHelper
import org.futo.inputmethod.latin.uix.forceUnlockDatastore
import java.security.MessageDigest

class FlorisAutocorrectService : AutocorrectPluginService() {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: FutoAutocorrectEngine
    private lateinit var hostedSettings: FutoHostedSettings
    private val authorizedHosts = mutableMapOf<Set<String>, Boolean>()

    override fun onCreate() {
        super.onCreate()
        val providerContext = applicationContext
        forceUnlockDatastore(providerContext)
        DataStoreHelper.init(providerContext)
        Settings.init(providerContext)
        engine = FutoAutocorrectEngine(providerContext, engineScope)
        hostedSettings = FutoHostedSettings(providerContext, engine)
    }

    override fun onServiceDestroyed() {
        synchronized(authorizedHosts) { authorizedHosts.clear() }
        engine.closeAsync()
        engineScope.cancel()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        synchronized(authorizedHosts) { authorizedHosts.clear() }
        return super.onUnbind(intent)
    }

    override suspend fun onStartSession(session: AutocorrectSession) {
        engine.startSession(session)
    }

    override fun isHostAuthorized(packageNames: Set<String>): Boolean {
        val cacheKey = packageNames.toSet()
        return synchronized(authorizedHosts) {
            authorizedHosts.getOrPut(cacheKey) {
                cacheKey.sorted().any { hostPackage ->
                    when {
                        hostPackage in ALLOWED_FLORISBOARD_PACKAGES ->
                            isPairedKnownHost(hostPackage)
                        hostPackage.startsWith("$FLORISBOARD_PACKAGE.") ->
                            isSignedWithProvider(hostPackage)
                        else -> false
                    }
                }
            }
        }
    }

    private fun isSignedWithProvider(hostPackage: String): Boolean {
        return packageManager.checkSignatures(hostPackage, packageName) ==
            PackageManager.SIGNATURE_MATCH
    }

    /**
     * The first known FlorisBoard package pairs this provider with its current signer. A later
     * known package must prove that signing lineage before it can read persisted provider data.
     */
    private fun isPairedKnownHost(hostPackage: String): Boolean {
        val identity = signingIdentity(hostPackage) ?: return false
        val preferences = getSharedPreferences(HOST_PAIRING_PREFERENCES, MODE_PRIVATE)
        synchronized(hostPairingLock) {
            if (!preferences.contains(PAIRED_CERTIFICATES)) {
                return persistPairing(identity)
            }
            val pairedCertificates = preferences.getStringSet(PAIRED_CERTIFICATES, null)
                ?.toSet()
                .orEmpty()
            if (pairedCertificates.isEmpty()) return false

            val pairedMultipleSigners = preferences.getBoolean(PAIRED_MULTIPLE_SIGNERS, false)
            val matches = if (pairedMultipleSigners) {
                identity.hasMultipleSigners &&
                    identity.currentCertificates == pairedCertificates
            } else {
                !identity.hasMultipleSigners &&
                    identity.lineageCertificates.containsAll(pairedCertificates)
            }
            if (matches && !pairedMultipleSigners) {
                val currentCertificates = identity.currentCertificates
                if (currentCertificates != pairedCertificates) {
                    return preferences.edit()
                        .putStringSet(PAIRED_CERTIFICATES, currentCertificates)
                        .commit()
                }
            }
            return matches
        }
    }

    private fun persistPairing(identity: SigningIdentity): Boolean {
        val certificates = identity.currentCertificates
        if (certificates.isEmpty()) return false
        return getSharedPreferences(HOST_PAIRING_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putStringSet(PAIRED_CERTIFICATES, certificates)
            .putBoolean(PAIRED_MULTIPLE_SIGNERS, identity.hasMultipleSigners)
            .commit()
    }

    @Suppress("DEPRECATION")
    private fun signingIdentity(hostPackage: String): SigningIdentity? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val packageInfo = try {
            packageManager.getPackageInfo(hostPackage, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val certificates = packageInfo.signatures
                .orEmpty()
                .mapTo(mutableSetOf()) { it.sha256Digest() }
            return SigningIdentity(
                currentCertificates = certificates,
                lineageCertificates = certificates,
                hasMultipleSigners = certificates.size > 1,
            )
        }

        val signingInfo = packageInfo.signingInfo ?: return null
        val hasMultipleSigners = signingInfo.hasMultipleSigners()
        val currentCertificates = signingInfo.apkContentsSigners
            .orEmpty()
            .mapTo(mutableSetOf()) { it.sha256Digest() }
        val lineageCertificates = if (hasMultipleSigners) {
            currentCertificates
        } else {
            signingInfo.signingCertificateHistory
                .orEmpty()
                .mapTo(mutableSetOf()) { it.sha256Digest() }
        }
        return SigningIdentity(
            currentCertificates = currentCertificates,
            lineageCertificates = lineageCertificates,
            hasMultipleSigners = hasMultipleSigners,
        ).takeIf {
            it.currentCertificates.isNotEmpty() && it.lineageCertificates.isNotEmpty()
        }
    }

    private fun Signature.sha256Digest(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private data class SigningIdentity(
        val currentCertificates: Set<String>,
        val lineageCertificates: Set<String>,
        val hasMultipleSigners: Boolean,
    )

    override suspend fun onSuggestResult(
        request: AutocorrectRequest,
    ): AutocorrectSuggestionResult = engine.suggest(request)

    override suspend fun onSuggestionAccepted(
        sessionId: Long,
        candidateId: String,
        acceptanceKind: AutocorrectAcceptanceKind,
    ) {
        engine.accepted(sessionId, candidateId, acceptanceKind)
    }

    override suspend fun onSuggestionReverted(sessionId: Long, candidateId: String) {
        engine.reverted(sessionId, candidateId)
    }

    override suspend fun onRemoveSuggestion(sessionId: Long, candidateId: String): Boolean {
        return engine.remove(sessionId, candidateId)
    }

    override suspend fun onTextEvent(event: AutocorrectTextEvent) {
        engine.textEvent(event)
    }

    override suspend fun onFinishSession(sessionId: Long) {
        engine.finishSession(sessionId)
    }

    override suspend fun onGetPluginUi(languageTags: List<String>): AutocorrectPluginUi {
        return hostedSettings.ui(languageTags)
    }

    override suspend fun onSetPluginUiValue(itemId: String, value: String): Boolean {
        return hostedSettings.setValue(itemId, value)
    }

    override suspend fun onInvokePluginUiAction(itemId: String): Boolean {
        return hostedSettings.invoke(itemId)
    }

    override suspend fun onPluginUiDocument(document: AutocorrectPluginDocument): Boolean {
        return hostedSettings.document(document)
    }

    private companion object {
        const val FLORISBOARD_PACKAGE = "dev.patrickgold.florisboard"
        const val HOST_PAIRING_PREFERENCES = "floris_autocorrect_host_pairing"
        const val PAIRED_CERTIFICATES = "paired_certificates"
        const val PAIRED_MULTIPLE_SIGNERS = "paired_multiple_signers"
        val hostPairingLock = Any()
        val ALLOWED_FLORISBOARD_PACKAGES = setOf(
            FLORISBOARD_PACKAGE,
            "$FLORISBOARD_PACKAGE.beta",
            "$FLORISBOARD_PACKAGE.debug",
            "$FLORISBOARD_PACKAGE.bench",
        )
    }
}
