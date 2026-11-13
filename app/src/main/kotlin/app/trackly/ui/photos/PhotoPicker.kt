package app.trackly.ui.photos

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.trackly.photos.CameraTempFiles
import app.trackly.photos.PhotoException
import app.trackly.photos.PhotoLimits
import app.trackly.photos.PhotoPreparer
import app.trackly.photos.PreparedPhoto
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/** Étiquettes de test du sélecteur de photos. */
object PhotoTags {
    const val ATTACH = "photo-joindre"
    const val OPTIONS = "photo-choix"
    const val CAMERA = "photo-appareil"
    const val GALLERY = "photo-galerie"
    const val STRIP = "photo-miniatures"
    const val THUMBNAIL = "photo-miniature"
    const val REMOVE = "photo-retirer"
}

/**
 * Sélecteur de photos **réutilisable** (chat du jour et bilan) : trois composables autour d'un état.
 *
 * ```
 * val picker = rememberPhotoPicker(remaining = MAX_PHOTOS - photos.size, onPhotos = ::add, onProblem = ::say)
 * PhotoAttachButton(picker)      // le bouton, dans la ligne de saisie
 * PhotoOptions(picker)           // « Appareil photo » / « Galerie », quand le bouton est touché
 * PhotoStrip(photos, ::remove)   // les vignettes avant l'envoi
 * ```
 *
 * Galerie : sélecteur de photos du système (aucune permission de stockage). Appareil photo : contrat qui écrit
 * dans un fichier temporaire du cache, supprimé dès que la photo est lue (rien n'arrive dans la galerie).
 * Les photos arrivent déjà réduites et compressées ([PreparedPhoto]) ; à l'envoi, `TurnInput.images` reçoit
 * `photos.map { it.image }`.
 */
@Stable
class PhotoPickerState internal constructor(
    private val openGallery: () -> Unit,
    private val openCamera: () -> Unit,
) {
    /** Le choix « Appareil photo / Galerie » est affiché. */
    var optionsOpen by mutableStateOf(false)
        private set

    fun toggleOptions() {
        optionsOpen = !optionsOpen
    }

    fun takePhoto() {
        optionsOpen = false
        openCamera()
    }

    fun pickFromGallery() {
        optionsOpen = false
        openGallery()
    }
}

/**
 * @param remaining nombre de photos qu'on peut encore joindre (au plus [PhotoLimits.MAX_PHOTOS] par message).
 * @param onPhotos reçoit les photos prêtes (réduites, compressées), hors du fil principal déjà traité.
 * @param onProblem message à afficher (photo illisible, trop de photos, pas d'appareil photo).
 */
@Composable
fun rememberPhotoPicker(
    remaining: Int,
    onPhotos: (List<PreparedPhoto>) -> Unit,
    onProblem: (String) -> Unit,
): PhotoPickerState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preparer = remember(context) { PhotoPreparer(context.contentResolver) }
    val left by rememberUpdatedState(remaining)
    val photosCallback by rememberUpdatedState(onPhotos)
    val problemCallback by rememberUpdatedState(onProblem)
    // Chemin du fichier de l'appareil photo en cours (survit à la recréation de l'activité pendant la prise).
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    // Nettoyage des captures abandonnées, sauf celle en cours : après une recréation de l'activité, le chemin est
    // déjà restauré ici (il doit l'être avant la purge, sinon la photo qui vient d'être prise serait supprimée).
    val temp = remember(context) { CameraTempFiles(context).also { it.purge(keep = pendingPath) } }

    suspend fun prepareAll(sources: List<Pair<Uri, File?>>) {
        val ready = mutableListOf<PreparedPhoto>()
        for ((uri, file) in sources) {
            try {
                ready += preparer.prepare(uri, deleteAfterRead = file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhotoException) {
                problemCallback(e.message)
            }
        }
        if (ready.isNotEmpty()) photosCallback(ready)
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PhotoLimits.MAX_PHOTOS)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val keep = uris.take(left.coerceAtLeast(0))
        if (keep.size < uris.size) problemCallback("Seules ${PhotoLimits.MAX_PHOTOS} photos peuvent être jointes à un message.")
        if (keep.isNotEmpty()) scope.launch { prepareAll(keep.map { it to null }) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = pendingPath?.let(::File)
        pendingPath = null
        if (file == null) return@rememberLauncherForActivityResult
        if (!taken) {
            file.delete()
            return@rememberLauncherForActivityResult
        }
        // Le même URI que celui donné à l'appareil photo ; le fichier est supprimé après lecture.
        scope.launch { prepareAll(listOf(Uri.fromFile(file) to file)) }
    }

    fun launchCamera() {
        if (left <= 0) {
            problemCallback("Seules ${PhotoLimits.MAX_PHOTOS} photos peuvent être jointes à un message.")
            return
        }
        val target = temp.create()
        pendingPath = target.file.path
        try {
            camera.launch(target.uri)
        } catch (e: ActivityNotFoundException) {
            target.file.delete()
            pendingPath = null
            problemCallback("Aucune application d'appareil photo n'a été trouvée.")
        }
    }
    // Trackly déclare la permission CAMERA (scan du QR code de la clé) : Android exige alors qu'elle soit accordée
    // pour ouvrir l'appareil photo du système, même si la photo est prise par une autre application.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else problemCallback("L'appareil photo n'est pas autorisé. Choisis plutôt une photo dans la galerie.")
    }

    return remember(gallery, camera, cameraPermission, temp) {
        PhotoPickerState(
            openGallery = {
                if (left <= 0) {
                    problemCallback("Seules ${PhotoLimits.MAX_PHOTOS} photos peuvent être jointes à un message.")
                } else {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            },
            openCamera = {
                if (left <= 0) {
                    problemCallback("Seules ${PhotoLimits.MAX_PHOTOS} photos peuvent être jointes à un message.")
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    launchCamera()
                } else {
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            },
        )
    }
}

private val PillShape = RoundedCornerShape(999.dp)

/** Bouton de la ligne de saisie : ouvre le choix entre l'appareil photo et la galerie. */
@Composable
fun PhotoAttachButton(picker: PhotoPickerState, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .size(TracklySpacing.MicSize)
            .testTag(PhotoTags.ATTACH)
            .pressable(picker::toggleOptions, pressedScale = 0.9f, enabled = enabled)
            .semantics { contentDescription = "Joindre une photo" }
            .clip(RoundedCornerShape(10.dp))
            .background(if (picker.optionsOpen) TracklyColors.Mist else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        PhotoGlyph(PhotoGlyphs.Camera, if (enabled) TracklyColors.Cobalt else TracklyColors.Ink3, Modifier.size(21.dp), stroke = 1.6f)
    }
}

/** Les deux sources, affichées sous forme de pastilles quand le bouton est touché. */
@Composable
fun PhotoOptions(picker: PhotoPickerState, modifier: Modifier = Modifier) {
    if (!picker.optionsOpen) return
    PhotoOptionsRow(onCamera = picker::takePhoto, onGallery = picker::pickFromGallery, modifier = modifier)
}

/** Version sans état de [PhotoOptions] (captures d'écran, aperçus). */
@Composable
fun PhotoOptionsRow(onCamera: () -> Unit, onGallery: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().testTag(PhotoTags.OPTIONS),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OptionPill("Appareil photo", PhotoTags.CAMERA, onCamera)
        OptionPill("Galerie", PhotoTags.GALLERY, onGallery)
    }
}

@Composable
private fun OptionPill(text: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .testTag(tag)
            .pressable(onClick, pressedScale = 0.95f)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = TracklyType.GaugeCaption.copy(color = TracklyColors.Cobalt),
            modifier = Modifier
                .clip(PillShape)
                .background(TracklyColors.Mist)
                .border(TracklySpacing.Hairline, TracklyColors.Cobalt, PillShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Vignettes des photos jointes, avant l'envoi, avec un bouton pour retirer chacune. */
@Composable
fun PhotoStrip(photos: List<PreparedPhoto>, onRemove: (PreparedPhoto) -> Unit, modifier: Modifier = Modifier) {
    if (photos.isEmpty()) return
    Row(
        modifier.fillMaxWidth().testTag(PhotoTags.STRIP).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        photos.forEachIndexed { index, photo ->
            Box(Modifier.size(width = 64.dp, height = 64.dp + 8.dp).padding(top = 8.dp)) {
                Box(
                    Modifier
                        .size(60.dp)
                        .testTag(PhotoTags.THUMBNAIL)
                        .semantics { contentDescription = "Photo ${index + 1} sur ${photos.size}" }
                        .clip(RoundedCornerShape(10.dp))
                        .background(TracklyColors.Mist)
                        .border(TracklySpacing.Hairline, TracklyColors.Line2, RoundedCornerShape(10.dp)),
                ) {
                    photo.thumbnail?.let { bitmap ->
                        Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(60.dp))
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(TracklySpacing.BackSize)
                        .testTag(PhotoTags.REMOVE)
                        .pressable({ onRemove(photo) }, pressedScale = 0.88f)
                        .semantics { contentDescription = "Retirer la photo ${index + 1}" },
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(
                        Modifier.size(20.dp).clip(CircleShape).background(TracklyColors.Ink).border(1.5.dp, TracklyColors.PaperHi, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        PhotoGlyph(PhotoGlyphs.Close, TracklyColors.PaperHi, Modifier.size(10.dp), stroke = 2.6f)
                    }
                }
            }
        }
    }
}

/** Pictogrammes au trait du sélecteur (viewBox 24, comme `Glyph`), à part pour ne pas toucher la liste commune. */
internal enum class PhotoGlyphs(val paths: List<String>) {
    Camera(
        listOf(
            "M4 8.5h3l1.6-2.5h6.8L17 8.5h3v10H4z",
            "M12 10.5a3.2 3.2 0 1 0 0 6.4a3.2 3.2 0 1 0 0-6.4z",
        ),
    ),
    Close(listOf("M6 6l12 12M18 6L6 18")),
}

private val glyphCache = HashMap<Pair<PhotoGlyphs, Float>, ImageVector>()

@Composable
internal fun PhotoGlyph(glyph: PhotoGlyphs, color: Color, modifier: Modifier = Modifier, stroke: Float = 1.4f) {
    val vector = glyphCache.getOrPut(glyph to stroke) {
        ImageVector.Builder("Photo${glyph.name}", 24.dp, 24.dp, 24f, 24f).apply {
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
    Image(rememberVectorPainter(vector), contentDescription = null, colorFilter = ColorFilter.tint(color), modifier = modifier)
}
