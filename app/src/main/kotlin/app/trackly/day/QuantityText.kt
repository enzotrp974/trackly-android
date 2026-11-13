package app.trackly.day

/**
 * Accord de l'unité d'une quantité, **au même endroit pour tous les écrans** (carte du chat, feuille de repas) :
 * « 1 pièce », « 1,5 pièce », « 2 pièces », « 3 tranches », « 2 cuillères à soupe ». En français, le pluriel
 * commence à 2 (1,5 reste au singulier). Les unités abrégées (g, ml, kcal…) ne changent jamais.
 */
object QuantityText {
    /** Abréviations : jamais de « s ». */
    private val abbreviations = setOf("g", "kg", "mg", "l", "dl", "cl", "ml", "kcal", "cal", "oz", "lb")

    /** Pluriels qui ne se font pas avec un simple « s » (mots d'unité courants). */
    private val irregular = mapOf("bocal" to "bocaux")

    /** Unités anglaises venues de l'ancien suivi : « piece » → « pièce ». */
    private val english = mapOf("piece" to "pièce", "pieces" to "pièce")

    fun unit(value: Double, unit: String): String {
        val trimmed = unit.trim().let { u -> english[u.lowercase()] ?: u }
        if (value < 2.0 || trimmed.isEmpty()) return trimmed
        // Seul le premier mot prend le pluriel : « cuillère à soupe » → « cuillères à soupe ».
        val head = trimmed.substringBefore(' ')
        val tail = trimmed.removePrefix(head)
        return plural(head) + tail
    }

    private fun plural(word: String): String {
        val lower = word.lowercase()
        return when {
            lower in abbreviations || word.length <= 2 || '.' in word -> word
            lower in irregular -> irregular.getValue(lower)
            lower.last() in "sxz" -> word
            else -> word + "s"
        }
    }

    /** « 2 » + « pièce » → « 2 pièces » ; nombre à la française (« 1,5 »). */
    fun format(value: Double, unit: String, number: String): String = "$number ${unit(value, unit)}"

    private val written = Regex("""^(\d+(?:[.,]\d+)?)\s+(\S.*)$""")

    /**
     * Remet d'accord une quantité déjà écrite (« 2 pièce » dans l'historique d'une carte enregistrée avant le
     * correctif). Sans effet sur une quantité déjà juste ou qui n'a pas la forme « nombre unité ».
     */
    fun agree(text: String): String {
        val match = written.matchEntire(text.trim()) ?: return text
        val number = match.groupValues[1]
        val value = number.replace(',', '.').toDoubleOrNull() ?: return text
        return "$number ${unit(value, match.groupValues[2])}"
    }
}
