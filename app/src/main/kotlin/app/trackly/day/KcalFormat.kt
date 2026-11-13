package app.trackly.day

/** Espace fine insécable (U+202F), séparateur de milliers en français. */
const val THIN_NBSP = ' '

/** 1140 → « 1 140 ». */
fun formatKcal(value: Int): String {
    val digits = value.toString()
    val head = digits.length % 3
    return buildString {
        digits.forEachIndexed { i, c ->
            if (i > 0 && (i - head) % 3 == 0) append(THIN_NBSP)
            append(c)
        }
    }
}
