package com.railspotting.android.signin

import android.app.Activity
import android.os.Build
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.railspotting.android.BuildConfig
import com.railspotting.android.RailSpottingApp
import com.railspotting.android.Urls
import com.railspotting.android.core.Native
import com.railspotting.android.store.Linked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** GET /mobile/hello's answer. */
data class Hello(val clientId: String, val nonce: String, val expiresIn: Int, val termsUrl: String, val privacyUrl: String,
                 val at: Long = System.currentTimeMillis()) {
    /** The nonce is single use and expires (5 minutes); leave a margin for the Google sheet. */
    fun fresh(now: Long = System.currentTimeMillis()) = now - at < (expiresIn - 60).coerceAtLeast(0) * 1000L

    companion object {
        fun parse(o: JSONObject): Hello = Hello(
            clientId = o.optString("google_client_id"),
            nonce = o.optString("nonce"),
            expiresIn = o.optInt("nonce_expires_in", 300),
            termsUrl = o.optString("terms_url", "https://railspotting.com/terms"),
            privacyUrl = o.optString("privacy_url", "https://railspotting.com/privacy"),
        )
    }
}

sealed class Outcome {
    data class Done(val linked: Linked, val moved: Boolean) : Outcome()
    data class Failed(val message: String) : Outcome()
    data object Cancelled : Outcome()
}

/**
 * Sign in with Google and link this phone (contract v1.7, 1): hello's nonce goes into the ID
 * token, and the link request is signed with the receiver's own key.
 */
class SignIn(private val app: RailSpottingApp) {
    /** GET /mobile/hello. A 503 means Google sign-in is not set up on the server. */
    suspend fun hello(): Result<Hello> = withContext(Dispatchers.IO) {
        val seed = app.seed()
        val r = try {
            JSONObject(Native.hello(Urls.SERVER, seed))
        } finally {
            seed.fill(0)
        }
        if (r.optBoolean("ok")) Result.success(Hello.parse(r.getJSONObject("data")))
        else Result.failure(Exception(message(r)))
    }

    suspend fun run(activity: Activity, first: Hello?, name: String): Outcome {
        var hello = first?.takeIf { it.fresh() } ?: hello().getOrElse { return Outcome.Failed(it.message ?: "") }
        // one retry with a new key when the headend says this key belongs to another receiver
        for (attempt in 0..1) {
            val token = when (val t = googleIdToken(activity, hello)) {
                is Token.Ok -> t.idToken
                is Token.Cancelled -> return Outcome.Cancelled
                is Token.Failed -> return Outcome.Failed(t.message)
            }
            val r = link(token, hello.nonce, name)
            if (r.optBoolean("ok")) {
                val linked = Linked.fromResult(r.getJSONObject("data")) ?: return Outcome.Failed("The server's answer had no device ID.")
                return Outcome.Done(linked, moved = r.optInt("status") == 200)
            }
            if (r.optString("error") == "key_in_use" && attempt == 0) {
                app.newSeed().fill(0)
                hello = hello().getOrElse { return Outcome.Failed(it.message ?: "") }
                continue
            }
            return Outcome.Failed(message(r))
        }
        return Outcome.Failed("Sign-in did not complete. Try again.")
    }

    private sealed class Token {
        data class Ok(val idToken: String) : Token()
        data object Cancelled : Token()
        data class Failed(val message: String) : Token()
    }

    private suspend fun googleIdToken(activity: Activity, hello: Hello): Token {
        if (hello.clientId.isEmpty()) return Token.Failed("Google sign-in is not set up on the server yet.")
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(hello.clientId) // the headend's web client, so the token is for it
            .setNonce(hello.nonce)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val req = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val cred = CredentialManager.create(activity).getCredential(activity, req).credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                Token.Ok(GoogleIdTokenCredential.createFrom(cred.data).idToken)
            } else {
                Token.Failed("That sign-in option is not supported. Choose a Google account.")
            }
        } catch (_: GetCredentialCancellationException) {
            Token.Cancelled
        } catch (_: NoCredentialException) {
            Token.Failed("Google offered no account for RailSpotting. Check that a Google account is on this phone " +
                "(Settings, Passwords and accounts). If it is, this build of the app is not registered with Google yet.")
        } catch (e: GetCredentialException) {
            Token.Failed("Google sign-in failed: ${e.message ?: e.type}")
        } catch (_: GoogleIdTokenParsingException) {
            Token.Failed("Google sign-in gave an answer the app could not read.")
        }
    }

    private suspend fun link(idToken: String, nonce: String, name: String): JSONObject = withContext(Dispatchers.IO) {
        val seed = app.seed()
        try {
            JSONObject(Native.link(Urls.SERVER, seed, idToken, nonce, name, Build.MODEL ?: "",
                Build.VERSION.RELEASE ?: "", BuildConfig.VERSION_NAME))
        } finally {
            seed.fill(0)
        }
    }

    companion object {
        /** What to tell the owner for an error from hello or link (contract v1.7, 1.4). */
        fun message(r: JSONObject): String {
            val status = if (r.isNull("status")) 0 else r.optInt("status")
            return when (r.optString("error")) {
                "invalid_token" -> "Google sign-in did not check out. Try again."
                "account_disabled" -> "This RailSpotting account is disabled."
                "email_taken" -> "Another Google account already uses this email on RailSpotting. Ask an admin for help."
                "key_in_use" -> "This phone's key belongs to another receiver. Try again."
                "rate_limited" -> "Too many tries. Wait " + waitText(r) + " and try again."
                "unavailable" -> "Google sign-in is not set up on the server yet."
                "clock_skew" -> "This phone's clock is too far off. Set the date and time to automatic, then try again."
                "network" -> "No connection to RailSpotting. Check the phone's internet connection."
                else -> r.optString("message").ifEmpty { "Sign-in failed (" + (if (status > 0) "$status " else "") + r.optString("error") + ")." }
            }
        }

        private fun waitText(r: JSONObject): String {
            val s = if (r.isNull("retry_after")) 60.0 else r.optDouble("retry_after", 60.0)
            return if (s < 90) "${s.toLong().coerceAtLeast(1)} seconds" else "${(s / 60).toLong()} minutes"
        }
    }
}
