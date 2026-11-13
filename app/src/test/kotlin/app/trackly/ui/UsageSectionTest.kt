package app.trackly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.ui.settings.SettingsScreen
import app.trackly.ui.settings.SettingsPage
import app.trackly.ui.settings.SettingsGroups
import app.trackly.ui.settings.UsageSettingsSection
import app.trackly.ui.settings.UsageTags
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import app.trackly.usage.MonthCost
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.YearMonth

/** Coût du mois dans les Réglages : total, répartition, échanges, budget, et mention d'estimation locale. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h1200dp-xxhdpi")
class UsageSectionTest {
    @get:Rule val compose = createComposeRule()
    private val nbsp = ' '

    private fun show(cost: MonthCost) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                SettingsScreen(InMemoryApiKeyStore(), onClose = {}, groups = SettingsGroups(model = { UsageSettingsSection(cost) }), startPage = SettingsPage.Model)
            }
        }
    }

    @Test
    fun `affiche le total du mois, la repartition, les echanges et le budget`() {
        show(
            MonthCost(
                month = YearMonth.of(2026, 11),
                text = 31_240_000, search = 1_500_000, transcription = 420_000,
                exchanges = 212, requests = 340, dictations = 38,
            ),
        )

        compose.onNodeWithText("novembre 2026").assertExists()
        compose.onNodeWithTag(UsageTags.TOTAL, useUnmergedTree = true).assertTextEquals("33,16${nbsp}$")
        compose.onNodeWithTag(UsageTags.TEXT, useUnmergedTree = true).assertTextEquals("31,24${nbsp}$")
        compose.onNodeWithTag(UsageTags.SEARCH, useUnmergedTree = true).assertTextEquals("1,50${nbsp}$")
        compose.onNodeWithTag(UsageTags.TRANSCRIPTION, useUnmergedTree = true).assertTextEquals("0,42${nbsp}$")
        compose.onNodeWithTag(UsageTags.ACTIVITY, useUnmergedTree = true).assertTextEquals("212 échanges avec l’assistant · 38 dictées")
        compose.onNodeWithTag(UsageTags.BUDGET, useUnmergedTree = true)
            .assertTextEquals("40 % du budget · budget de 82${nbsp}$ par mois · cible d’usage : 50 à 60${nbsp}$")
    }

    @Test
    fun `le total est la somme des lignes affichees, arrondies une a une`() {
        // 0,224 $ + 0,005 $ + 0,005 $ : exact 0,234 $ (0,23), mais les lignes affichées font 0,22 + 0,01 + 0,01.
        show(MonthCost(YearMonth.of(2026, 11), text = 224_000, search = 5_000, transcription = 5_000, exchanges = 3))

        compose.onNodeWithTag(UsageTags.TEXT, useUnmergedTree = true).assertTextEquals("0,22${nbsp}$")
        compose.onNodeWithTag(UsageTags.SEARCH, useUnmergedTree = true).assertTextEquals("0,01${nbsp}$")
        compose.onNodeWithTag(UsageTags.TRANSCRIPTION, useUnmergedTree = true).assertTextEquals("0,01${nbsp}$")
        compose.onNodeWithTag(UsageTags.TOTAL, useUnmergedTree = true).assertTextEquals("0,24${nbsp}$")
    }

    @Test
    fun `precise que c est une estimation locale`() {
        show(MonthCost(YearMonth.of(2026, 11)))

        compose.onNodeWithTag(UsageTags.ESTIMATE).assertIsDisplayed()
        compose.onNodeWithText("Estimation, tarifs du 2 novembre 2026.").assertExists()
    }

    @Test
    fun `mois vide - zero partout`() {
        show(MonthCost(YearMonth.of(2026, 11)))

        compose.onNodeWithTag(UsageTags.TOTAL, useUnmergedTree = true).assertTextEquals("0,00${nbsp}$")
        compose.onNodeWithTag(UsageTags.ACTIVITY, useUnmergedTree = true).assertTextEquals("0 échange avec l’assistant")
    }

    @Test
    fun `budget depasse - dit clairement`() {
        show(MonthCost(YearMonth.of(2026, 11), text = 90_000_000, exchanges = 1))

        compose.onNodeWithTag(UsageTags.BUDGET, useUnmergedTree = true).assertTextEquals("Budget dépassé · budget de 82${nbsp}$ par mois · cible d’usage : 50 à 60${nbsp}$")
    }
}
