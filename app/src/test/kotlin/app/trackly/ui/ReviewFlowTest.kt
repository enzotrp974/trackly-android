package app.trackly.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.alerts.FixedNotificationsAllowed
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.photos.PreparedPhoto
import app.trackly.review.InMemoryReviewSettings
import app.trackly.review.RecordingReviewNotifier
import app.trackly.review.ReviewSample
import app.trackly.review.ReviewServices
import app.trackly.testing.ModelReplies
import app.trackly.testing.ScriptedTransport
import app.trackly.ui.photos.PhotoPickerState
import app.trackly.ui.photos.PhotoTags
import app.trackly.ui.review.ReportPageTags
import app.trackly.ui.review.ReportTags
import app.trackly.ui.review.ReviewDestination
import app.trackly.ui.review.ReviewHost
import app.trackly.ui.review.ReviewNav
import app.trackly.ui.review.ReviewTags
import app.trackly.ui.settings.ReviewSettingsRoute
import app.trackly.ui.settings.ReviewSettingsTags
import app.trackly.ui.theme.MotionPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Parcours du bilan du lundi dans l'interface, avec des réponses de modèle simulées (aucun réseau). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class ReviewFlowTest {
    @get:Rule val compose = createComposeRule()

    private val notifier = RecordingReviewNotifier()
    private val nav = ReviewNav()

    private fun open(
        transport: ScriptedTransport,
        destination: ReviewDestination = ReviewDestination.Conversation,
        withPicker: Boolean = false,
        clock: java.time.Clock = ReviewSample.clock(),
    ): ReviewServices {
        val engine = runBlocking { ReviewSample.engine(clock) }
        val services = ReviewServices.inMemory(engine, notifier, transport, InMemoryApiKeyStore("sk-test-cle-factice-0000000000"))
        nav.start(destination)
        compose.setContent {
            ReviewHost(
                nav = nav,
                services = services,
                motion = MotionPolicy(reduced = true),
                // Le sélecteur de photos, avec une galerie factice qui rend une capture au toucher.
                imagePicker = if (withPicker) { _, onPhotos, _ ->
                    remember { PhotoPickerState(openGallery = { onPhotos(listOf(PreparedPhoto("c1", ReviewSample.screenshot, null, 3))) }, openCamera = {}) }
                } else {
                    null
                },
                reportViewer = { html -> BasicText(html.take(60), Modifier.testTag("faux-rapport")) },
            )
        }
        compose.waitForIdle()
        return services
    }

    /** Bouton appareil photo de la saisie, puis « Galerie ». */
    private fun pickFromGallery() {
        compose.onNodeWithTag(PhotoTags.ATTACH).performClick()
        compose.onNodeWithTag(PhotoTags.GALLERY).performClick()
        compose.waitForIdle()
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun send(text: String) {
        compose.onNodeWithTag(ReviewTags.INPUT).performTextInput(text)
        compose.onNodeWithTag(ReviewTags.SEND).performClick()
        compose.waitForIdle()
    }

    private val declare = """{"weigh_ins":[{"date":"2026-10-27","kg":72.4}],"steps_by_day":null,"steps_average":8500,"sessions":[{"type":"musculation","count":3}],"remove_weigh_in_dates":null,"remove_steps_dates":null}"""

    @Test
    fun `de la conversation au rapport - declaration, carte, bilan pret sans notification a l ecran, ouverture`() {
        val services = open(
            ScriptedTransport(
                ModelReplies.call("record_week_info", declare),
                ModelReplies.text("C’est noté. Tu veux ajouter autre chose ?"),
                ModelReplies.call("produce_weekly_report", """{"analysis":"Semaine proche de l'objectif.\n\nUne seule pesée : trajectoire à confirmer."}"""),
                ModelReplies.text("Ton bilan est prêt."),
            ),
        )

        // Invitation de la conversation : la semaine, ce qu'il faut donner.
        compose.onNodeWithTag(ReviewTags.HINT).assertIsDisplayed()
        compose.onNodeWithText("Du 26 octobre au 1er novembre 2026").assertIsDisplayed()

        send("Mardi 72,4 kg, 8 500 pas par jour, 3 séances de muscu.")
        waitFor(ReviewTags.INFO_CARD)
        compose.onNodeWithText("Mardi 27 octobre : 72,4 kg").assertExists()
        compose.onNodeWithText("Moyenne de 8\u202f500 par jour").assertExists()
        compose.onNodeWithText("C’est noté. Tu veux ajouter autre chose ?").assertExists()
        assertEquals(72.4, runBlocking { services.engine.weekInfo(ReviewSample.week.start)!!.weighIns.single().kg }, 0.0)

        // « Produire mon bilan » : rapport enregistré, carte « prêt » ; pas de notification, l'écran du bilan est ouvert.
        compose.onNodeWithTag(ReviewTags.PRODUCE).performClick()
        waitFor(ReviewTags.READY_CARD)
        assertEquals(0, notifier.ready.size)
        compose.onNodeWithText("Ton bilan est prêt").assertExists()
        assertTrue(runBlocking { services.reports.isDone(ReviewSample.week.start) })

        // La carte ouvre la page du bilan.
        compose.onNodeWithTag(ReviewTags.OPEN_REPORT).performClick()
        waitFor(ReportPageTags.SCREEN)
        compose.onNodeWithTag(ReportPageTags.VERDICT).assertIsDisplayed()
        // « Poser une question » ouvre le chat du bilan, par-dessus la page ; retour revient à la page, puis à la conversation.
        compose.onNodeWithTag(ReportPageTags.ASK).performClick()
        waitFor(ReviewTags.SCREEN)
        nav.back()
        waitFor(ReportPageTags.SCREEN)
        nav.back()
        compose.waitForIdle()
        compose.onNodeWithTag(ReviewTags.READY_CARD).assertIsDisplayed()
    }

    @Test
    fun `la notification ouvre la periode a faire, meme si le bilan de la precedente est reste ouvert`() {
        var now = ReviewSample.clock("2026-11-01T21:30").instant()
        val clock = object : java.time.Clock() {
            override fun getZone() = ReviewSample.clock().zone
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = now
        }
        open(ScriptedTransport(), clock = clock)
        compose.onNodeWithText("Du 26 octobre au 1er novembre 2026").assertIsDisplayed()

        now = ReviewSample.clock("2026-11-09T10:00").instant()
        nav.start(ReviewDestination.Conversation) // la notification arrive, l'app est restée sur l'ancienne semaine
        compose.waitForIdle()
        compose.onNodeWithText("Du 2 novembre au 9 novembre 2026").assertIsDisplayed()
    }

    @Test
    fun `annuler la carte des informations - la semaine redevient vide`() {
        val services = open(ScriptedTransport(ModelReplies.call("record_week_info", declare), ModelReplies.text("Noté.")))
        send("Mardi 72,4 kg")
        waitFor(ReviewTags.INFO_CARD)

        compose.onNodeWithContentDescription("Annuler").performClick()
        waitFor("carte-annulee")

        assertEquals(null, runBlocking { services.engine.weekInfo(ReviewSample.week.start) })
    }

    @Test
    fun `une capture jointe part avec le message et n est jamais conservee`() {
        val transport = ScriptedTransport(ModelReplies.text("Je lis 72,4 kg le mardi 27 octobre."))
        val services = open(transport, withPicker = true)

        pickFromGallery()
        compose.onNodeWithTag(PhotoTags.STRIP).assertIsDisplayed()
        compose.onNodeWithContentDescription("Photo 1 sur 1").assertExists()

        // Envoi possible sans texte : la capture suffit.
        compose.onNodeWithTag(ReviewTags.SEND).performClick()
        compose.waitUntil(5_000) { transport.requests.isNotEmpty() }
        compose.waitForIdle()

        val parts = transport.requests.single()["input"]!!.jsonArray.map { it.jsonObject }
            .last { it["role"]?.jsonPrimitive?.content == "user" }["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("input_text", "input_image"), parts)
        assertEquals(0, compose.onAllNodes(hasTestTag(PhotoTags.STRIP)).fetchSemanticsNodes().size)
        // L'historique garde le texte et le repère de la photo, jamais l'image ; la bulle affiche « 1 photo envoyée ».
        val stored = runBlocking { services.history.last(ReviewSample.week.start, 10) }
        assertEquals(listOf("Voici mes captures.\n[1 photo envoyée]", "Je lis 72,4 kg le mardi 27 octobre."), stored.map { it.text })
        compose.onNodeWithText("1 photo envoyée").assertExists()
        assertFalse(stored.any { it.text.contains("base64") })
    }

    @Test
    fun `retirer les captures jointes`() {
        open(ScriptedTransport(), withPicker = true)
        pickFromGallery()
        compose.onNodeWithTag(PhotoTags.REMOVE).performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasTestTag(PhotoTags.STRIP)).fetchSemanticsNodes().size)
    }

    @Test
    fun `liste des bilans passes - le plus recent d abord, un toucher ouvre le rapport`() {
        val services = open(ScriptedTransport(), ReviewDestination.Reports)
        compose.onNodeWithTag(ReportTags.EMPTY).assertIsDisplayed()

        runBlocking {
            services.reports.save(LocalDate.parse("2026-10-19"), Instant.parse("2026-10-26T08:10:00Z"), "<html>ancien</html>")
            services.reports.save(LocalDate.parse("2026-10-26"), Instant.parse("2026-11-02T08:20:00Z"), "<html>recent</html>")
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(ReportTags.ROW)).fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithTag(ReportTags.ROW)[0].assertIsDisplayed()
        compose.onNodeWithText("Du 26 octobre au 1er novembre 2026").assertExists()
        compose.onNodeWithText("Du 19 octobre au 25 octobre 2026").assertExists()
        // Le premier de la liste est la semaine la plus récente.
        compose.onAllNodesWithTag(ReportTags.ROW)[0].performClick()
        waitFor("faux-rapport")
        compose.onNodeWithText("<html>recent</html>").assertExists()
    }

    @Test
    fun `poser une question - seulement sur le bilan de la periode en cours`() {
        val services = open(ScriptedTransport(), ReviewDestination.Reports)
        val figures = runBlocking { services.engine.weeklyFigures(ReviewSample.week) }
        val page = app.trackly.review.page.ReportContent.encode(app.trackly.review.page.ReportPageBuilder.build(figures, "", LocalDate.parse("2026-11-01")))
        runBlocking {
            // La période en cours commence le 26 octobre (sans bilan précédent) ; un bilan d'une autre période n'a pas le droit au chat.
            services.reports.save(LocalDate.parse("2026-10-12"), Instant.parse("2026-10-19T08:10:00Z"), page)
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(ReportTags.ROW)).fetchSemanticsNodes().size == 1 }
        compose.onAllNodesWithTag(ReportTags.ROW)[0].performClick()
        waitFor(ReportPageTags.SCREEN)
        compose.onNodeWithTag(ReportPageTags.VERDICT).assertIsDisplayed()
        compose.onAllNodesWithTag(ReportPageTags.ASK).assertCountEquals(0)
    }

    @Test
    fun `reglages - heure de l invitation, rappels, acces au bilan et aux bilans passes`() {
        val settings = InMemoryReviewSettings()
        var reschedules = 0
        var started = 0
        var listed = 0
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                ReviewSettingsRoute(
                    settings = settings,
                    reschedule = { reschedules++ },
                    onStart = { started++ },
                    onReports = { listed++ },
                    notifications = FixedNotificationsAllowed(true),
                )
            }
        }
        compose.onNodeWithTag(ReviewSettingsTags.TIME).assertIsDisplayed()
        compose.onNodeWithText("09:00").assertExists()

        compose.onNodeWithTag(ReviewSettingsTags.EARLIER).performClick()
        compose.waitForIdle()
        assertEquals(LocalTime.of(8, 30), settings.state.value.inviteTime)
        compose.onNodeWithTag(ReviewSettingsTags.LATER).performClick()
        compose.onNodeWithTag(ReviewSettingsTags.LATER).performClick()
        compose.waitForIdle()
        assertEquals(LocalTime.of(9, 30), settings.state.value.inviteTime)
        assertEquals(3, reschedules)

        compose.onNodeWithTag(ReviewSettingsTags.START).performClick()
        compose.onNodeWithTag(ReviewSettingsTags.REPORTS).performClick()
        assertEquals(1, started)
        assertEquals(1, listed)

        compose.onNodeWithTag(ReviewSettingsTags.SWITCH).performClick()
        compose.waitForIdle()
        assertFalse(settings.state.value.enabled)
        assertEquals(4, reschedules)
        assertEquals(0, compose.onAllNodes(hasTestTag(ReviewSettingsTags.TIME)).fetchSemanticsNodes().size)
    }

    @Test
    fun `notifications desactivees - la section le dit et propose de les autoriser`() {
        compose.setContent {
            ReviewSettingsRoute(settings = InMemoryReviewSettings(), reschedule = {}, onStart = {}, onReports = {}, notifications = FixedNotificationsAllowed(false))
        }
        compose.onNodeWithTag(ReviewSettingsTags.ALLOW).assertIsDisplayed()
    }

    @Test
    fun `rappels voulus mais notifications bloquees - l interrupteur est eteint, honnete, et rien ne cache la demande`() {
        val settings = InMemoryReviewSettings() // rappels activés par défaut
        val permission = FixedNotificationsAllowed(false)
        compose.setContent {
            ReviewSettingsRoute(settings = settings, reschedule = {}, onStart = {}, onReports = {}, notifications = permission)
        }
        // Voulus ET permis : seul cet état allume l'interrupteur et montre l'heure.
        compose.onNodeWithTag(ReviewSettingsTags.SWITCH).assertIsOff()
        assertEquals(0, compose.onAllNodes(hasTestTag(ReviewSettingsTags.TIME)).fetchSemanticsNodes().size)
        compose.onNodeWithText("Notifications désactivées : pas d’invitation le lundi.").assertExists()
        compose.onNodeWithTag(ReviewSettingsTags.ALLOW).assertIsDisplayed()
        assertTrue(settings.state.value.enabled) // le choix de l'utilisateur n'est pas défait

        // L'autorisation arrive (retour des réglages d'Android) : l'interrupteur s'allume et l'heure apparaît.
        permission.set(true)
        compose.waitForIdle()
        compose.onNodeWithTag(ReviewSettingsTags.SWITCH).assertIsOn()
        compose.onNodeWithTag(ReviewSettingsTags.TIME).assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasTestTag(ReviewSettingsTags.ALLOW)).fetchSemanticsNodes().size)
    }

    @Test
    fun `rappels coupes a la main avec notifications permises - la section ne demande rien`() {
        val settings = InMemoryReviewSettings().also { it.setEnabled(false) }
        compose.setContent {
            ReviewSettingsRoute(settings = settings, reschedule = {}, onStart = {}, onReports = {}, notifications = FixedNotificationsAllowed(true))
        }
        compose.onNodeWithTag(ReviewSettingsTags.SWITCH).assertIsOff()
        assertEquals(0, compose.onAllNodes(hasTestTag(ReviewSettingsTags.ALLOW)).fetchSemanticsNodes().size)
    }
}
