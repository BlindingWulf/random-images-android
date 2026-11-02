package com.blindingwulf.randomimages

import android.annotation.TargetApi
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/**
 * Credential storage for the e621 API key and the FurAffinity session cookie.
 *
 * Both are bearer secrets, so they are never written in plaintext: each value is
 * sealed with an AES-GCM key tied to the AndroidKeyStore (hardware-backed where
 * the device supports it) and only ciphertext reaches disk. This replaces
 * androidx.security:security-crypto, whose EncryptedSharedPreferences needs
 * API 23 and would break the API 21 floor.
 *
 *  - 23+: the AES key is generated inside the keystore and never leaves it.
 *  - 21-22: the keystore cannot hold AES keys, so an RSA keypair lives there and
 *    the AES key is stored wrapped by it (the documented pre-M pattern).
 *  - If the keystore is unavailable the store still works, but values fall back
 *    to app-private plaintext; [encrypted] reports that and the UI shows it
 *    instead of silently downgrading.
 */
class SecureStore(context: Context) {

    private val appContext = context.applicationContext
    private val plain = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val seal: ValueSeal by lazy { createSeal() }

    data class Credentials(
        val e621Username: String = "",
        val e621ApiKey: String = "",
        val faUsername: String = "",
        val faCookie: String = "",
    )

    /** False only when the keystore failed and values are plaintext. */
    val encrypted: Boolean get() = seal.usable

    /**
     * Decrypted values are memoised because every unseal is a keystore round
     * trip: one AES-GCM open on 23+, and on 21-22 an RSA decrypt inside the
     * keystore per field. One slideshow fetch reads the store twice (once per
     * provider), so without this an Android 5 handset pays four private-key
     * operations per image, on a chip where each can take a tenth of a second.
     */
    private var cached: Credentials? = null

    @Synchronized
    fun load(): Credentials {
        cached?.let { return it }
        val creds = Credentials(
            e621Username = read(F_E621_USER),
            e621ApiKey = read(F_E621_KEY),
            faUsername = read(F_FA_USER),
            faCookie = read(F_FA_COOKIE),
        )
        cached = creds
        return creds
    }

    /** Empty fields are removed rather than stored empty, as creds.save does. */
    @Synchronized
    fun save(creds: Credentials) {
        val editor = plain.edit()
        listOf(
            F_E621_USER to creds.e621Username.trim(),
            F_E621_KEY to creds.e621ApiKey.trim(),
            F_FA_USER to creds.faUsername.trim(),
            F_FA_COOKIE to creds.faCookie.trim(),
        ).forEach { (key, value) ->
            if (value.isEmpty()) editor.remove(key) else editor.putString(key, seal.seal(value))
        }
        editor.apply()
        cached = null
    }

    @Synchronized
    fun delete() {
        plain.edit()
            .remove(F_E621_USER).remove(F_E621_KEY)
            .remove(F_FA_USER).remove(F_FA_COOKIE)
            .apply()
        cached = null
    }

    private fun read(key: String): String {
        val stored = plain.getString(key, "").orEmpty()
        if (stored.isEmpty()) return ""
        return seal.open(stored)
    }

    private fun createSeal(): ValueSeal = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            KeystoreAesSeal()
        } else {
            RsaWrappedAesSeal(appContext)
        }
    } catch (e: Exception) {
        Log.w(TAG, "AndroidKeyStore unavailable, credentials fall back to plaintext", e)
        PlainSeal()
    }

    companion object {
        private const val TAG = "SecureStore"
        private const val FILE = "riv_credentials"
        internal const val F_E621_USER = "e621_username"
        internal const val F_E621_KEY = "e621_api_key"
        internal const val F_FA_USER = "fa_username"
        internal const val F_FA_COOKIE = "fa_cookie"
    }
}

internal interface ValueSeal {
    val usable: Boolean
    fun seal(plain: String): String
    fun open(stored: String): String
}

internal class PlainSeal : ValueSeal {
    override val usable = false
    override fun seal(plain: String) = plain
    override fun open(stored: String) = stored
}

/** Prefixed so a value written before sealing existed is still readable. */
private const val PREFIX = "v1:"
private const val GCM_TAG_BITS = 128
private const val GCM_IV_BYTES = 12

private fun encodeB64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
private fun decodeB64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

/** API 23+: AES-GCM key generated inside and never exported from the keystore. */
@TargetApi(Build.VERSION_CODES.M)
private class KeystoreAesSeal : ValueSeal {

    override val usable = true

    override fun seal(plain: String): String {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + encodeB64(iv + body)
    }

    override fun open(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val blob = decodeB64(stored.removePrefix(PREFIX))
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(GCM_TAG_BITS, blob.copyOfRange(0, GCM_IV_BYTES)),
                )
            }
            cipher.doFinal(blob.copyOfRange(GCM_IV_BYTES, blob.size)).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt a stored credential", e)
            ""
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS_AES, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS_AES,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return requireNotNull(generator.generateKey())
    }

    private companion object {
        const val TAG = "SecureStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_AES = "riv_credentials_aes"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * API 21/22: the keystore only holds RSA there, so the AES key is wrapped by an
 * AndroidKeyStore RSA public key and only decrypted inside the keystore.
 */
private class RsaWrappedAesSeal(context: Context) : ValueSeal {

    private val ctx = context.applicationContext
    private val keys = ctx.getSharedPreferences(KEYFILE, Context.MODE_PRIVATE)

    override val usable = true

    override fun seal(plain: String): String {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, aesKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + encodeB64(iv + body)
    }

    override fun open(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val blob = decodeB64(stored.removePrefix(PREFIX))
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    aesKey(),
                    GCMParameterSpec(GCM_TAG_BITS, blob.copyOfRange(0, GCM_IV_BYTES)),
                )
            }
            cipher.doFinal(blob.copyOfRange(GCM_IV_BYTES, blob.size)).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt a stored credential", e)
            ""
        }
    }

    private fun aesKey(): SecretKey {
        keys.getString(WRAPPED_KEY, null)?.let { wrapped ->
            return SecretKeySpec(rsa(Cipher.DECRYPT_MODE).doFinal(decodeB64(wrapped)), "AES")
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        keys.edit().putString(WRAPPED_KEY, encodeB64(rsa(Cipher.ENCRYPT_MODE).doFinal(raw))).apply()
        return SecretKeySpec(raw, "AES")
    }

    private fun rsa(mode: Int): Cipher {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(ALIAS_RSA)) {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
            KeyPairGenerator.getInstance("RSA", ANDROID_KEYSTORE).apply {
                initialize(
                    KeyPairGeneratorSpec.Builder(ctx)
                        .setAlias(ALIAS_RSA)
                        .setSubject(X500Principal("CN=Random Images credential key"))
                        .setSerialNumber(BigInteger.ONE)
                        .setStartDate(start.time)
                        .setEndDate(end.time)
                        .build()
                )
            }.generateKeyPair()
        }
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        if (mode == Cipher.ENCRYPT_MODE) {
            cipher.init(mode, requireNotNull(ks.getCertificate(ALIAS_RSA)).publicKey)
        } else {
            val entry = ks.getEntry(ALIAS_RSA, null) as KeyStore.PrivateKeyEntry
            cipher.init(mode, entry.privateKey)
        }
        return cipher
    }

    private companion object {
        const val TAG = "SecureStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_RSA = "riv_credentials_rsa"
        const val WRAPPED_KEY = "wrapped_aes_key"
        const val KEYFILE = "riv_credentials_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
    }
}
