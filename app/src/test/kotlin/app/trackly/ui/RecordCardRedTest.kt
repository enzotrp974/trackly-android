package app.trackly.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.cards.CardItem
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.ui.chat.CardView
import app.trackly.ui.chat.OverLimitKey
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Récap du chat, côté TalkBack : le rouge parle de la journée alors que le chiffre de la carte est celui du repas ; la
 * phrase le précise, sans jugement. Une carte sans rouge (ou d'avant la 1.3) se lit comme avant.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class RecordCardRedTest {
    @get:Rule val compose = createComposeRule()

    private fun card(overLimit: List<String>) = RecordCard(
        actionId = 7,
        kind = RecordKind.Added,
        items = listOf(CardItem("e1", "Tablette de chocolat", "150 g", "snack", "2026-11-02", 810.0, 9.0, 86.0, 47.0)),
        overLimit = overLimit,
    )

    private fun spoken(overLimit: List<String>): String {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                Box(Modifier) { CardView(card(overLimit), false, false, {}, {}) }
            }
        }
        compose.waitForIdle()
        return compose.onAllNodes(SemanticsMatcher("phrase des macros") { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("Protéines ") == true })
            .fetchSemanticsNodes().single().config[SemanticsProperties.ContentDescription].first()
    }

    @Test
    fun `les lipides en rouge se disent comme un fait sur la journee`() {
        assertEquals("Protéines 9 grammes, glucides 86 grammes, lipides 47 grammes, limite du jour dépassée", spoken(listOf("fat")))
    }

    @Test
    fun `le champ de test porte les macros en rouge`() {
        spoken(listOf("fat"))
        val node = compose.onAllNodes(SemanticsMatcher.keyIsDefined(OverLimitKey)).fetchSemanticsNodes().single()
        assertEquals(listOf("fat"), node.config[OverLimitKey])
    }

    @Test
    fun `une carte sans rouge se lit comme avant`() {
        val phrase = spoken(emptyList())
        assertEquals("Protéines 9 grammes, glucides 86 grammes, lipides 47 grammes", phrase)
        assertFalse(phrase.contains("jour"))
        assertNull(compose.onAllNodes(SemanticsMatcher.keyIsDefined(OverLimitKey)).fetchSemanticsNodes().firstOrNull())
    }
}
