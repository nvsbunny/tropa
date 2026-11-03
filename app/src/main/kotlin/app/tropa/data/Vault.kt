package app.tropa.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Шифрование файла состояния: в нём ссылки подписок и ключи серверов.
 * Ключ AES-256 живёт в AndroidKeyStore и из телефона не извлекается, поэтому файл,
 * скопированный с телефона (root, отладка, резервная копия), без самой Тропы не прочесть.
 */
object Vault {
    private const val ALIAS = "tropa-state"
    /** Метка зашифрованного файла. Старый файл без неё — обычный JSON, его читаем как есть. */
    private const val PREFIX = "TROPA1:"

    fun isSealed(text: String) = text.startsWith(PREFIX)

    fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val body = c.iv + c.doFinal(plain.toByteArray())
        return PREFIX + Base64.getEncoder().encodeToString(body)
    }

    fun open(text: String): String {
        val body = Base64.getDecoder().decode(text.removePrefix(PREFIX))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, body, 0, 12))
        return String(c.doFinal(body, 12, body.size - 12))
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
