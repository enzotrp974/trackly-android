package app.trackly.core.importing

import app.trackly.core.action.Action
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.ActionScope
import app.trackly.core.action.ConsumptionChange
import app.trackly.core.action.GoalChange
import app.trackly.core.action.HistoryKinds
import app.trackly.core.action.ImportedSourceChange
import app.trackly.core.action.RecordKind
import app.trackly.core.action.Rejection
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Consumption
import app.trackly.core.model.Goal
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.VersionedRecord
import app.trackly.core.store.ImportedSource
import java.time.LocalDate

/** Objectif en vigueur à une date, et depuis quand. */
data class GoalInForce(val since: LocalDate, val targets: GoalTargets)

/** Résumé de la reprise, affiché à l'utilisateur. */
data class ImportReport(
    /** Journées présentes dans l'export. */
    val days: Int,
    val firstDay: LocalDate?,
    val lastDay: LocalDate?,
    /** Consommations actives (version courante) dans l'export. */
    val consumptions: Int,
    val products: Int,
    /** Plats distincts (toutes versions confondues). */
    val recipes: Int,
    val preferences: Int,
    /** Données écrites par cette reprise. */
    val added: Int,
    /** Données déjà présentes à l'identique (réimport). */
    val alreadyPresent: Int,
    /** Journées dont les totaux ont été comparés à ceux du moteur Python. */
    val verifiedDays: Int,
    /** Objectif en vigueur le jour de la reprise, après reprise. */
    val currentGoal: GoalInForce?,
    /** Points à signaler, sans blocage. */
    val anomalies: List<String>,
)

/**
 * Reprend l'historique de l'ancien système, en une seule transaction :
 *
 * - les totaux de chaque journée sont d'abord recalculés et comparés à ceux du moteur Python ;
 * - une donnée déjà reprise à l'identique ne crée rien (réimporter le même fichier : rien de neuf) ;
 * - même identifiant et même version avec un contenu différent : la reprise entière est refusée ;
 * - rien de ce qui a été saisi dans l'app n'est réécrit : une donnée non versionnée à la source
 *   (produit, objectif, préférence) déjà présente avec un autre contenu est conservée telle quelle,
 *   et signalée ;
 * - chaque document d'origine est conservé tel quel (registre des reprises).
 *
 * La reprise est tracée dans le journal des actions mais ne s'annule pas par « Annuler ».
 */
class ImportHistory(private val bundle: HistoryBundle) : Action {
    override val type get() = TYPE
    override val undoable get() = false

    /** Résumé, disponible après application (même refusée pour « rien de neuf »). */
    var report: ImportReport? = null
        private set

    /** Raisons d'un refus (conflits, écarts de totaux). */
    var problems: List<String> = emptyList()
        private set

    override suspend fun apply(scope: ActionScope): String {
        val differences = ParityCheck.differences(bundle)
        if (differences.isNotEmpty()) {
            problems = differences
            scope.reject(Rejection.Invalid("Les totaux recalculés diffèrent de ceux de l'ancien moteur"))
        }
        val run = Run(scope)
        for (day in bundle.days) {
            for (item in day.consumptions) run.consumption(item)
            for (item in day.estimates) run.versioned(HistoryKinds.DailyEstimates, item, "estimation du ${day.day}")
        }
        for (item in bundle.recipes) run.versioned(HistoryKinds.Recipes, item, "plat « ${item.record.name} »")
        for (item in bundle.goals) run.goal(item)
        for (item in bundle.products) run.unversioned(HistoryKinds.Products, item, "produit « ${item.record.name} »")
        for (item in bundle.preferences) run.unversioned(HistoryKinds.Preferences, item, "préférence « ${item.record.trigger} »")
        for (day in bundle.days) run.unversioned(HistoryKinds.DayTrackings, day.tracking, "suivi du ${day.day}")
        for (archive in bundle.archives) run.archive(archive)

        report = buildReport(scope, run)
        if (run.conflicts.isNotEmpty()) {
            problems = run.conflicts
            scope.reject(Rejection.Invalid("Conflit avec des données déjà présentes"))
        }
        if (run.added == 0) scope.reject(Rejection.NoChange)
        return "Reprise de l'historique : ${bundle.days.size} journées"
    }

    private suspend fun buildReport(scope: ActionScope, run: Run): ImportReport {
        val goal = scope.tx.goals.allCurrent()
            .filter { it.isActive && !it.effectiveFrom.isAfter(scope.today) }
            .maxByOrNull { it.effectiveFrom }
        val anomalies = buildList {
            addAll(run.anomalies)
            if (bundle.checks == null) add("Export sans totaux de contrôle : refaire l'export avec la dernière version du script")
            for (file in bundle.ignoredFiles) add("Fichier non repris : $file")
        }
        val consumptions = bundle.days.sumOf { d ->
            d.consumptions.groupBy { it.record.entryId }.values.count { v -> v.maxBy { it.record.version }.record.isActive }
        }
        return ImportReport(
            days = bundle.days.size,
            firstDay = bundle.days.firstOrNull()?.day,
            lastDay = bundle.days.lastOrNull()?.day,
            consumptions = consumptions,
            products = bundle.products.size,
            recipes = bundle.recipes.map { it.record.recipeId }.distinct().size,
            preferences = bundle.preferences.size,
            added = run.added,
            alreadyPresent = run.alreadyPresent,
            verifiedDays = bundle.checks?.size ?: 0,
            currentGoal = goal?.let { GoalInForce(it.effectiveFrom, it.targets) },
            anomalies = anomalies,
        )
    }

    /** Écritures d'une reprise, dans la transaction de l'action. */
    private class Run(private val scope: ActionScope) {
        var added = 0
        var alreadyPresent = 0
        val conflicts = mutableListOf<String>()
        val anomalies = mutableListOf<String>()
        private val ledger get() = scope.tx.imports

        private fun conflict(what: String) {
            conflicts += "$what : même identifiant et même version, contenu différent"
        }

        private suspend fun remember(source: SourceDocument) {
            val stored = ImportedSource(source.kind, source.key, source.version, source.sha256, source.json, scope.id)
            ledger.insert(stored)
            scope.record(ImportedSourceChange(stored))
        }

        /** Faux si la version est déjà reprise (identique) ou en conflit. */
        private suspend fun isNewVersion(source: SourceDocument, what: String): Boolean {
            val known = ledger.get(source.kind, source.key, source.version) ?: return true
            if (known.sha256 == source.sha256) alreadyPresent++ else conflict(what)
            return false
        }

        suspend fun consumption(item: Sourced<Consumption>) {
            val what = "consommation « ${item.record.food.label} » (${item.record.day})"
            if (!isNewVersion(item.source, what)) return
            val consumptions = scope.tx.consumptions
            val record = item.record.copy(recordedAt = item.recordedAt ?: scope.now, actionId = scope.id)
            val existing = consumptions.version(record.entryId, record.version)
            if (existing != null) {
                // Version écrite dans l'app avec la même clé : jamais réécrite.
                if (existing.sameContentAs(record)) alreadyPresent++ else conflict(what)
                return
            }
            val before = consumptions.current(record.entryId)
            consumptions.insert(record)
            scope.record(ConsumptionChange(before, record))
            remember(item.source)
            added++
        }

        /** Donnée versionnée à la source : la clé `(identifiant, version)` fait foi. */
        suspend fun <T : VersionedRecord<T>> versioned(kind: RecordKind<T>, item: Sourced<T>, what: String) {
            if (!isNewVersion(item.source, what)) return
            val record = item.record.traced(item.record.version, item.recordedAt ?: scope.now, scope.id)
            val existing = kind.versionsIn(scope).version(record.key, record.version)
            if (existing != null) {
                if (existing.sameContentAs(record)) alreadyPresent++ else conflict(what)
                return
            }
            kind.insertAsIs(scope, record)
            remember(item.source)
            added++
        }

        /**
         * Donnée sans version à la source : reprise une seule fois. Si l'app en a déjà une autre
         * version (modifiée depuis, ou créée dans l'app), elle est conservée et signalée.
         */
        suspend fun <T : VersionedRecord<T>> unversioned(kind: RecordKind<T>, item: Sourced<T>, what: String) {
            val known = ledger.latest(item.source.kind, item.source.key)
            if (known != null) {
                if (known.sha256 == item.source.sha256) {
                    alreadyPresent++
                } else {
                    anomalies += "$what différent dans le fichier : la version de l'app est conservée"
                }
                return
            }
            val current = kind.versionsIn(scope).current(item.record.key)
            if (current != null) {
                if (current.sameContentAs(item.record)) alreadyPresent++ else anomalies += "$what déjà présent dans l'app : conservé"
                return
            }
            kind.insertAsIs(scope, item.record.traced(1, item.recordedAt ?: scope.now, scope.id))
            remember(item.source)
            added++
        }

        suspend fun goal(item: Sourced<Goal>) {
            val from = item.record.effectiveFrom
            val what = "objectif du $from"
            val known = ledger.latest(item.source.kind, item.source.key)
            if (known != null) {
                if (known.sha256 == item.source.sha256) alreadyPresent++ else anomalies += "$what différent dans le fichier : la version de l'app est conservée"
                return
            }
            val current = scope.tx.goals.current(from)
            if (current != null) {
                if (current.isActive && current.targets == item.record.targets) alreadyPresent++ else anomalies += "$what déjà fixé dans l'app : conservé"
                return
            }
            val record = item.record.copy(version = 1, recordedAt = item.recordedAt ?: scope.now, actionId = scope.id)
            scope.tx.goals.insert(record)
            scope.record(GoalChange(null, record))
            remember(item.source)
            added++
        }

        /** Document conservé sans être utilisé (profil complet, pesées) : une nouvelle version s'il a changé. */
        suspend fun archive(source: SourceDocument) {
            val latest = ledger.latest(source.kind, source.key)
            if (latest != null && latest.sha256 == source.sha256) {
                alreadyPresent++
                return
            }
            remember(source.copy(version = (latest?.version ?: 0) + 1))
            added++
        }
    }

    companion object {
        const val TYPE = "history.import"
    }
}

/** Issue d'une reprise, pour l'écran de résumé. */
sealed interface ImportOutcome {
    data class Imported(val report: ImportReport) : ImportOutcome

    /** Le fichier est valide mais tout est déjà là : rien n'a été écrit. */
    data class NothingNew(val report: ImportReport) : ImportOutcome

    /** Rien n'a été écrit. */
    data class Refused(val reason: String, val details: List<String> = emptyList()) : ImportOutcome
}

/** Point d'entrée de la reprise : lecture du fichier, vérification, puis action [ImportHistory]. */
object HistoryImport {
    suspend fun run(engine: TracklyEngine, text: String): ImportOutcome {
        val bundle = try {
            BundleReader.read(text)
        } catch (e: BundleFormatException) {
            return ImportOutcome.Refused("Fichier refusé", listOfNotNull(e.message))
        }
        val action = ImportHistory(bundle)
        val outcome = try {
            engine.execute(action)
        } catch (e: Exception) {
            // Erreur inattendue du stockage : la transaction est annulée, rien n'est écrit.
            return ImportOutcome.Refused("La reprise a échoué, rien n'a été écrit", listOfNotNull(e.message))
        }
        return when (outcome) {
            is ActionOutcome.Applied -> ImportOutcome.Imported(checkNotNull(action.report))
            is ActionOutcome.Rejected -> {
                val report = action.report
                if (outcome.rejection == Rejection.NoChange && report != null) {
                    ImportOutcome.NothingNew(report)
                } else {
                    ImportOutcome.Refused(outcome.rejection.message, action.problems)
                }
            }
        }
    }
}
