package com.railspotting.android

import com.google.android.play.core.integrity.model.IntegrityErrorCode
import com.railspotting.android.integrity.Integrity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class IntegrityTest {
    @Test
    fun nonceBindsTheSignInAndTheKey() {
        // base64url(SHA-256("h3lloNonce-xyz.Q1VS...")), no padding; worked out independently
        assertEquals("K8DTqBjJRq02g6d8T14nTEeYSBjStVFaCTqaOEp5M7g",
            Integrity.nonce("h3lloNonce-xyz", "Q1VSVkVfS0VZX0ZPUl9URVNUU19PTkxZXzMyX0JZVEVT"))
        // another key, another nonce
        assertEquals(43, Integrity.nonce("h3lloNonce-xyz", "other").length)
        assert(Integrity.nonce("h3lloNonce-xyz", "other") != Integrity.nonce("h3lloNonce-xyz", "Q1VS"))
    }

    @Test
    fun aTokenIsSent() = runBlocking {
        assertEquals(Integrity.Outcome(token = "tok"), Integrity.attempt("n", 1_000) { "tok" })
    }

    @Test
    fun aTimeoutOrErrorBecomesAReasonAndNeverThrows() = runBlocking {
        assertEquals(Integrity.Outcome(error = "TIMEOUT"),
            Integrity.attempt("n", 50) { delay(10_000); "late" })
        assertEquals(Integrity.Outcome(error = "IllegalStateException"),
            Integrity.attempt("n", 1_000) { throw IllegalStateException("no Play services") })
        assertEquals(Integrity.Outcome(error = "EMPTY_TOKEN"), Integrity.attempt("n", 1_000) { "" })
    }

    @Test
    fun errorCodesByName() {
        assertEquals("API_NOT_AVAILABLE", Integrity.errorName(IntegrityErrorCode.API_NOT_AVAILABLE))
        assertEquals("PLAY_STORE_NOT_FOUND", Integrity.errorName(IntegrityErrorCode.PLAY_STORE_NOT_FOUND))
        assertEquals("ERROR_-12345", Integrity.errorName(-12345))
    }
}
