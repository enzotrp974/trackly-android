package app.trackly.core.importing

import app.trackly.core.goal.GoalCoherence
import app.trackly.core.goal.ProteinMinimum
import app.trackly.core.model.Confidence
import app.trackly.core.model.Consumption
import app.trackly.core.model.DailyEstimate
import app.trackly.core.model.DayTracking
import app.trackly.core.model.EntryId
import app.trackly.core.model.EstimateKind
import app.trackly.core.model.Food
import app.trackly.core.model.FoodSource
import app.trackly.core.model.Goal
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.KcalRange
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrients
import app.trackly.core.model.Nutrition
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.Origin
import app.trackly.core.model.Preference
import app.trackly.core.model.Product
import app.trackly.core.model.ProductPortion
import app.trackly.core.model.Quantity
import app.trackly.core.model.Units
import app.trackly.core.model.Recipe
import app.trackly.core.model.RecipeIngredient
import app.trackly.core.model.RecordState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate

/**
 * Lit l'export du moteur Python (format `suivi-kcal-bundle`)
 * et le vérifie. Toute donnée invalide refuse le fichier entier, avec un message situé.
 */
object BundleReader {
    const val FORMAT = "suivi-kcal-bundle"
    val SUPPORTED_VERSIONS = setOf(1, 2)

    /** Champs de statistiques d'usage d'un produit, recalculés et non comparés à la reprise. */
    private val USAGE_FIELDS = setOf("use_count", "counted_entry_ids", "last_used_at", "last_portion")
    private val JOURNAL = Regex("""journal/(\d{4}-\d{2}-\d{2})\.json""")

    /** Placeholder de trace : la reprise fixe la vraie date d'enregistrement et l'action. */
    private val PENDING: Instant = Instant.EPOCH

    fun read(text: String): HistoryBundle {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            fail("Le fichier n'est pas un JSON lisible")
        }
        val bundle = Fields(root as? JsonObject ?: fail("Le fichier doit contenir un objet JSON"), "export")
        if (!bundle.has("format") || bundle.stringOrNull("format") != FORMAT) {
            fail("Ce fichier n'est pas un export Trackly (format « $FORMAT » attendu)")
        }
        val version = bundle.int("schema_version")
        if (version !in SUPPORTED_VERSIONS) fail("Version d'export $version non prise en charge")
        val files = bundle.obj("files")

        val ignored = mutableListOf<String>()
        val archives = mutableListOf<SourceDocument>()
        var goals = emptyList<Sourced<Goal>>()
        var products = emptyList<Sourced<Product>>()
        var recipes = emptyList<Sourced<Recipe>>()
        var preferences = emptyList<Sourced<Preference>>()
        val days = mutableListOf<BundleDay>()

        if (!files.has("profile.json")) fail("Le profil (profile.json) manque dans l'export")
        for ((name, element) in files.obj.entries.sortedBy { it.key }) {
            val document = Fields(element as? JsonObject ?: fail("$name : objet JSON attendu"), name)
            val journal = JOURNAL.matchEntire(name)
            when {
                name == "profile.json" -> {
                    goals = readGoals(document)
                    archives += source("profile", "profile", 1, element)
                }
                name == "catalogue.json" -> products = document.objects("products").map(::readProduct)
                name == "recipes.json" -> recipes = document.objects("recipes").map(::readRecipe)
                name == "preferences.json" -> preferences = document.objects("preferences").map(::readPreference)
                name == "weights.json" -> archives += source("weights", "weights", 1, element)
                journal != null -> days += readDay(journal.groupValues[1], document, element as JsonObject)
                else -> ignored += name
            }
        }

        unique(days.flatMap { d -> d.consumptions.map { it.source.key to it.source.version } }, "consommation (identifiant et version)")
        unique(days.flatMap { d -> d.estimates.map { it.source.key to it.source.version } }, "estimation journalière (identifiant et version)")
        unique(goals.map { it.source.key }, "objectif (date)")
        unique(products.map { it.source.key }, "produit")
        unique(recipes.map { it.source.key to it.source.version }, "plat (identifiant et version)")
        unique(preferences.map { it.source.key }, "préférence")

        val checks = if (bundle.has("checks")) readChecks(bundle.obj("checks")) else null
        return HistoryBundle(version, days.sortedBy { it.day }, goals, products, recipes, preferences, archives, checks, ignored)
    }

    private fun <K> unique(keys: List<K>, what: String) {
        val duplicate = keys.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicate != null) fail("Doublon dans l'export : $what ${duplicate.key}")
    }

    private fun source(kind: String, key: String, version: Int, element: JsonElement, fingerprint: JsonElement = element): SourceDocument =
        SourceDocument(kind, key, version, canonical(element), sha256(canonical(fingerprint)))

    // ───── Profil et objectifs ─────

    private fun readGoals(profile: Fields): List<Sourced<Goal>> {
        val currentWeight = profile.number("current_reference_weight_kg")
        return profile.objects("goals").map { goal ->
            val from = goal.date("effective_from")
            // Mêmes calculs, dans le même ordre, que `compute_targets` du moteur Python.
            val weight = goal.numberOrNull("reference_weight_kg") ?: currentWeight
            val kcal = goal.number("kcal")
            val protein = weight * goal.number("protein_ideal_g_per_kg")
            val fat = weight * goal.number("fat_g_per_kg")
            val carbs = maxOf(0.0, (kcal - protein * 4 - fat * 9) / 4)
            // Minimum de protéines : un plancher à côté de l'idéal, jamais la cible.
            // Comme la migration 8 → 9 : pas de minimum au-dessus de l'idéal de la règle.
            val proteinMin = ProteinMinimum.ofRule(goal.obj, currentWeight)
                ?.takeUnless { pythonRound2(protein) + GoalCoherence.MINIMUM_TOLERANCE_G < it }
            val targets = goal.build { GoalTargets(pythonRound2(kcal), pythonRound2(protein), pythonRound2(carbs), pythonRound2(fat), proteinMin) }
            val record = Goal(from, 1, RecordState.Active, targets, PENDING, null)
            // L'empreinte inclut le poids de calcul : les cibles en dépendent.
            val fingerprint = JsonObject(goal.obj + ("reference_weight_kg" to JsonPrimitive(weight)))
            Sourced(record, source("goal", from.toString(), 1, goal.obj, fingerprint), null)
        }
    }

    // ───── Journées ─────

    private fun readDay(fileDay: String, document: Fields, element: JsonObject): BundleDay {
        val day = document.date("day")
        if (day.toString() != fileDay) fail("${document.where} : jour ${document.string("day")} différent du nom du fichier")
        val consumptions = document.objects("events").map { readConsumption(it, day) }
        val estimates = (if (document.has("daily_estimates")) document.objects("daily_estimates") else emptyList())
            .map { readEstimate(it, day) }
        val active = estimates.groupBy { it.record.assessmentId }.values
            .map { versions -> versions.maxBy { it.record.version } }
            .count { it.record.isActive }
        if (active > 1) fail("${document.where} : plusieurs estimations journalières actives")
        val tracking = DayTracking(
            day = day,
            version = 1,
            state = RecordState.Active,
            closedExplicitly = document.boolean("closed_explicitly"),
            trackingComplete = document.booleanOrNull("tracking_complete"),
            detailedTrackingComplete = document.booleanOrNull("detailed_tracking_complete"),
            recordedAt = PENDING,
            actionId = null,
        )
        // Le suivi conserve tout le document de la journée, hors repas et estimations (repris à part).
        val dayDocument = JsonObject(element - "events" - "daily_estimates")
        return BundleDay(
            day = day,
            consumptions = consumptions,
            estimates = estimates,
            tracking = Sourced(tracking, source("day_tracking", day.toString(), 1, dayDocument), document.instantOrNull("updated_at")),
        )
    }

    private fun readConsumption(entry: Fields, day: LocalDate): Sourced<Consumption> {
        val entryDay = entry.date("day")
        if (entryDay != day) fail("${entry.where} : rattachée au $entryDay, pas au $day")
        val nutrition = entry.obj("nutrition")
        val quantity = entry.obj("quantity")
        val source = entry.objOrNull("source")
        val estimate = entry.objOrNull("estimate")
        val record = entry.build {
            Consumption(
                entryId = EntryId(entry.string("entry_id")),
                version = entry.int("version"),
                state = RecordState.fromCode(entry.string("state")),
                day = entryDay,
                consumedAt = entry.instant("consumed_at"),
                moment = Moment.fromLegacyCategory(entry.string("category")),
                food = Food(
                    label = entry.string("label"),
                    nutrition = nutrition.build {
                        Nutrition(
                            kcal = nutrition.number("kcal"),
                            proteinG = nutrition.numberOrNull("protein_g"),
                            carbsG = nutrition.numberOrNull("carbs_g"),
                            fatG = nutrition.numberOrNull("fat_g"),
                            fiberG = nutrition.numberOrNull("fiber_g"),
                        )
                    },
                    quantity = quantity.build { Quantity(quantity.number("value"), quantity.string("unit")) },
                    assumptions = estimate?.strings("assumptions").orEmpty(),
                    kcalRange = estimate?.let { e -> e.build { KcalRange(e.number("kcal_low"), e.number("kcal_high")) } },
                    productId = entry.stringOrNull("product_id"),
                    recipeId = entry.stringOrNull("recipe_id"),
                    source = source?.let { FoodSource(it.string("type"), it.string("description"), it.stringOrNull("url")) },
                    confidence = entry.stringOrNull("confidence")?.let(Confidence::fromCode),
                ),
                origin = Origin.Import,
                recordedAt = PENDING,
                actionId = null,
            )
        }
        val kcal = record.food.nutrition.kcal
        record.food.kcalRange?.let { range ->
            if (kcal < range.low || kcal > range.high) fail("${entry.where} : valeur centrale hors fourchette")
        }
        return Sourced(record, source("consumption", record.entryId.value, record.version, entry.obj), entry.instantOrNull("updated_at"))
    }

    private fun readEstimate(item: Fields, day: LocalDate): Sourced<DailyEstimate> {
        if (item.date("day") != day) fail("${item.where} : estimation d'un autre jour")
        if (item.booleanOrNull("user_confirmed") != true) fail("${item.where} : estimation non validée par l'utilisateur")
        val record = item.build {
            DailyEstimate(
                assessmentId = item.string("assessment_id"),
                version = item.int("version"),
                state = RecordState.fromCode(item.string("state")),
                day = day,
                kind = EstimateKind.fromCode(item.string("kind")),
                kcal = item.numberOrNull("kcal"),
                kcalLow = item.number("kcal_low"),
                kcalHigh = item.numberOrNull("kcal_high"),
                description = item.objOrNull("source")?.stringOrNull("description") ?: "Estimation de la journée",
                replacesEntryIds = item.strings("replaces_entry_ids").map(::EntryId),
                recordedAt = PENDING,
                actionId = null,
            )
        }
        return Sourced(record, source("daily_estimate", record.assessmentId, record.version, item.obj), item.instantOrNull("updated_at"))
    }

    // ───── Catalogue, plats, préférences ─────

    private fun readNutrients(n: Fields) = n.build {
        Nutrients(n.numberOrNull("kcal"), n.numberOrNull("protein_g"), n.numberOrNull("carbs_g"), n.numberOrNull("fat_g"), n.numberOrNull("fiber_g"))
    }

    private fun readProduct(p: Fields): Sourced<Product> {
        val source = p.objOrNull("source")
        val record = p.build {
            Product(
                productId = p.string("product_id"),
                version = 1,
                state = RecordState.Active,
                name = p.string("name"),
                brand = p.stringOrNull("brand"),
                variant = p.stringOrNull("variant"),
                country = p.stringOrNull("country"),
                barcode = p.stringOrNull("barcode"),
                aliases = p.strings("aliases"),
                soldState = p.stringOrNull("sold_state"),
                nutrients = readNutrients(p.obj("nutrition_per_100")),
                basis = p.objOrNull("nutrition_basis")?.let { b -> b.build { NutritionBasis(b.number("value"), b.string("unit"), b.stringOrNull("state")) } },
                nutritionStatus = p.stringOrNull("nutrition_status"),
                source = source?.let { FoodSource(it.string("type"), it.string("description"), it.stringOrNull("url")) },
                confidence = p.stringOrNull("confidence")?.let(Confidence::fromCode),
                verified = p.booleanOrNull("verified") ?: false,
                favorite = p.booleanOrNull("favorite") ?: false,
                active = p.booleanOrNull("active") ?: true,
                redirectTo = p.stringOrNull("redirect_to"),
                defaultMatch = p.booleanOrNull("default_match_when_unqualified") ?: false,
                portion = p.objOrNull("portion")?.let { readPortion(it, p) },
                recordedAt = PENDING,
                actionId = null,
            )
        }
        // Les compteurs d'usage évoluent à chaque repas : ils ne font pas une fiche différente.
        val fingerprint = JsonObject(p.obj - USAGE_FIELDS)
        return Sourced(record, source("product", record.productId, 1, p.obj, fingerprint), p.instantOrNull("updated_at"))
    }

    /** Portion d'une fiche nettoyée (« 1 brique », 500 ml) : valeurs tirées de la fiche, seulement dans la même unité que sa base. */
    private fun readPortion(portion: Fields, p: Fields): ProductPortion? = portion.build {
        val weight = Quantity(portion.number("value"), portion.string("unit"))
        val basis = p.objOrNull("nutrition_basis")?.let { b -> b.build { b.number("value") to b.string("unit") } }
        if (basis == null || Units.canonical(basis.second) != Units.canonical(weight.unit)) return@build null
        val factor = weight.value / basis.first
        val n = readNutrients(p.obj("nutrition_per_100"))
        fun scale(value: Double?) = value?.let { it * factor }
        ProductPortion(portion.string("label"), weight, Nutrients(scale(n.kcal), scale(n.proteinG), scale(n.carbsG), scale(n.fatG), scale(n.fiberG)))
    }

    private fun readRecipe(r: Fields): Sourced<Recipe> {
        val record = r.build {
            Recipe(
                recipeId = r.string("recipe_id"),
                version = r.int("version"),
                state = RecordState.Active,
                name = r.string("name"),
                aliases = r.strings("aliases"),
                active = r.booleanOrNull("active") ?: true,
                defaultMatch = r.booleanOrNull("default_match_when_unqualified") ?: false,
                ingredients = r.objects("ingredients").map { i ->
                    i.build { RecipeIngredient(i.string("ingredient_id"), i.string("label"), i.number("quantity_g"), readNutrients(i.obj("nutrition_per_100"))) }
                },
                sourceTemplateJson = r.objOrNull("source_template")?.let { canonical(it.obj) },
                recordedAt = PENDING,
                actionId = null,
            )
        }
        return Sourced(record, source("recipe", record.recipeId, record.version, r.obj), null)
    }

    private fun readPreference(p: Fields): Sourced<Preference> {
        val record = p.build {
            Preference(
                preferenceId = p.string("preference_id"),
                version = 1,
                state = RecordState.Active,
                kind = p.string("kind"),
                trigger = p.string("trigger"),
                rule = p.string("rule"),
                active = p.boolean("active"),
                recordedAt = PENDING,
                actionId = null,
            )
        }
        return Sourced(record, source("preference", record.preferenceId, 1, p.obj), p.instantOrNull("updated_at"))
    }

    // ───── Totaux de contrôle ─────

    private fun readChecks(checks: Fields): Map<LocalDate, ExpectedDay> {
        val days = checks.obj("days")
        return days.obj.keys.associate { key ->
            val day = runCatching { LocalDate.parse(key) }.getOrElse { fail("Contrôle : jour invalide $key") }
            val check = days.obj(key)
            val totals = check.obj("totals")
            val known = totals.obj("known_totals")
            val targets = check.objOrNull("targets")
            day to ExpectedDay(
                day = day,
                kcal = totals.numberOrNull("kcal"),
                proteinG = totals.numberOrNull("protein_g"),
                carbsG = totals.numberOrNull("carbs_g"),
                fatG = totals.numberOrNull("fat_g"),
                fiberG = totals.numberOrNull("fiber_g"),
                kcalLow = totals.number("kcal_low"),
                kcalHigh = totals.numberOrNull("kcal_high"),
                energyStatus = totals.string("energy_status"),
                containsEstimates = totals.boolean("contains_estimates"),
                known = listOf("kcal", "protein_g", "carbs_g", "fat_g", "fiber_g").map(known::number),
                coverage = check.string("energy_status"),
                targets = targets?.let { t -> listOf("kcal", "protein_ideal_g", "carbs_g", "fat_g").map(t::number) },
            )
        }
    }
}
