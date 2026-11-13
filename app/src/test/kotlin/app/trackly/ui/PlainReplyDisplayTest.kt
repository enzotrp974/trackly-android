package app.trackly.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.ui.chat.AssistantReply
import app.trackly.ui.chat.ChatTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Le gras Markdown du modèle est mis en forme à l'affichage, sans astérisques (le texte stocké le garde). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class PlainReplyDisplayTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `la bulle de reponse affiche le texte sans gras Markdown`() {
        compose.setContent { AssistantReply("Il te reste **2 300 kcal**.") }
        compose.onNodeWithTag(ChatTags.REPLY).assertTextEquals("Il te reste 2 300 kcal.")
    }
}
