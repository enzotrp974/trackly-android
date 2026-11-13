package app.trackly.core.importing

import app.trackly.core.model.Consumption
import app.trackly.core.model.DailyEstimate
import app.trackly.core.model.DayTracking
import app.trackly.core.model.Goal
import app.trackly.core.model.Preference
import app.trackly.core.model.Product
import app.trackly.core.model.Recipe
import java.time.Instant
import java.time.LocalDate

/** Document d'origine d'une donnée de l'export, avant reprise. */
data class SourceDocument(
    /** Type de donnée (`consumption`, `product`, `profile`…). */
    val kind: String,
    val key: String,
    /** Version d'origine ; 1 pour une donnée non versionnée à la source. */
    val version: Int,
    /** JSON canonique du document, conservé tel quel. */
    val json: String,
    /** Empreinte du contenu comparé (voir [sha256]). */
    val sha256: String,
)

/**
 * Donnée lue dans l'export, avec son document d'origine. [recordedAt] est la date
 * d'enregistrement d'origine (`updated_at`), ou `null` si la source n'en a pas.
 */
data class Sourced<T>(val record: T, val source: SourceDocument, val recordedAt: Instant?)

/** Une journée de l'export : ses consommations (toutes versions), estimations et suivi déclaré. */
data class BundleDay(
    val day: LocalDate,
    val consumptions: List<Sourced<Consumption>>,
    val estimates: List<Sourced<DailyEstimate>>,
    val tracking: Sourced<DayTracking>,
)

/** Totaux attendus d'une journée, calculés par le moteur Python (`checks` de l'export). */
data class ExpectedDay(
    val day: LocalDate,
    val kcal: Double?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    val fiberG: Double?,
    val kcalLow: Double,
    val kcalHigh: Double?,
    val energyStatus: String,
    val containsEstimates: Boolean,
    /** Sous-totaux connus (`known_totals`) : kcal, protéines, glucides, lipides, fibres. */
    val known: List<Double>,
    /** Couverture de la journée (`day_energy_status`). */
    val coverage: String,
    /** Objectif du jour (kcal, protéines idéales, glucides, lipides), `null` sans objectif. */
    val targets: List<Double>?,
)

/**
 * Export de reprise lu et vérifié (format `suivi-kcal-bundle` du moteur Python).
 * Rien n'est encore écrit : la reprise passe par l'action [ImportHistory].
 */
data class HistoryBundle(
    val schemaVersion: Int,
    val days: List<BundleDay>,
    val goals: List<Sourced<Goal>>,
    val products: List<Sourced<Product>>,
    val recipes: List<Sourced<Recipe>>,
    val preferences: List<Sourced<Preference>>,
    /** Documents conservés sans être utilisés par l'app (profil complet, pesées). */
    val archives: List<SourceDocument>,
    /** Totaux de contrôle du moteur Python, par jour ; `null` pour un export ancien sans contrôle. */
    val checks: Map<LocalDate, ExpectedDay>?,
    /** Fichiers de l'export que l'app ne reprend pas. */
    val ignoredFiles: List<String>,
)
