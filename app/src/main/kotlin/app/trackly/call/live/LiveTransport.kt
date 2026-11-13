package app.trackly.call.live

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Niveaux audio de 0 (silence) à 1 : le micro du téléphone et la voix de l'IA telle qu'elle est jouée.
 * [micProcessed] : le micro après l'annulation d'écho de WebRTC, quand le transport sait le lire (`null` sinon) ;
 * c'est lui qui dit si l'utilisateur parle par-dessus l'IA sur haut-parleur.
 */
data class AudioLevels(val mic: Float = 0f, val ai: Float = 0f, val micProcessed: Float? = null)

/** Ce que le transport remonte au contrôleur d'appel. */
sealed interface TransportEvent {
    /** Le canal de données des événements est ouvert. */
    data object ChannelOpen : TransportEvent

    /** Un message JSON du serveur, tel quel. */
    data class Message(val json: String) : TransportEvent

    /** La connexion s'est arrêtée (fermeture normale ou perte). */
    data class Closed(val cause: String?) : TransportEvent
}

/**
 * Échec à l'ouverture, déjà formulé pour l'écran. Ne contient jamais la clé.
 * [noSession] : on sait qu'aucune session n'a été créée (refus HTTP, échec avant l'envoi), donc rien n'est facturé.
 */
class TransportException(message: String, val httpCode: Int? = null, val noSession: Boolean = httpCode != null) : Exception(message)

/** Étapes chronométrées du lancement d'un appel (gardées sur le téléphone, jamais envoyées). */
enum class LaunchStep(val label: String) {
    Key("clé"),
    Factory("fabrique WebRTC"),
    Offer("offre SDP"),
    Ice("collecte ICE"),
    Http("requête de session"),
    Answer("réponse appliquée"),
    Connected("ICE/DTLS"),
    Channel("canal ouvert"),
    Started("session démarrée"),
}

/**
 * Transport d'un appel GPT-Live, remplaçable : WebRTC réel ([WebRtcLiveTransport]) ou faux transports
 * (`FakeLiveTransport` dans les tests).
 *
 * Deux temps : [prepare] fait tout ce qui est gratuit (fabrique, offre, collecte ICE) et peut tourner avant le toucher ;
 * [open] envoie la seule requête facturée. La clé ne sert qu'à [open] et n'est jamais conservée par le transport.
 */
interface LiveTransport {
    /** Prépare sans contacter OpenAI. Sans effet si c'est déjà fait. [onStep] reçoit la durée de chaque étape (ms). */
    suspend fun prepare(onStep: (LaunchStep, Long) -> Unit = { _, _ -> })

    /**
     * Ouvre la session avec la configuration [session] (champ `session` de `POST /v1/live/sessions`), après
     * [prepare] s'il n'a pas eu lieu. Lève [TransportException] en cas d'échec. Les événements arrivent ensuite par
     * [onEvent], sur n'importe quel fil.
     */
    suspend fun open(
        session: JsonObject,
        apiKey: String,
        onEvent: (TransportEvent) -> Unit,
        onStep: (LaunchStep, Long) -> Unit = { _, _ -> },
    )

    /** Envoie un événement client sur le canal de données. Faux si le canal n'est pas ouvert. */
    fun send(json: String): Boolean

    /** Coupe ou rétablit le micro localement (la session reste facturée). */
    fun setMicMuted(muted: Boolean)

    /** Coupe ou rétablit localement la voix de l'IA (coupure gérée par l'app quand l'utilisateur parle par-dessus). */
    fun setSpeakerMuted(muted: Boolean)

    /** Ferme tout : canal, connexion, audio. Sans effet si déjà fermé. */
    fun close()

    /** Niveaux audio mis à jour en continu pendant l'appel. */
    val levels: StateFlow<AudioLevels>

    /** Vrai pour un transport qui ne coûte rien (démo, tests). */
    val isFree: Boolean

    /** La préparation est trop ancienne pour servir (offre de plus d'une minute) : à jeter et refaire. */
    val isStale: Boolean get() = false

    /** La requête facturée est partie (ou a pu partir) : une session a pu être créée. */
    val requestSent: Boolean get() = true
}
