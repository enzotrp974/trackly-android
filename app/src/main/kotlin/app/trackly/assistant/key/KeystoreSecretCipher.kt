package app.trackly.assistant.key

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffrement AES-256/GCM par une clé qui vit dans l'Android Keystore et n'en sort jamais.
 * Pas d'authentification exigée : la clé reste utilisable écran verrouillé (tâches de fond).
 * À appeler hors du thread principal. Testé sur appareil seulement (pas de Keystore en JVM).
 */
class KeystoreSecretCipher(private val alias: String = ALIAS) : SecretCipher {
    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun getOrCreateKey(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        .apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }
        .generateKey()

    override fun encrypt(plain: ByteArray): EncryptedBlob {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey()) // IV aléatoire fourni par le Keystore
        return EncryptedBlob(iv = cipher.iv, ciphertext = cipher.doFinal(plain))
    }

    override fun decrypt(blob: EncryptedBlob): ByteArray {
        val key = existingKey() ?: throw UnrecoverableKeyException("Clé du Keystore absente")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob.iv))
        return cipher.doFinal(blob.ciphertext)
    }

    override fun deleteKey() {
        keyStore().deleteEntry(alias)
    }

    companion object {
        const val ALIAS = "trackly_openai_key_v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
