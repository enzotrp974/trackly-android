package app.trackly.call

import app.trackly.call.live.LaunchStep
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Transcription du **dernier** appel : un seul fichier privé de l'app (`filesDir/appel`), écrasé à
 * l'appel suivant, jamais envoyé sans un geste de l'utilisateur (« Copier » dans Réglages › Appel).
 *
 * Exclu de la sauvegarde Android (les règles `data_extraction_rules.xml` et `backup_rules.xml` n'incluent que la base)
 * et de la copie Drive (qui n'emporte que la base et les réglages non secrets) : voir `CallTranscriptExclusionTest`.
 * Jamais écrit dans les journaux.
 */
class LastCallTranscript(private val file: File) {
    /** Remplace la transcription précédente par celle de l'appel qui vient de finir (rien si l'appel est vide). */
    fun write(lines: List<CallLine>, at: Instant) {
        val text = format(lines, at) ?: return
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    fun read(): String? = file.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    companion object {
        const val DIR = "appel"
        const val FILE_NAME = "derniere-transcription.txt"

        fun format(lines: List<CallLine>, at: Instant): String? {
            val kept = lines.filter { it.text.isNotBlank() }
            if (kept.isEmpty()) return null
            return buildString {
                append("Dernier appel Trackly · ").append(at).append('\n')
                for (line in kept) append('\n').append(if (line.speaker == Speaker.User) "Toi" else "Trackly").append(" : ").append(line.text.trim())
                append('\n')
            }
        }
    }
}

/** Délai d'une réflexion pendant l'appel (s'en sert pour régler la position par défaut). */
data class ReflectionTiming(val reflection: CallReflection, val ms: Long, val ok: Boolean)

/**
 * Mesures d'un appel, gardées sur le téléphone (aucun texte de conversation) : étapes du lancement, délai jusqu'à
 * « je t'écoute », délai de chaque réflexion, fin et durée. Elles servent à juger la cible « moins d'1 s ».
 */
data class CallMetrics(
    val at: Instant,
    /** La préparation gratuite était faite avant le toucher. */
    val prepared: Boolean,
    /** Durée de chaque étape du lancement, en ms ; celles d'avant le toucher sont marquées dans [beforeTap]. */
    val steps: List<Pair<LaunchStep, Long>>,
    val beforeTap: Set<LaunchStep>,
    /** Du toucher au signal « je t'écoute » (`session.started`) ; `null` si la session n'a pas démarré. */
    val listenMs: Long?,
    val reflections: List<ReflectionTiming>,
    val endReason: String,
    val seconds: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("a", at.toString())
        .put("prepare", prepared)
        .put("etapes", JSONArray().apply { steps.forEach { (s, ms) -> put(JSONObject().put("etape", s.name).put("ms", ms).put("avant", s in beforeTap)) } })
        .put("ecoute_ms", listenMs ?: JSONObject.NULL)
        .put("reflexions", JSONArray().apply { reflections.forEach { put(JSONObject().put("reglage", it.reflection.code).put("ms", it.ms).put("ok", it.ok)) } })
        .put("fin", endReason)
        .put("secondes", seconds)

    companion object {
        fun fromJson(o: JSONObject): CallMetrics? = runCatching {
            val stepsJson = o.getJSONArray("etapes")
            val steps = mutableListOf<Pair<LaunchStep, Long>>()
            val before = mutableSetOf<LaunchStep>()
            for (i in 0 until stepsJson.length()) {
                val s = stepsJson.getJSONObject(i)
                val step = LaunchStep.entries.firstOrNull { it.name == s.getString("etape") } ?: continue
                steps += step to s.getLong("ms")
                if (s.optBoolean("avant")) before += step
            }
            val refl = o.getJSONArray("reflexions")
            CallMetrics(
                at = Instant.parse(o.getString("a")),
                prepared = o.getBoolean("prepare"),
                steps = steps,
                beforeTap = before,
                listenMs = if (o.isNull("ecoute_ms")) null else o.getLong("ecoute_ms"),
                reflections = (0 until refl.length()).map { i ->
                    val r = refl.getJSONObject(i)
                    ReflectionTiming(CallReflection.fromCode(r.getString("reglage")), r.getLong("ms"), r.getBoolean("ok"))
                },
                endReason = o.getString("fin"),
                seconds = o.getLong("secondes"),
            )
        }.getOrNull()
    }
}

/** Les mesures des [KEEP] derniers appels, dans un fichier privé de l'app (hors sauvegarde, comme la transcription). */
class CallMetricsStore(private val file: File) {
    fun all(): List<CallMetrics> = runCatching {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { CallMetrics.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun add(metrics: CallMetrics) {
        val kept = (all() + metrics).takeLast(KEEP)
        file.parentFile?.mkdirs()
        file.writeText(JSONArray().apply { kept.forEach { put(it.toJson()) } }.toString())
    }

    companion object {
        const val FILE_NAME = "mesures.json"
        const val KEEP = 20

        /** Texte lisible des mesures, pour « Copier les mesures » (aucun texte d'appel). */
        fun text(all: List<CallMetrics>): String = buildString {
            append("Mesures des appels Trackly (").append(all.size).append(")\n")
            for (m in all.asReversed()) {
                append('\n').append(m.at).append(" · ").append(if (m.prepared) "préparé" else "non préparé")
                append(" · écoute ").append(m.listenMs?.let { "$it ms" } ?: "—")
                append(" · ").append(m.seconds).append(" s · fin ").append(m.endReason).append('\n')
                append("  ").append(m.steps.joinToString(" · ") { (s, ms) -> "${s.label} $ms ms" + if (s in m.beforeTap) " (avant)" else "" }).append('\n')
                if (m.reflections.isNotEmpty()) {
                    append("  réflexions : ").append(m.reflections.joinToString(" · ") { "${it.reflection.label} ${it.ms} ms" + if (it.ok) "" else " (échec)" }).append('\n')
                }
            }
        }
    }
}
