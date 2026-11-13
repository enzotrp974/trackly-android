package app.trackly.assistant.markdown

/**
 * Réponses libres du chat et du bilan : le Markdown du modèle, découpé en blocs que l'app dessine dans
 * son propre style (`ui/chat/MarkdownView`). Sous-ensemble utile au chat : titres, paragraphes, gras, italique,
 * barré, code, listes (imbriquées), tableaux, citations, séparateurs.
 *
 * Sûreté : aucun HTML n'est interprété (les balises sont ôtées, `<br>` devient un retour à la ligne) et aucun
 * lien n'est actif (seul le texte d'un lien reste). Un Markdown cassé ou inachevé reste lisible, sans exception :
 * ce qui ne se reconnaît pas reste du texte. En cours de réponse (`streaming`), un tableau s'affiche dès sa ligne
 * d'en-tête et un gras ouvert en fin de texte est déjà gras, pour ne jamais voir passer `|` ni `**`.
 *
 * Le texte enregistré n'est jamais modifié : seul l'affichage passe par ici.
 */
object Markdown {
    /** Profondeur d'imbrication au-delà de laquelle les marques restent du texte (garde-fou, entrées hostiles). */
    private const val MAX_DEPTH = 6

    private const val MARKS = "*-+_|:~`># "

    fun parse(text: String, streaming: Boolean = false): List<MdBlock> {
        var lines = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n')
        // En cours de réponse, une dernière ligne faite seulement de marques (« * », « - », « *** », « | », « --- »)
        // est le début de quelque chose : on attend la suite plutôt que d'afficher une puce vide ou un filet.
        if (streaming && lines.last().isNotBlank() && lines.last().all { it in MARKS }) lines = lines.dropLast(1)
        return BlockParser(lines, streaming, depth = 0).parse()
    }

    /** Texte tel qu'un lecteur le lit, sans aucune marque : notifications, accessibilité, contrôles. */
    fun plain(text: String, streaming: Boolean = false): String = plain(parse(text, streaming))

    fun plain(blocks: List<MdBlock>): String = blocks.mapNotNull { block ->
        when (block) {
            is MdBlock.Paragraph -> block.inlines.text()
            is MdBlock.Heading -> block.inlines.text()
            is MdBlock.ListBlock -> block.items.joinToString("\n") { "  ".repeat(it.level) + (it.marker ?: "•") + " " + it.inlines.text() }
            is MdBlock.Table -> (listOf(block.header) + block.rows).joinToString("\n") { row -> row.joinToString(" · ") { it.text() } }
            is MdBlock.Quote -> plain(block.blocks)
            is MdBlock.Code -> block.text
            MdBlock.Rule -> null
        }
    }.joinToString("\n")

    /** Une réponse faite seulement de paragraphes : elle garde la bulle étroite d'une phrase. */
    fun isSimple(blocks: List<MdBlock>): Boolean = blocks.all { it is MdBlock.Paragraph }

    private fun List<Inline>.text() = joinToString("") { it.text }

    // ---------------------------------------------------------------------------------------------------------
    // Blocs

    private val fence = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
    private val heading = Regex("""^ {0,3}(#{1,6})(?:[ ]+(.*?))?[ ]*$""")
    private val closingHashes = Regex("""(^|[ ]+)#+[ ]*$""")
    private val item = Regex("""^( *)([-*+]|\d{1,3}[.)])(?:[ ]+(.*))?$""")
    private val quote = Regex("""^ {0,3}>[ ]?(.*)$""")

    /** Séparateur « --- », « * * * », « ___ » (sans regex à groupe répété : pas de débordement de pile sur une ligne hostile). */
    private object RuleLine {
        fun matches(line: String): Boolean {
            val body = line.trimEnd()
            val indent = body.length - body.trimStart().length
            if (indent > 3) return false
            val mark = body.trimStart().firstOrNull() ?: return false
            if (mark != '-' && mark != '*' && mark != '_') return false
            var count = 0
            for (c in body) when (c) {
                mark -> count++
                ' ' -> Unit
                else -> return false
            }
            return count >= 3
        }
    }

    /** Ligne de séparation d'un tableau : « |---|:--:|--:| », y compris à moitié écrite (« |---|-- »). */
    private object DelimiterRow {
        fun matches(line: String): Boolean {
            var dashes = 0
            for (c in line) when (c) {
                '-' -> dashes++
                '|', ':', ' ' -> Unit
                else -> return false
            }
            return dashes > 0
        }
    }

    private class BlockParser(private val lines: List<String>, private val streaming: Boolean, private val depth: Int) {
        private val blocks = mutableListOf<MdBlock>()
        private var i = 0

        fun parse(): List<MdBlock> {
            while (i < lines.size) {
                val line = lines[i]
                when {
                    line.isBlank() -> i++
                    fence.matches(line) -> code()
                    RuleLine.matches(line) -> { blocks += MdBlock.Rule; i++ }
                    heading.matches(line) -> headingBlock()
                    depth < MAX_DEPTH && quote.matches(line) -> quoteBlock()
                    tableStarts(i) -> table()
                    item.matches(line) -> list()
                    else -> paragraph()
                }
            }
            return blocks
        }

        private val lastLine get() = lines.lastIndex

        /** Le texte inline d'un bloc ; seul le dernier bloc d'une réponse en cours peut finir sur une marque ouverte. */
        private fun inline(text: String, endLine: Int) =
            InlineParser.parse(text, openEnd = streaming && endLine >= lastLine && depth == 0)

        private fun startsBlock(index: Int): Boolean {
            val line = lines[index]
            return line.isBlank() || fence.matches(line) || RuleLine.matches(line) || heading.matches(line) ||
                quote.matches(line) || tableStarts(index) || item.matches(line)
        }

        private fun code() {
            val open = fence.find(lines[i])!!.groupValues[1]
            val body = mutableListOf<String>()
            i++
            while (i < lines.size) {
                val close = fence.find(lines[i])
                if (close != null && close.groupValues[1].first() == open.first() && close.groupValues[1].length >= open.length && close.groupValues[2].isBlank()) {
                    i++
                    break
                }
                body += lines[i]
                i++
            }
            val code = body.joinToString("\n").trimEnd()
            if (code.isNotBlank()) blocks += MdBlock.Code(code)
        }

        private fun headingBlock() {
            val match = heading.find(lines[i])!!
            val raw = match.groupValues[2].replace(closingHashes, "").trim()
            if (raw.isNotEmpty()) blocks += MdBlock.Heading(match.groupValues[1].length, inline(raw, i))
            i++
        }

        private fun quoteBlock() {
            val inner = mutableListOf<String>()
            while (i < lines.size) {
                val match = quote.find(lines[i]) ?: break
                inner += match.groupValues[1]
                i++
            }
            val nested = BlockParser(inner, streaming = false, depth = depth + 1).parse()
            if (nested.isNotEmpty()) blocks += MdBlock.Quote(nested)
        }

        private fun paragraph() {
            val text = mutableListOf(lines[i].trim())
            i++
            while (i < lines.size && !startsBlock(i)) {
                text += lines[i].trim()
                i++
            }
            // Un paragraphe qui ne contenait que des balises HTML ne laisse rien : on ne dessine pas de bloc vide.
            val inlines = inline(text.joinToString("\n"), i - 1)
            if (inlines.any { it.text.isNotBlank() }) blocks += MdBlock.Paragraph(inlines)
        }

        // --- Listes

        private fun list() {
            val items = mutableListOf<MdItem>()
            val indents = mutableListOf<Int>()
            var lastOrdered: Boolean? = null
            while (i < lines.size) {
                val line = lines[i]
                val match = item.find(line)
                if (match == null || RuleLine.matches(line)) {
                    if (line.isBlank()) {
                        // Une ligne vide ne coupe la liste que si la suite n'en est pas un élément du même genre.
                        val next = (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() } ?: break
                        val nextItem = item.find(lines[next])
                        if (nextItem == null || RuleLine.matches(lines[next])) break
                        // Une sous-liste plus en retrait continue la liste ; au même niveau, il faut le même genre.
                        val nested = indents.isNotEmpty() && nextItem.groupValues[1].length > indents.first()
                        if (!nested && nextItem.isOrdered() != lastOrdered) break
                        i = next
                        continue
                    }
                    if (items.isEmpty() || startsBlock(i)) break
                    // Ligne de suite : elle appartient à l'élément précédent.
                    val last = items.removeAt(items.lastIndex)
                    items += last.copy(raw = last.raw + "\n" + line.trim())
                    i++
                    continue
                }
                val indent = match.groupValues[1].length
                while (indents.isNotEmpty() && indent < indents.last()) indents.removeAt(indents.lastIndex)
                if (indents.isEmpty() || indent > indents.last()) indents += indent
                val level = (indents.size - 1).coerceAtMost(3)
                val marker = match.groupValues[2].takeIf { it.first().isDigit() }?.let { it.dropLast(1) + "." }
                if (level == 0) lastOrdered = match.isOrdered()
                items += MdItem(level, marker, match.groupValues[3].trim(), i)
                i++
            }
            val endLine = i - 1
            blocks += MdBlock.ListBlock(
                items.map { MdListItem(it.level, it.marker, inline(it.raw, if (it === items.last()) endLine else it.line)) },
            )
        }

        private fun MatchResult.isOrdered() = groupValues[2].first().isDigit()

        private data class MdItem(val level: Int, val marker: String?, val raw: String, val line: Int)

        // --- Tableaux

        private fun tableStarts(index: Int): Boolean {
            val line = lines[index]
            if (!line.contains('|') || (line.contains('-') && DelimiterRow.matches(line))) return false
            val cells = Cells.split(line)
            if (cells.none { it.isNotBlank() }) return false
            val next = lines.getOrNull(index + 1)
            if (next != null && next.contains('|') && next.contains('-') && DelimiterRow.matches(next)) return true
            if (!line.trimStart().startsWith("|")) return false
            // En-tête qui commence par « | » : un tableau dès qu'il a deux cellules, ou dès sa première en cours de réponse.
            return cells.size >= 2 || (streaming && index == lastLine)
        }

        private fun table() {
            val header = Cells.split(lines[i])
            i++
            var aligns: List<Align?> = emptyList()
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                val line = lines[i]
                if (DelimiterRow.matches(line) && line.contains('-')) {
                    if (aligns.isEmpty() && rows.isEmpty()) aligns = Cells.split(line).map { it.align() }
                } else {
                    rows += Cells.split(line)
                }
                i++
            }
            val width = header.size
            val endLine = i - 1
            fun cells(raw: List<String>, line: Int) = List(width) { col -> inline(raw.getOrElse(col) { "" }, if (col == raw.lastIndex) line else -1) }
            val headerCells = cells(header, if (rows.isEmpty()) endLine else -1)
            val bodyCells = rows.mapIndexed { index, raw -> cells(raw, if (index == rows.lastIndex) endLine else -1) }
            val resolved = List(width) { col ->
                aligns.getOrNull(col) ?: if (bodyCells.isNotEmpty() && bodyCells.all { row -> row[col].isNumeric() }) Align.End else Align.Start
            }
            blocks += MdBlock.Table(headerCells, bodyCells, resolved)
        }

        private fun String.align(): Align? {
            val cell = trim()
            val left = cell.startsWith(":")
            val right = cell.endsWith(":") && cell.length > 1
            return when {
                left && right -> Align.Center
                right -> Align.End
                left -> Align.Start
                else -> null
            }
        }

        private fun List<Inline>.isNumeric(): Boolean {
            val text = text().trim()
            return text.isEmpty() || number.matches(text)
        }
    }

    /** « 650 », « 42 g », « ~650 kcal », « 1 070 », « 12 % », « −3 » : une colonne de ces valeurs s'aligne à droite. */
    private val number = Regex("""^[~≈±+\-−]?\s*\d[\d\s  .,]*\s*(%|kcal|g|kg|mg|ml|cl|l|€|pas|min|h)?\.?$""", RegexOption.IGNORE_CASE)

    /** Découpe d'une ligne de tableau : une barre échappée (`\|`) ou dans du code reste dans la cellule. */
    private object Cells {
        fun split(line: String): List<String> {
            val cells = mutableListOf<String>()
            val current = StringBuilder()
            var code = 0
            var index = 0
            while (index < line.length) {
                val c = line[index]
                when {
                    c == '\\' && index + 1 < line.length && line[index + 1] == '|' -> { current.append("\\|"); index += 2; continue }
                    c == '`' -> {
                        var run = 0
                        while (index + run < line.length && line[index + run] == '`') run++
                        code = if (code == 0) run else if (code == run) 0 else code
                        current.append("`".repeat(run))
                        index += run
                        continue
                    }
                    c == '|' && code == 0 -> { cells += current.toString(); current.clear() }
                    else -> current.append(c)
                }
                index++
            }
            cells += current.toString()
            if (cells.size > 1 && cells.first().isBlank()) cells.removeAt(0)
            if (cells.size > 1 && cells.last().isBlank()) cells.removeAt(cells.lastIndex)
            return cells.map { it.trim() }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // En ligne

    private object InlineParser {
        private val autolink = Regex("""^<((?:https?|mailto):[^\s<>]+)>""")
        /** Seules les balises HTML courantes sont ôtées : « glucides<b et lipides>c » reste du texte. */
        private val tag = Regex(
            """^</?(?:b|i|u|s|em|strong|br|p|span|div|sup|sub|small|mark|code|pre|ul|ol|li|table|thead|tbody|tr|td|th|h[1-6]|a|img|font|center|del|ins|script|style)(?:\s+[A-Za-z-]+=(?:"[^"<>]*"|'[^'<>]*'|[^\s"'<>]+))*\s*/?>""",
            RegexOption.IGNORE_CASE,
        )
        private val br = Regex("""^<br\s*/?>""", RegexOption.IGNORE_CASE)
        private const val PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

        fun parse(text: String, openEnd: Boolean): List<Inline> {
            val out = mutableListOf<Inline>()
            run(text, Style(), openEnd, 0, out)
            return merge(out)
        }

        private data class Style(val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val strike: Boolean = false)

        private fun emit(out: MutableList<Inline>, text: String, style: Style) {
            if (text.isNotEmpty()) out += Inline(text, style.bold, style.italic, style.code, style.strike)
        }

        private fun run(text: String, style: Style, openEnd: Boolean, depth: Int, out: MutableList<Inline>) {
            val plain = StringBuilder()
            fun flush() { emit(out, plain.toString(), style); plain.clear() }
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '\\' && i + 1 < text.length && text[i + 1] in PUNCTUATION -> { plain.append(text[i + 1]); i += 2 }
                    c == '`' -> {
                        val run = countRun(text, i, '`')
                        val close = findCodeClose(text, i + run, run)
                        when {
                            close >= 0 -> {
                                flush()
                                emit(out, text.substring(i + run, close).let { if (it.length > 2 && it.startsWith(' ') && it.endsWith(' ')) it.substring(1, it.length - 1) else it }, style.copy(code = true))
                                i = close + run
                            }
                            openEnd -> { flush(); emit(out, text.substring(i + run), style.copy(code = true)); i = text.length }
                            else -> { plain.append(text, i, i + run); i += run }
                        }
                    }
                    c == '!' && i + 1 < text.length && text[i + 1] == '[' -> {
                        val link = link(text, i + 1)
                        if (link != null) { plain.append(link.first); i = link.second } else { plain.append(c); i++ }
                    }
                    c == '[' -> {
                        val link = link(text, i)
                        if (link != null && depth < MAX_DEPTH) {
                            flush()
                            run(link.first, style, false, depth + 1, out)
                            i = link.second
                        } else { plain.append(c); i++ }
                    }
                    c == '<' -> {
                        val rest = text.substring(i, minOf(text.length, i + 2048))
                        val auto = autolink.find(rest)
                        val html = if (auto == null) tag.find(rest) else null
                        when {
                            auto != null -> { plain.append(auto.groupValues[1]); i += auto.value.length }
                            html != null -> { if (br.matches(html.value)) plain.append('\n'); i += html.value.length }
                            else -> { plain.append(c); i++ }
                        }
                    }
                    (c == '*' || c == '_' || c == '~') && depth < MAX_DEPTH -> {
                        val run = countRun(text, i, c)
                        val styled = emphasis(text, i, run, c, style, openEnd, depth)
                        if (styled != null) {
                            flush()
                            out += styled.first
                            i = styled.second
                        } else if (openEnd && i + run == text.length) {
                            i += run // marque qui vient d'arriver, sans texte derrière : pas encore lisible
                        } else { plain.append(text, i, i + run); i += run }
                    }
                    else -> { plain.append(c); i++ }
                }
            }
            flush()
        }

        /** Gras, italique ou barré qui s'ouvre en [start] ; `null` si la marque reste du texte. */
        private fun emphasis(text: String, start: Int, run: Int, c: Char, style: Style, openEnd: Boolean, depth: Int): Pair<List<Inline>, Int>? {
            if (c == '~' && run != 2) return null
            if (run > 3) return null
            val after = text.getOrNull(start + run) ?: return null
            if (after.isWhitespace()) return null
            if (c == '_' && text.getOrNull(start - 1)?.isLetterOrDigit() == true) return null
            for (size in (if (c == '~') listOf(2) else (minOf(run, 3) downTo 1))) {
                val close = findClose(text, start + run, c, size)
                val inner: String
                val next: Int
                if (close >= 0) {
                    inner = text.substring(start + size, close)
                    next = close + size
                } else if (openEnd && size == run && text.getOrNull(start - 1).let { it == null || !it.isLetterOrDigit() }) {
                    // Marque ouverte en fin de réponse en cours (« **2 30 ») ; pas « 2*3 », qui n'ouvre rien.
                    inner = text.substring(start + size)
                    next = text.length
                } else continue
                // Une marque plus longue que sa fermeture : le reste de la marque fait partie du texte intérieur.
                val innerStyle = when {
                    c == '~' -> style.copy(strike = true)
                    size == 3 -> style.copy(bold = true, italic = true)
                    size == 2 -> style.copy(bold = true)
                    else -> style.copy(italic = true)
                }
                val out = mutableListOf<Inline>()
                run(inner, innerStyle, close < 0, depth + 1, out)
                if (out.isEmpty() && close >= 0) continue
                return out to next
            }
            return null
        }

        /** Fermeture de [size] marques [c], précédée d'un caractère non blanc ; la fin d'une marque plus longue compte. */
        private fun findClose(text: String, from: Int, c: Char, size: Int): Int {
            var i = from
            while (i < text.length) {
                when (text[i]) {
                    '\\' -> { i += 2; continue }
                    '`' -> {
                        val run = countRun(text, i, '`')
                        val close = findCodeClose(text, i + run, run)
                        i = if (close >= 0) close + run else i + run
                        continue
                    }
                    c -> {
                        val run = countRun(text, i, c)
                        val before = text.getOrNull(i - 1)
                        val afterRun = text.getOrNull(i + run)
                        val flanking = i > from && before != null && !before.isWhitespace() &&
                            (c != '_' || afterRun == null || !afterRun.isLetterOrDigit())
                        if (flanking && run >= size) return i + run - size
                        i += run
                        continue
                    }
                }
                i++
            }
            return -1
        }

        private fun findCodeClose(text: String, from: Int, size: Int): Int {
            var i = from
            while (i < text.length) {
                if (text[i] == '`') {
                    val run = countRun(text, i, '`')
                    if (run == size) return i
                    i += run
                } else i++
            }
            return -1
        }

        /** `[texte](adresse)` : le texte et la position qui suit ; l'adresse n'est jamais gardée. */
        private fun link(text: String, start: Int): Pair<String, Int>? {
            var i = start + 1
            var nesting = 0
            while (i < text.length) {
                when (text[i]) {
                    '\\' -> i++
                    '[' -> nesting++
                    ']' -> if (nesting == 0) break else nesting--
                    '\n' -> return null
                }
                i++
            }
            if (i >= text.length || text.getOrNull(i + 1) != '(') return null
            val label = text.substring(start + 1, i)
            var j = i + 2
            var parens = 0
            while (j < text.length) {
                when (text[j]) {
                    '(' -> parens++
                    ')' -> if (parens == 0) return label to j + 1 else parens--
                    ' ', '\n' -> if (parens == 0 && !text.startsWith(" \"", j)) return null
                }
                j++
            }
            return null
        }

        private fun countRun(text: String, start: Int, c: Char): Int {
            var n = 0
            while (start + n < text.length && text[start + n] == c) n++
            return n
        }

        private fun merge(runs: List<Inline>): List<Inline> {
            val merged = mutableListOf<Inline>()
            for (run in runs) {
                val last = merged.lastOrNull()
                if (last != null && last.bold == run.bold && last.italic == run.italic && last.code == run.code && last.strike == run.strike) {
                    merged[merged.lastIndex] = last.copy(text = last.text + run.text)
                } else merged += run
            }
            return merged
        }
    }
}

sealed interface MdBlock {
    data class Paragraph(val inlines: List<Inline>) : MdBlock
    data class Heading(val level: Int, val inlines: List<Inline>) : MdBlock
    data class ListBlock(val items: List<MdListItem>) : MdBlock
    data class Table(val header: List<List<Inline>>, val rows: List<List<List<Inline>>>, val aligns: List<Align>) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

/** Élément de liste : [level] d'imbrication (0 à 3), [marker] « 1. » pour une liste numérotée, `null` pour une puce. */
data class MdListItem(val level: Int, val marker: String?, val inlines: List<Inline>)

data class Inline(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
)

enum class Align { Start, Center, End }
