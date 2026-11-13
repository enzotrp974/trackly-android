package app.trackly.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.DeleteConsumptions
import app.trackly.core.action.SetGoal
import app.trackly.core.model.EntryId
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.backup.drive.DriveAuthorization
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Faux WorkManager pour le travail unique de la sauvegarde automatique : `request` ne remplace jamais un passage
 * programmé ou en cours (`KEEP`), `followUp` programme le passage suivant (`APPEND_OR_REPLACE`).
 */
class FakeAutoScheduling(private val clock: MutableClock) : AutoBackupScheduling {
    var dueAt: Instant? = null
    var running = false
    val followUps = mutableListOf<Duration>()
    var cancelled = 0

    override fun request(delay: Duration) {
        if (dueAt == null && !running) dueAt = clock.instant.plus(delay)
    }

    override fun followUp(delay: Duration) {
        followUps += delay
        dueAt = clock.instant.plus(delay)
    }

    override fun cancel() {
        cancelled++
        dueAt = null
    }
}

/**
 * Sauvegarde automatique de bout en bout : vraie base Room sur fichier, faux Drive, faux `AuthorizationClient`,
 * horloge réglable. Chaque passage du travail est rejoué à l'heure où WorkManager le lancerait.
 */
@RunWith(AndroidJUnit4::class)
class AutoBackupTest {
    private val f = BackupFixture()
    private val scheduling = FakeAutoScheduling(f.clock)
    private val notifier = RecordingNotifier()
    private val auto = AutoBackup(f.runner, f.status, notifier, scheduling, f.clock)

    @After
    fun close() = f.close()

    private fun later(d: Duration) {
        f.clock.instant = f.clock.instant.plus(d)
        f.drive.now = f.clock.instant
    }

    private fun minutes(n: Long) = Duration.ofMinutes(n)

    /** Base remplie, Drive connecté, et une première copie déjà faite : tout est sur Drive. */
    private suspend fun savedBase(): FakeDrive.StoredFile {
        f.drive.now = f.clock.instant
        f.seed()
        val folder = f.connectWithFolder()
        check(f.runner.backUp() is BackupResult.Success)
        check(!f.status.status.value.needsBackup)
        f.drive.requests.clear()
        return folder
    }

    /** Lance le passage programmé, à son heure. */
    private suspend fun runDue(attempt: Int = 0): ListenableWorker.Result {
        val due = scheduling.dueAt ?: error("aucun passage programmé")
        scheduling.dueAt = null
        f.clock.instant = due
        f.drive.now = due
        scheduling.running = true
        try {
            return auto.run(attempt)
        } finally {
            scheduling.running = false
        }
    }

    @Test
    fun `serie de changements rapproches - une seule copie, quelques minutes apres le dernier`() = runBlocking<Unit> {
        val folder = savedBase()
        val start = f.clock.instant
        auto.onChange()
        later(minutes(1))
        auto.onChange()
        later(minutes(1))
        auto.onChange()
        // Un seul passage programmé, celui du premier changement.
        assertEquals(start.plus(AutoBackupPolicy.QUIET), scheduling.dueAt)

        // 3 min après le premier changement, le dernier n'a qu'une minute : on attend encore 2 min, sans rien envoyer.
        assertEquals(ListenableWorker.Result.success(), runDue())
        assertEquals(0, f.drive.uploads())
        assertEquals(start.plus(minutes(5)), scheduling.dueAt)

        assertEquals(ListenableWorker.Result.success(), runDue())
        assertEquals(1, f.drive.uploads())
        val copies = f.drive.childrenOf(folder.id)
        assertEquals(2, copies.size)
        assertFalse(f.status.status.value.needsBackup)
        assertNull(f.status.status.value.unsavedSince)
        assertNull("rien ne reste programmé", scheduling.dueAt)

        // La copie note ce qu'elle contient : la restauration automatique retrouve ainsi la dernière bonne copie sans la télécharger.
        val newest = copies.maxBy { it.createdTime }
        assertEquals("3", newest.appProperties[BackupFiles.CONSUMPTIONS_KEY])
        assertEquals("4", newest.appProperties[BackupFiles.VERSIONS_KEY]) // 3 consommations + 1 objectif
    }

    @Test
    fun `rien ne part sans changement - ni automatique ni la nuit`() = runBlocking<Unit> {
        savedBase()
        later(Duration.ofHours(20))

        auto.requestIfPending()
        assertNull(scheduling.dueAt)
        assertEquals(ListenableWorker.Result.success(), auto.run(attempt = 0))
        assertEquals(ListenableWorker.Result.success(), NightlyBackup(f.runner, notifier, f.status, f.clock).run(attempt = 0))
        assertTrue("aucune requête Drive", f.drive.requests.isEmpty())
    }

    @Test
    fun `changement pendant l envoi - une copie de plus apres un nouveau calme`() = runBlocking<Unit> {
        savedBase()
        auto.onChange()
        f.drive.onUpload = {
            f.drive.onUpload = null
            f.status.recordChange(f.clock.instant())
        }

        assertEquals(ListenableWorker.Result.success(), runDue())
        assertEquals(1, f.drive.uploads())
        assertTrue("le changement arrivé pendant l'envoi reste à copier", f.status.status.value.pending)
        assertEquals(listOf(AutoBackupPolicy.QUIET), scheduling.followUps)

        assertEquals(ListenableWorker.Result.success(), runDue())
        assertEquals(2, f.drive.uploads())
        assertFalse(f.status.status.value.pending)
    }

    /**
     * Le passage a fini son travail, WorkManager ne l'a pas encore noté fini : un changement à ce moment-là ne doit pas
     * être absorbé par `KEEP` (SAUV-05).
     */
    private suspend fun changeWhileFinishing(): ListenableWorker.Result {
        val due = scheduling.dueAt ?: error("aucun passage programmé")
        scheduling.dueAt = null
        f.clock.instant = due
        f.drive.now = due
        scheduling.running = true
        try {
            return auto.run(attempt = 0).also {
                later(Duration.ofSeconds(1))
                auto.onChange()
            }
        } finally {
            scheduling.running = false
        }
    }

    @Test
    fun `changement a la fin d un passage sans rien a copier - il garde son passage`() = runBlocking<Unit> {
        savedBase()
        auto.onChange()
        // « Sauvegarder maintenant » a déjà tout copié : le passage n'a rien à faire.
        later(minutes(1))
        assertTrue(f.runner.backUp() is BackupResult.Success)

        assertEquals(ListenableWorker.Result.success(), changeWhileFinishing())

        assertTrue(f.status.status.value.pending)
        val due = scheduling.dueAt
        assertTrue("le changement a son passage : $due", due != null)
        assertEquals(ListenableWorker.Result.success(), runDue())
        assertEquals(2, f.drive.uploads())
        assertFalse(f.status.status.value.pending)
        assertNull("puis plus rien : pas de copies en boucle", scheduling.dueAt)
    }

    @Test
    fun `changement a la fin d un passage qui a copie - il garde son passage, dans les delais`() = runBlocking<Unit> {
        savedBase()
        auto.onChange()

        assertEquals(ListenableWorker.Result.success(), changeWhileFinishing())

        assertEquals(1, f.drive.uploads())
        assertTrue(f.status.status.value.pending)
        val changedAt = f.clock.instant
        assertEquals("3 minutes après le changement", changedAt.plus(AutoBackupPolicy.QUIET), scheduling.dueAt)
        assertEquals(ListenableWorker.Result.success(), runDue())
        assertEquals(2, f.drive.uploads())
        assertNull("puis plus rien : pas de copies en boucle", scheduling.dueAt)
    }

    @Test
    fun `garde-fou - une app sans consommation n envoie jamais rien`() = runBlocking<Unit> {
        val folder = f.connectWithFolder()
        val good = f.drive.addFile(
            "trackly-2026-11-01_03-00-00.db", folder.id, byteArrayOf(7),
            mapOf(BackupFiles.KIND_KEY to BackupFiles.KIND_DATABASE, BackupFiles.CONSUMPTIONS_KEY to "40", BackupFiles.VERSIONS_KEY to "120"),
            createdTime = Instant.parse("2026-11-01T02:00:00Z"),
        )
        // App vide, sauf un objectif : un changement, mais aucune consommation.
        check(f.engine.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
        auto.onChange()

        assertEquals(ListenableWorker.Result.success(), runDue())

        assertTrue("Drive n'est même pas joint", f.drive.requests.isEmpty())
        assertEquals(listOf(good.id), f.drive.childrenOf(folder.id).map { it.id })
        assertFalse(good.trashed)
        val status = f.status.status.value
        assertTrue(status.refused)
        assertTrue(status.lastRefusalMessage!!, status.lastRefusalMessage!!.contains("aucune consommation"))
        assertFalse("un refus n'est pas une panne", status.failing)
        assertTrue(notifier.reconnect.isEmpty() && notifier.failing.isEmpty())
    }

    @Test
    fun `garde-fou - toutes les consommations supprimees, refus aussi`() = runBlocking<Unit> {
        val folder = savedBase()
        val ids = f.database.consumptions().activeBetween("2026-10-01", "2026-11-30").map { EntryId(it.entryId) }
        check(f.engine.execute(DeleteConsumptions(ids)) is ActionOutcome.Applied)
        auto.onChange()

        runDue()

        assertEquals(0, f.drive.uploads())
        assertEquals(1, f.drive.childrenOf(folder.id).size)
        assertTrue(f.status.status.value.refused)
    }

    @Test
    fun `garde-fou - une base moins remplie que la derniere copie n est pas envoyee, sauf apres une restauration`() = runBlocking<Unit> {
        f.seed() // 4 versions
        val folder = f.connectWithFolder()
        val props = mapOf(BackupFiles.KIND_KEY to BackupFiles.KIND_DATABASE, BackupFiles.CONSUMPTIONS_KEY to "30", BackupFiles.VERSIONS_KEY to "50")
        val good = f.drive.addFile("trackly-2026-11-02_11-00-00.db", folder.id, byteArrayOf(7), props, createdTime = f.clock.instant.minus(minutes(30)))
        later(Duration.ZERO)
        auto.onChange()

        runDue()

        assertEquals(0, f.drive.uploads())
        val refusal = f.status.status.value.lastRefusalMessage!!
        assertTrue(refusal, refusal.contains("moins de données") && refusal.contains("4 versions contre 50"))
        assertFalse(f.drive.files[good.id]!!.trashed)

        // L'utilisateur a restauré une date plus ancienne : cette base devient la référence voulue.
        f.status.recordRestore(f.clock.instant(), "Copie du 20 octobre")
        later(minutes(1))
        auto.onChange()
        runDue()

        assertEquals(1, f.drive.uploads())
        assertFalse(f.status.status.value.refused)
        assertFalse(f.drive.files[good.id]!!.trashed)
    }

    /**
     * Limite fermée : une base ancienne (remise par Auto Backup) refusée ne finit pas par passer en continuant à
     * saisir. La copie qui a refusé est mémorisée ; seule une restauration lève le blocage.
     */
    @Test
    fun `garde-fou - la copie qui a refuse reste la reference, meme quand l app la depasse en nombre`() = runBlocking<Unit> {
        f.seed() // 4 versions
        val folder = f.connectWithFolder()
        val props = mapOf(BackupFiles.KIND_KEY to BackupFiles.KIND_DATABASE, BackupFiles.CONSUMPTIONS_KEY to "3", BackupFiles.VERSIONS_KEY to "6")
        val good = f.drive.addFile("trackly-2026-11-02_11-00-00.db", folder.id, byteArrayOf(7), props, createdTime = f.clock.instant.minus(minutes(30)))
        later(Duration.ZERO)
        auto.onChange()
        runDue()
        assertEquals(0, f.drive.uploads())
        assertEquals(good.id, f.status.blockingCopyId)

        // On continue à saisir sans restaurer : 7 versions, plus que les 6 de la copie.
        for (i in 1..3) f.add(f.engine, BackupFixture.MONDAY, Moment.Snack, "Fruit $i", 60.0, 1.0, 14.0, 0.2)
        later(minutes(5))
        auto.onChange()
        runDue()

        assertEquals("toujours rien : compter ne prouve pas que l'app contient cette copie", 0, f.drive.uploads())
        val refusal = f.status.status.value.lastRefusalMessage!!
        assertTrue(refusal, refusal.contains("n'a pas repris la copie Drive"))
        assertFalse(f.drive.files[good.id]!!.trashed)

        // Restaurer (cette copie ou une autre, choix voulu) lève le blocage.
        f.status.recordRestore(f.clock.instant(), "Copie choisie")
        assertNull(f.status.blockingCopyId)
        later(minutes(1))
        auto.onChange()
        runDue()
        assertEquals(1, f.drive.uploads())
    }

    /**
     * La copie qui a refusé n'est plus visible (accès retiré depuis le compte Google, dossier supprimé) et la
     * reconnexion crée un dossier vide. Sans référence, la base moins complète partirait : l'envoi reste refusé, avec la
     * sortie écrite (Depuis un fichier, ou export puis Depuis un fichier), et seule une restauration le lève.
     */
    @Test
    fun `garde-fou - copie de reference devenue invisible, dossier neuf - toujours refuse jusqu a une restauration`() = runBlocking<Unit> {
        f.seed() // 4 versions
        val folder = f.connectWithFolder()
        val props = mapOf(BackupFiles.KIND_KEY to BackupFiles.KIND_DATABASE, BackupFiles.CONSUMPTIONS_KEY to "3", BackupFiles.VERSIONS_KEY to "6")
        val good = f.drive.addFile("trackly-2026-11-02_11-00-00.db", folder.id, byteArrayOf(7), props, createdTime = f.clock.instant.minus(minutes(30)))
        later(Duration.ZERO)
        auto.onChange()
        runDue()
        assertEquals(good.id, f.status.blockingCopyId)

        // Trackly ne voit plus l'ancien dossier ni sa copie (elles restent dans Drive) ; la reconnexion en crée un neuf.
        f.drive.files.remove(good.id)
        f.drive.files.remove(folder.id)
        val fresh = f.folder.findOrCreate(f.api())
        assertTrue(fresh.created)
        for (i in 1..3) f.add(f.engine, BackupFixture.MONDAY, Moment.Snack, "Fruit $i", 60.0, 1.0, 14.0, 0.2)
        later(minutes(5))
        auto.onChange()
        runDue()

        assertEquals("rien ne part dans le dossier neuf", 0, f.drive.uploads())
        assertTrue(f.drive.childrenOf(fresh.folder.id).isEmpty())
        assertEquals(good.id, f.status.blockingCopyId)
        val refusal = f.status.status.value.lastRefusalMessage!!
        assertTrue(refusal, refusal.contains("n'est plus visible"))
        assertTrue(refusal, refusal.contains("Depuis un fichier"))
        assertTrue("la sortie quand on n'a plus la copie : $refusal", refusal.contains("Exporter dans un fichier"))
        assertTrue(notifier.reconnect.isEmpty() && notifier.failing.isEmpty())

        // Sortie : une restauration (la copie retrouvée, ou l'export de la base actuelle) lève le blocage.
        f.status.recordRestore(f.clock.instant(), "Fichier choisi sur le téléphone")
        later(minutes(1))
        auto.onChange()
        runDue()
        assertEquals(1, f.drive.uploads())
    }

    /**
     * La copie bloquante dépassée par une copie plus récente (autre appareil), puis mise à la corbeille
     * par la rotation, ne doit pas tout bloquer. Une copie réussie l'oublie.
     */
    @Test
    fun `garde-fou - copie bloquante depassee puis mise a la corbeille - les envois continuent`() = runBlocking<Unit> {
        f.seed() // 4 versions
        val folder = f.connectWithFolder()
        val props = mapOf(BackupFiles.KIND_KEY to BackupFiles.KIND_DATABASE, BackupFiles.CONSUMPTIONS_KEY to "3", BackupFiles.VERSIONS_KEY to "6")
        val blocking = f.drive.addFile("trackly-2026-11-02_11-00-00.db", folder.id, byteArrayOf(7), props, createdTime = f.clock.instant.minus(minutes(30)))
        later(Duration.ZERO)
        auto.onChange()
        runDue()
        assertEquals(blocking.id, f.status.blockingCopyId)

        // Un autre appareil envoie une copie plus récente, moins complète que l'app : elle devient la référence.
        val newer = mapOf(BackupFiles.KIND_KEY to BackupFiles.KIND_DATABASE, BackupFiles.CONSUMPTIONS_KEY to "2", BackupFiles.VERSIONS_KEY to "3")
        f.drive.addFile("trackly-2026-11-02_11-45-00.db", folder.id, byteArrayOf(8), newer, createdTime = f.clock.instant.plus(minutes(1)))
        later(minutes(5))
        auto.onChange()
        runDue()
        assertEquals(1, f.drive.uploads())
        assertNull("une copie partie oublie la copie bloquante", f.status.blockingCopyId)

        f.drive.files[blocking.id]!!.trashed = true // rotation
        f.add(f.engine, BackupFixture.MONDAY, Moment.Snack, "Pomme", 60.0, 0.3, 14.0, 0.2)
        later(minutes(5))
        auto.onChange()
        runDue()
        assertEquals(2, f.drive.uploads())
    }

    @Test
    fun `garde-fou - sans copie bloquante, un dossier neuf recoit la copie comme avant`() = runBlocking<Unit> {
        f.seed()
        val fresh = f.connectWithFolder()
        later(Duration.ZERO)
        auto.onChange()
        runDue()
        assertEquals(1, f.drive.uploads())
        assertEquals(1, f.drive.childrenOf(fresh.id).size)
        assertNull(f.status.blockingCopyId)
    }

    @Test
    fun `jeton refuse sans ecran - notification Reconnecte Google Drive, puis plus d essai automatique`() = runBlocking<Unit> {
        savedBase()
        f.auth.needsUserAction()
        auto.onChange()

        assertEquals(ListenableWorker.Result.success(), runDue())

        assertEquals(1, notifier.reconnect.size)
        assertTrue(f.status.status.value.needsReconnect)
        assertEquals(0, f.drive.uploads())
        // La reconnexion attend l'utilisateur : les changements suivants ne relancent rien, ni nouvelle notification.
        later(minutes(10))
        auto.onChange()
        assertNull(scheduling.dueAt)
        assertEquals(1, notifier.reconnect.size)
    }

    @Test
    fun `Drive indisponible - nouvel essai, alerte seulement si les essais echouent depuis 2 jours`() = runBlocking<Unit> {
        savedBase()
        auto.onChange()
        later(minutes(5))
        f.drive.failWith = 503

        assertEquals(ListenableWorker.Result.retry(), auto.run(attempt = 0))
        // Essais épuisés : le travail finit en succès (sa suite chaînée ne doit pas échouer avec lui), l'échec reste noté.
        assertEquals(ListenableWorker.Result.success(), auto.run(attempt = 3))
        assertTrue(f.status.status.value.pending)
        assertTrue(f.status.status.value.failingSince != null)
        assertTrue("quelques minutes de retard : pas d'alerte", notifier.failing.isEmpty())

        later(Duration.ofHours(49))
        assertEquals(ListenableWorker.Result.success(), auto.run(attempt = 3))
        assertEquals(1, notifier.failing.size)
    }

    @Test
    fun `ecriture jamais signalee - la nuit compare la base a la derniere copie et la rattrape`() = runBlocking<Unit> {
        val folder = savedBase()
        val night = NightlyBackup(f.runner, notifier, f.status, f.clock)
        // Écriture faite avant que l'écoute ne commence : aucun signal, rien de marqué « à copier ».
        f.add(f.engine, BackupFixture.MONDAY, Moment.Soir, "Soupe", 300.0, 10.0, 40.0, 8.0)
        assertFalse(f.status.status.value.needsBackup)
        later(Duration.ofHours(14))

        assertEquals(ListenableWorker.Result.success(), night.run(attempt = 0))
        assertEquals(1, f.drive.uploads())
        assertEquals(2, f.drive.childrenOf(folder.id).size)

        // Nuit suivante, rien de changé : rien ne part, Drive n'est pas joint.
        f.drive.requests.clear()
        later(Duration.ofHours(24))
        assertEquals(ListenableWorker.Result.success(), night.run(attempt = 0))
        assertTrue(f.drive.requests.isEmpty())
    }

    @Test
    fun `projet Google mal configure - une notification, puis plus d essai automatique`() = runBlocking<Unit> {
        savedBase()
        f.auth.next = { DriveAuthorization.Failed("Projet Google Cloud de Trackly pas prêt", misconfigured = true) }
        auto.onChange()

        assertEquals(ListenableWorker.Result.success(), runDue())
        assertEquals(1, notifier.reconnect.size)
        later(minutes(10))
        auto.onChange()
        assertNull("pas une notification par changement", scheduling.dueAt)
    }

    @Test
    fun `premier essai en echec juste apres la connexion - pas d alerte « depuis 2 jours »`() = runBlocking<Unit> {
        f.seed()
        f.connectWithFolder()
        auto.onChange()
        f.drive.failWith = 403

        assertEquals(ListenableWorker.Result.success(), runDue(attempt = 3))
        assertTrue(notifier.failing.isEmpty())

        later(Duration.ofHours(49))
        auto.run(attempt = 3)
        assertEquals(1, notifier.failing.size)
    }

    @Test
    fun `deconnecter puis connecter un autre compte - une premiere copie part dans le nouveau dossier`() = runBlocking<Unit> {
        savedBase()
        f.status.markDisconnected()
        f.status.markConnected("autre@example.com")
        assertTrue(f.status.status.value.needsBackup)
        auto.requestIfPending()
        assertTrue(scheduling.dueAt != null)
    }

    @Test
    fun `restauration appliquee - la base restauree est a copier`() = runBlocking<Unit> {
        savedBase()
        f.status.recordRestore(f.clock.instant(), "Copie avant la migration")
        assertTrue(f.status.status.value.needsBackup)
    }

    @Test
    fun `envoi en double evite - une copie deja faite par le bouton n est pas refaite par le travail`() = runBlocking<Unit> {
        savedBase()
        auto.onChange()
        later(minutes(5))
        check(f.runner.backUp() is BackupResult.Success) // bouton, avant le passage automatique
        f.drive.requests.clear()

        assertEquals(ListenableWorker.Result.success(), auto.run(attempt = 0))
        assertTrue(f.drive.requests.isEmpty())
    }

    @Test
    fun `sans connexion Google - aucun passage programme, les changements attendent`() = runBlocking<Unit> {
        f.seed()
        auto.onChange()
        assertNull(scheduling.dueAt)
        assertTrue(f.status.status.value.pending)
        assertEquals(ListenableWorker.Result.success(), auto.run(attempt = 0))
        assertTrue(f.drive.requests.isEmpty())
    }

    @Test
    fun `premiere version avec la sauvegarde automatique - une copie part sans attendre un changement`() = runBlocking<Unit> {
        f.seed()
        f.connectWithFolder()
        // Mise à jour par-dessus : dernière copie de nuit connue, mais aucun suivi des changements encore.
        f.status.recordSuccess(f.clock.instant().minus(Duration.ofHours(9)), "trackly-2026-11-02_03-00-00.db", seq = -1)

        auto.requestIfPending()
        runDue()

        assertEquals(1, f.drive.uploads())
        assertFalse(f.status.status.value.needsBackup)
    }
}

/** Règle du calme : quelques minutes sans changement, jamais plus de 15 minutes après le premier. */
class AutoBackupPolicyTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z")
    private val connected = BackupStatus(connected = true, lastSuccessAt = now.minus(Duration.ofHours(1)), pending = true)

    private fun status(lastChangeAgo: Duration, unsavedAgo: Duration) =
        connected.copy(lastChangeAt = now.minus(lastChangeAgo), unsavedSince = now.minus(unsavedAgo))

    @Test
    fun `calme atteint - copie`() {
        assertEquals(AutoBackupPolicy.Decision.BackUp, AutoBackupPolicy.decide(status(Duration.ofMinutes(3), Duration.ofMinutes(5)), now))
    }

    @Test
    fun `changements recents - attendre la fin du calme, ou la limite si elle vient avant`() {
        assertEquals(
            AutoBackupPolicy.Decision.Wait(Duration.ofMinutes(2)),
            AutoBackupPolicy.decide(status(Duration.ofMinutes(1), Duration.ofMinutes(5)), now),
        )
        assertEquals(
            AutoBackupPolicy.Decision.Wait(Duration.ofMinutes(1)),
            AutoBackupPolicy.decide(status(Duration.ofSeconds(10), Duration.ofMinutes(14)), now),
        )
    }

    @Test
    fun `changements continus - copie 15 minutes apres le premier`() {
        assertEquals(AutoBackupPolicy.Decision.BackUp, AutoBackupPolicy.decide(status(Duration.ofSeconds(5), Duration.ofMinutes(15)), now))
    }

    @Test
    fun `rien a copier, pas connecte ou reconnexion demandee - rien`() {
        val skip = AutoBackupPolicy.Decision.Skip
        assertEquals(skip, AutoBackupPolicy.decide(connected.copy(pending = false), now))
        assertEquals(skip, AutoBackupPolicy.decide(connected.copy(connected = false), now))
        assertEquals(skip, AutoBackupPolicy.decide(connected.copy(needsReconnect = true), now))
    }
}
