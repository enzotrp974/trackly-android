package app.trackly.assistant.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/** Questions laissées dans le fil du jour [day] et pas encore vues (le chat n'a pas été ouvert depuis). */
data class Unseen(val day: LocalDate, val count: Int)

/** Où est gardée la pastille entre deux lancements : elle doit rester visible au retour, même après un arrêt d'Android. */
interface UnseenQuestionsStore {
    fun read(): Unseen?

    fun write(value: Unseen?)
}

class InMemoryUnseenQuestionsStore : UnseenQuestionsStore {
    private var value: Unseen? = null

    override fun read(): Unseen? = value

    override fun write(value: Unseen?) {
        this.value = value
    }
}

/**
 * Sur le téléphone : un petit fichier de préférences, hors sauvegarde Android (les règles n'incluent que la base) et hors
 * copie Drive (`PortableSettings` ne prend que ses fichiers listés). Il ne porte qu'une date et un nombre, aucun texte.
 */
class SharedUnseenQuestionsStore(context: Context) : UnseenQuestionsStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): Unseen? {
        val day = prefs.getString(DAY, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val count = prefs.getInt(COUNT, 0).takeIf { it > 0 } ?: return null
        return Unseen(day, count)
    }

    override fun write(value: Unseen?) {
        prefs.edit().apply {
            if (value == null) clear() else putString(DAY, value.day.toString()).putInt(COUNT, value.count)
        }.apply()
    }

    companion object {
        const val FILE = "trackly-questions-non-vues"
        private const val DAY = "jour"
        private const val COUNT = "nombre"
    }
}

/**
 * Pastille du chat : la relecture après un appel a laissé des questions dans le fil du jour, et l'utilisateur ne les a pas
 * encore vues. Elle s'affiche sur le chemin qui ouvre le chat depuis l'accueil, et disparaît quand il ouvre le chat. Elle ne
 * vaut que pour le fil où les questions sont écrites : à 5 h, le fil repart à zéro et elle disparaît avec lui. Jamais de
 * notification Android.
 */
class UnseenQuestions(private val store: UnseenQuestionsStore = InMemoryUnseenQuestionsStore()) {
    private val _state = MutableStateFlow(runCatching { store.read() }.getOrNull())
    val state: StateFlow<Unseen?> = _state.asStateFlow()

    /** [count] questions viennent d'être écrites dans le fil du jour [day] : elles s'ajoutent à celles pas encore vues. */
    fun raise(count: Int, day: LocalDate) {
        if (count <= 0) return
        val before = _state.value?.takeIf { it.day == day }?.count ?: 0
        val next = Unseen(day, before + count)
        _state.value = next
        runCatching { store.write(next) }
    }

    /** Le chat est ouvert : tout ce qu'il contient est vu. */
    fun seen() {
        if (_state.value == null) return
        _state.value = null
        runCatching { store.write(null) }
    }

    companion object {
        /** Nombre à montrer sur l'accueil le jour [today] (0 : pas de pastille). */
        fun count(value: Unseen?, today: LocalDate): Int = value?.takeIf { it.day == today }?.count ?: 0
    }
}
