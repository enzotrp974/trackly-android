package app.trackly.assistant.cards

import app.trackly.core.engine.TracklyEngine
import app.trackly.core.summary.DaySummary
import java.time.LocalDate

/**
 * Ce qui fait passer une macro en rouge sur la carte d'enregistrement. L'assistant ne le calcule pas et ne le lit
 * jamais : la règle vit dans la vue du jour (`:core`), que l'assistant n'importe pas (garde de `AverageMarkPrivacyTest`).
 * L'application la branche ici, de l'extérieur ; l'assistant ne reçoit qu'une liste de noms de macros à stocker sur la
 * carte, et elle n'entre ni dans [ChatCard.summaryForModel] ni dans un résultat d'outil.
 */
fun interface RecordAlerts {
    /** Les macros ([PROTEIN], [FAT]) hors objectif sur ce jour, d'après son bilan. */
    fun overLimit(day: DaySummary): Set<String>

    companion object {
        const val PROTEIN = "protein"
        const val FAT = "fat"

        /** Aucune macro en rouge (tests, aperçus). */
        val None = RecordAlerts { emptySet() }
    }
}

/**
 * La même carte, avec l'état rouge **figé** à l'enregistrement : les macros hors objectif sur chaque jour touché, d'après le
 * bilan de ce jour une fois l'action appliquée. Une carte qui supprime des consommations n'a pas de rouge ; une carte déjà
 * enregistrée ne change jamais (elle n'est pas recalculée à l'affichage).
 */
suspend fun RecordCard.frozen(engine: TracklyEngine, alerts: RecordAlerts): RecordCard {
    if (kind == RecordKind.Deleted || items.isEmpty()) return this
    // Le repas est déjà enregistré : si le calcul du rouge échoue, la carte (et son bouton Annuler) doit exister quand même.
    val over = try {
        items.map { it.day }.distinct()
            .mapNotNull { iso -> runCatching { LocalDate.parse(iso) }.getOrNull() }
            .flatMapTo(linkedSetOf()) { alerts.overLimit(engine.daySummary(it)) }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        return this
    }
    if (over.isEmpty()) return this
    return copy(overLimit = listOf(RecordAlerts.PROTEIN, RecordAlerts.FAT).filter { it in over })
}
