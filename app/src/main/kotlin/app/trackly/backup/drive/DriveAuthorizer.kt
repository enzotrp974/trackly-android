package app.trackly.backup.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/** Message d'un échec de Google, d'après son code. */
internal fun driveFailure(statusCode: Int): DriveAuthorization.Failed = when (statusCode) {
    // 10 : identifiant OAuth Android absent ou empreinte SHA-1 incorrecte.
    CommonStatusCodes.DEVELOPER_ERROR -> DriveAuthorization.Failed(
        "Projet Google Cloud de Trackly pas prêt (identifiant OAuth Android, package ou empreinte SHA-1).",
        misconfigured = true,
    )
    CommonStatusCodes.NETWORK_ERROR -> DriveAuthorization.Failed("Pas de connexion Internet")
    else -> DriveAuthorization.Failed("Connexion à Google refusée (code $statusCode)")
}

/** Résultat d'une demande d'accès à Drive. */
sealed interface DriveAuthorization {
    /** Accès accordé : jeton valable environ une heure. */
    data class Granted(val accessToken: String, val accountEmail: String? = null) : DriveAuthorization

    /** Google doit afficher un écran (choix du compte, consentement) : impossible en arrière-plan. */
    data class NeedsUserAction(val pendingIntent: PendingIntent?) : DriveAuthorization

    /** Échec : configuration Google Cloud absente ou incorrecte, services Google indisponibles… */
    data class Failed(val message: String, val misconfigured: Boolean = false) : DriveAuthorization
}

/**
 * Accès à Drive avec la seule portée `drive.file`. Derrière une interface : les tests utilisent
 * un faux, sans services Google ni réseau.
 *
 * Trackly ne retire jamais l'accès accordé chez Google : avec `drive.file`, un retrait peut lui cacher ses
 * anciennes sauvegardes, même après reconnexion. « Déconnecter » oublie seulement la connexion sur le téléphone.
 */
interface DriveAuthorizer {
    /** Demande un jeton ; sans interaction si l'accès a déjà été accordé. */
    suspend fun authorize(): DriveAuthorization

    /** Oublie un jeton refusé par Drive, pour en obtenir un neuf au prochain [authorize]. */
    suspend fun clearToken(token: String)
}

/** Erreur d'autorisation remontée par la couche Drive. */
class DriveAuthException(val authorization: DriveAuthorization) : Exception(
    when (authorization) {
        is DriveAuthorization.NeedsUserAction -> "Reconnexion à Google nécessaire"
        is DriveAuthorization.Failed -> authorization.message
        is DriveAuthorization.Granted -> "Accès accordé"
    },
)

/** Jetons fournis par l'[authorizer] ; une demande d'interaction devient une [DriveAuthException]. */
class AuthorizerTokenSource(private val authorizer: DriveAuthorizer) : TokenSource {
    @Volatile private var current: String? = null

    override suspend fun token(): String = current ?: fetch()

    override suspend fun renew(stale: String): String {
        authorizer.clearToken(stale)
        current = null
        return fetch()
    }

    private suspend fun fetch(): String = when (val auth = authorizer.authorize()) {
        is DriveAuthorization.Granted -> auth.accessToken.also { current = it }
        else -> throw DriveAuthException(auth)
    }
}

/**
 * Implémentation Google : `AuthorizationClient` (Google Identity Services). Google Sign-In
 * n'est pas utilisé (supprimé de play-services-auth 22). Aucun jeton n'est stocké par Trackly :
 * il est redemandé à chaque sauvegarde.
 */
class GoogleDriveAuthorizer(context: Context) : DriveAuthorizer {
    private val client = Identity.getAuthorizationClient(context.applicationContext)

    override suspend fun authorize(): DriveAuthorization = try {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE))).build()
        val result = client.authorize(request).await()
        if (result.hasResolution()) {
            DriveAuthorization.NeedsUserAction(result.pendingIntent)
        } else {
            val token = result.accessToken
            if (token == null) {
                DriveAuthorization.Failed("Google n'a pas fourni d'accès à Drive")
            } else {
                val email = runCatching { result.toGoogleSignInAccount()?.email }.getOrNull()
                DriveAuthorization.Granted(token, email)
            }
        }
    } catch (e: ApiException) {
        failure(e)
    } catch (e: Exception) {
        DriveAuthorization.Failed("Connexion à Google impossible : ${e.message ?: e.javaClass.simpleName}")
    }

    override suspend fun clearToken(token: String) {
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    private fun failure(e: ApiException): DriveAuthorization.Failed = driveFailure(e.statusCode)

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

        /**
         * Motif d'échec caché dans le retour de l'écran Google, s'il y en a un. Sans lui, une configuration Google Cloud
         * absente se confond avec un écran fermé par l'utilisateur.
         */
        fun consentFailure(context: Context, data: Intent?): String? {
            if (data == null) return null
            return try {
                Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
                null
            } catch (e: ApiException) {
                // Écran refermé, ou retour sans statut (INTERNAL_ERROR) : pas un motif à montrer tel quel.
                if (e.statusCode == CommonStatusCodes.CANCELED || e.statusCode == CommonStatusCodes.INTERNAL_ERROR) null else driveFailure(e.statusCode).message
            }
        }
    }
}
