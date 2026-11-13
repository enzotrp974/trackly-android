package app.trackly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.DeclareConsumptions
import app.trackly.core.action.DeclaredItem
import app.trackly.core.action.MemorizeProduct
import app.trackly.core.action.NewProduct
import app.trackly.core.action.ProductUse
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrients
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.ProductKind
import app.trackly.core.model.ProductPortion
import app.trackly.core.model.Quantity
import app.trackly.testing.ModelReplies
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import app.trackly.ui.chat.CardTags
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Cartes des produits dans le chat : proposition de récurrence, « est-ce celui-ci ? »,
 * fiche nommée. Réponses de modèle simulées ; captures de référence Roborazzi.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class ProductCapturesTest {
    @get:Rule val compose = createComposeRule()

    private val sandwich = NewProduct(
        productId = "prd-sandwich",
        name = "Sandwich thon baguette",
        brand = "Marque B",
        nutrients = Nutrients(250.0, 12.0, 30.0, 8.0),
        basis = NutritionBasis(100.0, "g"),
    )

    private fun nuvo(id: String, name: String, portionKcal: Double) = NewProduct(
        productId = id,
        name = name,
        brand = "Nuvo",
        nutrients = Nutrients(400.0, 36.0, 22.0, 14.0),
        basis = NutritionBasis(100.0, "g"),
        portion = ProductPortion("1 barre", Quantity(55.0, "g"), Nutrients(portionKcal, 19.8, 12.1, 7.7)),
        shortcuts = listOf("barre nuvo"),
        kind = ProductKind.Recurring,
    )

    private fun engine(): TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10")).also { engine ->
        runBlocking { check(engine.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied) }
    }

    private fun open(engine: TracklyEngine, transport: ScriptedTransport) {
        val assistant = AssistantServices.inMemory(engine, transport, InMemoryApiKeyStore("sk-test-cle-factice-0000000000"))
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), assistant = assistant) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
    }

    private fun send(text: String, waitForTag: String) {
        compose.onNodeWithTag(ChatTags.INPUT).performTextInput(text)
        compose.onNodeWithTag(ChatTags.SEND).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(waitForTag)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    @Test
    fun carteProposeLaRecurrenceEtLeNom() {
        val engine = engine()
        runBlocking {
            check(engine.execute(MemorizeProduct(sandwich)) is ActionOutcome.Applied)
            check(
                engine.execute(
                    DeclareConsumptions(listOf(DeclaredItem.OfProduct(ProductUse(productId = "prd-sandwich", amount = Quantity(200.0, "g")), Moment.Midi, explicitDay = LocalDate.parse("2026-11-01")))),
                ) is ActionOutcome.Applied,
            )
        }
        val transport = ScriptedTransport(
            ModelReplies.call(
                "record_consumptions",
                """{"items":[{"product":{"product_id":"prd-sandwich","new_product":null,"quantity":200,"unit":"g","assumptions":[],"alternatives":[]},"food":null,"recipe_id":null,"moment":"midi","day":null}],"question":null}""",
            ),
            ModelReplies.text("C'est noté. Tu veux en faire un produit récurrent ? Dis-moi comment l'appeler."),
            // Réponse de l'utilisateur : le nom est donné, la fiche est renommée.
            ModelReplies.call("update_product", """{"operation":"make_recurring","product_id":"prd-sandwich","value":"Sandwich grand thon baguette"}"""),
            ModelReplies.text("C'est fait."),
        )
        open(engine, transport)

        send("le sandwich d'hier, je l'ai remangé à midi", CardTags.PRODUCTS)

        compose.onNodeWithText("Déjà pris : Sandwich thon baguette. Tu peux en faire un produit récurrent : dis-moi le nom à lui donner.").assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/produit-recurrence-proposee.png")

        send("appelle-le sandwich grand thon baguette", CardTags.PRODUCT)

        compose.onNodeWithContentDescription("Produit récurrent, Sandwich grand thon baguette").assertIsDisplayed()
        compose.onNodeWithText("avant : Sandwich thon baguette").assertIsDisplayed()
        assertEquals("Sandwich grand thon baguette", runBlocking { engine.product("prd-sandwich")!!.name })
        compose.onRoot().captureRoboImage("src/test/screenshots/produit-recurrent-nomme.png")

        // Annuler depuis la carte : le produit redevient ponctuel.
        compose.onAllNodes(hasTestTag(CardTags.UNDO)).onLast().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(CardTags.UNDONE)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(ProductKind.Occasional, runBlocking { engine.product("prd-sandwich")!!.kind })
    }

    @Test
    fun carteDemandeEstCeCeluiCi() {
        val engine = engine()
        runBlocking {
            check(engine.execute(MemorizeProduct(nuvo("prd-nuvo-choc", "Barre protéinée Nuvo chocolat", 220.0))) is ActionOutcome.Applied)
            check(engine.execute(MemorizeProduct(nuvo("prd-nuvo-van", "Barre protéinée Nuvo vanille", 209.0))) is ActionOutcome.Applied)
        }
        val transport = ScriptedTransport(
            ModelReplies.call(
                "record_consumptions",
                """{"items":[{"product":{"product_id":"prd-nuvo-choc","new_product":null,"quantity":1,"unit":"portion","assumptions":[],"alternatives":["prd-nuvo-van"]},"food":null,"recipe_id":null,"moment":"snack","day":null}],"question":null}""",
            ),
            ModelReplies.text("C'est noté pour ton snack. Est-ce la barre chocolat ?"),
        )
        open(engine, transport)

        send("snack barre Nuvo", CardTags.PRODUCTS)

        compose.onNodeWithText("Est-ce celui-ci ? Barre protéinée Nuvo chocolat. Sinon : Barre protéinée Nuvo vanille.").assertIsDisplayed()
        // L'estimation est déjà comptée.
        compose.onNodeWithContentDescription("Ajouté, Snack, 220 kilocalories").assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/produit-est-ce-celui-ci.png")
    }
}
