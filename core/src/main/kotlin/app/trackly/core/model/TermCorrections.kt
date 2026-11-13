package app.trackly.core.model

import java.text.Normalizer

/**
 * Mot corrigé (« vita » veut dire « Vitta ») : une [Preference] de type [TermCorrections.KIND] dont le
 * déclencheur est le mot entendu ([heard]) et la règle le vrai mot ([correct]).
 */
data class TermCorrection(val preferenceId: String, val heard: String, val correct: String)

/** Corrections de mots : lecture des préférences et remplacement en **mot entier**. */
object TermCorrections {
    /** Type de [Preference] d'une correction de mot. */
    const val KIND = "term_correction"

    /** Corrections actives parmi des préférences (les autres types, les règles désactivées et supprimées sont ignorés). */
    fun active(preferences: Collection<Preference>): List<TermCorrection> = preferences
        .filter { it.kind == KIND && it.isActive && it.active }
        .sortedWith(compareBy({ it.trigger.lowercase() }, { it.preferenceId }))
        .map { TermCorrection(it.preferenceId, it.trigger, it.rule) }

    /**
     * [text] avec chaque mot entendu remplacé par le vrai mot. Mot entier seulement (« vita » ne touche pas
     * « vitamine » ni « avitaminose »), sans tenir compte de la casse ni des accents. Un seul passage : un mot
     * remplacé n'est jamais corrigé une seconde fois. Un mot entendu qui fait déjà partie du vrai mot reste tel quel :
     * avec « express » → « Vitta Express », « Vitta Express » ne devient pas « Vitta Vitta Express ».
     */
    fun apply(text: String, corrections: Collection<TermCorrection>): String {
        if (text.isEmpty() || corrections.isEmpty()) return text
        val folded = fold(text)
        val found = corrections.flatMap { c -> outsideCorrect(folded, c).map { range -> range to c.correct } }
            .sortedWith(compareBy({ it.first.first }, { -(it.first.last - it.first.first) }))
        if (found.isEmpty()) return text
        val out = StringBuilder()
        var cursor = 0
        for ((range, correct) in found) {
            if (range.first < cursor) continue
            out.append(text, cursor, range.first).append(correct)
            cursor = range.last + 1
        }
        return out.append(text, cursor, text.length).toString()
    }

    /** Le mot [heard] figure-t-il dans [text] comme mot entier ? */
    fun contains(text: String, heard: String): Boolean = matches(fold(text), heard).isNotEmpty()

    /** Mots entiers à garder tels quels pour une transcription : les vrais mots des corrections actives. */
    fun correctWords(corrections: Collection<TermCorrection>): List<String> =
        corrections.map { it.correct.trim() }.filter { it.isNotEmpty() }.distinctBy(TextMatch::normalize)

    /** Les occurrences du mot entendu qui ne sont pas déjà à l'intérieur du vrai mot. */
    private fun outsideCorrect(foldedText: String, c: TermCorrection): List<IntRange> {
        val found = matches(foldedText, c.heard)
        // Correction de casse ou d'accent seulement (« vitta » → « Vitta ») : le mot entendu est le vrai mot, rien à protéger.
        if (fold(c.heard).trim() == fold(c.correct).trim()) return found
        val correct = matches(foldedText, c.correct)
        return found.filter { r -> correct.none { r.first >= it.first && r.last <= it.last } }
    }

    /** Une correction changerait-elle encore [text] ? */
    fun wouldChange(text: String, correction: TermCorrection): Boolean = apply(text, listOf(correction)) != text

    private fun matches(foldedText: String, heard: String): List<IntRange> {
        val words = fold(heard).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val pattern = words.joinToString("\\s+") { Regex.escape(it) }
        val regex = Regex("(?<![\\p{L}\\p{N}])$pattern(?![\\p{L}\\p{N}])")
        return regex.findAll(foldedText).map { it.range }.toList()
    }

    /** Minuscules sans accents, caractère par caractère : la longueur ne change pas, les positions restent valables. */
    private fun fold(text: String): String {
        val out = CharArray(text.length)
        for (i in text.indices) {
            val lower = text[i].lowercaseChar()
            out[i] = if (lower.code < 128) lower else Normalizer.normalize(lower.toString(), Normalizer.Form.NFD).firstOrNull() ?: lower
        }
        return String(out)
    }
}
