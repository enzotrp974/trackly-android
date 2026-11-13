package app.trackly.assistant.openai

/** Un message d'un flux SSE : nom d'événement (`event:`) et données (`data:`, lignes jointes par `\n`). */
data class SseMessage(val event: String?, val data: String)

/**
 * Décodeur de flux SSE (Server-Sent Events, norme WHATWG), sans dépendance : reçoit le texte par
 * morceaux de taille quelconque et rend les messages complets. Gère les fins de ligne `\n`, `\r\n`
 * et `\r`, les commentaires (`: …`, envoyés pour garder la connexion), les données sur plusieurs
 * lignes et les champs inconnus (ignorés). Un message n'est rendu qu'à la ligne vide qui le termine.
 */
class SseDecoder {
    private val line = StringBuilder()
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** `\r` vu en fin de morceau : un `\n` au début du suivant appartient à la même fin de ligne. */
    private var pendingCr = false

    fun feed(chunk: String): List<SseMessage> {
        val out = mutableListOf<SseMessage>()
        for (c in chunk) {
            if (pendingCr) {
                pendingCr = false
                if (c == '\n') continue
            }
            when (c) {
                '\n' -> endLine(out)
                '\r' -> {
                    endLine(out)
                    pendingCr = true
                }
                else -> line.append(c)
            }
        }
        return out
    }

    /** Fin du flux : un message sans ligne vide finale est abandonné, comme le veut la norme. */
    fun finish(): List<SseMessage> {
        line.setLength(0)
        reset()
        return emptyList()
    }

    private fun endLine(out: MutableList<SseMessage>) {
        val text = line.toString()
        line.setLength(0)
        if (text.isEmpty()) {
            if (hasData) out += SseMessage(event, data.toString())
            reset()
            return
        }
        if (text.startsWith(":")) return // commentaire
        val colon = text.indexOf(':')
        val field = if (colon < 0) text else text.substring(0, colon)
        var value = if (colon < 0) "" else text.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            else -> Unit // id, retry et champs inconnus : sans usage ici
        }
    }

    private fun reset() {
        event = null
        data.setLength(0)
        hasData = false
    }
}
