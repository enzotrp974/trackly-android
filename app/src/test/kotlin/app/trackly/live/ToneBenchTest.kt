package app.trackly.live

import app.trackly.assistant.prompt.ProteinSection
import app.trackly.assistant.prompt.ToneSection
import app.trackly.testing.ScriptedModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Banc simulé du ton : les scénarios T01 à T05 du banc réel ([AssistantEvalTest]), joués par le même code
 * (jeu d'essai, outils, moteur, attentes) avec des réponses de modèle écrites à la main. Aucun réseau, aucun coût.
 *
 * Pour chaque scénario :
 * - la requête porte ce qu'il faut au modèle pour bien faire (consignes du ton, ligne « Protéines » du contexte) ;
 * - une bonne réponse passe toutes les attentes du scénario ;
 * - une réponse fautive (question en trop, jugement, minimum pris pour l'objectif…) est refusée : les attentes mordent.
 * Le réglage du vrai modèle se fait ensuite sur téléphone, et avec le banc réel (`TRACKLY_EVAL_ONLY=T01,…`).
 */
class ToneBenchTest {
    private val bench = AssistantEvalTest()

    private fun scenario(id: String) = AssistantEvalTest.SCENARIOS.single { it.id == id }

    private fun play(id: String, vararg steps: (ScriptedModel.Request) -> kotlinx.serialization.json.JsonObject): AssistantEvalTest.Check =
        runBlocking { bench.play(scenario(id), ScriptedModel(*steps)) }

    private fun assertPasses(check: AssistantEvalTest.Check) =
        assertTrue("${check.scenario.id} : ${check.failures} ; réponse « ${check.reply} »", check.failures.isEmpty())

    private fun assertFails(check: AssistantEvalTest.Check, what: String) =
        assertTrue("${check.scenario.id} devait refuser $what ; réponse « ${check.reply} »", check.failures.any { it.contains(what) })

    private val pasta =
        """{"items":[{"product":null,"dish":null,"food":{"label":"Pâtes au thon","quantity":350,"unit":"g","kcal":620,"protein_g":38,"carbs_g":80,"fat_g":14,"fiber_g":null,""" +
            """"assumptions":["assiette habituelle"],"confidence":"medium"},"recipe_id":null,"moment":"midi","day":null}],"question":null}"""

    private val chocolate =
        """{"items":[{"product":null,"dish":null,"food":{"label":"Tablette de chocolat au lait","quantity":100,"unit":"g","kcal":535,"protein_g":7,"carbs_g":57,"fat_g":30,"fiber_g":null,""" +
            """"assumptions":["tablette de 100 g"],"confidence":"medium"},"recipe_id":null,"moment":"soir","day":null}],"question":null}"""

    private val bowl = """{"title":"Bowl riz poulet","moment":"midi","items":[{"product":null,"dish":{"recipe_id":"${AssistantEvalTest.BOWL}","named_version":false,"portions":1},"food":null}]}"""

    /** Les consignes du ton et des protéines partent à chaque message. */
    private fun ScriptedModel.Request.hasToneRules() {
        assertTrue(instructions.contains(ToneSection.body.lines().first().trim()))
        assertTrue(instructions.contains(ProteinSection.body.lines().first().trim()))
    }

    @Test
    fun `T01 repas simple - note, sans question ni commentaire`() {
        val good = play(
            "T01",
            { r ->
                r.hasToneRules()
                assertTrue(r.context, r.context.contains("Protéines : 0 g mangés ; il manque 112 g pour le minimum (112 g, plancher) et 140 g pour l'idéal (140 g, objectif)."))
                ScriptedModel.toolCall("record_consumptions", pasta)
            },
            { ScriptedModel.text("C'est noté 🍝 Il te reste 1 980 kcal aujourd'hui.") },
        )
        assertPasses(good)

        val question = play("T01", { ScriptedModel.toolCall("record_consumptions", pasta) }, { ScriptedModel.text("C'est noté ! Tu avais mis du fromage ?") })
        assertFails(question, "question inattendue")
    }

    @Test
    fun `T02 quatre idees - une liste, ou une seule question utile`() {
        val list = "Voilà 4 idées pour ce midi 😋\n- Bowl riz poulet\n- Salade thon œuf\n- Pâtes thon sauce tomate\n- Wrap poulet crudités\n\nMoi je prendrais le bowl. Dis-moi laquelle te tente pour le détail."
        assertPasses(play("T02", { r -> r.hasToneRules(); ScriptedModel.toolCall("get_meal_ideas", """{"moment":"midi"}""") }, { ScriptedModel.text(list) }))
        assertPasses(play("T02", { ScriptedModel.text("Avec plaisir ! Plutôt salé, sucré, ou peu importe ? 🙂") }))

        val grilling = play("T02", { ScriptedModel.text("Tu as faim comment ? Salé ou sucré ? Chaud ou froid ? Combien de temps ?") })
        assertFails(grilling, "trop de questions")
        val two = play("T02", { ScriptedModel.text("Plutôt salé ou sucré ? Et chaud ou froid ?") })
        assertFails(two, "trop de questions")
        val floor = play("T02", { ScriptedModel.text("Tu veux viser ton minimum ou ton idéal de protéines ?") })
        assertFails(floor, "question minimum ou idéal hors du dernier repas")
    }

    @Test
    fun `T03 dernier repas loin du minimum - demande minimum ou ideal avant les idees`() {
        val ask = "Tu préfères du salé, du sucré, ou peu importe ? Et il te reste un repas avec pas mal de protéines à rattraper : on vise ton minimum ou ton idéal ? 💪"
        val good = play(
            "T03",
            { r ->
                r.hasToneRules()
                // L'app donne l'écart, calculé : le modèle n'a rien à recalculer pour juger qu'il manque beaucoup.
                assertTrue(r.context, r.context.contains("Protéines : 45 g mangés ; il manque 67 g pour le minimum (112 g, plancher) et 95 g pour l'idéal (140 g, objectif)."))
                assertTrue(r.instructions.contains("demande-lui d'abord s'il veut viser son minimum ou son idéal"))
                ScriptedModel.text(ask)
            },
        )
        assertPasses(good)

        val noQuestion = play("T03", { ScriptedModel.text("Pour ce soir :\n- Omelette 4 œufs\n- Skyr et whey\n- Bowl riz poulet") })
        assertFails(noQuestion, "question minimum ou idéal attendue")
        val ideaFirst = play("T03", { ScriptedModel.text("Je te propose une omelette de 4 œufs. On vise ton minimum ou ton idéal ?") })
        assertFails(ideaFirst, "sans idée citée")
    }

    @Test
    fun `T04 beaucoup de marge - une idee, sans question du minimum`() {
        val good = play(
            "T04",
            { r -> r.hasToneRules(); ScriptedModel.toolCall("get_meal_ideas", """{"moment":"midi"}""") },
            { ScriptedModel.toolCall("propose_meal", bowl, id = "2") },
            { ScriptedModel.text("Je te propose ton bowl riz poulet 🍗") },
        )
        assertPasses(good)
        assertTrue(good.tools.contains("propose_meal"))

        val floorAsGoal = play("T04", { ScriptedModel.text("Je te propose ton bowl : de quoi t'approcher de ton minimum de protéines.") })
        assertFails(floorAsGoal, "le minimum n'est pas l'objectif")
    }

    @Test
    fun `T05 depassement - note, aucun commentaire`() {
        val good = play(
            "T05",
            { r -> r.hasToneRules(); ScriptedModel.toolCall("record_consumptions", chocolate) },
            { r ->
                // La journée dépasse l'objectif : le reste négatif est dans le résultat, la consigne dit de ne pas le citer.
                assertTrue(r.instructions.contains("Si le reste est négatif, ne le donne pas et ne commente pas : confirme seulement."))
                ScriptedModel.text("C'est noté 🍫")
            },
        )
        assertPasses(good)

        val judged = play("T05", { ScriptedModel.toolCall("record_consumptions", chocolate) }, { ScriptedModel.text("Oups, tu as dépassé ton objectif de 355 kcal 😬") })
        assertFails(judged, "jugement ou commentaire spontané")
        val negative = play("T05", { ScriptedModel.toolCall("record_consumptions", chocolate) }, { ScriptedModel.text("C'est noté, il te reste -355 kcal.") })
        assertFails(negative, "objectif ou reste négatif cité")
        val typographic = play("T05", { ScriptedModel.toolCall("record_consumptions", chocolate) }, { ScriptedModel.text("Noté, 355 kcal au-dessus pour aujourd'hui.") })
        assertFails(typographic, "objectif ou reste négatif cité")
        // Un mot ordinaire n'est pas un jugement : « trop bon », « merci beaucoup ».
        assertPasses(play("T05", { ScriptedModel.toolCall("record_consumptions", chocolate) }, { ScriptedModel.text("Noté, trop bon le chocolat, merci beaucoup 🍫") }))
    }

    @Test
    fun `les cinq scenarios du ton sont dans le banc reel`() {
        val ids = AssistantEvalTest.SCENARIOS.filter { it.category == "ton" }.map { it.id }
        assertTrue(ids.toString(), ids.containsAll(listOf("T01", "T02", "T03", "T04", "T05")))
        assertFalse(ToneSection.body.contains("Pas d'emoji"))
    }
}
