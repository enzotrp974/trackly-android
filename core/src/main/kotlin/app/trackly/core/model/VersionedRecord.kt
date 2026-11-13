package app.trackly.core.model

import java.time.Instant

/**
 * Donnée versionnée générique (estimation journalière, suivi d'une journée, produit, plat,
 * préférence…). Clé logique `(key, version)` ; la version la plus haute est l'état courant.
 * Rien n'est effacé : une suppression est une nouvelle version `deleted`.
 */
interface VersionedRecord<T : VersionedRecord<T>> {
    /** Identifiant stable, toutes versions confondues. */
    val key: String
    val version: Int
    val state: RecordState

    /** Moment où cette version a été enregistrée (ou, pour une reprise, sa date d'origine). */
    val recordedAt: Instant

    /** Action qui a produit cette version. */
    val actionId: ActionId?

    /** Même contenu, avec une autre version et une autre trace. */
    fun traced(version: Int, recordedAt: Instant, actionId: ActionId?): T

    /** Même contenu dans un autre état (suppression, restauration). */
    fun withState(state: RecordState): T

    val isActive: Boolean get() = state == RecordState.Active

    /** Même contenu métier, sans tenir compte de la version ni de sa trace. */
    fun sameContentAs(other: T): Boolean =
        traced(1, Instant.EPOCH, null) == other.traced(1, Instant.EPOCH, null)
}
