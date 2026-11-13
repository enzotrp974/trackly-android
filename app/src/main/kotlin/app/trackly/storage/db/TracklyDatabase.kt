package app.trackly.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Base locale de Trackly. Toute évolution du schéma : nouvelle version, `Migration` dans
 * `migrations/` (un fichier par migration), test de migration, schéma JSON exporté commité.
 * Jamais de migration destructive.
 *
 * Historique : 1 = consommations et journal des actions ; 2 = objectifs datés et
 * hypothèses d'estimation des consommations ; 3 = historique du chat ; 4 = reprise de
 * l'historique (estimations journalières, suivi des journées, produits, plats, préférences,
 * documents d'origine) ; 5 = produits ponctuels et récurrents (type, portion usuelle) et index
 * des consommations par produit ; 6 = compteur de coût local (usage OpenAI) ; 7 = plats avec variantes
 * (colonnes de `recipe_versions`) et provisions ; 8 = bilan du lundi (informations déclarées de la
 * semaine, conversation de bilan, rapports HTML) ; 9 = minimum de protéines dans les objectifs datés ;
 * 10 = fil du jour du chat (date de rattachement et masquage de chaque message) ;
 * 11 = objectif de poids modifiable (prévision d'arrivée) ; 12 = fin de période des bilans (un bilan couvre du lendemain
 * du dernier jusqu'à aujourd'hui, plus seulement une semaine).
 */
@Database(
    version = TracklyDatabase.VERSION,
    entities = [
        ConsumptionEntity::class,
        GoalEntity::class,
        ActionEntity::class,
        ActionEffectEntity::class,
        ChatMessageEntity::class,
        // Version 4 : reprise de l'historique.
        DailyEstimateEntity::class, DayTrackingEntity::class, ProductEntity::class, RecipeEntity::class,
        PreferenceEntity::class, ImportedSourceEntity::class,
        // Version 6 : compteur de coût.
        UsageEventEntity::class,
        // Version 7 : provisions.
        ProvisionEntity::class,
        // Version 8 : bilan du lundi.
        WeekInfoEntity::class, ReviewMessageEntity::class, WeeklyReportEntity::class,
        // Version 11 : objectif de poids.
        WeightGoalEntity::class,
    ],
    exportSchema = true,
)
abstract class TracklyDatabase : RoomDatabase() {
    abstract fun consumptions(): ConsumptionDao
    abstract fun goals(): GoalDao
    abstract fun actions(): ActionDao
    abstract fun chat(): ChatDao
    abstract fun dailyEstimates(): DailyEstimateDao
    abstract fun dayTracking(): DayTrackingDao
    abstract fun products(): ProductDao
    abstract fun recipes(): RecipeDao
    abstract fun preferences(): PreferenceDao
    abstract fun importedSources(): ImportedSourceDao
    abstract fun usage(): UsageDao
    abstract fun provisions(): ProvisionDao
    abstract fun weekInfos(): WeekInfoDao
    abstract fun review(): ReviewDao
    abstract fun weeklyReports(): WeeklyReportDao
    abstract fun weightGoals(): WeightGoalDao

    companion object {
        const val VERSION = 12
        const val NAME = "trackly.db"
    }
}
