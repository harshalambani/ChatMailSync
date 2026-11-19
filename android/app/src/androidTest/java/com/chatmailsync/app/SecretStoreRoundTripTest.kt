package com.chatmailsync.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ENC: the real Keystore round trip, on a device or emulator. The stored preference value must not
 * contain the password, must decrypt back to it, and must be gone after clearSecret.
 */
@RunWith(AndroidJUnit4::class)
class SecretStoreRoundTripTest {

    @Test
    fun theStoredValueIsCiphertextAndRoundTrips() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "enc_roundtrip_test_secret"
        val secret = "pw-FAKE-0000-0000"
        try {
            SecretStore.putSecret(ctx, key, secret)
            val prefs = ctx.getSharedPreferences("chatmailsync_prefs", android.content.Context.MODE_PRIVATE)
            val stored = prefs.getString(key, null)!!
            assertFalse("plaintext must not be stored", stored.contains(secret))
            val everything = prefs.all.values.joinToString("|") { it.toString() }
            assertFalse("no other key holds it", everything.replace(stored, "").contains(secret))
            assertEquals(secret, SecretStore.getSecret(ctx, key))
        } finally {
            SecretStore.clearSecret(ctx, key)
        }
        assertNull(SecretStore.getSecret(ctx, key))
    }
}
