package app.trackly.assistant

import app.trackly.assistant.key.ApiKeyStatus
import app.trackly.assistant.key.EncryptedApiKeyStore
import app.trackly.assistant.key.EncryptedBlob
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.key.SecretCipher
import app.trackly.assistant.key.validateApiKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Enregistrement de la clé lue par QR code (caractères, sans `String`) et forme d'une clé OpenAI. */
class ApiKeyCharsTest {
    @get:Rule val folder = TemporaryFolder()

    private class SoftwareCipher : SecretCipher {
        var key: SecretKey? = null
        val plains = mutableListOf<ByteArray>()

        override fun encrypt(plain: ByteArray): EncryptedBlob {
            plains += plain // gardé pour vérifier qu'il est mis à zéro ensuite
            val k = key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
            return EncryptedBlob(cipher.iv, cipher.doFinal(plain))
        }

        override fun decrypt(blob: EncryptedBlob): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.iv)) }.doFinal(blob.ciphertext)

        override fun deleteKey() {
            key = null
        }
    }

    private val secret = "sk-test-FAKE-0123456789abcdefghij"
    private val cipher = SoftwareCipher()
    private fun file() = folder.root.resolve("no_backup/${EncryptedApiKeyStore.FILE_NAME}")
    private fun store() = EncryptedApiKeyStore(file(), cipher, Dispatchers.Unconfined)

    @Test
    fun `enregistrer des caracteres - la cle est relue, absente du fichier en clair, octets en clair mis a zero`() = runBlocking {
        val store = store()
        store.save(secret.toCharArray())

        assertEquals(secret, store().read())
        assertEquals(ApiKeyStatus.Present("ghij"), store.status.value)
        assertFalse(String(file().readBytes(), Charsets.ISO_8859_1).contains("sk-test"))
        assertEquals(1, cipher.plains.size)
        assertFalse(cipher.plains.single().any { it != 0.toByte() })
    }

    @Test
    fun `les espaces autour de la cle sont retires sans copie en String`() = runBlocking {
        val store = store()
        store.save("  $secret\n".toCharArray())
        assertEquals(secret, store.read())
        assertEquals(ApiKeyStatus.Present("ghij"), store.status.value)
    }

    @Test
    fun `le magasin en memoire accepte aussi les caracteres`() = runBlocking {
        val store = InMemoryApiKeyStore()
        store.save(secret.toCharArray())
        assertEquals(secret, store.read())
    }

    @Test
    fun `forme de la cle - memes regles pour les caracteres et pour le texte`() {
        val cases = listOf("", "   ", "sk-abc", "pas-une-cle-openai-du-tout", "sk-proj-avec un espace-1234567890", "sk-" + "x".repeat(600), secret)
        for (case in cases) assertEquals("règle différente pour « ${case.take(10)} »", validateApiKey(case), validateApiKey(case.toCharArray()))
        assertNull(validateApiKey(secret.toCharArray()))
        assertNotNull(validateApiKey("sk-" + "x".repeat(600)))
        assertNull(validateApiKey("sk-" + "x".repeat(509))) // 512 caractères : la limite
    }

    @Test
    fun `le message d erreur ne contient jamais la valeur lue`() {
        for (bad in listOf("sk-court", "sk-avec un espace-0123456789abcdef", "https://secret.exemple.org/0123456789")) {
            assertFalse(validateApiKey(bad.toCharArray())!!.contains(bad))
        }
    }
}
