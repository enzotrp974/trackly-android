package app.trackly.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.trackly.R

/**
 * Chiffres en Bricolage Grotesque (gras, vivants), interface en Plus Jakarta Sans.
 * Polices variables embarquées (OFL, voir assets/licences).
 */
@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: Int, opticalSize: Float, width: Float = 100f) = FontFamily(
    Font(
        R.font.bricolage_grotesque,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight),
            FontVariation.width(width),
            // Le navigateur applique opsz = taille du texte ; on fait de même (axe 12–96).
            FontVariation.Setting("opsz", opticalSize.coerceIn(12f, 96f)),
        ),
    ),
)

@OptIn(ExperimentalTextApi::class)
private fun jakarta(weight: Int, italic: Boolean = false) = Font(
    if (italic) R.font.plus_jakarta_sans_italic else R.font.plus_jakarta_sans,
    weight = FontWeight(weight),
    style = if (italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val PlusJakartaSans = FontFamily(
    jakarta(300), jakarta(400), jakarta(500), jakarta(600), jakarta(700), jakarta(800),
    jakarta(300, italic = true), jakarta(400, italic = true),
)

private const val TABULAR = "\"tnum\", \"lnum\""
private const val LINING = "\"lnum\""

/** Ligne de 1 em sans marge : les chiffres s'empilent exactement dans l'odomètre. */
private val Tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

private fun sans(size: TextUnit, weight: Int, spacing: TextUnit = 0.em, lineHeight: TextUnit = TextUnit.Unspecified, italic: Boolean = false, features: String? = null) =
    TextStyle(
        fontFamily = PlusJakartaSans,
        fontSize = size,
        fontWeight = FontWeight(weight),
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        letterSpacing = spacing,
        lineHeight = lineHeight,
        fontFeatureSettings = features,
    )

private fun numerals(size: Float, weight: Int, spacing: TextUnit, width: Float = 100f) = TextStyle(
    fontFamily = bricolage(weight, opticalSize = size, width = width),
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = spacing,
    lineHeight = size.sp,
    lineHeightStyle = Tight,
    fontFeatureSettings = TABULAR,
)

object TracklyType {
    // En-têtes
    val Brand = sans(14.sp, 800, (-0.01).em, features = LINING)
    val DayLabel = sans(13.sp, 500, features = TABULAR)
    val Kicker = sans(10.sp, 700, 0.06.em)
    val SampleTag = sans(9.sp, 700, 0.06.em)

    // Bilan
    val HeroNumber = numerals(80f, 700, (-0.04).em, width = 85f)
    val HeroUnit = sans(14.sp, 400)
    val GaugeCaption = sans(10.5.sp, 400, 0.02.em, features = TABULAR)
    val GaugeNumber = sans(10.5.sp, 500, features = TABULAR).copy(lineHeight = 10.5.sp, lineHeightStyle = Tight)
    val Scale = sans(9.5.sp, 400, 0.01.em, features = TABULAR)
    val ScaleEnd = sans(9.5.sp, 600, features = TABULAR)

    // Page du bilan
    val ReportVerdict = numerals(52f, 700, (-0.04).em, width = 85f)
    val ReportStripValue = numerals(24f, 700, (-0.02).em)
    val ReportPointValue = numerals(21f, 700, (-0.01).em)
    val ReportNote = sans(17.sp, 500, lineHeight = (17 * 1.4).sp)
    val ReportSentence = sans(15.5.sp, 400, lineHeight = (15.5 * 1.45).sp, features = LINING)
    val ReportPointName = sans(15.sp, 700, lineHeight = (15 * 1.35).sp, features = LINING)
    val ReportPointDesc = sans(13.5.sp, 400, lineHeight = (13.5 * 1.35).sp, features = LINING)
    val ReportChartLabel = sans(10.5.sp, 500, features = TABULAR)

    // Macros
    val MacroLabel = sans(9.sp, 700, 0.06.em)
    val MacroGoal = sans(10.sp, 400, features = TABULAR)
    /** Valeur d'une macro en ligne (accueil, barres longues). */
    val MacroRowValue = numerals(26f, 700, (-0.025).em)

    // Repas
    val MealTitle = sans(15.5.sp, 700, (-0.005).em, lineHeight = (15.5 * 1.15).sp, features = LINING)
    val MealDetail = sans(12.5.sp, 400, lineHeight = (12.5 * 1.4).sp)
    val MealEmpty = sans(13.sp, 300, italic = true, features = LINING)
    val MealKcal = numerals(23f, 700, (-0.02).em)
    val MealUnit = sans(9.sp, 400, 0.1.em, features = TABULAR)
    /** Ligne de repas de l'accueil (une ligne de 50 dp) : moment, aliments, kcal. */
    val MealRowName = sans(14.5.sp, 700, (-0.005).em, features = LINING)
    val MealRowDetail = sans(12.5.sp, 400)
    val MealRowKcal = numerals(19f, 700, (-0.01).em)

    // Barre d'action
    val AskPlaceholder = sans(14.5.sp, 400)
    /** Libellés « Aliments » et « Bilan » de la barre du bas. */
    val DockLabel = sans(11.sp, 500)

    // Chat
    val ChatTitle = Brand
    val ChatDate = sans(10.5.sp, 400, 0.02.em, features = TABULAR)
    val Hint = TextStyle(
        fontFamily = bricolage(700, opticalSize = 34f),
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.03).em,
        lineHeight = (34 * 1.04).sp,
        fontFeatureSettings = LINING,
    )
    val HintBody = sans(13.5.sp, 400, lineHeight = (13.5 * 1.5).sp)
    val Bubble = sans(14.5.sp, 400, lineHeight = (14.5 * 1.4).sp)
    val Thinking = sans(13.sp, 600)
    // Réponses mises en page (Markdown) : même voix que la bulle, hiérarchie par la graisse.
    val ReplyHeading = sans(16.5.sp, 800, (-0.01).em, lineHeight = (16.5 * 1.3).sp, features = LINING)
    val ReplySubheading = sans(14.5.sp, 700, lineHeight = (14.5 * 1.35).sp, features = LINING)
    val ReplyListMarker = sans(14.5.sp, 700, lineHeight = (14.5 * 1.4).sp, features = TABULAR)
    val ReplyTableHead = sans(12.sp, 700, 0.02.em, lineHeight = (12 * 1.4).sp, features = TABULAR)
    val ReplyTableCell = sans(13.5.sp, 400, lineHeight = (13.5 * 1.35).sp, features = TABULAR)
    val ReplyCode = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = (12.5 * 1.45).sp)
    val Notice = sans(13.sp, 400, lineHeight = (13 * 1.45).sp)
    val NoticeTitle = sans(13.5.sp, 700)

    // Carte d'enregistrement
    val CardStatus = sans(13.sp, 600, features = LINING)
    val CardTotal = numerals(40f, 700, (-0.04).em)
    val CardTotalUnit = sans(13.sp, 500)
    val CardItem = sans(14.5.sp, 500, (-0.005).em, lineHeight = (14.5 * 1.3).sp, features = LINING)
    val CardItemDetail = sans(13.sp, 400, features = TABULAR)
    val CardItemKcal = numerals(19f, 700, (-0.01).em)
    val CardMacros = sans(13.5.sp, 500, features = TABULAR)
    val CardAssumption = sans(13.5.sp, 400, lineHeight = (13.5 * 1.35).sp, italic = true)
    val CardButton = sans(13.5.sp, 600)
    val Undone = sans(13.sp, 400, features = TABULAR)

    // Réglages
    val SettingsTitle = sans(17.sp, 800, (-0.01).em)
    val SettingsBody = sans(13.5.sp, 400, lineHeight = (13.5 * 1.5).sp)

    // Écran d'appel.
    val CallTimer = numerals(44f, 700, (-0.02).em)
    val CallStatus = sans(16.sp, 500, lineHeight = (16 * 1.35).sp, features = LINING)
    val CallButtonLabel = sans(12.sp, 500)
}
