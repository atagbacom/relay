package com.relay.app.crypto

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test

class LecteurQrTest {

    private val contenu = """{"v":1,"id":"05a1b2c3","pk":"BQ","n":"abc","nom":"Ama"}"""

    /** Image en niveaux de gris d'un QR, avec [pas] octets par ligne (bourrage compris). */
    private fun image(taille: Int, pas: Int = taille, tourne: Boolean = false): ByteArray {
        val matrice = QRCodeWriter().encode(contenu, BarcodeFormat.QR_CODE, taille, taille)
        val octets = ByteArray(pas * taille) { 0x7F }
        for (y in 0 until taille) for (x in 0 until taille) {
            val (sx, sy) = if (tourne) Pair(y, taille - 1 - x) else Pair(x, y)
            octets[y * pas + x] = if (matrice[sx, sy]) 0 else 0xFF.toByte()
        }
        return octets
    }

    @Test
    fun `un code QR est lu`() {
        assertEquals(contenu, LecteurQr.lire(image(400), 400, 400))
    }

    @Test
    fun `lignes avec bourrage comme sur une vraie caméra`() {
        assertEquals(contenu, LecteurQr.lire(image(400, pas = 448), 400, 400, pasDeLigne = 448))
    }

    @Test
    fun `code tourné d'un quart de tour`() {
        assertEquals(contenu, LecteurQr.lire(image(400, tourne = true), 400, 400))
    }

    @Test
    fun `image sans code`() {
        assertNull(LecteurQr.lire(ByteArray(400 * 400) { 0x80.toByte() }, 400, 400))
    }
}
