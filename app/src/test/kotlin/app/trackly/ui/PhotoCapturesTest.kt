package app.trackly.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.cards.CardItem
import app.trackly.assistant.cards.ProductRef
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.cards.SourceLink
import app.trackly.assistant.cards.SourcesCard
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatUiState
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.openai.ImageInput
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.GoalTargets
import app.trackly.photos.PreparedPhoto
import app.trackly.testing.ModelReplies
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import app.trackly.ui.chat.CardTags
import app.trackly.ui.chat.ChatActions
import app.trackly.ui.chat.ChatShell
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.photos.PhotoTags
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate

/**
 * Sélecteur de photos et cartes des photos : captures de référence Roborazzi et parcours clés.
 * Les vignettes sont des images synthétiques : aucune vraie photo dans le dépôt.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class PhotoCapturesTest {
    @get:Rule val compose = createComposeRule()

    private val at = Instant.parse("2026-11-02T15:10:00Z")

    /** Vignette synthétique : un fond uni et une forme, pour reconnaître chaque photo sur la capture. */
    private fun thumbnail(background: Int, shape: Int): Bitmap = Bitmap.createBitmap(192, 144, Bitmap.Config.ARGB_8888).also { bitmap ->
        Canvas(bitmap).apply {
            drawColor(background)
            drawCircle(96f, 72f, 46f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shape })
        }
    }

    private fun photo(id: String, background: Int, shape: Int) =
        PreparedPhoto(id, ImageInput("data:image/jpeg;base64,AAAA"), thumbnail(background, shape), 3)

    private val plate = RecordCard(
        actionId = 41,
        kind = RecordKind.Added,
        items = listOf(CardItem("e1", "Plat d'assiette (contenu incertain)", null, "midi", "2026-11-02", 650.0, 28.0, 70.0, 26.0)),
        assumptions = listOf("portion moyenne supposée", "sauce crémeuse supposée"),
        question = "Il y avait quoi environ dedans ?",
    )

    private fun shell(state: ChatUiState, actions: ChatActions = ChatActions()) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ChatShell(dateLabel = "Lundi 2 novembre", state = state, onBack = {}, actions = actions)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun selecteurDePhotos() {
        shell(
            ChatUiState(
                messages = listOf(
                    ChatMessage(1, at, ChatRole.User, "Comme snack j’ai pris une barre Nuvo et une pomme, 150 g"),
                ),
                photos = listOf(photo("a", Color.rgb(226, 214, 190), Color.rgb(27, 63, 159)), photo("b", Color.rgb(205, 222, 238), Color.rgb(170, 90, 60))),
            ),
        )

        // Le bouton ouvre le choix entre l'appareil photo et la galerie.
        compose.onNodeWithTag(PhotoTags.ATTACH).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PhotoTags.CAMERA).assertIsDisplayed()
        compose.onNodeWithTag(PhotoTags.GALLERY).assertIsDisplayed()
        compose.onNodeWithText("Appareil photo").assertIsDisplayed()
        compose.onNodeWithText("Galerie").assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithTag(PhotoTags.THUMBNAIL).fetchSemanticsNodes().size)
        compose.onRoot().captureRoboImage("src/test/screenshots/photo-selecteur.png")

        // Toucher à nouveau le bouton referme le choix.
        compose.onNodeWithTag(PhotoTags.ATTACH).performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag(PhotoTags.OPTIONS).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun retirerUnePhotoEtEnvoyerUnePhotoSeule() {
        var removed: PreparedPhoto? = null
        var sent = 0
        val first = photo("a", Color.rgb(226, 214, 190), Color.rgb(27, 63, 159))
        shell(
            ChatUiState(photos = listOf(first)),
            ChatActions(onSend = { sent++ }, onPhotoRemoved = { removed = it }),
        )

        // Sans texte, une photo suffit pour envoyer.
        compose.onNodeWithTag(ChatTags.SEND).assertIsEnabled().performClick()
        assertEquals(1, sent)
        compose.onNodeWithContentDescription("Retirer la photo 1").performClick()
        assertEquals(first, removed)
    }

    @Test
    fun cartePhotoSansPhotoNiTexteNeSeSoumetPas() {
        var sent = 0
        shell(ChatUiState(), ChatActions(onSend = { sent++ }))

        compose.onNodeWithTag(ChatTags.SEND).performClick()

        assertEquals(0, sent)
    }

    @Test
    fun carteQuestionEnAttente() {
        shell(
            ChatUiState(
                messages = listOf(
                    ChatMessage(1, at, ChatRole.User, "[1 photo envoyée]"),
                    ChatMessage(2, at, ChatRole.Assistant, "J’ai compté une assiette moyenne. Il y avait quoi environ dedans ?", listOf(plate)),
                ),
                pendingQuestions = setOf(plate.actionId),
            ),
        )

        // L'estimation est déjà comptée ; la question est signalée ; la photo n'est plus qu'un repère texte.
        compose.onNodeWithContentDescription("Ajouté, Midi, 650 kilocalories").assertIsDisplayed()
        compose.onNodeWithText("Question en attente : Il y avait quoi environ dedans ?").assertIsDisplayed()
        compose.onNodeWithText("1 photo envoyée").assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/carte-question-en-attente.png")
    }

    @Test
    fun questionRepondueDisparaitDeLaCarte() {
        shell(
            ChatUiState(
                messages = listOf(
                    ChatMessage(1, at, ChatRole.User, "[1 photo envoyée]"),
                    ChatMessage(2, at, ChatRole.Assistant, "Il y avait quoi environ dedans ?", listOf(plate)),
                    ChatMessage(3, at, ChatRole.User, "du poulet curry avec du riz"),
                ),
                pendingQuestions = emptySet(),
            ),
        )

        assertTrue(compose.onAllNodesWithTag(CardTags.QUESTION).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Ajouté, Midi, 650 kilocalories").assertIsDisplayed()
    }

    @Test
    fun carteRestaurantAvecSourcesEtConfirmation() {
        val bigMac = RecordCard(
            actionId = 52,
            kind = RecordKind.Added,
            items = listOf(CardItem("e2", "Big Mac (McDonald’s)", "1 pièce", "midi", "2026-11-02", 503.0, 26.0, 41.0, 26.0)),
            assumptions = listOf("valeurs de la carte de l’enseigne (McDonald’s)"),
            question = "C’est bien le Big Mac classique ?",
            memorized = listOf(ProductRef("prd-bigmac", "Big Mac (McDonald’s)")),
        )
        shell(
            ChatUiState(
                messages = listOf(
                    ChatMessage(1, at, ChatRole.User, "j’ai pris un Big Mac chez McDonald’s"),
                    ChatMessage(
                        2, at, ChatRole.Assistant, "J’ai compté un Big Mac d’après la carte de l’enseigne. C’est bien le Big Mac classique ?",
                        listOf(bigMac, SourcesCard(listOf(SourceLink("https://www.exemple.com/fr/produits/big-mac", "exemple.com")))),
                    ),
                ),
                pendingQuestions = setOf(bigMac.actionId),
            ),
        )

        compose.onNodeWithTag(CardTags.SOURCES).assertIsDisplayed()
        compose.onNodeWithText("Question en attente : C’est bien le Big Mac classique ?").assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/carte-restaurant-sources.png")
    }

    // ───── Parcours complet par le vrai écran : enregistrer d'abord, affiner ensuite ─────

    private fun engine(): TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10")).also {
        runBlocking { check(it.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied) }
    }

    @Test
    fun laQuestionDisparaitQuandLUtilisateurRepond() {
        val engine = engine()
        val plat = """{"label":"Plat d'assiette","quantity":null,"unit":null,"kcal":650,"protein_g":28,"carbs_g":70,"fat_g":26,"fiber_g":null,"assumptions":["portion moyenne supposée"],"confidence":"low"}"""
        val transport = ScriptedTransport(
            ModelReplies.call("record_consumptions", """{"items":[{"product":null,"food":$plat,"recipe_id":null,"moment":"midi","day":null}],"question":"Il y avait quoi environ dedans ?"}"""),
            ModelReplies.text("J’ai compté une assiette moyenne. Il y avait quoi environ dedans ?"),
            // La réponse de l'utilisateur confirme l'estimation : rien à corriger.
            ModelReplies.text("Parfait, je garde 650 kcal."),
        )
        val assistant = AssistantServices.inMemory(engine, transport, InMemoryApiKeyStore("sk-test-cle-factice-0000000000"))
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), assistant = assistant) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(ChatTags.INPUT).performTextInput("une assiette de midi")
        compose.onNodeWithTag(ChatTags.SEND).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(CardTags.QUESTION)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("Question en attente : Il y avait quoi environ dedans ?").assertIsDisplayed()

        compose.onNodeWithTag(ChatTags.INPUT).performTextInput("oui, c’est ça")
        compose.onNodeWithTag(ChatTags.SEND).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(CardTags.QUESTION)).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasTestTag(CardTags.QUESTION)).fetchSemanticsNodes().isEmpty())
    }
}
