package app.trackly.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.core.engine.TracklyEngine
import app.trackly.testing.SampleData
import app.trackly.ui.theme.MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Écran du jour : jours passés, en-tête compact sans chevauchement à 130 % et 200 % de taille
 * de police, rôles et cibles de 48 dp, dépassement et valeur basse.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class DayLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun ouvrir(engine: TracklyEngine = SampleData.engine(), fontScale: Float = 1f) {
        compose.setContent { AvecPolice(fontScale) { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true)) } }
        compose.waitForIdle()
    }

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun SemanticsNodeInteraction.rect(): Rect {
        val b = getBoundsInRoot()
        return Rect(b.left.value, b.top.value, b.right.value, b.bottom.value)
    }

    // ───── A12 : jour passé ─────

    @Test
    fun `hier vide - journee non suivie, sans il te reste ni pour l instant`() {
        ouvrir(SampleData.emptyEngine())
        compose.onNodeWithContentDescription("Jour précédent").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("HIER", substring = true).assertIsDisplayed()
        compose.onNodeWithText("JOURNÉE NON SUIVIE").assertIsDisplayed()
        compose.onNodeWithText("Rien n’a été noté ce jour-là.").assertIsDisplayed()
        compose.onNodeWithText("IL TE RESTE").assertDoesNotExist()
        assertEquals(0, compose.onAllNodesWithText("Rien pour l’instant").fetchSemanticsNodes().size)
        // Ni lignes vides ni macros à zéro : la phrase suffit. Un oubli se rattrape par le champ du bas (prérempli « Pour … : »).
        assertEquals(0, compose.onAllNodesWithText("Rien noté").fetchSemanticsNodes().size)
        compose.onNodeWithTag(app.trackly.ui.day.DayTags.MEALS).assertDoesNotExist()
        compose.onNodeWithContentDescription("Protéines : 0 grammes").assertDoesNotExist()
        compose.onNodeWithContentDescription("Écrire à Trackly").assertIsDisplayed()
    }

    @Test
    fun `hier avec des repas - il restait, a l imparfait`() {
        ouvrir()
        compose.onNodeWithContentDescription("Jour précédent").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("IL RESTAIT").assertIsDisplayed()
        compose.onNodeWithText("IL TE RESTE").assertDoesNotExist()
        compose.onNodeWithContentDescription("120").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hier, dimanche 1er novembre").assertIsDisplayed()
    }

    @Test
    fun `aujourd hui a le libelle devant la date`() {
        ouvrir()
        compose.onNodeWithText("AUJOURD’HUI", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Aujourd’hui, lundi 2 novembre").assertIsDisplayed()
    }

    // ───── A13 : en-tête à 130 % et 200 % ─────

    private fun assertEnTeteSansChevauchement(fontScale: Float) {
        ouvrir(fontScale = fontScale)
        compose.onNodeWithContentDescription("Jour précédent").performClick()
        compose.waitForIdle()
        val root = compose.onRoot().rect()
        val blocks = listOf(
            "Trackly" to compose.onNodeWithText("Trackly").rect(),
            "précédent" to compose.onNodeWithContentDescription("Jour précédent").rect(),
            "date" to compose.onNodeWithContentDescription("Hier, dimanche 1er novembre").rect(),
            "suivant" to compose.onNodeWithContentDescription("Jour suivant").rect(),
        )
        for ((name, r) in blocks) {
            assertTrue("$name sort de l'écran à ${fontScale * 100} % : $r", r.left >= 0f && r.right <= root.right)
        }
        for (i in blocks.indices) for (j in i + 1 until blocks.size) {
            val (a, ra) = blocks[i]
            val (b, rb) = blocks[j]
            assertFalse("$a et $b se chevauchent à ${fontScale * 100} % : $ra / $rb", ra.overlaps(rb))
        }
        // Flèches : largeur fixe de 48 dp, quelle que soit la police.
        assertEquals(48f, blocks[1].second.width, 0.5f)
        assertEquals(48f, blocks[3].second.width, 0.5f)
    }

    @Test
    fun `en-tete sans chevauchement a 100 pour cent`() = assertEnTeteSansChevauchement(1f)

    @Test
    fun `en-tete sans chevauchement a 130 pour cent`() = assertEnTeteSansChevauchement(1.3f)

    @Test
    fun `en-tete sans chevauchement a 200 pour cent`() = assertEnTeteSansChevauchement(2f)

    // ───── A22 : rôles, libellés, cibles ─────

    @Test
    fun `a 200 pour cent de police, la barre du bas garde son texte entier et ses boutons`() {
        ouvrir(fontScale = 2f)
        compose.onNodeWithText("Écrire à Trackly…").assertIsDisplayed()
        for (label in listOf("Écrire à Trackly", "Dicter à Trackly", "Mes aliments")) {
            compose.onNodeWithContentDescription(label).assertIsDisplayed()
        }
    }

    @Test
    fun `les elements pressables sont des boutons nommes`() {
        ouvrir()
        for (label in listOf("Jour précédent", "Écrire à Trackly", "Dicter à Trackly", "Mes aliments")) {
            compose.onNodeWithContentDescription(label).assert(isButton())
        }
        compose.onNodeWithContentDescription("Aujourd’hui, lundi 2 novembre").assert(isButton())
        compose.onNodeWithText("Trackly").assert(isButton())
        compose.onNodeWithText("Midi").assert(isButton())
    }

    @Test
    fun `la cible des fleches et des boutons du bas fait au moins 48 dp`() {
        ouvrir()
        for (label in listOf("Jour précédent", "Jour suivant", "Écrire à Trackly", "Dicter à Trackly", "Mes aliments")) {
            val r = compose.onNodeWithContentDescription(label).rect()
            assertTrue("$label : ${r.width} x ${r.height} dp", r.width >= 47.9f && r.height >= 47.9f)
        }
        val brand = compose.onNodeWithText("Trackly").rect()
        assertTrue("Trackly : ${brand.height} dp de haut", brand.height >= 47.9f)
    }

    @Test
    fun `l icone reglages est a cote de Trackly et le toucher ouvre les reglages`() {
        ouvrir()
        // Un seul bouton porte « Trackly » et son libellé d'action est explicite pour TalkBack.
        compose.onNodeWithText("Trackly").assert(
            SemanticsMatcher("action « Ouvrir les réglages »") { node ->
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label == "Ouvrir les réglages"
            },
        )
        compose.onNodeWithText("Trackly").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Jour précédent").assertDoesNotExist()
    }

    // ───── A21 : valeur basse et dépassement ─────

    @Test
    fun `une macro qui depasse sa cible affiche un depassement discret`() {
        // Lipides 70 g visés : le jeu d'essai du jour en compte 48 ; on ajoute de quoi dépasser de 18 g.
        val engine = SampleData.engine()
        kotlinx.coroutines.runBlocking {
            engine.execute(
                app.trackly.core.action.AddConsumptions(
                    app.trackly.core.action.NewConsumption(
                        app.trackly.core.model.Food("Huile", app.trackly.core.model.Nutrition(300.0, 0.0, 0.0, 40.0)),
                        app.trackly.core.model.Moment.Soir,
                    ),
                ),
            )
        }
        ouvrir(engine)
        compose.onNodeWithText("+18 g").assertIsDisplayed()
        // Le rouge s'ajoute à la phrase TalkBack, comme un fait : « limite dépassée ».
        compose.onNodeWithContentDescription("Lipides : 88 sur 70 grammes, 18 de plus, limite dépassée").assertIsDisplayed()
        // Les autres macros restent dans leur cible : aucun dépassement affiché.
        compose.onNodeWithContentDescription("Protéines : 96 sur 140 grammes").assertIsDisplayed()
    }
}

/** Même interface avec une taille de police agrandie (réglage d'accessibilité Android). */
@Composable
internal fun AvecPolice(fontScale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { content() }
}

private fun Rect.overlaps(other: Rect): Boolean =
    left < other.right - 0.5f && other.left < right - 0.5f && top < other.bottom - 0.5f && other.top < bottom - 0.5f
