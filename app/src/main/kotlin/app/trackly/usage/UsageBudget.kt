package app.trackly.usage

import java.math.BigDecimal
import java.math.RoundingMode

/** Budget mensuel de l'utilisateur (confirmé le 2 novembre 2026) et cible d'usage. */
object UsageBudget {
    const val MONTHLY: Micros = 82_000_000
    const val TARGET_LOW: Micros = 50_000_000
    const val TARGET_HIGH: Micros = 60_000_000
}

/** Montants en français : « 12,34 $ », espace insécable avant le symbole. */
object Money {
    private const val NBSP = ' '

    /** Dollars arrondis au centime ; « moins de 0,01 $ » pour un montant non nul plus petit. */
    fun format(micros: Micros): String {
        val cents = BigDecimal(micros).movePointLeft(4).setScale(0, RoundingMode.HALF_UP).toLong()
        if (micros > 0 && cents == 0L) return "moins de 0,01${NBSP}$"
        val whole = cents / 100
        val fraction = (cents % 100).toString().padStart(2, '0')
        return "${grouped(whole)},$fraction${NBSP}$"
    }

    /** Centimes affichés pour un montant (arrondi au plus proche). */
    private fun cents(micros: Micros): Long = BigDecimal(micros).movePointLeft(4).setScale(0, RoundingMode.HALF_UP).toLong()

    /**
     * Total affiché = somme des lignes **telles qu'affichées** (chacune arrondie au centime) : le total
     * égale toujours ce que l'on additionne à l'écran, jamais l'arrondi du total exact.
     */
    fun formatSum(lines: List<Micros>): String {
        val cents = lines.sumOf { cents(it) }
        if (cents == 0L && lines.any { it > 0 }) return "moins de 0,01${NBSP}$"
        return "${grouped(cents / 100)},${(cents % 100).toString().padStart(2, '0')}${NBSP}$"
    }

    /** Dollars entiers, pour un budget : « 82 $ ». */
    fun formatWhole(micros: Micros): String = "${grouped(micros / 1_000_000)}${NBSP}$"

    private fun grouped(n: Long): String = n.toString().reversed().chunked(3).joinToString(NBSP.toString()).reversed()
}
