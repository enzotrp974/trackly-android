package app.trackly.call.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.trackly.call.AudioRoute
import app.trackly.call.CallAudio

/**
 * Sortie audio de l'appel : mode communication, focus audio, haut-parleur ou écouteurs.
 * Haut-parleur par défaut, sauf si des écouteurs sont branchés. Les écouteurs (Bluetooth, filaires, USB) passent
 * avant l'écouteur du téléphone quand le haut-parleur est coupé. Un vrai appel téléphonique ou une autre app qui
 * prend le son raccroche l'appel ([onFocusLost]) : sinon il resterait facturé.
 */
class AudioRouter(context: Context) : CallAudio {
    /** La sortie change (écouteurs branchés ou retirés). */
    var onRoute: (AudioRoute) -> Unit = {}
    var onFocusLost: () -> Unit = {}

    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private var speakerWanted = true
    private var active = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            // Écouteurs branchés pendant l'appel : on y passe.
            if (addedDevices.orEmpty().any { routeOf(it.type)?.isHeadset == true && it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }) {
                speakerWanted = false
            }
            apply()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            apply()
        }
    }

    val headsetAvailable: Boolean get() = bestHeadset() != null

    override fun begin(): AudioRoute? {
        active = true
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // Un vrai appel téléphonique ou une autre app prend le son : on raccroche (sinon l'appel resterait facturé).
            .setOnAudioFocusChangeListener({ change ->
                if (active && (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) onFocusLost()
            }, main)
            .build()
        // Focus refusé (vrai appel téléphonique en cours…) : rien n'est laissé en place, l'appel ne part pas.
        // Sans `setAcceptsDelayedFocusGain`, la réponse est accordée ou refusée, jamais différée.
        if (am.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            active = false
            am.mode = AudioManager.MODE_NORMAL
            return null
        }
        focus = request
        speakerWanted = bestHeadset() == null
        am.registerAudioDeviceCallback(deviceCallback, main)
        return apply()
    }

    override fun setSpeaker(on: Boolean): AudioRoute {
        speakerWanted = on
        return apply()
    }

    override fun end() {
        if (!active) return
        active = false
        runCatching { am.unregisterAudioDeviceCallback(deviceCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
            @Suppress("DEPRECATION")
            runCatching { am.stopBluetoothSco() }
        }
        am.mode = AudioManager.MODE_NORMAL
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun apply(): AudioRoute {
        if (!active) return AudioRoute.Speaker
        val route = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) applyModern() else applyLegacy()
        onRoute(route)
        return route
    }

    private fun applyModern(): AudioRoute {
        val devices = am.availableCommunicationDevices
        val target = if (speakerWanted) {
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } else {
            bestHeadset() ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        }
        if (target != null) am.setCommunicationDevice(target)
        return target?.let { routeOf(it.type) } ?: AudioRoute.Speaker
    }

    @Suppress("DEPRECATION")
    private fun applyLegacy(): AudioRoute {
        am.isSpeakerphoneOn = speakerWanted
        // Android 10-11 : la liaison Bluetooth d'appel (SCO) se lance à la main.
        val bluetooth = !speakerWanted && bestHeadset()?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        runCatching { if (bluetooth) am.startBluetoothSco() else am.stopBluetoothSco() }
        return if (speakerWanted) AudioRoute.Speaker else bestHeadset()?.let { routeOf(it.type) } ?: AudioRoute.Earpiece
    }

    private fun bestHeadset(): AudioDeviceInfo? {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.availableCommunicationDevices
        } else {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        val order = listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        return order.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
    }

    private fun routeOf(type: Int): AudioRoute? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.Speaker
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.Earpiece
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.Wired
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioRoute.Bluetooth
        else -> null
    }
}
