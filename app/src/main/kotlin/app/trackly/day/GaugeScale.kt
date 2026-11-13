package app.trackly.day

/** Graduations de la jauge « 0 → objectif » : une tous les 100 kcal, une grande tous les 500. */
data class GaugeScale(val goalKcal: Int, val ticks: List<Tick>, val labels: List<Label>) {
    data class Tick(val kcal: Int, val fraction: Float, val major: Boolean)
    data class Label(val kcal: Int, val fraction: Float)

    /** Nombre de graduations allumées (cobalt) pour une consommation donnée. */
    fun litTicks(consumedKcal: Int): Int = ticks.count { it.kcal <= consumedKcal }

    companion object {
        private const val TICK_KCAL = 100
        private const val MAJOR_KCAL = 500
        private const val LABEL_KCAL = 1000

        /** Un repère chiffré trop proche de la borne finale (« sur 2 600 ») la chevaucherait. */
        private const val LABEL_MAX_FRACTION = 0.85f

        fun of(goalKcal: Int): GaugeScale {
            require(goalKcal > 0) { "Objectif calorique invalide : $goalKcal" }
            val ticks = (0..goalKcal step TICK_KCAL).map {
                Tick(kcal = it, fraction = it.toFloat() / goalKcal, major = it % MAJOR_KCAL == 0)
            }
            val labels = (0..goalKcal step LABEL_KCAL)
                .map { Label(kcal = it, fraction = it.toFloat() / goalKcal) }
                .filter { it.fraction < LABEL_MAX_FRACTION }
            return GaugeScale(goalKcal, ticks, labels)
        }

        fun fill(consumedKcal: Int, goalKcal: Int): Float =
            (consumedKcal.toFloat() / goalKcal).coerceIn(0f, 1f)
    }
}
