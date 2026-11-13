package app.trackly.assistant.key

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.GeneralSecurityException
import kotlin.coroutines.CoroutineContext

/** État de la clé OpenAI, tel qu'affiché dans les réglages (jamais la clé en clair). */
sealed interface ApiKeyStatus {
    /** Lecture pas encore faite. */
    data object Unknown : ApiKeyStatus

    data object Missing : ApiKeyStatus

    /** Clé enregistrée ; seuls ses 4 derniers caractères sont montrés. */
    data class Present(val lastFour: String) : ApiKeyStatus

    /**
     * La clé enregistrée n'a pas pu être déchiffrée (téléphone restauré, Keystore réinitialisé…) :
     * elle a été effacée et doit être saisie à nouveau.
     */
    data object Unreadable : ApiKeyStatus
}

/** Clé OpenAI de l'utilisateur, stockée chiffrée sur le téléphone. */
interface ApiKeyStore {
    val status: StateFlow<ApiKeyStatus>

    /** Clé en clair pour une requête, ou `null` (absente ou illisible). Met [status] à jour. */
    suspend fun read(): String?

    /** Relit l'état (écran des réglages) sans exposer la clé. */
    suspend fun refresh() {
        read()
    }

    suspend fun save(key: String)

    /**
     * Enregistre la clé donnée en caractères (lecture d'un QR code). L'appelant l'efface ensuite
     * (`fill`). Par défaut passe par [save] ; le stockage chiffré l'encode sans créer de `String`.
     */
    suspend fun save(key: CharArray) = save(String(key))

    suspend fun clear()
}

/** Vérifie la forme d'une clé saisie ; renvoie un message d'erreur, ou `null` si elle convient. */
fun validateApiKey(key: String): String? = validateApiKey(key.toCharArray())

/**
 * Même vérification sur des caractères (QR code lu par l'appareil photo) : aucune copie en `String`,
 * le message d'erreur ne contient jamais la clé.
 */
fun validateApiKey(key: CharArray): String? = when {
    key.all { it.isWhitespace() } -> "Colle ta clé OpenAI."
    key.any { it.isWhitespace() } -> "La clé ne doit pas contenir d'espace."
    key.size < 20 -> "Cette clé est trop courte."
    key.size > MAX_API_KEY_LENGTH -> "Cette valeur est trop longue pour une clé OpenAI."
    !(key[0] == 's' && key[1] == 'k' && key[2] == '-') -> "Une clé OpenAI commence par « sk- »."
    else -> null
}

/** Longueur maximale acceptée : les clés de projet d'OpenAI font environ 160 caractères. */
const val MAX_API_KEY_LENGTH = 512

/** Texte chiffré et vecteur d'initialisation (AES/GCM). */
class EncryptedBlob(val iv: ByteArray, val ciphertext: ByteArray)

/** Chiffrement de la clé. Réel : Android Keystore ([KeystoreSecretCipher]) ; tests : logiciel. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): EncryptedBlob

    /** Lève [GeneralSecurityException] si la clé de chiffrement manque ou ne correspond pas. */
    fun decrypt(blob: EncryptedBlob): ByteArray

    fun deleteKey()
}

/**
 * Clé chiffrée dans un fichier de `noBackupFilesDir` : jamais sauvegardée ni transférée
 * (la clé de chiffrement, elle, ne quitte pas le Keystore du téléphone). Format versionné :
 * `[1 octet version = 1][1 octet taille IV][IV][texte chiffré + tag]`. Écriture atomique.
 *
 * Si le déchiffrement échoue, le fichier et la clé du Keystore sont effacés et l'état passe
 * à [ApiKeyStatus.Unreadable] : l'app redemande la clé, sans planter.
 */
class EncryptedApiKeyStore(
    private val file: File,
    private val cipher: SecretCipher,
    private val io: CoroutineContext = Dispatchers.IO,
) : ApiKeyStore {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<ApiKeyStatus>(ApiKeyStatus.Unknown)
    override val status: StateFlow<ApiKeyStatus> = _status.asStateFlow()

    override suspend fun read(): String? = mutex.withLock {
        withContext(io) { readLocked() }
    }

    override suspend fun save(key: String) = mutex.withLock {
        val clean = key.trim()
        withContext(io) {
            val plain = clean.toByteArray(Charsets.UTF_8)
            val blob = try {
                cipher.encrypt(plain)
            } finally {
                plain.fill(0)
            }
            writeAtomically(encode(blob))
        }
        _status.value = ApiKeyStatus.Present(clean.takeLast(4))
    }

    override suspend fun save(key: CharArray) = mutex.withLock {
        // Espaces de début et de fin retirés sans créer de String ; la copie en octets est effacée dès chiffrée.
        var start = 0
        var end = key.size
        while (start < end && key[start].isWhitespace()) start++
        while (end > start && key[end - 1].isWhitespace()) end--
        val lastFour = String(key, maxOf(start, end - 4), end - maxOf(start, end - 4))
        withContext(io) {
            val buffer = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(key, start, end - start))
            val plain = ByteArray(buffer.remaining()).also { buffer.get(it) }
            if (buffer.hasArray()) buffer.array().fill(0)
            val blob = try {
                cipher.encrypt(plain)
            } finally {
                plain.fill(0)
            }
            writeAtomically(encode(blob))
        }
        _status.value = ApiKeyStatus.Present(lastFour)
    }

    override suspend fun clear() = mutex.withLock {
        withContext(io) {
            file.delete()
            runCatching { cipher.deleteKey() }
        }
        _status.value = ApiKeyStatus.Missing
    }

    private fun readLocked(): String? {
        if (!file.isFile) {
            if (_status.value != ApiKeyStatus.Unreadable) _status.value = ApiKeyStatus.Missing
            return null
        }
        return try {
            val plain = cipher.decrypt(decode(file.readBytes()))
            try {
                String(plain, Charsets.UTF_8).also { _status.value = ApiKeyStatus.Present(it.takeLast(4)) }
            } finally {
                plain.fill(0)
            }
        } catch (e: GeneralSecurityException) {
            forget()
        } catch (e: IllegalArgumentException) {
            forget() // fichier altéré ou d'un format inconnu
        }
    }

    private fun forget(): String? {
        file.delete()
        runCatching { cipher.deleteKey() }
        _status.value = ApiKeyStatus.Unreadable
        return null
    }

    private fun writeAtomically(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Impossible d'enregistrer la clé" }
        }
    }

    companion object {
        const val FORMAT_VERSION: Byte = 1
        const val FILE_NAME = "openai-key.bin"

        fun encode(blob: EncryptedBlob): ByteArray {
            require(blob.iv.size in 1..255) { "IV invalide" }
            return byteArrayOf(FORMAT_VERSION, blob.iv.size.toByte()) + blob.iv + blob.ciphertext
        }

        fun decode(bytes: ByteArray): EncryptedBlob {
            require(bytes.size > 2 && bytes[0] == FORMAT_VERSION) { "Format de clé inconnu" }
            val ivSize = bytes[1].toInt() and 0xFF
            require(ivSize > 0 && bytes.size > 2 + ivSize) { "Clé tronquée" }
            return EncryptedBlob(bytes.copyOfRange(2, 2 + ivSize), bytes.copyOfRange(2 + ivSize, bytes.size))
        }
    }
}

/** Clé en mémoire, pour les tests et les aperçus. */
class InMemoryApiKeyStore(initial: String? = null) : ApiKeyStore {
    private var key: String? = initial
    private val _status = MutableStateFlow(statusOf(initial))
    override val status: StateFlow<ApiKeyStatus> = _status.asStateFlow()

    override suspend fun read(): String? = key

    override suspend fun save(key: String) {
        this.key = key.trim()
        _status.value = statusOf(this.key)
    }

    override suspend fun clear() {
        key = null
        _status.value = ApiKeyStatus.Missing
    }

    private fun statusOf(key: String?) = key?.let { ApiKeyStatus.Present(it.takeLast(4)) } ?: ApiKeyStatus.Missing
}
