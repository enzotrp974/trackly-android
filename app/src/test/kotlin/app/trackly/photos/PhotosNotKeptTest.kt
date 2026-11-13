package app.trackly.photos

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.chat.ChatHistory
import app.trackly.assistant.chat.ChatUiState
import app.trackly.assistant.chat.ChatViewModel
import app.trackly.assistant.chat.RoomChatHistory
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.storage.db.TracklyDatabase
import app.trackly.testing.ModelReplies
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Photos jamais conservées : après l'envoi, aucun fichier image ne reste sur le téléphone et
 * aucune donnée d'image n'est dans la base ; l'historique du chat garde seulement « 1 photo envoyée ».
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotosNotKeptTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, TracklyDatabase::class.java).build()
    private val temp = CameraTempFiles(context)

    @Before
    fun main() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun close() {
        Dispatchers.resetMain()
        db.close()
        temp.purge()
    }

    /** Photo « prise » : un vrai JPEG écrit dans le fichier temporaire que l'appareil photo recevrait. */
    private fun takePhoto(): File {
        val file = temp.createFile()
        val bitmap = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(200, 180, 150))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return file
    }

    /** Tout le texte de la base : chaque colonne de chaque table. */
    private fun everythingInDatabase(): String {
        val sql = db.openHelper.readableDatabase
        val out = StringBuilder()
        sql.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'").use { tables ->
            while (tables.moveToNext()) {
                val table = tables.getString(0)
                sql.query("SELECT * FROM `$table`").use { rows ->
                    while (rows.moveToNext()) {
                        for (i in 0 until rows.columnCount) {
                            out.append(if (rows.getType(i) == Cursor.FIELD_TYPE_BLOB) "[blob ${rows.getBlob(i).size} octets] " else "${rows.getString(i)} ")
                        }
                        out.append('\n')
                    }
                }
            }
        }
        return out.toString()
    }

    private fun filesOf(vararg roots: File?): List<File> = roots.filterNotNull().flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }

    @Test
    fun `apres l envoi, aucun fichier image et aucune donnee d image en base, seulement un repere texte`() = runBlocking<Unit> {
        val engine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10"))
        val transport = ScriptedTransport(
            ModelReplies.call(
                "record_consumptions",
                """{"items":[{"product":null,"food":{"label":"Plat d'assiette","quantity":null,"unit":null,"kcal":600,"protein_g":null,"carbs_g":null,"fat_g":null,"fiber_g":null,"assumptions":[],"confidence":"low"},"recipe_id":null,"moment":"midi","day":null}],"question":"Il y avait quoi dedans ?"}""",
            ),
            ModelReplies.text("J'ai compté une assiette moyenne."),
        )
        val history: ChatHistory = RoomChatHistory(db.chat())
        val model = ChatViewModel(AssistantServices.inMemory(engine, transport, InMemoryApiKeyStore("sk-test-cle-factice-0000000000"), history))
        val collector = launch(Dispatchers.Unconfined) { model.state.collect { } }

        // Appareil photo : le fichier temporaire est lu puis supprimé tout de suite.
        val captured = takePhoto()
        val photo = PhotoPreparer(context.contentResolver, Dispatchers.Unconfined).prepare(Uri.fromFile(captured), deleteAfterRead = captured)
        assertFalse("le fichier de l'appareil photo doit être supprimé après lecture", captured.exists())

        // Photo seule, sans texte.
        model.addPhotos(listOf(photo))
        assertEquals(1, withTimeout(10_000) { model.state.first { it.photos.isNotEmpty() } }.photos.size)
        model.send()
        val state: ChatUiState = withTimeout(20_000) { model.state.first { it.messages.size >= 2 && !it.thinking } }
        collector.cancel()

        // La photo est bien partie vers le modèle (sinon le test ne prouverait rien).
        val sent = transport.requests[0].toString()
        assertTrue(sent.contains("\"type\":\"input_image\""))
        assertTrue(sent.contains("data:image/jpeg;base64,"))
        // Les photos jointes sont vidées de l'écran dès l'envoi.
        assertTrue(state.photos.isEmpty())

        // Aucune donnée d'image en base ; le repère texte seul reste dans l'historique.
        val database = everythingInDatabase()
        assertFalse(database.contains("data:image"))
        assertFalse(database.contains("base64"))
        assertFalse(database.contains("/9j/")) // début d'un JPEG en base64
        assertTrue(database.contains("[1 photo envoyée]"))
        val user = state.messages.first()
        assertEquals("[1 photo envoyée]", user.text)
        assertEquals(1, user.photoCount)
        assertEquals("", user.shownText)
        // Le modèle relit le repère dans l'historique des tours suivants.
        assertTrue(user.toHistory().text.contains("1 photo envoyée"))

        // Aucun fichier image sur le téléphone : ni cache, ni fichiers, ni stockage externe privé.
        val leftovers = filesOf(context.cacheDir, context.filesDir, context.noBackupFilesDir, context.externalCacheDir, context.getExternalFilesDir(null))
        val images = leftovers.filter { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic", "gif") }
        assertTrue("fichiers image restants : $images", images.isEmpty())
        assertTrue(File(context.cacheDir, CameraTempFiles.DIR_NAME).listFiles().orEmpty().isEmpty())
        // Ni octets de JPEG dans un autre fichier (en-tête FF D8 FF).
        val jpegByContent = leftovers.filter { f -> f.length() > 3 && f.inputStream().use { s -> val h = ByteArray(3); s.read(h) == 3 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() && h[2] == 0xFF.toByte() } }
        assertTrue("fichiers au contenu JPEG : $jpegByContent", jpegByContent.isEmpty())
    }

    @Test
    fun `l appareil photo annule laisse un fichier vide supprime par le balayage`() {
        val file = temp.createFile()
        assertTrue(file.exists())
        temp.purge()
        assertFalse(file.exists())
    }

    @Test
    fun `au plus six photos par message, les autres sont refusees avec un message`() {
        val engine = SampleData.emptyEngine()
        val model = ChatViewModel(AssistantServices.inMemory(engine))
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val collector = scope.launch { model.state.collect { } }
        fun fake(i: Int) = PreparedPhoto("p$i", app.trackly.assistant.openai.ImageInput("data:image/jpeg;base64,AAAA"), null, 3)

        model.addPhotos((1..4).map(::fake))
        model.addPhotos((5..8).map(::fake))

        val full = runBlocking { withTimeout(10_000) { model.state.first { it.photos.size == PhotoLimits.MAX_PHOTOS && it.notice != null } } }
        assertEquals("Seules 6 photos peuvent être jointes à un message.", full.notice)
        model.removePhoto(full.photos.first())
        assertEquals(PhotoLimits.MAX_PHOTOS - 1, runBlocking { withTimeout(10_000) { model.state.first { it.photos.size == PhotoLimits.MAX_PHOTOS - 1 } } }.photos.size)
        collector.cancel()
    }
}
