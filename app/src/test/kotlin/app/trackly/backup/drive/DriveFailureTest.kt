package app.trackly.backup.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Message donné à l'utilisateur d'après le code d'échec de Google, sans appel réseau. */
class DriveFailureTest {
    @Test
    fun `code 10 - projet Google Cloud pas pret, configuration a corriger`() {
        val failure = driveFailure(10)
        assertTrue(failure.misconfigured)
        assertTrue(failure.message.startsWith("Projet Google Cloud de Trackly pas prêt"))
        // Le message ne renvoie plus à un guide du dépôt.
        assertFalse(failure.message.contains("docs/"))
    }

    @Test
    fun `code reseau - pas de connexion Internet, pas de configuration en cause`() {
        val failure = driveFailure(7)
        assertFalse(failure.misconfigured)
        assertEquals("Pas de connexion Internet", failure.message)
    }

    @Test
    fun `autre code - refus de Google avec son code`() {
        val failure = driveFailure(12501)
        assertFalse(failure.misconfigured)
        assertEquals("Connexion à Google refusée (code 12501)", failure.message)
    }
}
