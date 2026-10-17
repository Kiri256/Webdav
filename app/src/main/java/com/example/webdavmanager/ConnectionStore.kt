package com.example.webdavmanager

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.connectionDataStore by preferencesDataStore("connection")

internal class ConnectionStore(private val context: Context) {
    private object Keys {
        val id = stringPreferencesKey("id")
        val name = stringPreferencesKey("name")
        val baseUrl = stringPreferencesKey("base_url")
        val username = stringPreferencesKey("username")
        val password = stringPreferencesKey("password")
        val rootPath = stringPreferencesKey("root_path")
    }

    suspend fun load(): WebDavConnection? {
        val prefs = context.connectionDataStore.data.first()
        val baseUrl = prefs[Keys.baseUrl] ?: return null
        val connection = WebDavConnection(
            id = prefs[Keys.id] ?: UUID.randomUUID().toString(),
            name = prefs[Keys.name] ?: "WebDAV",
            baseUrl = baseUrl,
            username = prefs[Keys.username].orEmpty(),
            password = "",
            rootPath = prefs[Keys.rootPath] ?: "/",
        )
        return try {
            connection.copy(password = prefs[Keys.password]?.let { KeyStoreCrypto.decrypt(it) }.orEmpty())
        } catch (e: Exception) {
            throw ConnectionRecoveryException(connection, e)
        }
    }

    suspend fun save(connection: WebDavConnection) {
        context.connectionDataStore.edit { prefs ->
            prefs[Keys.id] = connection.id
            prefs[Keys.name] = connection.name
            prefs[Keys.baseUrl] = connection.baseUrl.trimEnd('/')
            prefs[Keys.username] = connection.username
            prefs[Keys.password] = KeyStoreCrypto.encrypt(connection.password)
            prefs[Keys.rootPath] = normalizePath(connection.rootPath)
        }
    }

    suspend fun clear() {
        context.connectionDataStore.edit { it.clear() }
    }
}

internal class ConnectionRecoveryException(val connection: WebDavConnection, cause: Exception) :
    Exception("Saved password could not be decrypted. Enter the password again.", cause)

internal object KeyStoreCrypto {
    private const val alias = "webdav_connection_secret"
    private const val transform = "AES/GCM/NoPadding"

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(transform)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        val packed = Base64.decode(value, Base64.NO_WRAP)
        val iv = packed.copyOfRange(0, 12)
        val encrypted = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance(transform)
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}


