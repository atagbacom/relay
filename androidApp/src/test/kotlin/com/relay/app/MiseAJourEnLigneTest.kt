package com.relay.app

import org.junit.Assert.*
import org.junit.Test

class MiseAJourEnLigneTest {

    @Test
    fun `comparaison des versions`() {
        assertTrue(MiseAJourEnLigne.plusRecente("0.4.0", "0.3.9"))
        assertTrue(MiseAJourEnLigne.plusRecente("v1.0.0", "0.9.12"))
        assertTrue(MiseAJourEnLigne.plusRecente("0.3.10", "0.3.9"))
        assertFalse(MiseAJourEnLigne.plusRecente("0.3.0", "0.3.0"))
        assertFalse(MiseAJourEnLigne.plusRecente("0.2.9", "0.3.0"))
        assertTrue("version de développement : toute publication est plus récente",
            MiseAJourEnLigne.plusRecente("0.3.0", "0.0.0-dev"))
    }

    @Test
    fun `fichier adapté à l'architecture du téléphone`() {
        val noms = listOf("relay-0.4.0.apk", "relay-0.4.0-arm64-v8a.apk", "relay-0.4.0-armeabi-v7a.apk", "SHA256SUMS.txt")
        assertEquals("relay-0.4.0-arm64-v8a.apk", MiseAJourEnLigne.choisirFichier(noms, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("relay-0.4.0-armeabi-v7a.apk", MiseAJourEnLigne.choisirFichier(noms, listOf("armeabi-v7a", "armeabi")))
        assertEquals("relay-0.4.0.apk", MiseAJourEnLigne.choisirFichier(noms, listOf("x86_64")))
    }

    @Test
    fun `empreinte lue dans SHA256SUMS`() {
        val sommes = "ABC123  relay-0.4.0.apk\ndef456  relay-0.4.0-arm64-v8a.apk\n"
        assertEquals("def456", MiseAJourEnLigne.empreinteDans(sommes, "relay-0.4.0-arm64-v8a.apk"))
        assertEquals("abc123", MiseAJourEnLigne.empreinteDans(sommes, "relay-0.4.0.apk"))
        assertNull(MiseAJourEnLigne.empreinteDans(sommes, "autre.apk"))
    }
}
