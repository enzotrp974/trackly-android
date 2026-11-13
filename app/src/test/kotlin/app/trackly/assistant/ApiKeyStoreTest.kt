package app.trackly.assistant

import app.trackly.assistant.key.ApiKeyStatus
import app.trackly.assistant.key.EncryptedApiKeyStore
import app.trackly.assistant.key.EncryptedBlob
import app.trackly.assistant.key.SecretCipher
import app.trackly.assistant.key.validateApiKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stockage chiffré de la clé OpenAI, avec un chiffrement AES/GCM logiciel à la place de
 * l'Android Keystore (absent de la JVM). Les pannes du Keystore sont simulées.
 */
class ApiKeyStoreTest {
    @get:Rule val folder = TemporaryFolder()

    /** AES/GCM du JDK, clé en mémoire. Réservé aux tests. */
    private class SoftwareCipher : SecretCipher {
        var key: SecretKey? = null
        var failure: Exception? = null

        override fun encrypt(plain: ByteArray): EncryptedBlob {
            val k = key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
            return EncryptedBlob(cipher.iv, cipher.doFinal(plain))
        }

        override fun decrypt(blob: EncryptedBlob): ByteArray {
            failure?.let { throw it }
            val k = key ?: throw UnrecoverableKeyException("clé absente")
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, blob.iv)) }
                .doFinal(blob.ciphertext)
        }

        override fun deleteKey() {
            key = null
        }
    }

    private val secret = "sk-proj-cle-de-test-ABCD1234wxyz" // gitleaks:allow (fausse clé de test)
    private val cipher = SoftwareCipher()
    private fun file() = folder.root.resolve("no_backup/${EncryptedApiKeyStore.FILE_NAME}")
    private fun store() = EncryptedApiKeyStore(file(), cipher, Dispatchers.Unconfined)

    @Test
    fun `aller-retour - la cle est relue, et absente du fichier en clair`() = runBlocking {
        val store = store()
        store.save(secret)

        assertEquals(secret, store().read())
        assertEquals(ApiKeyStatus.Present("wxyz"), store.status.value)
        val bytes = file().readBytes()
        assertEquals(EncryptedApiKeyStore.FORMAT_VERSION, bytes[0])
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("sk-proj"))
    }

    @Test
    fun `sans fichier - pas de cle`() = runBlocking {
        val store = store()
        assertNull(store.read())
        assertEquals(ApiKeyStatus.Missing, store.status.value)
    }

    @Test
    fun `cle du Keystore perdue - la cle est effacee et redemandee, sans planter`() = runBlocking {
        store().save(secret)
        cipher.key = null // appareil restauré, Keystore réinitialisé

        val store = store()
        assertNull(store.read())
        assertEquals(ApiKeyStatus.Unreadable, store.status.value)
        assertFalse(file().exists())
    }

    @Test
    fun `texte chiffre altere - redemande propre`() = runBlocking {
        store().save(secret)
        cipher.failure = AEADBadTagException("tag invalide")

        val store = store()
        assertNull(store.read())
        assertEquals(ApiKeyStatus.Unreadable, store.status.value)
        assertNull(cipher.key)
    }

    @Test
    fun `fichier d un format inconnu - redemande propre`() = runBlocking {
        file().parentFile!!.mkdirs()
        file().writeBytes(byteArrayOf(9, 1, 2, 3))
        val store = store()
        assertNull(store.read())
        assertEquals(ApiKeyStatus.Unreadable, store.status.value)
    }

    @Test
    fun `supprimer la cle`() = runBlocking {
        val store = store()
        store.save(secret)
        store.clear()
        assertNull(store.read())
        assertEquals(ApiKeyStatus.Missing, store.status.value)
        assertFalse(file().exists())
    }

    @Test
    fun `forme de la cle saisie`() {
        assertNotNull(validateApiKey(""))
        assertNotNull(validateApiKey("sk-abc"))
        assertNotNull(validateApiKey("pas-une-cle-openai-du-tout"))
        assertNotNull(validateApiKey("sk-proj-avec un espace-1234567890"))
        assertNull(validateApiKey(secret))
        assertTrue(validateApiKey("sk-abc")!!.isNotBlank())
    }
}
