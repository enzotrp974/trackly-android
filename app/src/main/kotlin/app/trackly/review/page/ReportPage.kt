package app.trackly.review.page

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * La page du bilan, **figée à la production du rapport** : chiffres, phrases et courbes de ce jour-là, relus tels
 * quels (un rapport n'est jamais recalculé). Elle est construite par [ReportPageBuilder] depuis les chiffres du moteur
 * (`WeeklyFigures`, dont `forecast`) ; l'IA n'y apporte que [note], le « mot de Trackly ». Aucune date n'y est calculée par
 * l'IA, et aucune n'est écrite quand la prévision n'en donne pas de ferme.
 *
 * L'ordre des blocs est fixé par l'application, jamais par l'IA : verdict, date, courbe du poids, mot de Trackly, points,
 * conseils, poids de référence, explication du calcul.
 *
 * [advice] : conseils chiffrés (objectif temporaire, pas, date), vides quand tout va bien. [referenceWeight] : le poids de
 * référence mis à jour à la production du bilan, avec Annuler. Un bloc vide n'apparaît pas.
 */
@Serializable
data class ReportPage(
    /** Version du format : une page plus récente que l'app s'ouvre comme un rapport introuvable plutôt que de mentir. */
    val format: Int = FORMAT,
    val periodStart: String,
    val periodEnd: String,
    val generatedOn: String,
    val headline: Headline,
    val weight: WeightSection,
    /** Le « mot de Trackly » : texte brut de l'IA, paragraphes séparés par une ligne vide ; vide : le bloc n'apparaît pas. */
    val note: String = "",
    val points: List<Point>,
    val advice: List<Advice> = emptyList(),
    val referenceWeight: ReferenceWeight? = null,
    /** Hypothèses du calcul de la date, en phrases simples, sous « Comment la date est calculée ». */
    val howItWorks: List<String> = emptyList(),
) {
    companion object {
        const val FORMAT = 1
    }
}

/** Ton d'un verdict ou d'une pastille. Jamais une couleur de texte : le jaune ne colore qu'une pastille, une barre ou un trait. */
@Serializable
enum class Tone { Ok, Warn, Neutral }

/** En-tête : ce que dit la prévision d'arrivée, en une phrase. */
@Serializable
data class Headline(
    /** « En avance. », « Estimation. », « Objectif atteint. »… */
    val verdict: String,
    val tone: Tone,
    val sentence: String,
    /** Passage de [sentence] écrit en gras (la date d'arrivée), s'il y en a un. */
    val sentenceBold: String? = null,
    /** Trois chiffres côte à côte ; la marge manque quand elle n'a pas de sens (pas de date, objectif atteint). */
    /** Légende du premier chiffre : « ARRIVÉE PRÉVUE », ou « ESTIMATION PROVISOIRE » sans date ferme. */
    val arrivalLabel: String = "ARRIVÉE PRÉVUE",
    val arrival: String,
    val target: String,
    val margin: String? = null,
    val marginUnit: String? = null,
    /** La marge est un retard : une pastille jaune la précède. */
    val marginLate: Boolean = false,
    /** Fourchette de la date (« Entre le 2 et le 20 mars »), seulement pour une date mesurée. */
    val range: String? = null,
)

@Serializable
data class XY(val day: Long, val kg: Double)

/** Courbe du poids, ou la raison de son absence. */
@Serializable
data class WeightSection(val chart: WeightChart? = null, val emptyNote: String? = null)

/**
 * Courbe du poids : « toi » (moyennes des pesées par semaine, jusqu'au poids du jour), pesées en points pâles, « prévu » (le
 * chemin qui arrive pile à la date visée) et « à ce rythme » (jusqu'à l'arrivée, pointillé). Jours en jours depuis 1970
 * (`LocalDate.toEpochDay`). [projection] et [arrival] manquent sans date ferme.
 */
@Serializable
data class WeightChart(
    val today: Long,
    val targetDay: Long,
    val targetKg: Double,
    val you: List<XY>,
    val weighIns: List<XY>,
    val planned: List<XY> = emptyList(),
    val projection: List<XY> = emptyList(),
    val arrival: XY? = null,
    /** Le verdict est jaune (retard, poids stable, poids qui s'éloigne) : la ligne « à ce rythme » et le point d'arrivée sont jaunes. */
    val late: Boolean = false,
    val currentKg: Double? = null,
)

/** Statut d'un point du bilan. Un ralentissement attendu de la perte est [Info], jamais [Warn]. */
@Serializable
enum class PointStatus { Ok, Top, Warn, Info, Neutral }

/**
 * Un point de la semaine : une ligne quand ça va ; quand ça coince ([PointStatus.Warn]) ou quand il y a une explication à
 * lire ([PointStatus.Info]), un détail et un petit graphique.
 */
@Serializable
data class Point(
    val id: String,
    val name: String,
    val status: PointStatus,
    val desc: String,
    val value: String,
    val unit: String = "",
    val detail: String? = null,
    val chart: PointChart? = null,
)

@Serializable
enum class ChartKind {
    /** Barres depuis 0, avec le trait de la référence (pas). */
    Bars,

    /** Barres d'écart à l'objectif : au-dessus en jaune, au-dessous en bleu des glucides (calories). */
    Gap,

    /** Un point par jour, avec les traits « min » et « idéal » ; l'échelle ne part pas de 0 (protéines). */
    Dots,

    /** Perte par semaine prévue, semaine après semaine (rythme). */
    Weekly,
}

@Serializable
data class ChartRef(val value: Double, val label: String)

/** Petit graphique d'un point : valeurs par jour (ou par semaine), repères et jours en alerte. */
@Serializable
data class PointChart(
    val kind: ChartKind,
    val title: String,
    val labels: List<String>,
    val values: List<Double?>,
    /** Une valeur de plus par repère, du plus bas au plus haut. */
    val refs: List<ChartRef> = emptyList(),
    /** Valeurs en alerte (jaune), même longueur que [values]. */
    val flagged: List<Boolean> = emptyList(),
    /** Valeurs écrites au-dessus de la valeur, seulement sur les cas notables. */
    val valueLabels: List<String?> = emptyList(),
    /** Légende courte sous le graphique (« toi », « prévu »). */
    val legend: List<String> = emptyList(),
)

/** Sorte de conseil : ce que la carte montre et le bouton qu'elle porte. */
@Serializable
enum class AdviceKind {
    /** Objectif temporaire : grand chiffre, dates, bouton « Appliquer » puis « Annuler ». */
    Temporary,

    /** Le même effort en pas, au poids actuel : **une phrase, sans bouton**. */
    Steps,

    /** Garder son rythme et viser la date d'arrivée prévue : une phrase et le bouton secondaire « Changer la date ». */
    Date,
}

/**
 * Un conseil chiffré, **chiffré par l'application** (`CatchUpAdvices`, dans `:core`), jamais par l'IA. Les textes sont
 * figés à la production du bilan, comme le reste de la page ; l'état du bouton (appliqué, annulé, terminé) se lit en direct
 * dans les objectifs, d'après [start] (objectif temporaire) ou [targetKg] et [newDate] (date) : la page n'est jamais réécrite.
 *
 * Carte façon « Proposition » : [kicker], [title], grand chiffre [big] et [unit], [rows]. Pour [AdviceKind.Steps] et
 * [AdviceKind.Date], seul [title] sert : c'est la phrase.
 */
@Serializable
data class Advice(
    val kicker: String,
    val title: String,
    val big: String,
    val unit: String = "",
    val rows: List<String> = emptyList(),
    /** Réservé ; reste `null` : l'action du bouton se retrouve par la date, voir ci-dessus. */
    val actionId: Long? = null,
    val kind: AdviceKind = AdviceKind.Temporary,
    /** Objectif temporaire : premier jour (`AAAA-MM-JJ`), durée, baisse par jour, objectif d'avant (kcal). */
    val start: String? = null,
    val days: Int? = null,
    val dropKcal: Double? = null,
    val baseKcal: Double? = null,
    /** « Changer la date » : poids visé gardé et nouvelle date (`AAAA-MM-JJ`). */
    val targetKg: Double? = null,
    val newDate: String? = null,
)

/** Ce que la mise à jour du poids de référence a donné à la production du bilan. */
@Serializable
enum class ReferenceStatus {
    /** Mis à jour : nouvelle version datée, avec Annuler ([ReferenceWeight.actionId]). */
    Updated,

    /** Les pesées donnent le même poids : rien ne change. */
    Unchanged,

    /** Aucune pesée utilisable : rien ne change, sans alerte. */
    NoWeighIn,

    /** Refusé par un garde-fou (protéines sous le minimum) : rien ne change. */
    NotUpdated,
}

/**
 * Le poids de référence à la production du bilan, figé avec la page : [beforeKg] → [afterKg], d'où vient le poids
 * ([source]), puis seulement les valeurs qui changent (« minimum de protéines 112 → 104 g », « lipides max inchangé (69 g) »)
 * et toujours « calories inchangées » ([lines]). L'état du bouton Annuler (fait ou non) se lit en direct dans le journal des
 * actions, d'après [actionId] : la page n'est jamais réécrite.
 */
@Serializable
data class ReferenceWeight(
    val beforeKg: Double,
    val afterKg: Double,
    val lines: List<String>,
    /** L'action de mise à jour, pour Annuler ; `null` quand rien n'a changé. */
    val actionId: Long? = null,
    val status: ReferenceStatus = ReferenceStatus.Updated,
    val source: String = "",
)

/** Lecture et écriture de la page dans la colonne `html` du rapport : un JSON, là où les anciens bilans gardent leur HTML. */
object ReportContent {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(page: ReportPage): String = json.encodeToString(ReportPage.serializer(), page)

    /** Le contenu est une page (JSON), pas l'HTML d'un ancien bilan. */
    fun isPage(content: String): Boolean = content.trimStart().startsWith("{")

    /**
     * La page d'un rapport enregistré, ou `null` pour un ancien bilan (HTML : il garde son rendu) et pour une page illisible
     * ou d'un format plus récent que cette version (voir [isPage] pour les distinguer).
     */
    fun decode(content: String): ReportPage? {
        if (!isPage(content)) return null
        val page = runCatching { json.decodeFromString(ReportPage.serializer(), content) }.getOrNull() ?: return null
        return page.takeIf { it.format == ReportPage.FORMAT }
    }
}
