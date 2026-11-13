package app.trackly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import app.trackly.assistant.openai.ImageInput
import app.trackly.photos.PreparedPhoto
import app.trackly.ui.photos.rememberPhotoPicker
import app.trackly.assistant.cards.CardSessions
import app.trackly.assistant.cards.CardSteps
import app.trackly.assistant.cards.CardWeighIn
import app.trackly.assistant.cards.ReportReadyCard
import app.trackly.assistant.cards.WeekInfoCard
import app.trackly.assistant.cards.WeightGoalCard
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.core.model.RecordState
import app.trackly.core.review.DaySteps
import app.trackly.core.review.SessionCount
import app.trackly.core.review.WeekInfo
import app.trackly.core.review.WeighIn
import app.trackly.review.ReportInfo
import app.trackly.review.ReviewSample
import app.trackly.review.ReviewSettingsState
import app.trackly.review.ReviewUiState
import app.trackly.review.StoredReport
import app.trackly.ui.review.ReportScreen
import app.trackly.ui.review.ReportsScreen
import app.trackly.ui.review.ReviewScreen
import app.trackly.ui.settings.ReviewSettingsSection
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import app.trackly.ui.theme.TracklyColors
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate

/**
 * Captures de référence du bilan du lundi (Roborazzi) : la conversation de bilan, la liste des bilans
 * et les Réglages. Le rapport lui-même est du HTML dans un WebView, que Robolectric ne dessine pas :
 * son rendu se vérifie dans un vrai navigateur.
 * Enregistrer : `./gradlew recordRoborazziDebug` ; comparer : `./gradlew verifyRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class ReviewCapturesTest {
    @get:Rule val compose = createComposeRule()

    private val week = ReviewSample.week
    private fun at(iso: String) = Instant.parse(iso)
    private fun day(iso: String) = LocalDate.parse(iso)

    private val info = WeekInfo(
        weekStart = week.start, version = 1, state = RecordState.Active,
        weighIns = listOf(WeighIn(day("2026-10-27"), 72.4), WeighIn(day("2026-10-30"), 72.0)),
        steps = listOf(DaySteps(day("2026-10-26"), 9_000), DaySteps(day("2026-10-27"), 11_000)),
        sessions = listOf(SessionCount("course", 1), SessionCount("musculation", 3)),
        recordedAt = Instant.EPOCH, actionId = null,
    )

    /** Capture de montre synthétique : un fond et des barres, pour la vignette. */
    private fun capture(id: String) = PreparedPhoto(
        id,
        ImageInput("data:image/jpeg;base64,AAAA"),
        Bitmap.createBitmap(144, 192, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(0xFF10233F.toInt())
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7FA6E8.toInt() }
                for (i in 0 until 5) drawRect(24f + i * 20f, 150f - i * 18f, 36f + i * 20f, 160f, paint)
            }
        },
        3,
    )

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) { content() } }
        compose.waitForIdle()
    }

    @Test
    fun conversationInvitation() {
        show { ReviewScreen(state = ReviewUiState(period = week), onBack = {}) }
        compose.onRoot().captureRoboImage("src/test/screenshots/bilan-invitation.png")
    }

    @Test
    fun conversationAvecCartesEtCaptures() {
        val messages = listOf(
            ChatMessage(1, at("2026-11-02T08:31:00Z"), ChatRole.User, "Voici ma capture de la semaine, et j’ai fait 3 séances de muscu et une course.\n[1 photo envoyée]"),
            ChatMessage(
                2, at("2026-11-02T08:31:20Z"), ChatRole.Assistant,
                "Je lis 72,4 kg le mardi 27 octobre et 72,0 kg le jeudi 29. Tu as aussi tes pas ?",
                listOf(
                    WeekInfoCard(
                        actionId = 11, weekStart = "2026-10-26",
                        weighIns = listOf(CardWeighIn("2026-10-27", 72.4), CardWeighIn("2026-10-30", 72.0)),
                        steps = listOf(CardSteps("2026-10-26", 9_000), CardSteps("2026-10-27", 11_000)),
                        sessions = listOf(CardSessions("course", 1), CardSessions("musculation", 3)),
                    ),
                ),
            ),
            ChatMessage(3, at("2026-11-02T08:32:10Z"), ChatRole.User, "Produis mon bilan maintenant, avec ce que tu as."),
            ChatMessage(
                4, at("2026-11-02T08:32:40Z"), ChatRole.Assistant, "Voilà, ton bilan est prêt.",
                listOf(ReportReadyCard(reportId = 3, weekStart = "2026-10-26", notified = true)),
            ),
        )
        show {
            ReviewScreen(
                state = ReviewUiState(period = week, messages = messages, photos = listOf(capture("c1"), capture("c2")), declared = info, latestReport = ReportInfo(3, week.start, at("2026-11-02T08:32:40Z"))),
                onBack = {},
                draft = "Et jeudi 71,9 kg",
                // Le vrai sélecteur de photos (bouton appareil photo dans la saisie).
                photoPicker = rememberPhotoPicker(remaining = 4, onPhotos = {}, onProblem = {}),
            )
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/bilan-conversation.png")
    }

    /** Carte « Objectif de poids changé » : active, puis annulée. */
    private fun goalMessages() = listOf(
        ChatMessage(1, at("2026-11-02T08:31:00Z"), ChatRole.User, "Vise 60 kg pour le 1er avril."),
        ChatMessage(
            2, at("2026-11-02T08:31:20Z"), ChatRole.Assistant, "C’est noté : je recalcule ta date d’arrivée avec ce nouvel objectif.",
            listOf(WeightGoalCard(actionId = 12, beforeKg = 61.0, beforeDate = "2027-03-01", afterKg = 60.0, afterDate = "2027-04-01")),
        ),
    )

    @Test
    fun carteObjectifDePoidsChange() {
        show { ReviewScreen(state = ReviewUiState(period = week, messages = goalMessages()), onBack = {}) }
        compose.onRoot().captureRoboImage("src/test/screenshots/bilan-carte-objectif-poids.png")
    }

    @Test
    fun carteObjectifDePoidsAnnule() {
        show { ReviewScreen(state = ReviewUiState(period = week, messages = goalMessages(), undone = setOf(12L)), onBack = {}) }
        compose.onRoot().captureRoboImage("src/test/screenshots/bilan-carte-objectif-poids-annule.png")
    }

    @Test
    fun conversationAvecInformationsDejaDonnees() {
        show { ReviewScreen(state = ReviewUiState(period = week, declared = info), onBack = {}) }
        compose.onRoot().captureRoboImage("src/test/screenshots/bilan-invitation-informations.png")
    }

    @Test
    fun listeDesBilans() {
        show {
            ReportsScreen(
                reports = listOf(
                    ReportInfo(3, day("2026-10-26"), at("2026-11-02T08:32:40Z")),
                    ReportInfo(2, day("2026-10-19"), at("2026-10-26T08:40:00Z")),
                    ReportInfo(1, day("2026-10-12"), at("2026-10-13T17:05:00Z")),
                ),
                onOpen = {}, onStartReview = {}, onBack = {},
            )
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/bilans-liste.png")
    }

    @Test
    fun listeDesBilansVide() {
        show { ReportsScreen(reports = emptyList(), onOpen = {}, onStartReview = {}, onBack = {}) }
        compose.onRoot().captureRoboImage("src/test/screenshots/bilans-liste-vide.png")
    }

    @Test
    fun rapportOuvert() {
        show {
            ReportScreen(
                report = StoredReport(ReportInfo(3, week.start, at("2026-11-02T08:32:40Z")), "<html></html>"),
                onBack = {},
                viewer = { Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopStart) { BasicText("Rapport HTML (WebView non dessiné par Robolectric)") } },
            )
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/bilan-rapport-ecran.png")
    }

    @Test
    fun reglages() {
        show {
            Column(Modifier.padding(horizontal = 26.dp).background(TracklyColors.PaperHi)) {
                ReviewSettingsSection(ReviewSettingsState(), notificationsAllowed = true, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/reglages-bilan.png")
    }

    @Test
    fun reglagesNotificationsDesactivees() {
        show {
            Column(Modifier.padding(horizontal = 26.dp).background(TracklyColors.PaperHi)) {
                ReviewSettingsSection(ReviewSettingsState(), notificationsAllowed = false, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/reglages-bilan-notifications.png")
    }
}
