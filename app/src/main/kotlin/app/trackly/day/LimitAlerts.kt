package app.trackly.day

import app.trackly.assistant.cards.RecordAlerts
import app.trackly.core.overview.Bar
import app.trackly.core.overview.DayOverviews
import app.trackly.core.summary.DaySummary

/**
 * Règle du rouge de la carte d'enregistrement : celle de l'accueil, côté rouge seulement, pour les **lipides** : la limite
 * du jour est dépassée, le récap du chat la montre en rouge. Un dépassement ne se défait pas en
 * mangeant : figé sur la carte, il reste vrai.
 *
 * Les protéines, elles, ne passent pas en rouge dans le chat : « minimum non atteint » dépend de la fin de la journée (un dîner
 * noté en deux fois ferait figer un rouge faux dix minutes avant d'être corrigé), alors que la barre de l'accueil se recalcule.
 * Le champ de la carte sait les porter ([RecordAlerts.PROTEIN]) : les ajouter ici suffirait. Les glucides, jamais ; le jaune n'a
 * pas de place dans le chat.
 */
object LimitAlerts : RecordAlerts {
    override fun overLimit(day: DaySummary): Set<String> =
        if (Bar.Fat in DayOverviews.exceeded(day)) setOf(RecordAlerts.FAT) else emptySet()
}
