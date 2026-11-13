package app.trackly.assistant

import app.trackly.assistant.openai.ResponsesRequest
import app.trackly.assistant.prompt.EstimationSection
import app.trackly.assistant.prompt.SystemPrompt
import app.trackly.assistant.prompt.WebSearchSection
import app.trackly.assistant.prompt.review
import app.trackly.assistant.turn.AssistantConfig
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Règles ajoutées après le banc d'évaluation réel (`live/AssistantEvalTest`, rapport EVAL-ASSISTANT) :
 * elles corrigent des échecs systématiques, ce test les protège.
 */
class PromptRulesTest {
    private val instructions = SystemPrompt.default().render()

    @Test
    fun `portion habituelle d un aliment courant - estimee et affichee, sans question (D13, quatre configurations)`() {
        val body = EstimationSection.body
        assertTrue(body.contains("Portion ou variété non précisée d'un aliment courant"))
        assertTrue(body.contains("ne pose AUCUNE question (`question` vaut null)"))
        // Les cas où une question reste utile (un détail qui change fortement le résultat) sont nommés.
        assertTrue(body.contains("cru ou cuit"))
        assertTrue(body.contains("quantité qui paraît absurde"))
        assertTrue(instructions.contains(body.lines().last().trim()))
    }

    @Test
    fun `recherche web - pas pour un ingredient generique, et toujours avant d ecrire (D04 et D07 avec Terra)`() {
        val body = WebSearchSection.body
        assertTrue(body.contains("ingrédient générique aux valeurs bien connues"))
        assertTrue(body.contains("Une portion à supposer n'est pas une imprécision à chercher"))
        assertTrue(body.contains("fais-le AVANT d'appeler record_consumptions ou correct_consumption"))
        // La recherche reste obligatoire pour un restaurant, une enseigne ou un produit de marque inconnu.
        assertTrue(body.contains("restaurant, d'un fast-food ou d'une enseigne nommés"))
        assertTrue(body.contains("produit de marque absent de la mémoire"))
    }

    @Test
    fun `retention du cache - 12 h pour l assistant, absente d une requete brute`() {
        assertEquals("12h", AssistantConfig().promptCacheTtl)
        val plain = ResponsesRequest(instructions = "x", input = emptyList()).toJson()
        assertFalse(plain.containsKey("prompt_cache_options"))
        val twelve = ResponsesRequest(instructions = "x", input = emptyList(), promptCacheTtl = "12h").toJson()
        assertEquals("12h", twelve["prompt_cache_options"]!!.jsonObject["ttl"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reponses libres - le Markdown est permis, au fil du jour comme au bilan`() {
        for (prompt in listOf(instructions, SystemPrompt.review().render())) {
            assertTrue(prompt.contains("le chat affiche le Markdown"))
            assertTrue(prompt.contains("tableau Markdown de 5 colonnes au plus"))
            assertFalse(prompt.lowercase().contains("pas de markdown"))
        }
    }

    @Test
    fun `ton d ami - tutoiement, quelques emojis, jamais froid`() {
        assertTrue(instructions.contains("Tu es son ami et son assistant kcal : tutoie-le"))
        assertTrue(instructions.contains("quelques émojis"))
        assertFalse(instructions.contains("Pas d'emoji"))
        assertTrue(instructions.contains("Jamais froid ni professionnel"))
    }

    @Test
    fun `questions - une seule utile pour des idees, aucune pour un repas declare`() {
        assertTrue(instructions.contains("au plus UNE question courte et utile"))
        assertTrue(instructions.contains("Déclarer un repas reste simple et rapide"))
        assertTrue(instructions.contains("au plus une fois dans la journée"))
    }

    @Test
    fun `depassement - jamais commente spontanement, ni reste negatif`() {
        assertTrue(instructions.contains("Aucun commentaire spontané sur un dépassement"))
        assertTrue(instructions.contains("Si le reste est négatif, ne le donne pas et ne commente pas"))
    }

    @Test
    fun `proteines - minimum plancher, ideal recommande, sans chipoter`() {
        assertTrue(instructions.contains("le minimum n'est qu'un plancher, jamais l'objectif. Sur la journée, vise le minimum coûte que coûte et recommande l'idéal"))
        assertTrue(instructions.contains("demande-lui d'abord s'il veut viser son minimum ou son idéal"))
        assertTrue(instructions.contains("pas « 35 g de whey au lieu de 30 g »"))
        assertTrue(instructions.contains("ne pose pas la question du minimum ou de l'idéal"))
        // La règle des protéines vient après les conseils de repas, qu'elle complète sans les réécrire.
        val ids = SystemPrompt.default().sections.map { it.id }
        assertTrue(ids.indexOf("protein") == ids.indexOf("provisions") + 1)
    }

    @Test
    fun `fil du jour - l assistant sait qu il repart de zero a 5 h`() {
        assertTrue(instructions.contains("Le chat repart de zéro chaque jour à 5 h"))
        assertTrue(instructions.contains("« comme tout à l'heure »"))
    }

    @Test
    fun `memoire - jamais de note de mise en forme`() {
        assertTrue(instructions.contains("ne retiens jamais une note sur la mise en forme"))
        assertTrue(app.trackly.assistant.tools.ManageMemoryTool.description.contains("la mise en forme des réponses (fixée par l'application)"))
    }
}
