package app.trackly.core.review

/**
 * Hypothèses chiffrées du bilan, **toutes au même endroit**. Les valeurs de référence sont des valeurs par défaut
 * (pas encore réglables dans l'app) ; le reste est une hypothèse de l'application (rien n'est mesuré),
 * signalée comme telle dans le rapport.
 *
 * Les coûts en kcal par kilo et par 1 000 pas sont des **calculs de l'auteur** à partir du
 * Compendium des activités physiques 2024 (marche plane, 3,8 à 4,8 MET ; coût net = (MET - 1) x poids,
 * à 100-115 pas par minute) :
 * - https://pubmed.ncbi.nlm.nih.gov/38242596/ (Herrmann et coll., J Sport Health Sci, 2024)
 * - https://pacompendium.com/walking/ (valeurs de marche)
 * - https://pacompendium.com/conditioning-exercise/ (musculation, 3,5 à 6 MET)
 * - https://pacompendium.com/running/ (course, 9,3 MET à 10 km/h)
 */
data class ReviewParams(
    /** Dépense de référence, kcal par jour (valeur par défaut, jamais validée par une mesure). */
    val referenceKcal: Double = 3000.0,
    /** Incertitude de la référence, kcal par jour (ordre de grandeur de l'erreur d'une dépense non mesurée). */
    val referenceUncertaintyKcal: Double = 300.0,
    /** Pas par jour pour cette dépense de référence. */
    val referenceSteps: Double = 20_000.0,
    /** Séances par semaine de la référence : milieu de « 3 à 4 ». */
    val referenceSessionsPerWeek: Double = 3.5,

    /**
     * Coût net d'une tranche de 1 000 pas, en kcal par kg de poids : 0,44 à 0,55 d'après le Compendium 2024
     * (voir ci-dessus), arrondi à 0,5. Soit environ 31 kcal à 62 kg et 36 kcal à 72 kg : l'ajustement suit le poids.
     */
    val kcalPerKgPer1000Steps: Double = 0.5,
    /** Incertitude de l'ajustement des pas (part de l'ajustement) : l'écart observé entre 0,44 et 0,55 donne +-12 %, doublé par prudence (cadence variable). */
    val stepsUncertaintyShare: Double = 0.25,
    /**
     * Part comptée des pas au-dessus de la référence. La dépense totale plafonne quand l'activité est très haute
     * (Pontzer et coll., Current Biology, 2016, https://omicsdi.org/dataset/biostudies-literature/S-EPMC4803033) :
     * règle de prudence de l'auteur, pas une valeur validée.
     */
    val stepsAboveReferenceShare: Double = 0.5,
    /**
     * Poids de travail quand aucune pesée n'est connue : sert **seulement** au coût des pas, n'est jamais
     * présenté comme le poids de l'utilisateur. 70 kg est l'ordre de grandeur du poids de départ supposé de la recherche.
     */
    val defaultWeightKg: Double = 70.0,

    /**
     * Coût net d'une séance de musculation (ou d'un autre sport sans beaucoup de pas), kcal : 175 à 350 pour 60 min
     * à 70 kg d'après le Compendium 2024 (3,5 à 6 MET), retenu à 250.
     */
    val kcalPerSession: Double = 250.0,
    /** Incertitude du coût d'une séance, kcal (la moitié de l'écart 175-350 environ, arrondie). */
    val sessionUncertaintyKcal: Double = 100.0,

    /** Équivalent énergétique d'un kilo de poids. Convention courante (plage 5 500 à 7 700 dans la littérature). */
    val kcalPerKg: Double = 7700.0,
    /** Pesées minimum dans une semaine pour que sa moyenne soit jugée fiable (MacroFactor : au moins 3 par semaine, https://help.macrofactorapp.com/dashboard/weight_trend/). */
    val reliableWeighIns: Int = 3,
    /**
     * Écart-type d'une pesée du matin autour de la moyenne de la semaine, en kg : 0,5 à 1 kg d'une pesée à l'autre
     * (poids plus haut le dimanche et le lundi, Orsama et coll., Obesity Facts, 2014, https://pubmed.ncbi.nlm.nih.gov/24504358/).
     */
    val weighInNoiseKg: Double = 0.8,
    /** Bruit minimal d'une moyenne hebdomadaire, même avec 7 pesées, en kg (0,3 à 0,5 d'après la recherche). */
    val weekMeanMinNoiseKg: Double = 0.3,
    /** Nombre de semaines civiles regardées pour la tendance (la semaine du bilan et les précédentes). */
    val trendWindowWeeks: Int = 4,
    /** Semaines pesées minimum pour calculer une tendance : en dessous, « trop tôt pour conclure ». */
    val trendMinWeeks: Int = 3,
    /** Largeur de la fourchette de la tendance en erreurs types (2 = environ 95 %). */
    val trendRangeFactor: Double = 2.0,
    /**
     * Demi-largeur maximale de la fourchette, kg par semaine, pour dire « dans le rythme » : au-delà, la fourchette
     * est trop large (plus de 60 % du rythme visé, environ 0,5 kg par semaine) pour affirmer quoi que ce soit.
     */
    val trendPrecisionKgPerWeek: Double = 0.3,

    // ───── Prévision d'arrivée ─────

    /** Poids de la dépense de référence : « 3 000 kcal à 70 kg » (valeur par défaut). */
    val referenceWeightKg: Double = 70.0,
    /**
     * Baisse de la dépense par kilo perdu, kcal par jour. Hypothèse de l'application : la règle de Hall (100 kJ, environ 24 kcal
     * par jour et par kilo pour un adulte, Hall et coll., Lancet 2011, https://pubmed.ncbi.nlm.nih.gov/21872751/),
     * relevée pour une activité de référence élevée (20 000 pas par jour), dont le coût suit le poids (0,5 kcal par kg et par 1 000 pas,
     * soit environ 10 kcal par kilo à 20 000 pas), et pour l'adaptation métabolique. Arrondi à 30, à mi-chemin d'une
     * dépense proportionnelle au poids (3 000 / 70 ≈ 43). La correction par les pesées absorbe l'écart réel.
     */
    val expenditureKcalPerKg: Double = 30.0,
    /** Semaines civiles regardées pour ajuster la courbe sur les pesées (plus large que la tendance : marge plus étroite). */
    val forecastWindowWeeks: Int = 6,
    /** Jours comptés minimum, sur les 4 dernières semaines, pour un rythme théorique tiré des repas réels. */
    val forecastMinIntakeDays: Int = 7,
    /** Jours comptés minimum, sur la fenêtre des pesées, pour en déduire la dépense mesurée. */
    val forecastMinMeasuredIntakeDays: Int = 14,
    /** En dessous de ce rythme (kg par semaine, vers la cible), mesuré avec une fourchette étroite, le poids est stable. */
    val stallMaxKgPerWeek: Double = 0.1,
    /** Sans assez de semaines pesées, une pesée plus ancienne ne sert pas de point de départ à une estimation. */
    val forecastMaxWeighInAgeDays: Int = 14,
    /** Au-delà, une date n'a plus de sens : « pas à ce rythme ». */
    val forecastHorizonDays: Int = 3 * 365,
) {
    init {
        require(trendMinWeeks in 2..trendWindowWeeks) { "Fenêtre de tendance incohérente" }
        require(forecastWindowWeeks >= trendMinWeeks) { "Fenêtre de prévision incohérente" }
        require(expenditureKcalPerKg > 0) { "Baisse de la dépense par kilo invalide" }
    }

    companion object {
        val Default = ReviewParams()
    }
}
