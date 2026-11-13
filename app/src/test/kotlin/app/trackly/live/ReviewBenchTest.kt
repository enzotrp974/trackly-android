package app.trackly.live

import app.trackly.assistant.prompt.ReviewReportSection
import app.trackly.assistant.prompt.ReviewRepliesSection
import app.trackly.live.ReviewGrade.Kind
import app.trackly.testing.ScriptedModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Banc simulé du prompt du bilan. Voir [ReviewBench] pour les scénarios et [ReviewGrade] pour la grille.
 *
 * Pour chaque scénario :
 * - le modèle reçoit ce qu'il faut pour bien faire (consignes, prévision, conseils, ce que la page affiche) ;
 * - une bonne réponse, écrite à la main, passe la grille automatique et finit telle quelle sur la page ;
 * - des réponses fautives (verdict contraire, chiffre inventé, conseil interdit, ton froid ou qui juge, trop long) sont refusées.
 * Aucune réponse réelle de modèle : la comparaison d'Opus et de Sol se fait hors dépôt, avec les deux modes à variable plus bas.
 */
class ReviewBenchTest {
    private val exportDir: String? = System.getenv("TRACKLY_REVIEW_EXPORT")?.takeIf { it.isNotBlank() }
    private val gradeDir: String? = System.getenv("TRACKLY_REVIEW_GRADE")?.takeIf { it.isNotBlank() }

    private fun play(id: String, vararg steps: (ScriptedModel.Request) -> JsonObject) =
        runBlocking { ReviewBench.play(ReviewBench.scenario(id), ScriptedModel(*steps)) }

    private fun report(analysis: String): (ScriptedModel.Request) -> JsonObject = {
        ScriptedModel.toolCall("produce_weekly_report", Json.encodeToString(JsonObject.serializer(), buildJsonObject { put("analysis", analysis) }))
    }

    private val done: (ScriptedModel.Request) -> JsonObject = { ScriptedModel.text("Voilà ton bilan, bonne lecture 🙂") }

    /** Joue [text] comme mot (rapport) ou comme réponse, et renvoie les échecs de la grille. */
    private fun failures(id: String, text: String): List<ReviewGrade.Failure> {
        val s = ReviewBench.scenario(id)
        val played = if (s.expect == ReviewBench.Expect.Report) play(id, report(text), done) else play(id, { ScriptedModel.text(text) })
        if (s.expect == ReviewBench.Expect.Report) assertEquals("le mot finit tel quel sur la page", text, played.page!!.note)
        val kind = if (s.expect == ReviewBench.Expect.Report) Kind.Note else Kind.Reply
        return ReviewGrade.grade(text, kind, played.first.context + "\n" + s.message, played.page ?: played.appPage) +
            ReviewGrade.scenario(text, s.mention.takeIf { s.followUpQuestions == null }, s.avoid)
    }

    /** La phrase qui suit le rapport, après [note] : grille des réponses et questions permises par le scénario. */
    private fun followUp(id: String, note: String, reply: String): List<ReviewGrade.Failure> {
        val s = ReviewBench.scenario(id)
        val played = play(id, report(note), { ScriptedModel.text(reply) })
        assertEquals(reply, played.reply)
        return ReviewGrade.grade(reply, Kind.Reply, played.first.context, played.page!!) + ReviewGrade.scenario(reply, s.mention, null, s.followUpQuestions)
    }

    private fun assertPasses(id: String, text: String) {
        val f = failures(id, text)
        assertTrue("$id devait passer : $f", f.isEmpty())
    }

    private fun assertRefused(id: String, criterion: String, text: String) {
        val f = failures(id, text)
        assertTrue("$id devait refuser (« $criterion ») : « $text » ; échecs : $f", f.any { it.criterion == criterion })
    }

    /** Verdict de l'en-tête attendu, et ligne de conseil du contexte, pour chaque scénario. */
    private val expected = mapOf(
        "B01" to ("En avance." to "Aucun conseil : la prévision"),
        "B02" to ("Dans les temps." to "Aucun conseil : la prévision"),
        "B03" to ("En retard." to "Objectif temporaire possible"),
        "B04" to ("Dans les temps." to "Aucun conseil : la prévision"),
        "B05" to ("Estimation." to "Aucun conseil : pas de date ferme"),
        "B06" to ("Dans les temps." to "Aucun conseil : la prévision"),
        "B07" to ("Plutôt en retard." to "Pas de dépassement à rattraper"),
        "B08" to ("Poids stable." to "Pas de dépassement à rattraper"),
        "B09" to ("Plutôt en retard." to "Pas de dépassement à rattraper"),
        "B10" to ("Dans les temps." to "Aucun conseil : la prévision"),
        "B11" to ("Dans les temps." to "Aucun conseil : la prévision"),
        "B12" to ("Dans les temps." to "Aucun conseil : la prévision"),
    )

    @Test
    fun `chaque scenario donne au modele la prevision, les conseils et ce que la page affiche`() {
        for (s in ReviewBench.SCENARIOS) {
            val played = play(s.id, { ScriptedModel.text("(rien)") })
            val r = played.first
            val (verdict, advice) = expected.getValue(s.id)
            assertEquals(s.id, verdict, played.appPage.headline.verdict)
            assertTrue("${s.id} : ${r.context}", r.context.contains("Ce que la page du bilan affiche"))
            assertTrue("${s.id} : ${r.context}", r.context.contains("- En-tête : « $verdict »"))
            assertTrue("${s.id} : ${r.context}", r.context.contains(advice))
            assertTrue(s.id, r.context.contains("Une perte qui ralentit à mesure que le poids baisse est NORMALE"))
            assertTrue(s.id, r.instructions.contains(ReviewReportSection.body.lines().first().trim()))
            assertTrue(s.id, r.instructions.contains(ReviewRepliesSection.body.lines().first().trim()))
        }
        // La marge vient de la page, en jours et dans le bon sens : le modèle n'a rien à calculer.
        assertTrue(play("B01", { ScriptedModel.text("-") }).first.context.contains("- Marge affichée : 73 jours d'avance sur la date visée."))
        // Sous « Dans les temps », la marge ne dit pas « d'avance » : le verdict ne le promet pas.
        val b02 = play("B02", { ScriptedModel.text("-") }).first.context
        assertTrue(b02, b02.contains("- Arrivée prévue 3 jours avant la date visée, mais le verdict reste « Dans les temps » : ne dis pas « en avance »."))
        assertTrue(play("B03", { ScriptedModel.text("-") }).first.context.contains("Pas « Ou bouger plus : environ +"))
        assertTrue(play("B03", { ScriptedModel.text("-") }).first.context.contains("(une phrase, sans bouton)"))
        assertTrue(play("B03", { ScriptedModel.text("-") }).first.context.contains("- Marge affichée : 210 jours de retard sur la date visée."))
        assertFalse(play("B05", { ScriptedModel.text("-") }).first.context.contains("Marge affichée"))
        // Le ralentissement observé est un point « à savoir », jamais une alerte ; les protéines coincent sur le minimum.
        assertTrue(play("B04", { ScriptedModel.text("-") }).first.context.contains("  - Rythme (à savoir, ce n'est pas une alerte) : contre 0,50 en septembre, 0,38 kg/sem."))
        assertTrue(play("B02", { ScriptedModel.text("-") }).first.context.contains("  - Protéines (à regarder) : minimum tenu 4 jours sur 7"))
    }

    /** Une bonne réponse par scénario, écrite à la main. */
    private val good = mapOf(
        "B01" to "Franchement, bravo 💪 Tu es en avance, avec 73 jours de marge sur ta date. Tes pas au-dessus de tes habitudes y sont pour beaucoup, et tout le reste est au vert : continue comme ça.",
        "B02" to "Tranquille, tu es dans les temps 👌 Calories pile sur l'objectif et séances toutes faites. Un seul point à regarder : les protéines, avec ton minimum tenu 4 jours sur 7.",
        "B03" to "Pas de panique : tu es en retard sur ta date, mais tes protéines tiennent tous les jours. Le point à regarder, ce sont les calories, 400 kcal au-dessus sur la période ; si tu veux les rattraper, une option est juste en dessous.",
        "B04" to "Ça avance bien : tu es dans les temps 👌 Tu perds moins vite qu'en septembre, et c'est normal : en pesant moins, tu dépenses moins, c'est déjà compté dans ta date. Tout le reste est au vert, rien à changer.",
        "B05" to "Bonne base : la date se précisera avec quelques semaines de pesées de plus. Sur les jours notés, tes calories sont pile sur l'objectif, c'est la base 🙂",
        "B06" to "Je comprends 🙂 C'est normal de perdre moins vite qu'en septembre : en pesant moins, tu dépenses moins, et c'est déjà compté dans ta date. Tu es dans les temps, vers le 24 février : pas besoin de descendre à 2 100 kcal ni d'ajouter du cardio.",
        "B07" to "Pas de panique, tu es un peu en retard sur ta date, mais ça reste jouable. Tes repas, tes pas et tes séances sont au rendez-vous : le retard vient du rythme, pas de toi. Si tu préfères, une nouvelle date est proposée juste en dessous 🙂",
        "B08" to "Ton poids ne bouge presque plus ces dernières semaines : à ce rythme, pas de date d'arrivée pour l'instant. Tes repas sont pile sur l'objectif et tes séances sont faites. Si tu veux en parler, pose-moi ta question.",
        "B10" to "Bonne période, tu es dans les temps 👌 La pesée de mercredi est plus haute, mais c'est le lendemain de ton gros repas de mardi : surtout de l'eau et du repas, pas une vraie reprise. Le reste de la période est pile sur l'objectif.",
        "B11" to "Tranquille, tu es dans les temps 👌 Calories pile sur l'objectif et protéines tenues tous les jours. Le seul point à regarder, ce sont les pas, tirés vers le bas par jeudi.",
        "B12" to "Pas grave du tout 🙂 Ta pesée de mercredi suit ton gros repas de mardi soir : c'est surtout de l'eau et du repas, pas une vraie reprise. Tu restes dans les temps, continue comme d'habitude.",
        "B09" to "Je comprends 🙂 Tu es plutôt en retard, mais ça vient du rythme, pas de tes repas, qui sont pile sur l'objectif. Pas besoin de descendre à 2 000 kcal ni de faire du cardio : si tu veux, on peut viser le 21 mars à la place.",
    )

    @Test
    fun `une bonne reponse passe la grille dans chaque scenario`() {
        assertEquals(ReviewBench.SCENARIOS.map { it.id }.toSet(), good.keys)
        good.forEach { (id, text) -> assertPasses(id, text) }
    }

    @Test
    fun `une pesee haute apres un gros repas - le lien est fait, sans parler de reprise`() {
        // Le contexte met les calories de chaque jour à côté des pesées : le gros repas du mardi, puis la pesée haute du mercredi.
        val context = play("B10", { ScriptedModel.text("-") }).first.context
        assertTrue(context, context.contains("- mardi 3 novembre : 3\u202F600 kcal (objectif 2\u202F300, écart +1\u202F300) ; 18\u202F000 pas"))
        assertTrue(context, Regex("- mercredi 4 novembre : pesée du matin 6\\d,\\d kg ; 2\u202F290 kcal \\(objectif 2\u202F300, écart −10\\) ; 20\u202F100 pas").containsMatchIn(context))
        assertTrue(context, context.contains("- vendredi 30 octobre (veille de la période) : 2\u202F300 kcal"))
        assertRefused("B10", ReviewGrade.CONSISTENT, "Ça roule, tu es dans les temps, mais tu as repris un peu de poids mercredi.")
        assertRefused("B10", ReviewGrade.USEFUL, "Ça roule, tu es dans les temps 👌 Le reste de la période est pile sur l'objectif.")
        // « Ta pesée remonte un peu après le repas de mardi » n'est pas une reprise ; « beaucoup » ne suffit pas comme lien.
        assertPasses("B10", "Ça roule, tu es dans les temps. Ta pesée remonte un peu le lendemain du gros repas de mardi : c'est de l'eau, rien de plus.")
        assertRefused("B12", ReviewGrade.USEFUL, "Tu as beaucoup mangé, oui, mais tu restes dans les temps.")
        assertRefused("B04", ReviewGrade.ADVICE, "Ça avance bien. Ce que je ferais à ta place : marcher un peu plus cette semaine.")
        assertRefused("B07", ReviewGrade.ADVICE, "Pas de panique, ça reste jouable. Ce que je ferais à ta place : dépenser un peu plus, pour rattraper.")
        assertRefused("B12", ReviewGrade.CONSISTENT, "Oui, tu as pris du poids mercredi : fais attention cette semaine.")
        assertPasses("B12", "Pas de panique 🙂 Ta pesée de mercredi suit ton repas de mardi soir : surtout de l'eau et du repas, plutôt qu'une vraie reprise.")
    }

    @Test
    fun `une petite imprecision - une ou deux questions apres le rapport, pas d interrogatoire`() {
        val note = good.getValue("B11")
        assertTrue(followUp("B11", note, "Voilà ton bilan 🙂 Petite question : jeudi, seulement 2 100 pas, tu avais oublié ta montre ? Si oui, donne-moi le bon chiffre et je refais le bilan.").isEmpty())
        val none = followUp("B11", note, "Voilà ton bilan, bonne lecture 🙂")
        assertTrue("$none", none.any { it.criterion == ReviewGrade.USEFUL })
        val grilling = followUp("B11", note, "Voilà ton bilan. Jeudi, tu avais ta montre ? Tu as marché ? Tu étais malade ? Tu veux corriger ?")
        assertTrue("$grilling", grilling.any { it.criterion == ReviewGrade.USEFUL })
        // Sans imprécision, aucune question : B01 (tout est là) et B05 (il a dit qu'il n'a pas ses pas).
        assertTrue(followUp("B01", good.getValue("B01"), "Voilà ton bilan, bonne lecture 🙂").isEmpty())
        assertTrue(followUp("B05", good.getValue("B05"), "Voilà ton bilan 🙂").isEmpty())
        val asked = followUp("B05", good.getValue("B05"), "Voilà ton bilan. Tu peux me donner tes pas de la semaine ?")
        assertTrue("$asked", asked.any { it.criterion == ReviewGrade.USEFUL })
    }

    @Test
    fun `une suggestion de methode est libre, plus de depense seulement avec un conseil de l app`() {
        assertPasses("B05", "Bonne base : la date se précisera avec quelques semaines de pesées de plus. Ce que je ferais à ta place : me peser deux ou trois matins par semaine.")
        assertRefused("B04", ReviewGrade.ADVICE, "Ça avance bien, tu es dans les temps. Ce que je ferais à ta place : un peu plus de dépense cette semaine.")
        assertPasses("B03", "Pas de panique : tu es en retard sur ta date, mais tes protéines tiennent tous les jours. Ce que je ferais à ta place : un peu plus de dépense, avec l'option des pas juste en dessous.")
    }

    @Test
    fun `les exemples du prompt ne recopient aucune bonne reponse du banc`() {
        val prompt = ReviewGrade.normalize(ReviewReportSection.body + ReviewRepliesSection.body)
        for ((id, text) in good) {
            val start = ReviewGrade.normalize(text).split(' ').take(6).joinToString(" ")
            assertFalse("$id : « $start » est dans le prompt", prompt.contains(start))
        }
    }

    @Test
    fun `la limite de l outil suit celle de la grille`() {
        assertEquals(ReviewGrade.NOTE_MAX_CHARS, app.trackly.assistant.tools.ProduceReportTool.NOTE_CHARS)
        assertTrue(ReviewReportSection.body.contains("${app.trackly.assistant.tools.ProduceReportTool.NOTE_CHARS} caractères au plus"))
        // Un rapport de 1 000 caractères est refusé par l'outil : le modèle doit le réécrire.
        val long = "Tu es en avance. " + "Tout est au vert sur la période, continue comme ça. ".repeat(20)
        val played = play("B01", report(long), report(good.getValue("B01")), done)
        assertEquals(good.getValue("B01"), played.page!!.note)
    }

    @Test
    fun `la grille refuse un verdict contraire a la page`() {
        assertRefused("B01", ReviewGrade.VERDICT, "Tu es un peu en retard, mais tout est au vert.")
        assertRefused("B02", ReviewGrade.VERDICT, "Tu es en avance sur ta date, bravo.")
        assertRefused("B03", ReviewGrade.VERDICT, "Tu es dans les temps, continue comme ça.")
        assertRefused("B05", ReviewGrade.VERDICT, "Tu es en avance, tu arriveras vers le 10 février.")
        assertRefused("B07", ReviewGrade.VERDICT, "Tu es dans les temps, rien à changer.")
    }

    @Test
    fun `la grille refuse un chiffre ou une date inventes`() {
        assertRefused("B01", ReviewGrade.NUMBERS, "Bravo, tu as 75 jours d'avance sur ta date.")
        assertRefused("B03", ReviewGrade.NUMBERS, "Si tu veux, passe à 2 150 kcal pendant 5 jours, une option est juste en dessous.")
        assertRefused("B04", ReviewGrade.NUMBERS, "Tu es dans les temps, vers le 2 mars : rien à changer.")
    }

    @Test
    fun `la grille refuse un conseil de baisse ou de sport sur un ralentissement normal`() {
        assertRefused("B04", ReviewGrade.ADVICE, "Tu es dans les temps, mais ta perte stagne un peu : baisse légèrement tes calories cette semaine.")
        assertRefused("B04", ReviewGrade.ADVICE, "Tu es dans les temps. Pour garder le rythme, ajoute une séance de cardio.")
        assertRefused("B06", ReviewGrade.ADVICE, "C'est normal, mais si ça te rassure, tu peux descendre à 2 100 kcal quelques jours.")
        assertRefused("B06", ReviewGrade.ADVICE, "C'est normal, rien d'inquiétant. Si tu veux, on peut revoir ton objectif calorique.")
        assertRefused("B07", ReviewGrade.ADVICE, "Tu es un peu en retard : mange un peu moins le week-end et ça repartira.")
        assertRefused("B07", ReviewGrade.ADVICE, "Tu es un peu en retard, ça reste jouable : marche plus cette semaine.")
        assertRefused("B04", ReviewGrade.ADVICE, "Tu es dans les temps. Une séance de cardio en plus te ferait du bien.")
        assertRefused("B04", ReviewGrade.ADVICE, "Tu es dans les temps. Côté pas, tu peux ajouter un peu de marche.")
        assertRefused("B06", ReviewGrade.ADVICE, "C'est normal. Tu peux descendre à 2 100 si tu veux.")
        assertRefused("B06", ReviewGrade.ADVICE, "C'est normal, mais on peut revoir ton objectif ensemble.")
        assertRefused("B09", ReviewGrade.ADVICE, "Ok, descends à 2 000 kcal, et le cardio t'aidera.")
        // Avec un conseil de l'app, « mange moins » et le cardio ne sont toujours pas des conseils de l'app.
        assertRefused("B03", ReviewGrade.ADVICE, "Pas de panique : mange un peu moins le soir et ça repartira.")
        assertRefused("B03", ReviewGrade.ADVICE, "Pas de panique, tu es en retard : ajoute une séance de cardio.")
        // Sans date, un poids stable n'est ni « normal » ni « déjà compté ».
        assertRefused("B08", ReviewGrade.VERDICT, "Ton poids ne bouge plus, c'est normal et déjà compté dans ta date.")
        assertRefused("B08", ReviewGrade.VERDICT, "Ton poids ne bouge plus, mais tu es dans les temps.")
        assertRefused("B08", ReviewGrade.VERDICT, "On fait le point, ton poids reste stable. Tes repas sont pile sur l'objectif : rien à changer.")
    }

    @Test
    fun `la grille laisse passer une explication qui n est pas un conseil`() {
        assertPasses("B04", "Bonne nouvelle, tu es dans les temps 👌 En pesant moins, ta dépense baisse un peu tes besoins en calories, c'est déjà compté dans ta date. Ça aide beaucoup à tenir ton objectif.")
        assertPasses("B05", "Bonne base : pas encore de date ferme pour ton objectif du 1er mars. Tes calories sont pile sur l'objectif 🙂")
        // Émojis libres : trois passent, seul l'abus est refusé. 👍🏽 et 🤷‍♂️ comptent pour un émoji chacun.
        assertPasses("B01", "Franchement, bravo 💪 Tu es en avance, avec 73 jours de marge sur ta date 🎉 Tout est au vert, continue comme ça 😄")
        assertTrue(ReviewGrade.grade("Ça roule 👍🏽 tu es dans les temps.", Kind.Reply, "", play("B02", { ScriptedModel.text("-") }).appPage).none { it.detail.contains("émojis") })
        assertTrue(ReviewGrade.grade("Bof 🤷‍♂️ tu es dans les temps.", Kind.Note, "", play("B02", { ScriptedModel.text("-") }).appPage).none { it.detail.contains("émojis") })
    }

    @Test
    fun `la grille refuse un ton froid, qui juge ou qui vouvoie`() {
        assertRefused("B03", ReviewGrade.TONE, "Tu as dépassé ton objectif de 400 kcal, c'est pour ça que tu es en retard.")
        assertRefused("B03", ReviewGrade.TONE, "400 kcal au-dessus sur la période. Tu es en retard sur ta date.")
        assertRefused("B02", ReviewGrade.TONE, "Vous êtes dans les temps, un seul point à regarder : les protéines.")
        assertRefused("B01", ReviewGrade.TONE, "Bravo 💪🔥 Tu es en avance 🎉 continue 😄")
        assertRefused("B01", ReviewGrade.TONE, "Régularité de saisie : complète. Verdict : en avance.")
    }

    @Test
    fun `la grille refuse un mot trop long, une liste ou un tout va bien contraire a la page`() {
        val report = "Tu as saisi 7 jours sur 7, sans jour estimé ni partiel.\n\nTon poids de la période est de 64,6 kg en moyenne sur 7 pesées. " +
            "La tendance sur 4 semaines pesées est d'environ −0,45 kg par semaine.\n\nTes protéines sont à 138 g par jour en moyenne pour 135 g visés.\n\n" +
            "Si tu veux, garde ta pesée de chaque matin : c'est elle qui rend la prévision aussi précise.\n\nLa dépense reste une estimation, environ 3 010 kcal par jour, " +
            "fondée sur des hypothèses et pas sur une mesure. Aucune information ne manque, mais une semaine seule ne suffit pas pour conclure."
        assertRefused("B01", ReviewGrade.SHORT, report)
        assertRefused("B01", ReviewGrade.SHORT, "Tu es en avance :\n- pas au-dessus\n- calories pile")
        // Un émoji clôt la phrase : six phrases, même sans tous les points.
        assertRefused("B01", ReviewGrade.SHORT, "Bravo 💪 Tu es en avance 🎯 Tes pas sont au top. Tout est au vert. Tes séances sont faites. Continue comme ça.")
        // Les nombres en lettres sont comparés aussi : « trois jours sur sept » n'est pas sur la page de B02.
        assertRefused("B02", ReviewGrade.NUMBERS, "Ça roule, tu es dans les temps. Les protéines : sous ton minimum neuf jours sur dix.")
        assertRefused("B02", ReviewGrade.CONSISTENT, "Tu es dans les temps et tout est au vert, rien à corriger.")
        assertRefused("B04", ReviewGrade.CONSISTENT, "Tu es dans les temps, mais le rythme est à surveiller.")
    }

    @Test
    fun `le prompt du bilan porte le ton d ami et la regle du ralentissement`() {
        val replies = ReviewRepliesSection.body
        val report = ReviewReportSection.body
        assertFalse(replies.contains("Pas d'emoji"))
        assertTrue(replies.contains("Tu es son ami et son assistant kcal : tutoie-le"))
        assertTrue(replies.contains("une question ou une inquiétude n'est pas une demande"))
        assertTrue(report.contains("Émojis à ton goût"))
        assertFalse(report.contains("Au plus un émoji"))
        assertTrue(report.contains("Ce que je ferais à ta place"))
        assertTrue(app.trackly.assistant.prompt.ReviewRoleSection.body.contains("Pesées et repas, dans ton mot comme dans tes réponses"))
        assertTrue(report.contains("jamais plus optimiste ni plus inquiet"))
        assertTrue(report.contains("${ReviewGrade.NOTE_MAX_CHARS} caractères au plus"))
    }

    /**
     * Exporte chaque scénario tel que le modèle le reçoit (consignes, outils, contexte, message), pour la comparaison hors
     * dépôt entre modèles. Ne tourne qu'avec `TRACKLY_REVIEW_EXPORT=<dossier>` ; n'écrit rien dans le dépôt.
     */
    @Test
    fun `export des scenarios tels que le modele les recoit`() {
        assumeTrue("Export désactivé (TRACKLY_REVIEW_EXPORT)", exportDir != null)
        val dir = File(exportDir!!).apply { mkdirs() }
        for (s in ReviewBench.SCENARIOS) {
            val played = play(s.id, { ScriptedModel.text("(export)") })
            val body = played.first.body
            File(dir, "${s.id}.request.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), body))
            File(dir, "${s.id}.md").writeText(readable(body))
            // Après le rapport : la requête de l'étape suivante, avec un mot fixe, pour juger la phrase et les questions qui suivent.
            if (s.followUpQuestions != null) {
                val next = play(s.id, report(good.getValue(s.id)), { ScriptedModel.text("(export)") }).requests[1].body
                File(dir, "${s.id}-suite.md").writeText(readable(next))
            }
        }
        // Ce que chaque scénario vérifie : à part, pour ne pas le montrer au modèle évalué.
        File(dir, "index.md").writeText(
            ReviewBench.SCENARIOS.joinToString("\n", prefix = "| Scénario | Titre | Attendu | Ce qu'il vérifie |\n|---|---|---|---|\n") {
                "| ${it.id} | ${it.title} | ${if (it.expect == ReviewBench.Expect.Report) "rapport" else "réponse"} | ${it.focus} |"
            } + "\n",
        )
    }

    /**
     * Note avec la grille automatique les réponses d'un modèle écrites hors dépôt (`<dossier>/Bxx.json`, au format de la
     * consigne : `{"tool": …, "arguments": …}` ou `{"text": …}`), en les rejouant dans l'app : un rapport passe par le vrai
     * `produce_weekly_report`. Écrit `<dossier>/grille-auto.md`. Ne tourne qu'avec `TRACKLY_REVIEW_GRADE=<dossier>`.
     */
    @Test
    fun `grille automatique sur des reponses ecrites hors depot`() {
        assumeTrue("Notation désactivée (TRACKLY_REVIEW_GRADE)", gradeDir != null)
        val dir = File(gradeDir!!)
        val rows = ReviewBench.SCENARIOS.mapNotNull { s ->
            val file = File(dir, "${s.id}.json").takeIf { it.isFile } ?: return@mapNotNull null
            val answer = Json.parseToJsonElement(file.readText()).jsonObject
            val tool = answer["tool"]?.jsonPrimitive?.content
            val played = if (tool != null) {
                val args = Json.encodeToString(JsonObject.serializer(), answer["arguments"]!!.jsonObject)
                play(s.id, { ScriptedModel.toolCall(tool, args) }, { ScriptedModel.text("Voilà ton bilan, bonne lecture.") })
            } else {
                play(s.id, { ScriptedModel.text(answer["text"]!!.jsonPrimitive.content) })
            }
            val text = if (tool == "produce_weekly_report") answer["arguments"]!!.jsonObject["analysis"]!!.jsonPrimitive.content else answer["text"]?.jsonPrimitive?.content ?: "(outil $tool)"
            val kind = if (tool == "produce_weekly_report") ReviewGrade.Kind.Note else ReviewGrade.Kind.Reply
            val wrongShape = when {
                s.expect == ReviewBench.Expect.Report && tool != "produce_weekly_report" -> listOf(ReviewGrade.Failure("forme", "rapport attendu, reçu ${tool ?: "un texte"}"))
                s.expect == ReviewBench.Expect.Reply && tool != null -> listOf(ReviewGrade.Failure("forme", "réponse attendue, reçu l'outil $tool"))
                else -> emptyList()
            }
            val sources = played.first.context + "\n" + s.message
            val page = played.page ?: played.appPage
            val failures = wrongShape + ReviewGrade.grade(text, kind, sources, page) + ReviewGrade.scenario(text, s.mention.takeIf { s.followUpQuestions == null }, s.avoid)
            // L'étape qui suit le rapport, si le modèle l'a écrite (`Bxx-suite.json`) : grille des réponses et questions permises.
            val suite = File(dir, "${s.id}-suite.json").takeIf { it.isFile }?.let { Json.parseToJsonElement(it.readText()).jsonObject["text"]?.jsonPrimitive?.content }
            val suiteFailures = when {
                suite != null -> ReviewGrade.grade(suite, Kind.Reply, sources, page) + ReviewGrade.scenario(suite, s.mention, null, s.followUpQuestions)
                s.followUpQuestions != null -> listOf(ReviewGrade.Failure("forme", "${s.id}-suite.json manquant : l'étape après le rapport n'a pas de réponse"))
                else -> emptyList()
            }
            Triple(s, text + (suite?.let { "\n\n[après le rapport] $it" }.orEmpty()), failures + suiteFailures)
        }
        File(dir, "grille-auto.md").writeText(
            buildString {
                appendLine("# Grille automatique : ${dir.name}")
                appendLine()
                appendLine("| Scénario | Critères en échec | Caractères |")
                appendLine("|---|---|---|")
                rows.forEach { (s, text, f) -> appendLine("| ${s.id} ${s.title} | ${if (f.isEmpty()) "aucun" else f.map { it.criterion }.distinct().joinToString(", ")} | ${text.trim().length} |") }
                appendLine()
                rows.forEach { (s, text, f) ->
                    appendLine("## ${s.id} · ${s.title}")
                    appendLine()
                    appendLine("````text")
                    appendLine(text.trim())
                    appendLine("````")
                    appendLine()
                    if (f.isEmpty()) appendLine("Aucun échec automatique.") else f.forEach { appendLine("- $it") }
                    appendLine()
                }
            },
        )
    }

    /** La requête en clair : consignes, outils, puis messages, sans rien qui dise ce que le scénario vérifie. */
    private fun readable(body: JsonObject): String = buildString {
        appendLine("# Requête reçue par le modèle")
        appendLine()
        appendLine("## Instructions (message système)")
        appendLine()
        appendLine("````text")
        appendLine(body["instructions"]!!.jsonPrimitive.content)
        appendLine("````")
        appendLine()
        appendLine("## Outils disponibles")
        appendLine()
        for (tool in body["tools"]!!.jsonArray.map { it.jsonObject }) {
            val name = tool["name"]?.jsonPrimitive?.content ?: tool["type"]!!.jsonPrimitive.content
            appendLine("### $name")
            appendLine()
            appendLine("````text")
            tool["description"]?.jsonPrimitive?.content?.let { appendLine(it) }
            tool["parameters"]?.let { appendLine("Paramètres : " + Json.encodeToString(JsonObject.serializer(), it.jsonObject)) }
            appendLine("````")
            appendLine()
        }
        appendLine("## Entrée")
        for (item in body["input"]!!.jsonArray.map { it.jsonObject }) {
            val type = item["type"]?.jsonPrimitive?.content
            if (type == "function_call" || type == "function_call_output") {
                appendLine()
                appendLine(if (type == "function_call") "### Ton appel d'outil (déjà fait)" else "### Résultat de l'outil")
                appendLine()
                appendLine("````text")
                appendLine(if (type == "function_call") "${item["name"]!!.jsonPrimitive.content} ${item["arguments"]!!.jsonPrimitive.content}" else item["output"]!!.jsonPrimitive.content)
                appendLine("````")
                continue
            }
            if (item["content"] == null) continue
            val role = item["role"]?.jsonPrimitive?.content ?: type ?: "?"
            appendLine()
            appendLine("### Message « $role »")
            appendLine()
            appendLine("````text")
            val content = item["content"]!!
            val text = runCatching { content.jsonPrimitive.content }.getOrElse {
                content.jsonArray.joinToString("\n") { part -> part.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
            }
            appendLine(text)
            appendLine("````")
        }
    }
}
