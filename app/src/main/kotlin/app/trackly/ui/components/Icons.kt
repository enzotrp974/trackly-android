package app.trackly.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

/** Pictogrammes au trait du prototype (viewBox 24, extrémités arrondies). */
enum class Glyph(vararg val paths: String) {
    Cup(
        "M4.5 9.5h11v4.2a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5V9.5z",
        "M15.5 10.5h1.2a2.4 2.4 0 0 1 0 4.8h-1.2M8 3.5v2.2M12 3.5v2.2",
    ),
    Bowl(
        "M3 12.5h18a9 9 0 0 1-18 0z",
        "M8 8.5c0-1.6 1.5-1.6 1.5-3.2M12.5 8.5c0-1.6 1.5-1.6 1.5-3.2M17 8.5c0-1.6 1.2-1.6 1.2-3",
    ),
    Apple(
        "M12 7.6c-2.2-1.9-7-1.4-7 3.6 0 4.4 3 8.8 5.3 8.8 1 0 1.1-.5 1.7-.5s.7.5 1.7.5c2.3 0 5.3-4.4 5.3-8.8 0-5-4.8-5.5-7-3.6z",
        "M12 7.6c0-2 1-3.5 3-4.1",
    ),
    Moon("M20 14.6A8 8 0 0 1 9.4 4 8 8 0 1 0 20 14.6z"),
    Key("M15.5 3.5a5 5 0 1 1-4.7 6.7L4 17v3.5h3.5V18H10v-2.5h2.5l1.6-1.6a5 5 0 1 0 1.4-10.4z", "M16.5 7.5h.01"),
    Spark("M12 3l1.9 5.6L19.5 10.5l-5.6 1.9L12 18l-1.9-5.6L4.5 10.5l5.6-1.9L12 3z", "M19 16v4M17 18h4"),
    Chart("M5 20V12M12 20V5M19 20v-6"),
    Cloud("M7 18.5a4.5 4.5 0 0 1-.6-9 5.5 5.5 0 0 1 10.7-1 4.5 4.5 0 0 1 .4 10H7z"),
    Target("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z", "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z"),
    Bell("M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 1.5h-15L6 16.5z", "M10 21a2.2 2.2 0 0 0 4 0"),
    Plus("M12 5v14M5 12h14"),
    Mic("M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3"),

    // Appel : bouton de la barre du bas, et boutons de l'écran d'appel.
    /** Barres de son : lancer l'appel. */
    Waves("M4 10.5v3M8 7.5v9M12 4.5v15M16 7.5v9M20 10.5v3"),
    MicOff("M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3", "M4 4l16 16"),
    Speaker("M4 9.5h3.5L12 5.5v13l-4.5-4H4z", "M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11"),
    /** Combiné posé : raccrocher. */
    HangUp("M3.5 13.8c4.6-4.2 12.4-4.2 17 0l-1.6 2.6-3.6-1.3v-2.4a10 10 0 0 0-6.6 0v2.4l-3.6 1.3z"),
    ChevronLeft("M14.5 5.5L8 12l6.5 6.5"),
    ChevronRight("M9.5 5.5L16 12l-6.5 6.5"),

    // Chat et carte d'enregistrement.
    Send("M12 19V5.5", "M6 11l6-6 6 6"),
    Undo("M9 14L4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11"),
    Pen("M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16v4z"),
    Info("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 11v5.5M12 7.7v.1"),
    Check("M5 12.5l4.5 4.5L19 7.5"),
    Settings("M4 7h9M17 7h3M4 17h3M11 17h9", "M15 4.5v5M9 14.5v5"),
    Calendar("M4.5 6.5h15v13h-15z", "M4.5 10.5h15M8.5 4v4M15.5 4v4"),
    Chat("M5 5.5h14a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1-1.5 1.5h-8.5l-4 3.5v-3.5H5A1.5 1.5 0 0 1 3.5 15V7A1.5 1.5 0 0 1 5 5.5z"),
}

private val cache = HashMap<Pair<Glyph, Float>, ImageVector>()

private fun vectorOf(glyph: Glyph, stroke: Float): ImageVector = cache.getOrPut(glyph to stroke) {
    ImageVector.Builder(
        name = glyph.name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        glyph.paths.forEach { d ->
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
}

/** Pictogramme décoratif (sans description) : le libellé est porté par le bouton parent. */
@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier, stroke: Float = 1.4f) {
    Image(
        painter = rememberVectorPainter(vectorOf(glyph, stroke)),
        contentDescription = null,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier,
    )
}
