package app.trackly.core.action

import app.trackly.core.model.DailyEstimate
import app.trackly.core.model.DayTracking
import app.trackly.core.model.Preference
import app.trackly.core.model.Product
import app.trackly.core.model.Provision
import app.trackly.core.model.Recipe
import app.trackly.core.model.RecordState
import app.trackly.core.model.VersionedRecord
import app.trackly.core.store.ImportedSource
import app.trackly.core.store.RecordVersions
import app.trackly.core.store.StoreTransaction
import java.time.LocalDate

/** Changement d'une donnée versionnée générique. */
data class RecordChange<T : VersionedRecord<T>>(
    override val kind: String,
    val before: T?,
    val after: T,
    override val days: Set<LocalDate>,
) : Change {
    override val key get() = after.key
    override val beforeVersion get() = before?.version
    override val afterVersion get() = after.version
}

/** Document d'origine conservé par une reprise (pas une donnée métier : ne s'annule pas seul). */
data class ImportedSourceChange(val source: ImportedSource) : Change {
    override val kind get() = NAME
    override val key get() = "${source.kind}/${source.key}"
    override val beforeVersion: Int? get() = null
    override val afterVersion get() = source.version
    override val days: Set<LocalDate> get() = emptySet()

    companion object {
        const val NAME = "imported_source"
    }
}

/**
 * Type de donnée versionnée générique : écriture d'une nouvelle version et annulation,
 * sans code propre au type. Un nouveau type n'a qu'à déclarer son instance ici.
 */
class RecordKind<T : VersionedRecord<T>>(
    override val name: String,
    private val versions: (StoreTransaction) -> RecordVersions<T>,
    private val daysOf: (T) -> Set<LocalDate> = { emptySet() },
    /** Vrai : une version réécrite au même contenu compte comme intacte pour l'annulation ([VersionedKind.sameContent]). */
    private val contentAware: Boolean = false,
) : VersionedKind {
    fun versionsIn(scope: ActionScope): RecordVersions<T> = versions(scope.tx)

    override suspend fun currentVersion(scope: ActionScope, key: String) = versions(scope.tx).current(key)?.version

    override suspend fun sameContent(scope: ActionScope, key: String, current: Int?, written: Int): Boolean {
        if (!contentAware || current == null) return false
        val store = versions(scope.tx)
        val now = store.version(key, current) ?: return false
        val then = store.version(key, written) ?: return false
        return now.sameContentAs(then)
    }

    override suspend fun restore(scope: ActionScope, key: String, toVersion: Int?): Change {
        val store = versions(scope.tx)
        val current = store.current(key) ?: scope.reject(Rejection.NotFound("$name $key"))
        val content = if (toVersion == null) {
            current.withState(RecordState.Deleted)
        } else {
            store.version(key, toVersion) ?: scope.reject(Rejection.NotFound("version $toVersion de $name $key"))
        }
        return writeNextVersion(scope, current, content)
    }

    /** Écrit [content] comme version suivante de [current] et enregistre le changement. */
    suspend fun writeNextVersion(scope: ActionScope, current: T?, content: T): RecordChange<T> {
        val next = content.traced((current?.version ?: 0) + 1, scope.now, scope.id)
        versions(scope.tx).insert(next)
        return RecordChange(name, current, next, daysOf(next) + current?.let(daysOf).orEmpty()).also(scope::record)
    }

    /** Ajoute une version reprise telle quelle (son numéro d'origine est conservé). */
    suspend fun insertAsIs(scope: ActionScope, record: T): RecordChange<T> {
        val store = versions(scope.tx)
        val current = store.current(record.key)
        store.insert(record)
        return RecordChange(name, current, record, daysOf(record)).also(scope::record)
    }
}

/** Types de données ajoutés par la reprise de l'historique. */
object HistoryKinds {
    val DailyEstimates = RecordKind<DailyEstimate>("daily_estimate", { it.dailyEstimates }, { setOf(it.day) })
    val DayTrackings = RecordKind<DayTracking>("day_tracking", { it.dayTracking }, { setOf(it.day) })
    val Products = RecordKind<Product>("product", { it.products })
    val Recipes = RecordKind<Recipe>("recipe", { it.recipes })
    val Preferences = RecordKind<Preference>("preference", { it.preferences })

    /** Provisions : « j'ai » / « je n'ai plus », en versions. */
    val Provisions = RecordKind<Provision>("provision", { it.provisions })

    val all: List<VersionedKind> = listOf(DailyEstimates, DayTrackings, Products, Recipes, Preferences, Provisions)
}
