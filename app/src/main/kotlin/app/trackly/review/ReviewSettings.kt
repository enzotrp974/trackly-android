package app.trackly.review

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime

/**
 * Réglages du bilan du lundi. L'invitation arrive le lundi à [inviteTime] (9 h par défaut, heure
 * locale) ; la relance a lieu à [REMINDER_TIME] seulement si le bilan de la semaine n'est pas fait.
 */
data class ReviewSettingsState(
    val enabled: Boolean = true,
    val inviteTime: LocalTime = DEFAULT_INVITE_TIME,
) {
    companion object {
        val DEFAULT_INVITE_TIME: LocalTime = LocalTime.of(9, 0)

        /** Plage permise pour l'invitation : elle précède toujours la relance du soir. */
        val EARLIEST: LocalTime = LocalTime.of(5, 0)
        val LATEST: LocalTime = LocalTime.of(17, 30)

        /** Pas de réglage de l'heure, en minutes. */
        const val STEP_MINUTES = 30L
    }
}

/** Heure de la relance du soir. */
val REMINDER_TIME: LocalTime = LocalTime.of(19, 0)

interface ReviewSettings {
    val state: StateFlow<ReviewSettingsState>

    fun setEnabled(enabled: Boolean)

    /** Heure de l'invitation du lundi ; ramenée dans la plage permise. */
    fun setInviteTime(time: LocalTime)
}

fun LocalTime.coerceInvite(): LocalTime = when {
    isBefore(ReviewSettingsState.EARLIEST) -> ReviewSettingsState.EARLIEST
    isAfter(ReviewSettingsState.LATEST) -> ReviewSettingsState.LATEST
    else -> withSecond(0).withNano(0)
}

class SharedReviewSettings(context: Context) : ReviewSettings {
    private val prefs = context.applicationContext.getSharedPreferences("trackly-bilan", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    override val state: StateFlow<ReviewSettingsState> = _state.asStateFlow()

    private fun read() = ReviewSettingsState(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        inviteTime = LocalTime.ofSecondOfDay(prefs.getInt(KEY_INVITE_MINUTES, ReviewSettingsState.DEFAULT_INVITE_TIME.hour * 60) * 60L).coerceInvite(),
    )

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.value = _state.value.copy(enabled = enabled)
    }

    override fun setInviteTime(time: LocalTime) {
        val coerced = time.coerceInvite()
        prefs.edit().putInt(KEY_INVITE_MINUTES, coerced.hour * 60 + coerced.minute).apply()
        _state.value = _state.value.copy(inviteTime = coerced)
    }

    private companion object {
        const val KEY_ENABLED = "rappels_actifs"
        const val KEY_INVITE_MINUTES = "invitation_minutes"
    }
}

class InMemoryReviewSettings(initial: ReviewSettingsState = ReviewSettingsState()) : ReviewSettings {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<ReviewSettingsState> = _state.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled)
    }

    override fun setInviteTime(time: LocalTime) {
        _state.value = _state.value.copy(inviteTime = time.coerceInvite())
    }
}
