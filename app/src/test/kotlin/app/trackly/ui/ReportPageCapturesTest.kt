package app.trackly.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.core.review.WeighIn
import app.trackly.review.ReportInfo
import app.trackly.review.StoredReport
import app.trackly.review.page.Advice
import app.trackly.review.page.ReferenceWeight
import app.trackly.review.page.ReportContent
import app.trackly.review.page.ReportPage
import app.trackly.review.page.ReportPageBuilder
import app.trackly.review.page.ReportPageFixtures
import app.trackly.review.page.ReportPageFixtures.Scenario
import app.trackly.ui.review.ReportChartTags
import app.trackly.ui.review.ReportPageScreen
import app.trackly.ui.review.ReportPageTags
import app.trackly.ui.review.ReportScreen
import app.trackly.ui.review.WeightReadout
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate

/**
 * Page du bilan (mise en page « Rapport ») : une capture par scénario (en avance, dans les temps, en retard,
 * ralentissement normal), une par statut de la prévision, la police à 200 %, et le comportement (toucher sur la courbe,
 * « Poser une question », ancien bilan HTML intact). Données fictives (`ReportPageFixtures`).
 * Enregistrer : `./gradlew recordRoborazziDebug` ; comparer : `./gradlew verifyRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h2000dp-xxhdpi")
class ReportPageCapturesTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.parse("2026-11-06")

    private fun page(scenario: Scenario, analysis: String = ReportPageFixtures.ANALYSIS) =
        ReportPageBuilder.build(ReportPageFixtures.figures(scenario), analysis, today)

    private fun page(weights: List<WeighIn>) = ReportPageBuilder.build(ReportPageFixtures.figures(weights), ReportPageFixtures.ANALYSIS, today)

    private fun show(page: ReportPage, fontScale: Float = 1f, onAsk: (() -> Unit)? = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                AvecPolice(fontScale) { ReportPageScreen(page, onBack = {}, onAsk = onAsk) }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    private val wobble = listOf(0.2, -0.2, 0.1, -0.1)

    private fun curve(weeks: Int, kgAt: (Int) -> Double): List<WeighIn> {
        val first = app.trackly.core.review.WeekRange.containing(ReportPageFixtures.range.end).start.minusWeeks(weeks - 1L)
        return (0 until weeks).flatMap { w ->
            listOf(0, 2, 4, 6).mapIndexed { i, d -> WeighIn(first.plusDays(w * 7L + d), Math.round((kgAt(w * 7 + d) + wobble[i]) * 10) / 10.0) }
        }.filter { !it.day.isAfter(ReportPageFixtures.range.end) }
    }

    // ───── Les quatre scénarios. Avec 7 semaines de pesées, la fourchette de la prévision reste large : le verdict affiché ───── ... est « Dans les temps » ou « Plutôt en retard » ; « En avance » et « En retard » sont testés dans ReportPageBuilderTest.

    @Test fun enAvance() { show(page(Scenario.Avance, "Franchement, belle période. Tu as de l’avance et tout est au vert : rien à corriger, une ligne par point suffit.")); capture("bilan-page-arrivee-avant-la-date") }

    @Test fun dansLesTemps() { show(page(Scenario.Temps, "Tu es dans les temps. Un seul point à surveiller : les protéines, trois jours sous le minimum. Le reste roule.")); capture("bilan-page-arrivee-pile-a-la-date") }

    @Test fun enRetard() { show(page(Scenario.Retard, "C’est rattrapable. Le retard vient surtout des pas, un peu du week-end.")); capture("bilan-page-arrivee-apres-la-date") }

    @Test fun ralentissementNormal() {
        show(
            ReportPageBuilder.build(
                ReportPageFixtures.figures(ReportPageFixtures.slowingWeights(), Scenario.Ralenti),
                "Tu perds moins vite qu’en septembre, et c’est normal : en pesant moins, tu dépenses moins. Ta date ne bouge presque pas, rien à changer.", today,
            ),
        )
        capture("bilan-page-ralentissement")
    }

    @Test fun enRetardPolice200() { show(page(Scenario.Retard), fontScale = 2f); capture("bilan-page-arrivee-apres-la-date-police-200") }

    // ───── Les statuts de la prévision ─────

    @Test fun aucunePesee() { show(page(emptyList())); capture("bilan-page-aucune-pesee") }

    @Test fun estimationSansDateFerme() { show(page(curve(2) { 66.0 - 0.07 * it })); capture("bilan-page-estimation") }

    @Test fun objectifAtteint() { show(page(curve(6) { 61.2 })); capture("bilan-page-atteint") }

    @Test fun poidsStable() { show(page(curve(6) { 66.0 })); capture("bilan-page-poids-stable") }

    @Test fun sEloigneDeLObjectif() { show(page(curve(6) { 63.0 + 0.4 * it / 7 })); capture("bilan-page-s-eloigne") }

    // ───── Emplacements des conseils et du poids de référence : visibles seulement quand ils sont remplis ; sans moteur, aucun bouton ─────

    @Test
    fun emplacementsVidesParDefaut() {
        show(page(Scenario.Retard))
        compose.onAllNodesWithTag(ReportPageTags.ADVICE).assertCountEquals(0)
        compose.onAllNodesWithTag(ReportPageTags.REFERENCE).assertCountEquals(0)
    }

    @Test
    fun emplacementsRemplisSansFauxBouton() {
        val full = page(Scenario.Retard).copy(
            advice = listOf(Advice("Objectif temporaire", "Rattraper les 400 kcal", "2 200", "kcal", listOf("Du samedi 7 au mardi 10 novembre, −100 par jour"))),
            referenceWeight = ReferenceWeight(74.0, 73.5, listOf("minimum de protéines 118 → 117 g", "lipides max inchangé (69 g)", "calories inchangées"), source = "Moyenne de tes 4 pesées des 7 derniers jours."),
        )
        show(full)
        compose.onNodeWithTag(ReportPageTags.ADVICE).assertExists()
        compose.onNodeWithTag(ReportPageTags.REFERENCE).assertExists()
        // Aucun bouton n'agit : « Poser une question » est le seul de la page.
        compose.onNodeWithText("Appliquer").assertDoesNotExist()
        compose.onNodeWithText("Annuler").assertDoesNotExist()
    }

    // ───── Comportement ─────

    @Test
    fun poserUneQuestionOuvreLeChatDuBilan() {
        var asked = 0
        show(page(Scenario.Retard), onAsk = { asked++ })
        compose.onNodeWithTag(ReportPageTags.ASK).assertIsDisplayed().performClick()
        assertEquals(1, asked)
    }

    @Test
    fun sansChatDeCettePeriode_pasDeBoutonPoserUneQuestion() {
        show(page(Scenario.Retard), onAsk = null)
        compose.onAllNodesWithTag(ReportPageTags.ASK).assertCountEquals(0)
        compose.onNodeWithTag(ReportPageTags.VERDICT).assertIsDisplayed()
    }

    @Test
    fun ancienBilanEnPage_reportScreenSansOnAsk_neMontrePasLeBouton() {
        val stored = ReportContent.encode(page(Scenario.Avance))
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ReportScreen(StoredReport(info, stored), onBack = {})
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag(ReportPageTags.ASK).assertCountEquals(0)
        compose.onNodeWithTag(ReportPageTags.VERDICT).assertIsDisplayed()
    }

    @Test
    fun toucherLaCourbeAfficheLaDateEtLesValeurs() {
        show(page(Scenario.Retard))
        compose.onAllNodesWithTag(ReportChartTags.READOUT).assertCountEquals(0)
        compose.onNodeWithTag(ReportChartTags.WEIGHT).performTouchInput { click(center) }
        compose.onNodeWithTag(ReportChartTags.READOUT).assertExists()
    }

    @Test
    fun lectureDeLaCourbe_dateEtValeursDuJour() {
        val chart = page(Scenario.Retard).weight.chart!!
        val day = chart.today
        val lines = WeightReadout.at(chart, day)
        assertEquals("6 novembre 2026", lines.date)
        assertTrue(lines.values.joinToString(), lines.values.any { it.startsWith("toi ") && it.endsWith(" kg") })
        assertTrue(lines.values.any { it.startsWith("prévu ") })
        assertTrue(lines.values.any { it.startsWith("à ce rythme ") })
        // Avant le début de la courbe, rien n'est inventé.
        val before = WeightReadout.at(chart, chart.you.first().day - 40)
        assertTrue(before.values.isEmpty())
    }

    @Test
    fun estimation_aucuneLigneAceRythme() {
        val chart = page(curve(2) { 66.0 - 0.07 * it }).weight.chart!!
        assertTrue(WeightReadout.at(chart, chart.today + 30).values.none { it.startsWith("à ce rythme") })
    }

    // ───── Les anciens bilans gardent leur rendu ─────

    private val info = ReportInfo(3, LocalDate.parse("2026-10-26"), Instant.parse("2026-11-02T08:32:40Z"))

    @Test
    fun ancienBilanHtmlResteDansLeVisionneur() {
        var shown: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ReportScreen(StoredReport(info, "<!doctype html><html><body>Ancien bilan</body></html>"), onBack = {}, viewer = { shown = it })
            }
        }
        compose.waitForIdle()
        assertTrue(shown!!.contains("Ancien bilan"))
        compose.onAllNodesWithTag(ReportPageTags.SCREEN).assertCountEquals(0)
    }

    @Test
    fun nouveauBilanEstUnePage_pasDuHtml() {
        var viewerCalled = false
        val stored = ReportContent.encode(page(Scenario.Avance))
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ReportScreen(StoredReport(info, stored), onBack = {}, viewer = { viewerCalled = true })
            }
        }
        compose.waitForIdle()
        assertFalse(viewerCalled)
        compose.onNodeWithTag(ReportPageTags.VERDICT).assertTextEquals(page(Scenario.Avance).headline.verdict)
    }

    @Test
    fun pageDUnFormatPlusRecent_neSAffichePasCommeDuHtml() {
        var viewerCalled = false
        val future = ReportContent.encode(page(Scenario.Avance)).replaceFirst("\"format\":1", "\"format\":99")
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ReportScreen(StoredReport(info, future), onBack = {}, viewer = { viewerCalled = true })
            }
        }
        compose.waitForIdle()
        assertFalse(viewerCalled)
        compose.onNodeWithText("Ce bilan n’a pas pu être lu", substring = true).assertExists()
    }
}
