package app.trackly.photos

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Vignettes des photos envoyées pendant la session (idée 7 de l'audit) : on voit quelle photo a donné
 * quelle carte. **En mémoire seulement** (jamais sur le disque ni en base) : elles disparaissent quand
 * Android ferme l'app, et la bulle garde alors son repère « 1 photo envoyée ».
 *
 * Au plus [maxMessages] messages gardés (les plus récents), pour borner la mémoire.
 */
class SessionThumbnails(private val maxMessages: Int = MAX_MESSAGES) {
    private val _byMessage = MutableStateFlow<Map<Long, List<Bitmap>>>(emptyMap())

    /** Vignettes par identifiant de message de l'utilisateur. */
    val byMessage: StateFlow<Map<Long, List<Bitmap>>> = _byMessage.asStateFlow()

    fun put(messageId: Long, thumbnails: List<Bitmap>) {
        if (thumbnails.isEmpty()) return
        _byMessage.update { current ->
            val next = LinkedHashMap(current)
            next.remove(messageId)
            next[messageId] = thumbnails
            while (next.size > maxMessages) next.remove(next.keys.first())
            next
        }
    }

    companion object {
        const val MAX_MESSAGES = 12
    }
}
