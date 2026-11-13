package app.trackly.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.trackly.assistant.cards.SourcesCard
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklyType

/**
 * Sources consultées par la recherche web, discrètes sous la réponse : « Sources : carte.exemple.ch · … ».
 * Chaque nom est un lien qui s'ouvre dans le navigateur (la doc d'OpenAI demande des citations visibles et cliquables).
 */
@Composable
internal fun SourcesView(card: SourcesCard) {
    if (card.sources.isEmpty()) return
    val links = TextLinkStyles(SpanStyle(color = TracklyColors.Cobalt, textDecoration = TextDecoration.Underline))
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = TracklyColors.Ink3)) { append(if (card.sources.size == 1) "Source : " else "Sources : ") }
        card.sources.forEachIndexed { i, source ->
            if (i > 0) withStyle(SpanStyle(color = TracklyColors.Ink3)) { append(" · ") }
            withLink(LinkAnnotation.Url(source.url, links)) { append(source.label) }
        }
    }
    BasicText(
        text,
        style = TracklyType.GaugeCaption.copy(color = TracklyColors.Ink3, lineHeight = TracklyType.GaugeCaption.lineHeight),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 300.dp)
            .padding(top = 6.dp, start = 4.dp)
            .testTag(CardTags.SOURCES)
            .semantics { contentDescription = "Sources consultées sur Internet : " + card.sources.joinToString(", ") { it.label } },
    )
}
