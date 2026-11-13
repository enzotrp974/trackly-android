package app.trackly.review

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.MainActivity
import app.trackly.core.review.WeekRange
import app.trackly.ui.review.configureReportWebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Instant
import java.time.LocalDate

/** Notifications du bilan : canal dédié, textes, et ce que le toucher ouvre. */
@RunWith(AndroidJUnit4::class)
class ReviewNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)!!
    private val week = WeekRange(LocalDate.parse("2026-10-26"))

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    private fun title(n: Notification) = n.extras.getString(Notification.EXTRA_TITLE)

    @Test
    fun `le bilan est pret - la notification ouvre le rapport dans l app`() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        AndroidReviewNotifier(context).reportReady(ReportInfo(7, week.start, Instant.parse("2026-11-02T09:00:00Z")))

        val notification = posted().single()
        assertEquals("Ton bilan est prêt", title(notification))
        assertEquals(AndroidReviewNotifier.CHANNEL, notification.channelId)
        val open = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, open.component!!.className)
        assertEquals(MainActivity.OPEN_REPORT, open.getStringExtra(MainActivity.EXTRA_OPEN))
        assertTrue("pas de bouton sur « bilan prêt »", notification.actions == null || notification.actions.isEmpty())
        assertEquals(7L, open.getLongExtra(MainActivity.EXTRA_REPORT_ID, -1L))
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    /** La carte « Ton bilan est prêt » ne dit « notification envoyée » que si elle l'est ; canal coupé, elle ne l'est pas. */
    @Test
    fun `bilan pret - envoyee si permise, pas si le canal Bilan du lundi est coupe`() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val report = ReportInfo(7, week.start, Instant.parse("2026-11-02T09:00:00Z"))
        assertTrue(AndroidReviewNotifier(context).reportReady(report))

        manager.cancelAll()
        AndroidReviewNotifier.ensureChannel(context)
        manager.getNotificationChannel(AndroidReviewNotifier.CHANNEL).importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(manager.getNotificationChannel(AndroidReviewNotifier.CHANNEL))

        assertFalse(AndroidReviewNotifier(context).reportReady(report))
    }

    @Test
    fun `invitation et relance - elles ouvrent la conversation de bilan`() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = AndroidReviewNotifier(context)

        notifier.invite(week)
        notifier.remind(week)

        val titles = posted().map(::title)
        assertEquals(listOf("Ton bilan t'attend", "C'est lundi : fais ton bilan"), titles.sortedBy { it!!.length })
        for (n in posted()) {
            val intent = shadowOf(n.contentIntent).savedIntent
            assertEquals(MainActivity.OPEN_REVIEW, intent.getStringExtra(MainActivity.EXTRA_OPEN))
        }
        // Bouton « Faire mon bilan » : il ouvre la même conversation.
        for (n in posted()) {
            val action = n.actions.single()
            assertEquals("Faire mon bilan", action.title.toString())
            assertEquals(MainActivity.OPEN_REVIEW, shadowOf(action.actionIntent).savedIntent.getStringExtra(MainActivity.EXTRA_OPEN))
        }
        // Canal dédié, importance par défaut.
        val channel = manager.getNotificationChannel(AndroidReviewNotifier.CHANNEL)
        assertNotNull(channel)
        assertEquals("Bilan du lundi", channel.name.toString())
    }

    @Test
    fun `permission refusee - rien n est envoye, sans erreur`() {
        AndroidReviewNotifier(context).invite(week)
        assertTrue(posted().isEmpty())
        assertFalse(AndroidReviewNotifier.canPost(context))
    }

    @Test
    fun `le rapport s ouvre dans un WebView sans JavaScript ni reseau`() {
        val view = WebView(context)
        configureReportWebView(view)
        val settings = view.settings
        assertFalse(settings.javaScriptEnabled)
        assertTrue(settings.blockNetworkLoads)
        assertTrue(settings.blockNetworkImage)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.domStorageEnabled)
        assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
    }
}
