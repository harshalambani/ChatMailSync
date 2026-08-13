package com.chatmailsync.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore-backed at-rest encryption for the one secret this app ever
 * needs to hold on the Android side: an IMAP app password. This is the Android
 * half of a guarantee both platforms make — the desktop build encrypts the same
 * secret with Windows DPAPI (src/secret_store.py, called from
 * gui_worker._save_imap_credentials) over an NTFS-ACL-hardened auth/ directory.
 * Android has neither of those primitives, and app-private storage alone is not
 * considered sufficient for a live credential (a rooted device or a
 * backup-extraction tool can read another app's private files).
 * AndroidKeyStore gives a hardware/TEE-backed key that never itself leaves
 * secure storage, so encrypting with it is the closest Android equivalent to
 * what DPAPI plus the ACL give on Windows. src/config.py's own comments already anticipate
 * per-OS hardening layered on a shared file format — this is that layer for
 * Android, just backed by SharedPreferences instead of a file.
 *
 * Ciphertext is stored as "<base64 iv>:<base64 ciphertext>" in a plain
 * SharedPreferences file. The IV is regenerated per encryption (GCM requires
 * a fresh IV per key use) and must travel alongside the ciphertext to decrypt
 * it later, but is not itself sensitive.
 */
object SecretStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    // Both names renamed off the pre-rename "wagmail" spelling on 2026-08-08.
    //
    // KEY_ALIAS: getOrCreateKey() finds nothing under the new alias and
    // generates a fresh key, so any password saved under the old one fails its
    // GCM tag check on the next read. That is recoverable rather than fatal
    // because getSecret() already treats undecryptable ciphertext as "no
    // password saved" — it catches, clears the dead blob and returns null (see
    // its comment below, written for the Keystore-wiped and
    // restored-to-another-device cases; an alias rename lands on the same
    // path). The old key itself stays in AndroidKeyStore, orphaned, until the
    // app is uninstalled or its storage cleared.
    //
    // PREFS_NAME: the same file AppPrefs declares independently — keep the two
    // in step, and see the longer note there on what this rename would cost
    // once the app is on the Galaxy Store.
    private const val KEY_ALIAS = "chatmail_imap_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val PREFS_NAME = "chatmail_prefs"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        // No setKeySize() call, so this is AES-128, AndroidKeyStore's default —
        // deliberate, not an oversight. 128-bit AES is unbroken and nothing in
        // the threat model is helped by 256: the attacks that reach this secret
        // (root executing as our UID, asking the Keystore to decrypt for us) go
        // around the cipher rather than through it. Raising it is also not free
        // — a key regenerated at a different size cannot decrypt anything
        // already saved, so every existing user would silently hit the
        // "password could not be decrypted" path and have to re-enter it.
        // Documented in Chat-Mail-Sync-Password-Storage.docx §3; if this ever
        // changes, that comparison table changes with it.
        //
        // No user-authentication requirement: this password gates access to
        // one email account, not the device itself, and the background
        // SyncWorker/WatchFolderWorker must be able to read it unattended
        // (e.g. from a periodic watched-folder auto-sync) without a biometric
        // prompt blocking a headless run.
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /** Encrypts [value] with the Keystore key and stores it under [key] in
     * `chatmail_prefs`. Overwrites any previous value stored under the same
     * key. */
    fun putSecret(context: Context, key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val encoded = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        prefs(context).edit().putString(key, encoded).apply()
    }

    /** Returns the decrypted secret stored under [key], or null if nothing is
     * stored, the stored blob is malformed, or decryption fails (e.g. the
     * Keystore key was wiped by a factory-reset-protection event or app
     * data was restored onto a different device, where the key material
     * cannot travel). A decrypt failure also clears the now-undecryptable
     * blob so the caller cleanly falls back to "no password saved" rather
     * than retrying against a permanently broken value. */
    fun getSecret(context: Context, key: String): String? {
        val encoded = prefs(context).getString(key, null) ?: return null
        val parts = encoded.split(":", limit = 2)
        if (parts.size != 2) {
            clearSecret(context, key)
            return null
        }
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            clearSecret(context, key)
            null
        }
    }

    fun clearSecret(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }
}
