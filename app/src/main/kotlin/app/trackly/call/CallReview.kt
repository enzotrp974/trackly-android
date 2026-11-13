package app.trackly.call

import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.chat.ChatHistory
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.UnseenQuestions
import app.trackly.core.time.TrackingDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Relecture d'un appel qui attend de finir : ce qu'il faut pour la reprendre après un arrêt d'Android. Gardée sur le
 * téléphone seulement le temps de la relecture ([PendingReviewStore]), puis effacée.
 */
data class PendingReview(
    /** Début de l'appel (heure murale) : sert d'identifiant, et ce qui a été noté depuis se relit dans le fil du jour. */
    val startedAt: Instant,
    val endedAt: Instant,
    val reflection: CallReflection,
    val transcript: List<CallLine>,
    val noted: List<NotedEntry> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val unprocessed: List<String> = emptyList(),
    /**
     * L'appel est fini, mais sa dernière réflexion n'avait pas encore rendu son rapport : on ne sait pas ce qu'elle a
     * traité. Remplacée par le rapport dès qu'il arrive ; si l'app est tuée avant, la relecture relit toute la parole de l'utilisateur.
     */
    val provisional: Boolean = false,
    /** Essais déjà commencés (un essai interrompu par un arrêt d'Android compte). */
    val attempts: Int = 0,
    /**
     * Début du dernier essai. Si le fil du jour contient depuis un message final de la relecture, cet essai a abouti (l'app a
     * été tuée juste avant d'effacer le fichier) : il n'est pas refait.
     */
    val attemptAt: Instant? = null,
)

/**
 * Où attendent les relectures pas encore faites. Sur le téléphone, un fichier privé de l'app ([FilePendingReviewStore]),
 * comme la transcription du dernier appel : jamais dans la sauvegarde Android ni sur Drive, effacé dès la relecture finie.
 */
interface PendingReviewStore {
    /** Rapide (lisible sur le fil principal) : y a-t-il quelque chose à reprendre ? */
    fun isEmpty(): Boolean

    fun all(): List<PendingReview>

    fun put(review: PendingReview)

    fun remove(startedAt: Instant)

    /**
     * Le fichier existe mais ne se lit plus, lancement après lancement : au bout de quelques lancements, il est mis de côté
     * (jamais effacé) et un fichier neuf prend sa place. `true` s'il vient d'être mis de côté.
     */
    fun setAsideIfUnreadable(at: Instant): Boolean = false
}

class InMemoryPendingReviewStore : PendingReviewStore {
    private val items = mutableListOf<PendingReview>()

    override fun isEmpty(): Boolean = synchronized(items) { items.isEmpty() }

    override fun all(): List<PendingReview> = synchronized(items) { items.toList() }

    override fun put(review: PendingReview) = synchronized(items) {
        items.removeAll { it.startedAt == review.startedAt }
        items += review
    }

    override fun remove(startedAt: Instant) {
        synchronized(items) { items.removeAll { it.startedAt == startedAt } }
    }
}

/** Relectures en attente dans `filesDir/appel` : hors sauvegarde Android et hors copie Drive (`CallTranscriptExclusionTest`). */
class FilePendingReviewStore(private val file: File) : PendingReviewStore {
    @Serializable
    private data class Line(val qui: String, val texte: String)

    @Serializable
    private data class Entry(
        val id: String,
        val libelle: String,
        val moment: String,
        val jour: String,
        val produit: String? = null,
        val recette: String? = null,
        val action: Long,
    )

    @Serializable
    private data class Stored(
        val debut: Long,
        val fin: Long,
        val reglage: String,
        val transcription: List<Line>,
        val notes: List<Entry> = emptyList(),
        val questions: List<String> = emptyList(),
        val nonTraite: List<String> = emptyList(),
        val provisoire: Boolean = false,
        val essais: Int = 0,
        val essaiA: Long? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Stored.serializer())

    /** Lancements où le fichier n'a pas pu être lu, d'affilée (dans le même dossier privé, hors sauvegardes). */
    private val unreadableLaunches = File(file.path + UNREADABLE_SUFFIX)

    /** Une lecture ratée n'est comptée qu'une fois par lancement (ce magasin vit autant que le processus). */
    private var unreadableCounted = false

    override fun isEmpty(): Boolean = !file.isFile

    /**
     * Une lecture ratée (fichier illisible un instant) remonte en erreur : prise pour « rien en attente », elle laisserait
     * une autre relecture écrire par-dessus, et [put] remplacerait le fichier en perdant ce qu'il gardait.
     * Un contenu illisible pour de bon, lui, ne bloque pas les relectures suivantes.
     */
    @Synchronized
    override fun all(): List<PendingReview> {
        if (!file.isFile) return emptyList()
        val text = file.readText()
        if (unreadableLaunches.isFile) unreadableLaunches.delete()
        return runCatching { json.decodeFromString(serializer, text).map(::toModel) }.getOrDefault(emptyList())
    }

    @Synchronized
    override fun put(review: PendingReview) = save(all().filterNot { it.startedAt == review.startedAt } + review)

    @Synchronized
    override fun remove(startedAt: Instant) = save(all().filterNot { it.startedAt == startedAt })

    @Synchronized
    override fun setAsideIfUnreadable(at: Instant): Boolean {
        if (!file.isFile || unreadableCounted || runCatching { file.readText() }.isSuccess) return false
        unreadableCounted = true
        val launches = runCatching { unreadableLaunches.readText().trim().toInt() }.getOrDefault(0) + 1
        if (launches < SET_ASIDE_AFTER_LAUNCHES) {
            runCatching { unreadableLaunches.writeText(launches.toString()) }
            return false
        }
        if (!file.renameTo(File(file.parentFile, SET_ASIDE_PREFIX + at.toEpochMilli() + ".json"))) return false
        unreadableLaunches.delete()
        return true
    }

    private fun save(items: List<PendingReview>) {
        if (items.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(serializer, items.map(::toStored)))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun toStored(r: PendingReview) = Stored(
        debut = r.startedAt.toEpochMilli(),
        fin = r.endedAt.toEpochMilli(),
        reglage = r.reflection.code,
        transcription = r.transcript.map { Line(it.speaker.name, it.text) },
        notes = r.noted.filterNot { it.deleted }.map { Entry(it.entryId, it.label, it.moment, it.day, it.productId, it.recipeId, it.actionId) },
        questions = r.openQuestions,
        nonTraite = r.unprocessed,
        provisoire = r.provisional,
        essais = r.attempts,
        essaiA = r.attemptAt?.toEpochMilli(),
    )

    private fun toModel(s: Stored) = PendingReview(
        startedAt = Instant.ofEpochMilli(s.debut),
        endedAt = Instant.ofEpochMilli(s.fin),
        reflection = CallReflection.fromCode(s.reglage),
        transcript = s.transcription.map { CallLine(Speaker.entries.firstOrNull { e -> e.name == it.qui } ?: Speaker.User, it.texte) },
        noted = s.notes.map { NotedEntry(it.id, it.libelle, it.moment, it.jour, it.produit, it.recette, it.action) },
        openQuestions = s.questions,
        unprocessed = s.nonTraite,
        provisional = s.provisoire,
        attempts = s.essais,
        attemptAt = s.essaiA?.let(Instant::ofEpochMilli),
    )

    companion object {
        const val FILE_NAME = "relectures-en-attente.json"

        /** Début du nom d'un fichier illisible mis de côté (jamais effacé, dans le même dossier privé). */
        const val SET_ASIDE_PREFIX = "relectures-mises-de-cote-"

        /** Compteur des lancements où le fichier ne s'est pas lu, à côté de lui. */
        const val UNREADABLE_SUFFIX = ".illisible"

        /** Une panne passagère attend ; au troisième lancement d'affilée sans lecture, le fichier est mis de côté. */
        const val SET_ASIDE_AFTER_LAUNCHES = 3
    }
}

/** Ce que la relecture après l'appel reçoit. */
data class ReviewRequest(
    val transcript: List<CallLine>,
    /** Début de l'appel : tout ce qui est enregistré depuis (pendant la relecture aussi, par le chat) compte comme déjà noté. */
    val startedAt: Instant,
    /** Déjà noté pendant l'appel et depuis (fil du jour) : à corriger, jamais à noter une deuxième fois. */
    val noted: List<NotedEntry>,
    /** Questions de la dernière réflexion restées sans réponse, dans l'ordre d'importance. */
    val openQuestions: List<String>,
    /** Paroles de l'utilisateur qu'aucune réflexion n'a traitées. */
    val unprocessed: List<String>,
    /** La dernière réflexion n'avait pas rendu son rapport : toute la parole de l'utilisateur est à relire. */
    val provisional: Boolean,
    val reflection: CallReflection,
)

sealed interface ReviewResult {
    /** Ce que la relecture a noté ou corrigé (déjà dans le chat du jour, annulable). */
    val noted: List<NotedEntry>

    /** Relecture finie. [questions] : celles écrites dans le fil du jour (vide : rien n'a été écrit, sauf des cartes). */
    data class Done(val questions: List<String>, override val noted: List<NotedEntry> = emptyList()) : ReviewResult

    /** Réseau, clé, réponse illisible… : rien n'est dit, la relecture sera reprise. Ce qui a déjà été noté reste noté. */
    data class Failed(val reason: String, override val noted: List<NotedEntry> = emptyList()) : ReviewResult
}

/** La relecture elle-même : l'agent Trackly du chat, au réglage de l'appel ([AgentCallThinker]). */
fun interface CallReviewer {
    suspend fun review(request: ReviewRequest): ReviewResult

    companion object {
        val None = CallReviewer { ReviewResult.Failed("aucune relecture") }
    }
}

/**
 * Relecture après l'appel. Une fois l'appel fini et sa dernière réflexion terminée, la réflexion relit **une fois**,
 * sans bloquer l'écran, au même modèle et au même effort que pendant l'appel :
 * - les paroles qu'aucune réflexion n'a traitées sont notées si elles sont claires, sinon demandées ;
 * - les questions restées sans réponse et importantes sont écrites dans le fil du jour, sous le récap de l'appel déjà écrit
 *   par les réflexions ; rien d'autre n'est répété. Aucune question : rien de plus. Une pastille les signale sur
 *   l'accueil tant que l'utilisateur n'a pas ouvert le chat ([UnseenQuestions]) ; jamais de notification Android.
 * - Tout traité pendant l'appel, ou pur bavardage : aucune relecture, rien d'écrit, rien de payé.
 *
 * **Survit à un arrêt.** Ce qu'il faut pour la reprendre est gardé dans [store] dès la fin de l'appel, puis effacé dès que la
 * relecture a réussi. Échec (réseau…) : nouvel essai à la prochaine ouverture de l'app, [MAX_ATTEMPTS] essais au plus, puis
 * un message court dans le fil du jour, sans aucun chiffre. Hors ligne, aucun essai n'est compté.
 */
class CallReview(
    private val scope: CoroutineScope,
    private val store: PendingReviewStore,
    private val reviewer: CallReviewer,
    private val history: ChatHistory,
    private val unseen: UnseenQuestions,
    /** Heure du moteur (date de rattachement du fil du jour). */
    private val now: () -> Instant,
    private val isOnline: () -> Boolean = { true },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = 120_000,
) {
    /** Écritures du fichier, dans l'ordre d'arrivée (la fin d'appel d'abord, son rapport ensuite). */
    private val storeLock = Mutex()

    /** Une relecture à la fois. */
    private val runLock = Mutex()

    /**
     * Relectures que le fichier n'a pas pu garder (disque plein) : reprises depuis la mémoire tant que l'app vit, comme le
     * rapport de l'appel gardé en mémoire. Effacées dès qu'elles sont faites ou que le fichier les garde.
     */
    private val unsaved = java.util.concurrent.ConcurrentHashMap<Instant, PendingReview>()

    /** Appels dont le rapport est arrivé dans ce processus : leur fin d'appel provisoire ne doit plus être écrite. */
    private val reported: MutableSet<Instant> = java.util.Collections.synchronizedSet(mutableSetOf())

    /**
     * Un appel est en cours : aucune relecture ne part (elle partage le verrou de la réflexion, qui doit rester libre pour
     * l'appel). Branché par `CallServices`.
     */
    @Volatile var callInProgress: () -> Boolean = { false }

    private val jobs: MutableSet<Job> = java.util.Collections.synchronizedSet(mutableSetOf())
    private val active = java.util.concurrent.atomic.AtomicInteger(0)
    private val _running = MutableStateFlow(false)

    /** Une relecture est en cours ou va partir : l'app reste en vie jusqu'à sa fin (micro fermé). */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * Pendant l'appel (phrase finie de l'utilisateur, réflexion qui part) : la même version provisoire que [onCallEnded], tenue à jour,
     * pour qu'un arrêt d'Android avant le raccroché ne perde aucun repas dit. À la reprise, la relecture relit
     * toute la parole de l'utilisateur avec la garde contre les doublons : chaque repas est noté, ou devient une question. Écriture
     * légère, hors du fil de l'appel ; [running] ne bouge pas (l'appel n'est pas retenu pour autant). Pendant l'appel, rien
     * n'est relu ([callInProgress]).
     */
    fun onCallProgress(startedAt: Instant, transcript: List<CallLine>, reflection: CallReflection) {
        if (!SmallTalk.substantive(transcript.filter { it.speaker == Speaker.User }.map { it.text })) return
        val at = now()
        // Lancé sans attendre le répartiteur : les écritures prennent le verrou dans l'ordre des appels (la fin d'appel après).
        scope.launch(start = CoroutineStart.UNDISPATCHED) { keepProvisional(startedAt, at, transcript, reflection) }
    }

    /**
     * Fin de l'appel, avant son rapport (la dernière réflexion peut encore tourner) : de quoi relire est gardé tout de suite,
     * pour que la relecture se fasse même si Android tue l'app avant le rapport. Rien pour un appel sans vraie parole.
     */
    fun onCallEnded(startedAt: Instant, transcript: List<CallLine>, reflection: CallReflection) {
        if (!SmallTalk.substantive(transcript.filter { it.speaker == Speaker.User }.map { it.text })) return
        val endedAt = now()
        busy { keepProvisional(startedAt, endedAt, transcript, reflection) }
    }

    /** Version provisoire, remplacée par une plus complète ; jamais par-dessus le rapport de l'appel. */
    private suspend fun keepProvisional(startedAt: Instant, at: Instant, transcript: List<CallLine>, reflection: CallReflection) {
        stored {
            if (startedAt in reported) return@stored
            val before = all().firstOrNull { it.startedAt == startedAt }
            if (before != null && !before.provisional) return@stored
            put(PendingReview(startedAt, at, reflection, transcript, provisional = true))
        }
    }

    /** Rapport de l'appel : relecture tout de suite si elle sert, sinon rien n'est gardé ni payé. */
    fun onReport(report: CallMealReport) {
        reported += report.startedAt
        val reportedAt = now()
        busy {
            val worth = report.openQuestions.isNotEmpty() || SmallTalk.substantive(report.unprocessed)
            if (worth) review(report, reportedAt) else stored { remove(report.startedAt) }
            // Puis celles d'appels précédents restées en attente (laissées pour cet appel, ou en échec).
            for (other in waiting()) if (other != report.startedAt) run(other)
        }
    }

    /** Relecture d'un appel dont le rapport vient d'arriver : gardée dans [store], puis lancée. */
    private suspend fun review(report: CallMealReport, reportedAt: Instant) {
        val before = stored { all().firstOrNull { it.startedAt == report.startedAt } }
        val pending = PendingReview(
            startedAt = report.startedAt,
            // La fin de l'appel, pas l'heure du rapport (la dernière réflexion a pu finir bien après).
            endedAt = before?.endedAt ?: reportedAt,
            reflection = report.reflection,
            transcript = report.transcript,
            noted = report.noted,
            openQuestions = report.openQuestions,
            unprocessed = report.unprocessed,
            attempts = before?.attempts ?: 0,
            attemptAt = before?.attemptAt,
        )
        // Fichier impossible à écrire (disque plein) : la relecture part quand même, avec le rapport en mémoire.
        if (stored { put(pending) } == null) unsaved[pending.startedAt] = pending else unsaved.remove(pending.startedAt)
        run(pending.startedAt)
    }

    /** Appels dont la relecture attend : ceux du fichier (s'il se lit), puis ceux gardés en mémoire seulement. */
    private suspend fun waiting(): List<Instant> = (stored { all() }.orEmpty().map { it.startedAt } + unsaved.keys).distinct()

    /**
     * Un appel commence : la relecture en cours s'arrête tout de suite pour libérer la réflexion. Elle reste en attente, sans
     * compter cet essai, et reprend après le rapport de cet appel (ou à la prochaine ouverture).
     */
    fun yieldToCall() {
        synchronized(jobs) { jobs.toList() }.forEach { it.cancel() }
    }

    /**
     * Ouverture de l'app : les relectures restées en attente (échec, app tuée) sont reprises. `false` s'il n'y a rien à
     * reprendre ([store] vide, lu sans attendre) ou si une relecture tourne déjà.
     */
    fun resume(): Boolean {
        if (_running.value || (unsaved.isEmpty() && runCatching { store.isEmpty() }.getOrDefault(true))) return false
        busy {
            // Fichier illisible lancement après lancement : mis de côté, l'utilisateur est prévenu, les relectures repartent. Jusqu'au
            // bout même si un appel arrête la reprise : un fichier mis de côté sans message ne serait jamais annoncé.
            withContext(NonCancellable) {
                if (stored { all() } == null && stored { setAsideIfUnreadable(now()) } == true) {
                    val at = now()
                    history.append(at, ChatRole.Assistant, SET_ASIDE_MESSAGE)
                    unseen.raise(1, TrackingDay.of(at))
                }
            }
            for (startedAt in waiting()) run(startedAt)
        }
        return true
    }

    private fun busy(block: suspend () -> Unit) {
        active.incrementAndGet()
        _running.value = true
        var job: Job? = null
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fichier ou base illisible : rien à dire, la relecture reste en attente pour la prochaine ouverture.
            } finally {
                job?.let(jobs::remove)
                if (active.decrementAndGet() == 0) _running.value = false
            }
        }
        if (job.isActive) jobs += job
    }

    private suspend fun <T> stored(block: PendingReviewStore.() -> T): T? = storeLock.withLock {
        runCatching { withContext(io) { store.block() } }.getOrNull()
    }

    private suspend fun run(startedAt: Instant) = runLock.withLock {
        // Un appel est en cours : la relecture attend la fin de celui-ci (après son rapport, ou à la prochaine ouverture).
        if (callInProgress()) return@withLock
        // Les essais des autres appels ne sont pas tranchés dans le fichier (écriture ou lecture ratée) : cette relecture
        // n'écrit rien et attend la prochaine occasion, sinon son message pourrait passer pour le leur.
        if (!settleInterrupted(except = startedAt)) return@withLock
        val saved = stored { all() }?.firstOrNull { it.startedAt == startedAt }
        // La version gardée en mémoire seulement est toujours la plus récente (le rapport que le fichier n'a pas pu garder).
        val pending = unsaved[startedAt] ?: saved ?: return@withLock
        val today = TrackingDay.of(now())
        // L'essai précédent a déjà écrit son message (app tuée avant d'effacer le fichier) : rien à refaire.
        if (alreadyWritten(pending)) {
            stored { remove(startedAt) }
            unsaved.remove(startedAt)
            return@withLock
        }
        // Fil de l'appel passé (5 h) ou essais épuisés : un message court, sans relire.
        if (TrackingDay.of(pending.endedAt) != today || pending.attempts >= MAX_ATTEMPTS) {
            giveUp(pending)
            return@withLock
        }
        // Hors ligne : l'essai attend la prochaine ouverture, sans compter.
        if (!isOnline()) return@withLock
        val attempt = pending.copy(attempts = pending.attempts + 1, attemptAt = now())
        when {
            stored { put(attempt) } != null -> unsaved.remove(startedAt)
            // Essai gardé dans le fichier mais incrément non écrit : sans essai compté, le plafond ne tiendrait plus. Aucune
            // requête payante, on attend la prochaine occasion.
            saved != null -> {
                // Arrêtée par un appel pendant l'écriture (qui a pu aboutir) : l'essai ne compte pas, comme plus bas.
                if (!currentCoroutineContext().isActive) withContext(NonCancellable) { stored { put(pending) } }
                return@withLock
            }
            else -> unsaved.computeIfPresent(startedAt) { _, _ -> attempt }
        }
        val noted = merge(pending.noted, notedSince(pending.startedAt))
        val request = ReviewRequest(
            startedAt = pending.startedAt,
            transcript = pending.transcript,
            noted = noted,
            openQuestions = if (pending.provisional) emptyList() else pending.openQuestions,
            unprocessed = if (pending.provisional) pending.transcript.filter { it.speaker == Speaker.User }.map { it.text } else pending.unprocessed,
            provisional = pending.provisional,
            reflection = pending.reflection,
        )
        val result = try {
            withTimeoutOrNull(timeoutMs) { reviewer.review(request) } ?: ReviewResult.Failed("délai dépassé")
        } catch (e: CancellationException) {
            // Arrêtée pour laisser la place à un appel : cet essai ne compte pas.
            withContext(NonCancellable) { stored { put(pending) } }
            unsaved.computeIfPresent(startedAt) { _, _ -> pending }
            throw e
        } catch (e: Exception) {
            ReviewResult.Failed(e.javaClass.simpleName)
        }
        when (result) {
            is ReviewResult.Done -> {
                if (result.questions.isNotEmpty()) unseen.raise(result.questions.size, TrackingDay.of(now()))
                stored { remove(startedAt) }
                unsaved.remove(startedAt)
            }
            is ReviewResult.Failed -> if (attempt.attempts >= MAX_ATTEMPTS) giveUp(attempt)
        }
    }

    /** Sortie simple après les essais : les questions connues telles quelles, ou de redire ce qui manque. Aucun chiffre. */
    private suspend fun giveUp(pending: PendingReview) {
        val questions = if (pending.provisional) emptyList() else pending.openQuestions
        val unread = pending.provisional || SmallTalk.substantive(pending.unprocessed)
        if (questions.isNotEmpty() || unread) {
            val at = now()
            val lines = mutableListOf(HEADER)
            lines += "Je n’ai pas pu relire la fin de ton appel ${callTime(pending.startedAt, at)}." +
                if (unread) " Si tu y as dit quelque chose qui n’apparaît pas ici, redis-le-moi." else ""
            if (questions.isNotEmpty()) lines += questionsBlock(questions)
            history.append(at, ChatRole.Assistant, lines.joinToString("\n\n"))
            unseen.raise(maxOf(1, questions.size), TrackingDay.of(at))
        }
        stored { remove(pending.startedAt) }
        unsaved.remove(pending.startedAt)
    }

    /**
     * Avant tout essai ou message d'un appel, les essais des autres appels restés ouverts (échec, app tuée) sont tranchés :
     * leur message final, s'il existe, a été écrit avant ce point. Sans cela, le message de cet appel-ci serait pris pour
     * le leur, et leur relecture effacée sans être faite. Un essai tranché sans message reste à refaire.
     *
     * `false` si le fichier n'a pas pu être lu ou si une remise à zéro n'a pas été écrite : rien n'est tranché pour de bon,
     * l'appelant n'écrit rien. Un lien durable entre le message et son appel demanderait une colonne (migration) ou une
     * marque dans le texte du chat ; attendre coûte seulement un délai, rien n'est perdu.
     */
    private suspend fun settleInterrupted(except: Instant): Boolean {
        val all = stored { all() } ?: return false
        for (other in all) {
            if (other.startedAt == except || other.attemptAt == null) continue
            val settled = if (alreadyWritten(other)) stored { remove(other.startedAt) } else stored { put(other.copy(attemptAt = null)) }
            if (settled == null) return false
        }
        // Gardées en mémoire seulement : leur essai a fini dans ce processus sans message final (sinon elles seraient effacées).
        unsaved.replaceAll { startedAt, r -> if (startedAt != except && r.attemptAt != null) r.copy(attemptAt = null) else r }
        return true
    }

    /**
     * Un message final de la relecture (récap ou questions) écrit depuis le début du dernier essai. Grâce à
     * [settleInterrupted], aucun autre appel n'a écrit depuis : ce message est le sien.
     */
    private suspend fun alreadyWritten(pending: PendingReview): Boolean {
        val since = pending.attemptAt ?: return false
        return runCatching {
            history.last(TrackingDay.of(now()), SCAN_LIMIT).any { m ->
                m.role == ChatRole.Assistant && !m.at.isBefore(since) && m.text.startsWith(HEADER) && m.text.trim() != HEADER
            }
        }.getOrDefault(false)
    }

    /** « de 12h28 », ou « d’hier à 23h48 » / « du 2/11 à 23h48 » quand le fil du jour a changé depuis. */
    private fun callTime(startedAt: Instant, now: Instant): String {
        val time = TIME.format(startedAt.atZone(TrackingDay.ZONE))
        val callDay = TrackingDay.of(startedAt)
        val today = TrackingDay.of(now)
        return when (callDay) {
            today -> "de $time"
            today.minusDays(1) -> "d’hier à $time"
            else -> "du ${callDay.dayOfMonth}/${callDay.monthValue} à $time"
        }
    }

    /** Consommations notées dans le fil du jour depuis le début de l'appel (réflexions, relecture interrompue, chat). */
    private suspend fun notedSince(startedAt: Instant): List<NotedEntry> = runCatching {
        history.last(TrackingDay.of(now()), SCAN_LIMIT)
            .filter { !it.at.isBefore(startedAt) }
            .flatMap { message -> AgentCallThinker.notedFrom(message.cards.filterIsInstance<RecordCard>()) }
    }.getOrDefault(emptyList())

    private fun merge(first: List<NotedEntry>, then: List<NotedEntry>): List<NotedEntry> {
        val all = LinkedHashMap<String, NotedEntry>()
        for (e in first + then) if (e.deleted) all.remove(e.entryId) else all[e.entryId] = e
        return all.values.toList()
    }

    companion object {
        /**
         * Fichier des relectures mis de côté : ce qu'il gardait ne peut plus être relu. Court, sans chiffre, et sans [HEADER]
         * (ce n'est le message final d'aucun appel).
         */
        const val SET_ASIDE_MESSAGE = "📞 Une relecture d’appel n’a pas pu se faire : redis-moi ce qui manque."

        /** Première ligne du message écrit par la relecture dans le fil du jour. */
        const val HEADER = "📞 Après l’appel"

        /** Essais au plus : le premier juste après l'appel, puis aux ouvertures suivantes. */
        const val MAX_ATTEMPTS = 3

        /** Au-delà de 5 questions, la relecture ne garde que les plus importantes. */
        const val MAX_QUESTIONS = 5

        private const val SCAN_LIMIT = 60
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("H'h'mm")

        /** Les questions telles que l'utilisateur les lit dans le chat, auxquelles il répond comme à un message. */
        fun questionsBlock(questions: List<String>): String = buildString {
            append(if (questions.size == 1) "Il me reste une question :" else "Il me reste ${questions.size} questions :")
            for (q in questions) append("\n- ").append(q.trim())
        }
    }
}

/**
 * Parole sans contenu à relire : salutations, politesse, « ok », « c'est tout ». Un appel de pur bavardage ne coûte pas de
 * relecture ; au moindre doute (un vrai mot, un nombre), la parole est relue.
 */
internal object SmallTalk {
    private val FILLER = setOf(
        "bonjour", "bonsoir", "salut", "coucou", "hello", "allo", "ca", "va", "vas", "bien", "tres", "et", "toi", "merci", "beaucoup",
        "au", "revoir", "bye", "ciao", "a", "plus", "tard", "tout", "l", "heure", "bonne", "journee", "soiree", "nuit", "ok", "okay",
        "oui", "ouais", "non", "bon", "ben", "bah", "allez", "voila", "euh", "heu", "hum", "hmm", "mm", "d", "accord", "super",
        "parfait", "cool", "top", "genial", "c", "est", "fini", "j", "ai", "rien", "autre", "chose", "trackly", "alors",
        "tu", "es", "la", "le", "les", "toujours", "oh", "eh", "ah", "bisous", "pas", "de", "souci", "probleme", "nickel",
        "je", "me", "moi", "suis", "il", "elle", "on", "nous", "vous", "que", "qui", "quoi", "comment", "sais", "vais", "y",
    )

    /** Au moins un vrai mot hors politesse (« et le pain »), ou un nombre (« oui, 200 g »), sur toute la parole. */
    fun substantive(lines: List<String>): Boolean =
        lines.flatMap { Goodbye.normalize(it).split(' ') }
            .any { w -> w.isNotEmpty() && w !in FILLER && (w.count(Char::isLetter) >= 2 || w.any(Char::isDigit)) }
}
