package app.trackly.core.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** État d'une version : rien n'est effacé, une suppression est une nouvelle version `deleted`. */
enum class RecordState(val code: String) {
    Active("active"),
    Deleted("deleted"),
    ;

    companion object {
        fun fromCode(code: String): RecordState =
            entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("État inconnu : $code")
    }
}

/** Identifiant stable d'une consommation, toutes versions confondues (UUID, comme `entry_id`). */
@JvmInline
value class EntryId(val value: String) {
    init {
        require(value.isNotBlank()) { "Identifiant vide" }
    }

    override fun toString() = value

    companion object {
        fun random() = EntryId(UUID.randomUUID().toString())

        /**
         * Identifiant neuf qui se range dans l'ordre de la déclaration : les 48 premiers bits sont le numéro de l'action
         * ([ActionId], croissant), les 12 suivants la position de l'aliment dans la déclaration ([index]), le reste est
         * aléatoire. C'est un UUID valide (version 7) ; comparé comme texte, il suit l'ordre d'enregistrement, ce qui
         * donne un départage stable aux aliments d'un même repas (même moment, même heure réelle). Au-delà de 4095
         * aliments dans une déclaration, les suivants partagent la dernière position.
         */
        fun ordered(action: ActionId, index: Int): EntryId {
            val sequence = action.value and 0xFFFF_FFFF_FFFFL
            val random = UUID.randomUUID().toString().split('-')
            return EntryId(
                "%08x-%04x-7%03x-%s-%s".format(sequence ushr 16, sequence and 0xFFFF, index.coerceIn(0, 0xFFF), random[3], random[4]),
            )
        }
    }
}

/** Identifiant d'une action appliquée, croissant dans l'ordre d'application. */
@JvmInline
value class ActionId(val value: Long) {
    override fun toString() = value.toString()
}

/** Provenance d'une version (assistant, panneau de développement, reprise…). Texte libre stable. */
@JvmInline
value class Origin(val code: String) {
    companion object {
        val App = Origin("trackly")
        val DevPanel = Origin("dev_panel")
        val Import = Origin("import")
        val Assistant = Origin("assistant")
    }
}

/**
 * Une version d'une consommation. La clé logique est `(entryId, version)` ; la version
 * la plus haute est l'état courant. Seules les versions courantes actives comptent.
 */
data class Consumption(
    val entryId: EntryId,
    val version: Int,
    val state: RecordState,
    /** Date de rattachement : jour de suivi auquel la consommation compte. */
    val day: LocalDate,
    /** Heure réelle de la consommation (ou de sa déclaration), jamais modifiée par le rattachement. */
    val consumedAt: Instant,
    val moment: Moment,
    val food: Food,
    val origin: Origin,
    /** Moment où cette version a été enregistrée. */
    val recordedAt: Instant,
    /** Action qui a produit cette version (`null` pour une version reprise). */
    val actionId: ActionId?,
) {
    init {
        require(version >= 1) { "Version invalide : $version" }
    }

    val isActive: Boolean get() = state == RecordState.Active

    /** Même contenu métier, sans tenir compte de la version ni de sa trace. */
    fun sameContentAs(other: Consumption): Boolean =
        entryId == other.entryId && state == other.state && day == other.day && consumedAt == other.consumedAt &&
            moment == other.moment && food == other.food
}

/**
 * Cibles d'un objectif nutritionnel. Une macro sans cible vaut `null`.
 *
 * [proteinG] est l'**idéal** de protéines, la cible. [proteinMinG] est le **minimum de protéines** (par exemple 1,6 g/kg
 * de poids de référence) : un plancher à atteindre, jamais l'objectif, jamais affiché comme repère.
 * `null` : aucun minimum connu (objectif saisi sans profil, ou ancien objectif sans règle).
 */
data class GoalTargets(
    val kcal: Double,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val proteinMinG: Double? = null,
) {
    init {
        require(kcal.isFinite() && kcal > 0) { "Objectif calorique invalide : $kcal" }
        for (value in listOfNotNull(proteinG, carbsG, fatG, proteinMinG)) {
            require(value.isFinite() && value >= 0) { "Objectif de macro invalide : $value" }
        }
    }
}

/**
 * Une version d'un objectif nutritionnel daté, applicable à partir de [effectiveFrom].
 * La clé logique est `(effectiveFrom, version)`. Le bilan d'un jour utilise l'objectif actif
 * dont la date de départ est la plus récente sans dépasser ce jour.
 */
data class Goal(
    val effectiveFrom: LocalDate,
    val version: Int,
    val state: RecordState,
    val targets: GoalTargets,
    val recordedAt: Instant,
    val actionId: ActionId?,
) {
    init {
        require(version >= 1) { "Version invalide : $version" }
    }

    val isActive: Boolean get() = state == RecordState.Active
}
