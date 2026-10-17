package com.railspotting.android.integrity

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.model.IntegrityErrorCode
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Play Integrity at link time (proposed contract v1.14). The headend asks Google whether the
 * link comes from a real Android device running this app, and uses the answer to decide how far
 * to trust a new phone. It never blocks the link: builds from source and sideloaded copies keep
 * working, and when there is no token the app sends why.
 */
object Integrity {
    /**
     * The Google Cloud project the headend decodes tokens as (railspotting-com). Needed for apps
     * not installed from Play; it is also the first part of the app's OAuth client IDs.
     */
    const val CLOUD_PROJECT = 1071680891315L
    const val TIMEOUT_MS = 5_000L

    /** A token, or why there is none (an IntegrityErrorCode name or a short reason). */
    data class Outcome(val token: String = "", val error: String = "")

    /**
     * The nonce that binds Google's verdict to this sign-in and this key:
     * base64url(SHA-256(hello nonce + "." + public key)), without padding.
     */
    fun nonce(helloNonce: String, publicKey: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest("$helloNonce.$publicKey".toByteArray(Charsets.UTF_8)))

    /** Ask Google for a token, giving up after [TIMEOUT_MS]. */
    suspend fun request(context: Context, nonce: String): Outcome =
        attempt(nonce, TIMEOUT_MS) { fetch(context.applicationContext, it) }

    /** The request with its timeout and errors turned into an [Outcome]; `fetch` is Google's call. */
    internal suspend fun attempt(nonce: String, timeoutMs: Long, fetch: suspend (String) -> String): Outcome =
        try {
            val token = withTimeout(timeoutMs) { fetch(nonce) }
            if (token.isBlank()) Outcome(error = "EMPTY_TOKEN") else Outcome(token = token)
        } catch (_: TimeoutCancellationException) {
            Outcome(error = "TIMEOUT")
        } catch (e: IntegrityServiceException) {
            Outcome(error = errorName(e.errorCode))
        } catch (_: LinkageError) {
            Outcome(error = "API_NOT_AVAILABLE") // a build without the library
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome(error = e.javaClass.simpleName.ifEmpty { "ERROR" })
        }

    /** IntegrityErrorCode's name for a code, e.g. -1 to API_NOT_AVAILABLE. */
    fun errorName(code: Int): String =
        IntegrityErrorCode::class.java.fields.firstOrNull { f ->
            Modifier.isStatic(f.modifiers) && f.type == Int::class.javaPrimitiveType && f.getInt(null) == code
        }?.name ?: "ERROR_$code"

    private suspend fun fetch(context: Context, nonce: String): String = suspendCancellableCoroutine { cont ->
        val req = IntegrityTokenRequest.builder().setNonce(nonce).setCloudProjectNumber(CLOUD_PROJECT).build()
        IntegrityManagerFactory.create(context).requestIntegrityToken(req)
            .addOnSuccessListener { cont.resume(it.token()) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
