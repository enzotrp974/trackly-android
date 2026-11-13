package app.trackly.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Tokens du style « Porcelaine × Minéral ».
 * Seule source des couleurs et espacements de l'app : aucun écran ne code de valeur en dur.
 */
object TracklyColors {
    val Ink = Color(0xFF0E1B3D)
    val Ink2 = Color(0xFF414E70)
    val Ink3 = Color(0xFF5F6B88)
    val Paper = Color(0xFFF5F8FC)
    val PaperHi = Color(0xFFFBFCFE)
    val Line = Ink.copy(alpha = 0.12f)
    val Line2 = Ink.copy(alpha = 0.24f)

    /** Accent unique : enregistrement, dictée, « + », jauge. */
    val Cobalt = Color(0xFF1B3F9F)
    val CobaltHi = Color(0xFF2F5BC8)
    val CobaltLo = Color(0xFF12296F)
    val Mist = Color(0xFFE7EEFB)

    /** Macros distinguées par la valeur et la texture : encre / cobalt clair / cobalt hachuré. */
    val Protein = Ink
    val Carbs = Color(0xFF4F74D4)
    val Fat = Cobalt
    val FatHatchGround = Color(0xFFDBE5F8)

    /**
     * États d'alerte d'une barre (jauge des calories, barres de macros), seuls changements de couleur de l'accueil :
     * jaune « attention », rouge « dépassé ». Seul le remplissage les prend ; chiffres, étiquettes et graduations restent.
     * Les lipides gardent leurs hachures : les traits prennent [Warn] ou [Bad], le fond [WarnHatchGround] ou [BadHatchGround].
     */
    val Warn = Color(0xFFB87A00)
    val Bad = Color(0xFFB8322A)
    val WarnHatchGround = Color(0xFFF3E6C6)
    val BadHatchGround = Color(0xFFF5DAD6)

    /** Lumière qui dérive sur la pierre, et trame de points perforés. */
    val Glow = Color.White
    val GlowCool = Color(0xFFF0F4FB)
    val Dot = Ink.copy(alpha = 0.20f)
    val TrackEmpty = Ink.copy(alpha = 0.08f)
    val TickOff = Ink.copy(alpha = 0.22f)
    val TickMajorOff = Ink.copy(alpha = 0.42f)
    val Shadow = Ink
    val ShadowCobalt = CobaltLo
}

object TracklySpacing {
    /** Marge latérale de l'écran du jour. */
    val Gutter = 26.dp
    val DockGutter = 16.dp
    val ChatGutter = 18.dp
    val SheetRadius = 18.dp
    val Hairline = 1.dp
    val DotGrid = 14.dp
    val DotRadius = 0.9.dp

    /** Hauteurs tactiles. */
    val AskHeight = 54.dp
    /** Champ « Écrire à Trackly… » de la barre du bas de l'accueil. */
    val DockFieldHeight = 44.dp
    /** Gros micro de la barre du bas de l'accueil. */
    val DockMicSize = 66.dp
    /** Largeur minimale des boutons « Aliments » et « Bilan » de la barre du bas. */
    val DockSideWidth = 84.dp
    /** Bouton d'appel au bout du champ de la barre du bas (rond dessiné ; cible de 48 dp). */
    val DockCallSize = 34.dp
    val MicSize = 40.dp
    /** Cible tactile minimale (Material, TalkBack) : tout élément qui se touche en occupe au moins autant. */
    val TouchTarget = 48.dp
    val ArrowSize = 30.dp
    val RowAddSize = 30.dp
    val BackSize = 36.dp
}
