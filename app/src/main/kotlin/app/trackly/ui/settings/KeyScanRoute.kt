package app.trackly.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trackly.assistant.key.ApiKeyStore
import app.trackly.keyscan.KeyScanController
import app.trackly.keyscan.KeyScanState
import app.trackly.keyscan.LumaFrame
import app.trackly.keyscan.QrDecoder
import app.trackly.keyscan.ZxingQrDecoder
import app.trackly.ui.theme.TracklyType
import kotlinx.coroutines.delay
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Délai avant de refermer l'écran, une fois la clé enregistrée. */
private const val CLOSE_AFTER_SAVED_MS = 1_800L

/**
 * Écran de scan branché sur le système : permission de l'appareil photo demandée à l'ouverture de
 * l'écran (avec explication d'abord), CameraX pour l'image, ZXing pour le décodage. Rien ne sort du
 * téléphone, aucune image n'est conservée. L'écran est protégé contre les captures d'écran
 * (`FLAG_SECURE`) tant qu'il est ouvert : l'image de l'appareil photo peut montrer le QR code de la clé.
 */
@Composable
fun KeyScanRoute(
    keyStore: ApiKeyStore,
    onClose: () -> Unit,
    decoder: QrDecoder = remember { ZxingQrDecoder() },
    /** Source des images ; CameraX par défaut. Les tests la remplacent par une caméra simulée. */
    viewfinder: @Composable BoxScope.(KeyScanController) -> Unit = { CameraViewfinder(it) },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(keyStore, decoder) { KeyScanController(decoder, keyStore, scope) }
    val scan by controller.state.collectAsStateWithLifecycle()
    var access by rememberSaveable { mutableStateOf(if (context.cameraGranted()) CameraAccess.Granted else CameraAccess.Unasked) }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        access = when {
            granted -> CameraAccess.Granted
            // Le système ne propose plus sa fenêtre après un refus « définitif » : seuls les paramètres restent.
            context.findActivity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA) } == true -> CameraAccess.Denied
            else -> CameraAccess.Blocked
        }
    }
    // Retour des paramètres de l'app : l'autorisation a pu être accordée entre-temps.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (access != CameraAccess.Granted && context.cameraGranted()) access = CameraAccess.Granted
    }
    DisposableEffect(controller) { onDispose { controller.stop() } }
    SecureWindow()
    BackHandler(onBack = onClose)
    LaunchedEffect(scan) {
        if (scan is KeyScanState.Saved) {
            delay(CLOSE_AFTER_SAVED_MS)
            onClose()
        }
    }

    KeyScanScreen(
        access = access,
        scan = scan,
        onAllow = { request.launch(Manifest.permission.CAMERA) },
        onOpenAppSettings = { openAppSettings(context) },
        onRetry = controller::retry,
        onClose = onClose,
        viewfinder = { viewfinder(controller) },
    )
}

/** Interdit les captures d'écran et masque la vignette des applications récentes pendant le scan. */
@Composable
private fun SecureWindow() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        val alreadySecure = window != null && (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** Aperçu de l'appareil photo arrière + analyse des images (la plus récente seulement), tant que l'écran est visible. */
@Composable
private fun BoxScope.CameraViewfinder(controller: KeyScanController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            // TextureView : suit les coins arrondis du viseur (SurfaceView les ignorerait).
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var unavailable by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, controller) {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        // L'écran peut se fermer avant que la caméra soit prête : dans ce cas, ne jamais la lier
        // (le listener et onDispose tournent tous deux sur le fil principal).
        var disposed = false
        future.addListener(
            {
                if (disposed) return@addListener
                try {
                    val cameraProvider = future.get().also { provider = it }
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        // Le QR d'une clé est dense : les 640 × 480 par défaut sont trop justes à 30 cm de l'écran.
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                                )
                                .build(),
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                        .also { it.setAnalyzer(executor) { image -> analyze(image, controller) } }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) {
                    unavailable = true // pas d'appareil photo arrière, ou déjà utilisé par une autre app
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            disposed = true
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    AndroidView({ previewView }, Modifier.fillMaxSize())
    if (unavailable) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            BasicText(
                "L’appareil photo n’est pas disponible. Colle ta clé à la place.",
                style = TracklyType.Notice.copy(color = Color.White),
            )
        }
    }
}

/** Copie le plan de luminosité de l'image (rien d'autre n'est lu), puis libère l'image aussitôt. */
private fun analyze(image: ImageProxy, controller: KeyScanController) {
    try {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        controller.onFrame(LumaFrame(bytes, plane.rowStride, image.width, image.height))
    } catch (_: RuntimeException) {
        // Image inattendue : on attend la suivante.
    } finally {
        image.close()
    }
}

private fun Context.cameraGranted() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    runCatching { context.startActivity(intent) }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
