package app.trackly.keyscan

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Garde-fou : aucun code de l'app n'écrit dans le journal Android ni sur la console. La clé OpenAI
 * (saisie, QR code, requêtes) ne peut donc pas y passer, même par erreur ou par une exception affichée.
 */
class NoLogsInSourcesTest {
    private val forbidden = Regex("""\bLog\.[dviwe]\(|\bprintln\(|\bprint\(|printStackTrace\(|android\.util\.Log\b|Timber\.""")

    private fun offenders(root: File, allowed: Set<String> = emptySet()) =
        root.walkTopDown().filter { it.extension == "kt" && it.name !in allowed }.flatMap { file ->
            file.readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }.map { "${file.name}:${it.index + 1}" }
        }.toList()

    @Test
    fun `aucun Log, println ou printStackTrace dans le code de l app`() {
        val root = File("src/main/kotlin")
        assertTrue("dossier des sources introuvable : ${root.absolutePath}", root.isDirectory)
        val found = offenders(root) + offenders(File("src/release/kotlin"))
        assertTrue("journaux trouvés : $found", found.isEmpty())
    }

    /**
     * Seule exception : la mesure de latence de l'APK **debug**. Elle n'écrit qu'une [LatencyLine] formatée :
     * des noms d'étapes et des durées, jamais de texte, d'adresse, d'en-tête ni de clé.
     */
    @Test
    fun `en debug, seul le journal de latence ecrit, et seulement des durees`() {
        val debug = File("src/debug/kotlin")
        assertTrue("journaux trouvés : ${offenders(debug, setOf("BuildLatencyLog.kt"))}", offenders(debug, setOf("BuildLatencyLog.kt")).isEmpty())
        val latency = debug.walkTopDown().single { it.name == "BuildLatencyLog.kt" }.readLines().filter { forbidden.containsMatchIn(it) && !it.trim().startsWith("import") }
        assertTrue("une seule écriture attendue : $latency", latency.size == 1 && latency.single().contains("Log.d(TAG, line.format())"))
    }
}
