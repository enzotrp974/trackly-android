package app.trackly.ui.settings

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.trackly.assistant.key.ApiKeyStatus
import app.trackly.assistant.key.ApiKeyStore
import app.trackly.assistant.key.KeyVerification
import app.trackly.assistant.key.KeyVerificationState
import app.trackly.assistant.key.SecretClipboard
import app.trackly.assistant.key.message
import app.trackly.assistant.key.validateApiKey
import app.trackly.ui.chat.PillButton
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.Hairline
import app.trackly.ui.components.PorcelainBackdrop
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

object SettingsTags {
    const val SCREEN = "reglages"
    const val KEY_INPUT = "reglages-cle-saisie"
    const val KEY_STATUS = "reglages-cle-etat"
    const val KEY_SCAN = "reglages-cle-scanner"
    const val KEY_PASTE = "reglages-cle-coller"
    const val KEY_MESSAGE = "reglages-cle-message"
    const val KEY_SECTION = "reglages-cle-section"
    const val KEY_CHECK = "reglages-cle-verification"
    const val KEY_VERIFY = "reglages-cle-verifier"
}

/**
 * Ce que les Réglages ajoutent autour de la clé OpenAI : le [scanner] de QR code (`null` : pas de bouton,
 * aperçus et tests) et le [clipboard] vidé après un enregistrement réussi.
 */
class KeyTools(
    val clipboard: SecretClipboard = SecretClipboard.None,
    val scanner: (@Composable (onClose: () -> Unit) -> Unit)? = null,
    /** Vérification gratuite de la clé après chaque enregistrement ; `null` : pas de contrôle (aperçus et tests). */
    val verification: KeyVerification? = null,
)

/** Les thèmes des Réglages : l'accueil les liste, et chacun n'ouvre que lui-même. */
enum class SettingsPage(val title: String, val glyph: Glyph, val tag: String) {
    Key("Clé OpenAI", Glyph.Key, "reglages-page-cle"),
    Model("Assistant", Glyph.Spark, "reglages-page-modele"),
    Dictation("Dictée", Glyph.Mic, "reglages-page-dictee"),
    Call("Appel", Glyph.Waves, "reglages-page-appel"),
    Usage("Consommation", Glyph.Chart, "reglages-page-consommation"),
    Drive("Sauvegarde Drive", Glyph.Cloud, "reglages-page-drive"),
    Goals("Objectifs", Glyph.Target, "reglages-page-objectifs"),
    Review("Bilan du lundi", Glyph.Calendar, "reglages-page-bilan"),
    Notifications("Notifications", Glyph.Bell, "reglages-page-notifications"),
}

/** Le contenu de chaque thème, et la ligne « Mise en route » tant qu'elle reste à faire. */
class SettingsGroups(
    val top: @Composable ColumnScope.() -> Unit = {},
    val model: @Composable ColumnScope.() -> Unit = {},
    val dictation: @Composable ColumnScope.() -> Unit = {},
    val call: @Composable ColumnScope.() -> Unit = {},
    val usage: @Composable ColumnScope.() -> Unit = {},
    val drive: @Composable ColumnScope.() -> Unit = {},
    val goals: @Composable ColumnScope.() -> Unit = {},
    val review: @Composable ColumnScope.() -> Unit = {},
    val notifications: @Composable ColumnScope.() -> Unit = {},
)

/**
 * Réglages : une simple liste de thèmes ; en toucher un ouvre une page qui ne montre que lui. [startPage] ouvre
 * directement un thème : le retour ferme alors les Réglages. [scrollToKey] ouvre la clé ; [onScrolledToKey] le dit à l'appelant.
 */
@Composable
fun SettingsScreen(
    keyStore: ApiKeyStore,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    keyTools: KeyTools = KeyTools(),
    scrollToKey: Boolean = false,
    onScrolledToKey: () -> Unit = {},
    groups: SettingsGroups = SettingsGroups(),
    startPage: SettingsPage? = null,
) {
    var scanning by rememberSaveable { mutableStateOf(false) }
    val firstPage = startPage ?: if (scrollToKey) SettingsPage.Key else null
    var page by rememberSaveable { mutableStateOf(firstPage) }
    // Ouverte directement (notification, « Régler » de la mise en route) : le retour ramène d'où l'on vient, pas à l'accueil.
    var direct by rememberSaveable { mutableStateOf(firstPage != null) }
    val back = {
        if (page != null && !direct) page = null else onClose()
    }
    // Demande arrivée pendant que les Réglages sont ouverts (notification de sauvegarde).
    LaunchedEffect(startPage) {
        if (startPage != null && page != startPage) {
            page = startPage
            direct = true
        }
    }
    LaunchedEffect(scrollToKey) {
        if (scrollToKey) {
            if (page != SettingsPage.Key) {
                page = SettingsPage.Key
                direct = true
            }
            onScrolledToKey()
        }
    }
    val focus = LocalFocusManager.current
    // Le clavier d'un autre écran ne doit pas rester ouvert par-dessus les Réglages.
    LaunchedEffect(Unit) { focus.clearFocus(force = true) }
    BackHandler(onBack = back)
    Box(modifier.fillMaxSize().testTag(SettingsTags.SCREEN)) {
        PorcelainBackdrop(background = TracklyColors.PaperHi, glow = false, dotsFadeAt = 0.2f, dotsAlpha = 0.6f)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 14.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(TracklySpacing.BackSize)
                        .pressable(back, pressedScale = 0.86f)
                        .semantics { contentDescription = if (page != null && !direct) "Retour aux réglages" else "Fermer les réglages" },
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(Glyph.ChevronLeft, TracklyColors.Ink, Modifier.size(19.dp))
                }
                BasicText(page?.title ?: "Réglages", style = TracklyType.SettingsTitle.copy(color = TracklyColors.Ink), modifier = Modifier.padding(start = 6.dp).semantics { heading() })
            }
            key(page) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = TracklySpacing.Gutter, vertical = 12.dp),
                ) {
                    when (page) {
                        null -> {
                            groups.top(this)
                            PageList(onOpen = { page = it })
                        }
                        SettingsPage.Key -> Column(Modifier.fillMaxWidth().testTag(SettingsTags.KEY_SECTION)) {
                            ApiKeySection(
                                keyStore,
                                keyTools.clipboard,
                                onScan = keyTools.scanner?.let { { scanning = true } },
                                verification = keyTools.verification,
                            )
                        }
                        SettingsPage.Model -> groups.model(this)
                        SettingsPage.Dictation -> groups.dictation(this)
                        SettingsPage.Call -> groups.call(this)
                        SettingsPage.Usage -> groups.usage(this)
                        SettingsPage.Drive -> groups.drive(this)
                        SettingsPage.Goals -> groups.goals(this)
                        SettingsPage.Review -> groups.review(this)
                        SettingsPage.Notifications -> groups.notifications(this)
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
        keyTools.scanner?.takeIf { scanning }?.invoke { scanning = false }
    }
}

private val PageListShape = RoundedCornerShape(TracklySpacing.SheetRadius)

/** Les lignes de l'accueil des Réglages : un thème par ligne. */
@Composable
private fun PageList(onOpen: (SettingsPage) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(PageListShape)
            .background(TracklyColors.Paper)
            .border(TracklySpacing.Hairline, TracklyColors.Line, PageListShape),
    ) {
        SettingsPage.entries.forEachIndexed { i, entry ->
            if (i > 0) Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag(entry.tag)
                    .pressable({ onOpen(entry) }, pressedScale = 0.985f)
                    .semantics(mergeDescendants = true) { role = Role.Button }
                    .heightIn(min = 58.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(TracklyColors.Mist), contentAlignment = Alignment.Center) {
                    GlyphIcon(entry.glyph, TracklyColors.Cobalt, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                BasicText(entry.title, style = TracklyType.MealTitle.copy(color = TracklyColors.Ink), modifier = Modifier.weight(1f))
                GlyphIcon(Glyph.ChevronRight, TracklyColors.Ink3, Modifier.size(17.dp))
            }
        }
    }
}

private val FieldShape = RoundedCornerShape(10.dp)

private const val KEY_SAVED = "Clé enregistrée."

/**
 * Clé OpenAI, stockée chiffrée sur le téléphone (Android Keystore) : scan d'un QR code ([onScan], `null` :
 * pas de bouton) ou collage. Après un enregistrement réussi, [clipboard] est vidé.
 */
@Composable
fun ApiKeySection(
    keyStore: ApiKeyStore,
    clipboard: SecretClipboard = SecretClipboard.None,
    onScan: (() -> Unit)? = null,
    verification: KeyVerification? = null,
) {
    val status by keyStore.status.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val check by (verification?.state ?: remember { MutableStateFlow<KeyVerificationState>(KeyVerificationState.Unchecked) }).collectAsState()
    LaunchedEffect(keyStore) { keyStore.refresh() }
    LaunchedEffect(verification) { verification?.retryIfPending() }
    val save: () -> Unit = {
        val clean = input.trim()
        val problem = validateApiKey(clean)
        if (problem != null) {
            message = problem
        } else {
            scope.launch {
                keyStore.save(clean)
                input = ""
                focus.clearFocus()
                // La clé collée ne doit pas rester dans le presse-papiers (et son historique, sur Samsung).
                clipboard.clear()
                message = KEY_SAVED
            }
        }
    }

    BasicText(
        when (val s = status) {
            ApiKeyStatus.Unknown -> "Lecture de la clé…"
            ApiKeyStatus.Missing -> "Aucune clé."
            is ApiKeyStatus.Present -> "Clé enregistrée, finit par « ${s.lastFour} »."
            ApiKeyStatus.Unreadable -> "Clé illisible sur ce téléphone : saisis-la à nouveau."
        },
        style = TracklyType.SettingsBody.copy(color = TracklyColors.Ink),
        modifier = Modifier.testTag(SettingsTags.KEY_STATUS).semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (status is ApiKeyStatus.Present) {
        check.message()?.let { text ->
            Spacer(Modifier.height(4.dp))
            BasicText(
                text,
                style = TracklyType.MealTitle.copy(color = if (check == KeyVerificationState.Refused) TracklyColors.Ink else TracklyColors.Cobalt),
                modifier = Modifier.testTag(SettingsTags.KEY_CHECK).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (verification != null && (check == KeyVerificationState.Unchecked || check is KeyVerificationState.Pending)) {
            Box(Modifier.testTag(SettingsTags.KEY_VERIFY)) { LinkLine("Vérifier la clé maintenant") { verification.verify() } }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onScan != null) {
            Box(Modifier.testTag(SettingsTags.KEY_SCAN)) { PillButton("Scanner un QR code", primary = true, onClick = onScan) }
        }
        Box(Modifier.testTag(SettingsTags.KEY_PASTE)) {
            PillButton("Coller", primary = onScan == null) {
                val pasted = clipboard.read()
                if (pasted.isNullOrBlank()) {
                    message = "Presse-papiers vide : copie d’abord ta clé."
                } else {
                    input = pasted
                    save()
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(FieldShape)
            .background(TracklyColors.Paper)
            .border(TracklySpacing.Hairline, TracklyColors.Line2, FieldShape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (input.isEmpty()) {
            BasicText(
                if (status is ApiKeyStatus.Present) "Nouvelle clé (sk-…)" else "Ou tape ta clé (sk-…)",
                style = TracklyType.Bubble.copy(color = TracklyColors.Ink3),
            )
        }
        BasicTextField(
            value = input,
            onValueChange = {
                input = it
                message = null
            },
            singleLine = true,
            textStyle = TracklyType.Bubble.copy(color = TracklyColors.Ink),
            cursorBrush = SolidColor(TracklyColors.Cobalt),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth().testTag(SettingsTags.KEY_INPUT).semantics { contentDescription = "Clé OpenAI" },
        )
    }
    // Une clé refusée par OpenAI ne s'annonce pas « enregistrée » : « Clé refusée » suffit.
    message?.takeUnless { it == KEY_SAVED && check == KeyVerificationState.Refused }?.let {
        Spacer(Modifier.height(8.dp))
        BasicText(
            it,
            style = TracklyType.Notice.copy(color = TracklyColors.Ink),
            modifier = Modifier.testTag(SettingsTags.KEY_MESSAGE).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton("Enregistrer la clé", primary = true, onClick = save)
        if (status is ApiKeyStatus.Present) {
            PillButton("Supprimer", primary = false) {
                scope.launch {
                    keyStore.clear()
                    message = "Clé supprimée de ce téléphone."
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    BasicText(text.uppercase(), style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt), modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun LinkLine(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TracklyType.SettingsBody.copy(color = TracklyColors.Cobalt, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        modifier = Modifier
            .pressable(onClick, pressedScale = 0.97f)
            .semantics { role = Role.Button }
            .padding(vertical = 6.dp),
    )
}
