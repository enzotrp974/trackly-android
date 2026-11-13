package app.trackly.storage

import app.trackly.core.model.DailyEstimate
import app.trackly.core.model.DayTracking
import app.trackly.core.model.Preference
import app.trackly.core.model.Product
import app.trackly.core.model.Provision
import app.trackly.core.store.ProvisionVersions
import app.trackly.core.model.Recipe
import app.trackly.core.store.DailyEstimateVersions
import app.trackly.core.store.DayTrackingVersions
import app.trackly.core.store.ImportLedger
import app.trackly.core.store.ImportedSource
import app.trackly.core.store.PreferenceVersions
import app.trackly.core.store.ProductVersions
import app.trackly.core.store.RecipeVersions
import app.trackly.storage.HistoryMappers.toEntity
import app.trackly.storage.HistoryMappers.toModel
import app.trackly.storage.db.TracklyDatabase
import java.time.LocalDate

// Implémentation Room des données de la reprise (version 3), utilisée par RoomTracklyStore.

internal class RoomDailyEstimates(db: TracklyDatabase) : DailyEstimateVersions {
    private val dao = db.dailyEstimates()

    override suspend fun current(key: String) = dao.current(key)?.toModel()
    override suspend fun version(key: String, version: Int) = dao.version(key, version)?.toModel()
    override suspend fun allCurrent() = dao.allCurrent().map { it.toModel() }
    override suspend fun allVersionsBetween(from: LocalDate, to: LocalDate) =
        dao.allVersionsBetween(from.toString(), to.toString()).map { it.toModel() }
    override suspend fun insert(record: DailyEstimate) = dao.insert(record.toEntity())
}

internal class RoomDayTracking(db: TracklyDatabase) : DayTrackingVersions {
    private val dao = db.dayTracking()

    override suspend fun current(key: String) = dao.current(key)?.toModel()
    override suspend fun version(key: String, version: Int) = dao.version(key, version)?.toModel()
    override suspend fun allCurrent() = dao.allCurrent().map { it.toModel() }
    override suspend fun currentBetween(from: LocalDate, to: LocalDate) =
        dao.currentBetween(from.toString(), to.toString()).map { it.toModel() }
    override suspend fun insert(record: DayTracking) = dao.insert(record.toEntity())
}

internal class RoomProducts(db: TracklyDatabase) : ProductVersions {
    private val dao = db.products()

    override suspend fun current(key: String) = dao.current(key)?.toModel()
    override suspend fun version(key: String, version: Int) = dao.version(key, version)?.toModel()
    override suspend fun allCurrent() = dao.allCurrent().map { it.toModel() }
    override suspend fun insert(record: Product) = dao.insert(record.toEntity())
}

internal class RoomRecipes(db: TracklyDatabase) : RecipeVersions {
    private val dao = db.recipes()

    override suspend fun current(key: String) = dao.current(key)?.toModel()
    override suspend fun version(key: String, version: Int) = dao.version(key, version)?.toModel()
    override suspend fun allCurrent() = dao.allCurrent().map { it.toModel() }
    override suspend fun insert(record: Recipe) = dao.insert(record.toEntity())
}

internal class RoomPreferences(db: TracklyDatabase) : PreferenceVersions {
    private val dao = db.preferences()

    override suspend fun current(key: String) = dao.current(key)?.toModel()
    override suspend fun version(key: String, version: Int) = dao.version(key, version)?.toModel()
    override suspend fun allCurrent() = dao.allCurrent().map { it.toModel() }
    override suspend fun insert(record: Preference) = dao.insert(record.toEntity())
}

internal class RoomProvisions(db: TracklyDatabase) : ProvisionVersions {
    private val dao = db.provisions()

    override suspend fun current(key: String) = dao.current(key)?.toModel()
    override suspend fun version(key: String, version: Int) = dao.version(key, version)?.toModel()
    override suspend fun allCurrent() = dao.allCurrent().map { it.toModel() }
    override suspend fun insert(record: Provision) = dao.insert(record.toEntity())
}

internal class RoomImportLedger(db: TracklyDatabase) : ImportLedger {
    private val dao = db.importedSources()

    override suspend fun get(kind: String, key: String, version: Int) = dao.get(kind, key, version)?.toModel()
    override suspend fun latest(kind: String, key: String) = dao.latest(kind, key)?.toModel()
    override suspend fun insert(source: ImportedSource) = dao.insert(source.toEntity())
    override suspend fun count() = dao.count()
}
