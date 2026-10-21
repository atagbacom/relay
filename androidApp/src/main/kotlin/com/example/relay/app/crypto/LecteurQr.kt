package com.relay.app.crypto

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Lecture des codes QR avec ZXing, sous licence Apache 2.0.
 *
 * Remplace ML Kit : composant propriétaire de Google, embarqué dans une
 * application AGPL et dépendant des services Google. Logique pure, sans
 * Android : testée hors appareil.
 */
object LecteurQr {

    private val indices = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * Décode une image en niveaux de gris (le plan de luminance Y de la
     * caméra). [pasDeLigne] : octets par ligne, souvent plus que la largeur.
     * Le détecteur trouve le code quelle que soit son orientation.
     */
    fun lire(luminance: ByteArray, largeur: Int, hauteur: Int, pasDeLigne: Int = largeur): String? {
        val donnees = if (pasDeLigne == largeur) luminance else ByteArray(largeur * hauteur).also { sortie ->
            for (ligne in 0 until hauteur) System.arraycopy(luminance, ligne * pasDeLigne, sortie, ligne * largeur, largeur)
        }
        val source = PlanarYUVLuminanceSource(donnees, largeur, hauteur, 0, 0, largeur, hauteur, false)
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), indices).text
        } catch (e: NotFoundException) {
            null
        } catch (e: Exception) {
            null  // code abîmé ou flou : on attend l'image suivante
        }
    }
}
